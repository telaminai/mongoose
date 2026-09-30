package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.event.Signal;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommand;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the independent review of #48 at f8deed60 (findings 1-4 and F6), each on a real server. Every wait is
 * a latch, a thread-state condition or a bounded join of the CALLER (a caller left waiting is the wrong result), never a
 * sleep standing in for a completion.
 */
class AdminReviewRegressionTest {

    static final String PROCESSOR = "cmds";

    /** The events that hold the agent thread, and the downstream event a command's handler raises. */
    public record Block(String name) { }

    public record Downstream(String name) { }

    public record Marker(String name) { }

    /** Installs a clock strategy on the agent thread, after boot (the server sets the processor's clock at boot). */
    public record InstallClock(com.telamin.fluxtion.runtime.time.ClockStrategy strategy) { }

    /** A node owning signal and lambda commands, counting each run. */
    public static class CmdNode extends ObjectEventHandlerNode {
        final AtomicInteger okCalls = new AtomicInteger(), throwCalls = new AtomicInteger(), replyThenFailCalls = new AtomicInteger(),
                holdCalls = new AtomicInteger(), lambdaRuns = new AtomicInteger();
        /** Held by a Block event, so the agent cannot claim queued work; released by the test. */
        final CountDownLatch agentHeld = new CountDownLatch(1), releaseAgent = new CountDownLatch(1);
        /** Held by DEMO.hold's handler, so its command is claimed and running. */
        final CountDownLatch holdStarted = new CountDownLatch(1), releaseHold = new CountDownLatch(1);
        final List<String> markers = new CopyOnWriteArrayList<>();
        private MessageSink<String> sink;

        @ServiceRegistered
        public void admin(AdminCommandRegistry registry, String name) {
            registry.registerSignalCommand("DEMO.ok");
            registry.registerSignalCommand("DEMO.throw");
            registry.registerSignalCommand("DEMO.replyThenFail");
            registry.registerSignalCommand("DEMO.hold");
            registry.registerCommand("DEMO.lambda", (args, out, err) -> {
                lambdaRuns.incrementAndGet();
                out.accept("DEMO-lambda-ok");
            });
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed("events");
        }

        static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof Block) {
                agentHeld.countDown();
                await(releaseAgent);
            } else if (event instanceof InstallClock ic) {
                getContext().getParentDataFlow().setClockStrategy(ic.strategy());
            } else if (event instanceof Marker m) {
                markers.add(m.name());
            } else if (event instanceof Downstream d) {
                throw new IllegalStateException("DEMO downstream failure after " + d.name());
            } else if (event instanceof Signal<?> signal && signal.getValue() instanceof AdminCommandRequest request) {
                switch (signal.filterString()) {
                    case "admin:DEMO.ok" -> {
                        okCalls.incrementAndGet();
                        if (sink != null) sink.accept("ok");
                        request.getOutput().accept("DEMO-ok");
                    }
                    case "admin:DEMO.throw" -> {
                        throwCalls.incrementAndGet();
                        throw new IllegalStateException("DEMO signal failure");
                    }
                    case "admin:DEMO.replyThenFail" -> {
                        replyThenFailCalls.incrementAndGet();
                        request.getOutput().accept("DEMO-replied");
                        getContext().getParentDataFlow().onEvent(new Downstream("DEMO.replyThenFail"));   // queued: same cycle
                    }
                    case "admin:DEMO.hold" -> {
                        holdCalls.incrementAndGet();
                        holdStarted.countDown();
                        await(releaseHold);
                        request.getOutput().accept("DEMO-held");
                    }
                    default -> { }
                }
            }
            return true;
        }
    }

    /** A clock that throws UnsupportedOperationException once, when armed, then reads 42 (finding 2). */
    static final class OnceFailingClock implements com.telamin.fluxtion.runtime.time.ClockStrategy {
        final AtomicBoolean armed = new AtomicBoolean();

        @Override
        public long getWallClockTime() {
            if (armed.compareAndSet(true, false)) throw new UnsupportedOperationException("DEMO clock unavailable once");
            return 42L;
        }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> events, AdminCommandProcessor admin, CmdNode node,
                  DefaultEventProcessor processor, InMemoryMessageSink sink) implements AutoCloseable {
        GroupReplayer replayer() {
            return server.replayers().get("processor-agent");
        }

        void awaitReplayDone() throws InterruptedException {
            long deadline = System.nanoTime() + 8_000_000_000L;
            while ((replayer() == null || !replayer().complete()) && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(replayer() != null && replayer().complete(), "the replay neither completed nor stopped");
        }

        /** The agent has handled everything offered before this: a marker, awaited by its own record. */
        void drained(String name) throws InterruptedException {
            events.offer(new Marker(name));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!node.markers.contains(name) && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(node.markers.contains(name), "the agent handled the marker " + name);
        }

        @Override
        public void close() {
            node.releaseAgent.countDown();
            node.releaseHold.countDown();
            server.stop();
        }
    }

    static Server boot(ReplayConfig replay) throws Exception {
        CmdNode node = new CmdNode();
        DefaultEventProcessor processor = new DefaultEventProcessor(node);
        InMemoryEventSource<Object> events = new InMemoryEventSource<>();
        events.setName("events");
        AdminCommandProcessor admin = new AdminCommandProcessor();
        InMemoryMessageSink sink = new InMemoryMessageSink();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put(PROCESSOR, EventProcessorConfig.builder().handler(processor).build()).build())
                .addEventFeed(EventFeedConfig.builder().instance(events).name("events").broadcast(true)
                        .agent("events-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(200);
        return new Server(server, events, admin, node, processor, sink);
    }

    static AdminCommandRequest request(String name, List<Object> replies) {
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(name);
        request.setArguments(List.of());
        request.setOutput(replies::add);
        request.setErrOutput(o -> replies.add("ERR " + o));
        return request;
    }

    static List<Object> command(Server s, String name) {
        List<Object> replies = new CopyOnWriteArrayList<>();
        s.admin().processAdminCommandRequest(request(name, replies));
        return replies;
    }

    /**
     * The caller, on its own thread, bounded: a caller still waiting after {@code seconds} is a wrong result (finding 3),
     * reported rather than hung on. What it received and what it threw are returned.
     */
    record Call(Thread thread, List<Object> replies, AtomicReference<Throwable> thrown) {
        boolean finishedWithin(long seconds) throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(seconds));
            return !thread.isAlive();
        }

        void awaitWaiting() {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING,
                    "the caller was admitted and is waiting for its command: " + thread.getState());
        }
    }

    static Call call(Server s, String name) {
        List<Object> replies = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                s.admin().processAdminCommandRequest(request(name, replies));
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "DEMO-caller");
        t.setDaemon(true);
        t.start();
        return new Call(t, replies, thrown);
    }

    @AfterEach
    void clearTheBound() {
        System.clearProperty("mongoose.admin.completionTimeoutMs");
    }

    // ---- 1: a signal command that fails reaches the recording and the replay ------------------------------------

    @Test
    void f1_aSucceedingSignalCommand_isRecordedAsInvoked_andReplays() throws Exception {     // the positive control
        InMemoryReplayStore store = new InMemoryReplayStore();
        try (Server s = boot(ReplayConfig.record(Set.of(PROCESSOR), Map.of(), null, store))) {
            assertEquals(List.of("DEMO-ok"), command(s, "DEMO.ok"));
        }
        assertInstanceOf(ReplayEntry.AdminInvoked.class, store.entries(PROCESSOR).get(0), store.entries(PROCESSOR).toString());
        try (Server r = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(), null, store))) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped(PROCESSOR));
            assertEquals(1, r.node().okCalls.get(), "replayed once");
        }
    }

    @Test
    void f1_aSignalCommandWhoseHandlerThrows_isRecordedFailed_runsOnce_andStopsTheReplay() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<Object> replies;
        int liveCalls;
        try (Server s = boot(ReplayConfig.record(Set.of(PROCESSOR), Map.of(), null, store))) {
            replies = command(s, "DEMO.throw");
            liveCalls = s.node().throwCalls.get();
        }
        assertEquals(1, replies.size(), "the caller is answered once: " + replies);
        assertTrue(replies.get(0).toString().startsWith("ERR ") && replies.get(0).toString().contains("DEMO signal failure"), replies.toString());
        assertEquals(1, liveCalls, "the handler ran once: a retry does not run the command again");
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertEquals(1, entries.size(), entries.toString());
        assertInstanceOf(ReplayEntry.Failed.class, entries.get(0), "a failed command is a failed input (D4), not an invocation: " + entries);
        try (Server r = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(), null, store))) {
            r.awaitReplayDone();
            assertNotNull(r.replayer().stopped(PROCESSOR), "the replay stops at the failure");
            assertEquals(0, r.node().throwCalls.get(), "before running the throwing handler again");
        }
    }

    @Test
    void f1_aSignalCommandWhoseCycleFailsAfterItReplied_isRecordedFailed_andStopsTheReplay() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<Object> replies;
        int liveCalls;
        try (Server s = boot(ReplayConfig.record(Set.of(PROCESSOR), Map.of(), null, store))) {
            replies = command(s, "DEMO.replyThenFail");
            liveCalls = s.node().replyThenFailCalls.get();
        }
        assertEquals("DEMO-replied", replies.get(0), "the handler's own reply reached the caller: " + replies);
        assertTrue(replies.stream().anyMatch(r -> r.toString().startsWith("ERR ") && r.toString().contains("DEMO downstream failure")),
                "and so did the failure that followed it in the same cycle: " + replies);
        assertEquals(1, liveCalls, "run once");
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertInstanceOf(ReplayEntry.Failed.class, entries.get(0), "recorded as failed: " + entries);
        try (Server r = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(), null, store))) {
            r.awaitReplayDone();
            assertNotNull(r.replayer().stopped(PROCESSOR), "the replay stops at the failure");
            assertEquals(0, r.node().replyThenFailCalls.get(), "and does not run it again");
        }
    }

    // ---- 2: a failure inside a supported cycle is not a disabled cycle ------------------------------------------

    @Test
    void f2_aSetupFailureInsideRunInEventCycle_refusesTheCommand_andNeverRunsIt() throws Exception {
        try (Server s = boot(null)) {
            // an UNMODIFIED DefaultEventProcessor, given a clock after boot, on its agent thread (the first version of this
            // test set it in a constructor; the server replaced it at boot, so the clock never failed: see the commit)
            OnceFailingClock clock = new OnceFailingClock();
            s.events().offer(new InstallClock(clock));
            s.drained("clock-installed");
            clock.armed.set(true);                                          // the next read: the command's audit setup
            List<Object> replies = command(s, "DEMO.lambda");
            assertFalse(clock.armed.get(), "precondition: the clock failed, inside runInEventCycle's setup");
            assertEquals(0, s.node().lambdaRuns.get(), "a command whose cycle failed to start is not run by another route");
            assertEquals(1, replies.size(), replies.toString());
            assertTrue(replies.get(0).toString().startsWith("ERR admin command 'DEMO.lambda' did not run")
                    && replies.get(0).toString().contains("DEMO clock unavailable once"), "refused, naming why: " + replies);
        }
    }

    // ---- 3: the caller's lifetime ------------------------------------------------------------------------------

    @Test
    void f3_aCommandAfterTheServerStopped_isRefusedByName_andNeverRuns() throws Exception {
        System.setProperty("mongoose.admin.completionTimeoutMs", "500");
        Server s = boot(null);
        s.close();
        Call c = call(s, "DEMO.ok");
        assertTrue(c.finishedWithin(10), "the caller of a stopped server is answered, not left waiting");
        assertEquals(0, s.node().okCalls.get(), "and the command did not run");
        assertEquals(1, c.replies().size(), c.replies().toString());
        assertTrue(c.replies().get(0).toString().startsWith("ERR "), "refused: " + c.replies());
    }

    @Test
    void f3_aLiveCommandForAReplayedProcessor_isRefusedByName_andNeverRuns() throws Exception {
        System.setProperty("mongoose.admin.completionTimeoutMs", "500");
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.append(PROCESSOR, new ReplayEntry.TimerFired(99, List.of(5L)));   // never deliverable: the replay stays open
        try (Server r = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(), null, store))) {
            Call c = call(r, "DEMO.ok");
            assertTrue(c.finishedWithin(10), "the caller of a replay-muted processor is answered, not left waiting");
            assertEquals(0, r.node().okCalls.get(), "and the live command did not reach the replayed processor");
            assertEquals(1, c.replies().size(), c.replies().toString());
            assertTrue(c.replies().get(0).toString().startsWith("ERR "), "refused: " + c.replies());
        }
    }

    @Test
    void f3_aCallerInterruptedBeforeItsCommandWasClaimed_leavesItUnrun_forever() throws Exception {
        try (Server s = boot(null)) {
            s.events().offer(new Block("DEMO-agent"));
            assertTrue(s.node().agentHeld.await(5, TimeUnit.SECONDS), "the agent is held: nothing can be claimed");
            Call c = call(s, "DEMO.ok");
            c.awaitWaiting();
            c.thread().interrupt();
            assertTrue(c.finishedWithin(10), "the interrupted caller returns");
            s.node().releaseAgent.countDown();
            s.drained("after-release");                         // the agent has handled everything queued before it
            assertEquals(0, s.node().okCalls.get(), "a command cancelled before it was claimed never runs later");
            assertFalse(c.replies().contains("DEMO-ok"), "and no late reply reaches the departed caller: " + c.replies());
            assertTrue(c.replies().stream().anyMatch(r -> r.toString().startsWith("ERR ") && r.toString().contains("cancelled")),
                    "the caller was told it was cancelled: " + c.replies());
        }
    }

    @Test
    void f3_aCallerInterruptedAfterItsCommandStarted_isToldSo_andNoLateReplyReachesIt() throws Exception {
        try (Server s = boot(null)) {
            Call c = call(s, "DEMO.hold");
            assertTrue(s.node().holdStarted.await(5, TimeUnit.SECONDS), "the command was claimed and is running");
            c.awaitWaiting();
            c.thread().interrupt();
            assertTrue(c.finishedWithin(10), "the interrupted caller returns");
            s.node().releaseHold.countDown();
            s.drained("after-hold");
            assertEquals(1, s.node().holdCalls.get(), "the command ran once");
            assertFalse(c.replies().contains("DEMO-held"), "its late reply did not reach the departed caller: " + c.replies());
            assertTrue(c.replies().stream().anyMatch(r -> r.toString().startsWith("ERR ") && r.toString().contains("started")),
                    "the caller was told its command had started, not that it was cancelled: " + c.replies());
            assertFalse(c.replies().stream().anyMatch(r -> r.toString().contains("cancelled")), c.replies().toString());
        }
    }

    // ---- 4: the command's identity is its registered name ------------------------------------------------------

    @Test
    void f4_aCommandNameWithSurroundingWhitespace_reachesTheSameHandler_once() throws Exception {
        try (Server s = boot(null)) {
            assertEquals(List.of("DEMO-ok"), command(s, "DEMO.ok"));
            assertEquals(List.of("DEMO-ok"), command(s, " DEMO.ok "), "the same handler answers");
            assertEquals(List.of("DEMO-ok"), command(s, "\tDEMO.ok\n"));
            assertEquals(3, s.node().okCalls.get(), "each request ran the handler once");
        }
    }

    // ---- F6: the reusable public publishCommand(List) ----------------------------------------------------------

    @Test
    void f6_aTemplatePublishedTwice_runsItsCommandTwice() throws Exception {
        try (Server s = boot(null)) {
            AdminCommand template = s.admin().registeredCommand("DEMO.lambda");
            List<Object> replies = new CopyOnWriteArrayList<>();
            template.setOutput(replies::add);
            template.setErrOutput(o -> replies.add("ERR " + o));
            template.publishCommand(List.of("DEMO.lambda"));
            template.publishCommand(List.of("DEMO.lambda"));
            assertEquals(2, s.node().lambdaRuns.get(), "each publish is its own execution: " + replies);
        }
    }
}
