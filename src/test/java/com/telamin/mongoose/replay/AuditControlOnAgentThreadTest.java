package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import com.telamin.mongoose.service.servercontrol.MongooseServerAdmin;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mongoose#46, end to end on a real server: the {@code audit.start} / {@code audit.stop} admin commands run on the
 * admin transport's thread, and change a processor's audit sink, which is {@code onEvent(EventLogControlEvent)} on it.
 * That change must run on the processor group's own agent thread, never overlapping another cycle, and the admin caller
 * is answered once it has been applied, or told why it could not be: the processor is gone, the group is not running,
 * or its thread did not reach the change in time. Nothing waits forever, and the agent thread never waits on a caller
 * that waits on it.
 */
class AuditControlOnAgentThreadTest {

    static final String GROUP = "processor-agent";

    /** Subscribes to the feed; each event costs a little time, so a foreign onEvent has a window to land in. */
    public static class FeedNode extends ObjectEventHandlerNode {
        final AtomicLong events = new AtomicLong();
        volatile Thread agentThread;
        volatile CountDownLatch blockOn;            // set: the next event wedges the agent thread until released

        @Override
        public void start() {
            getContext().subscribeToNamedFeed("events");
        }

        @Override
        protected boolean handleEvent(Object event) {
            agentThread = Thread.currentThread();
            events.incrementAndGet();
            CountDownLatch block = blockOn;
            if (block != null) {
                blockOn = null;
                try {
                    block.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long until = System.nanoTime() + 20_000;
            while (System.nanoTime() < until) Thread.onSpinWait();
            return true;
        }
    }

    /**
     * Notes the thread of every onEvent, flags any two that overlap, and the threads its audit sink is changed on. The
     * sink change is {@code DataFlow.setAuditLogProcessor}, which is {@code onEvent(new EventLogControlEvent(..))}.
     */
    public static class ThreadCheckingProcessor extends DefaultEventProcessor {
        final FeedNode node;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger overlaps = new AtomicInteger();
        final Set<String> controlThreads = ConcurrentHashMap.newKeySet();
        final AtomicInteger controlEvents = new AtomicInteger();
        final AtomicReference<LogRecordListener> installed = new AtomicReference<>();
        volatile long installDelayMs;

        public ThreadCheckingProcessor() {
            this(new FeedNode());
        }

        private ThreadCheckingProcessor(FeedNode node) {
            super(node);
            this.node = node;
        }

        @Override
        public void onEvent(Object event) {
            if (inFlight.incrementAndGet() > 1) overlaps.incrementAndGet();
            try {
                if (event instanceof EventLogControlEvent control && control.getLogRecordProcessor() != null) {
                    controlThreads.add(Thread.currentThread().getName());
                    controlEvents.incrementAndGet();
                    if (installDelayMs > 0) sleep(installDelayMs);
                    installed.set(control.getLogRecordProcessor());
                }
                super.onEvent(event);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A booted server: one processor in {@value #GROUP}, a busy feed, the admin commands, and audit capture on. */
    record Rig(MongooseServer server, ThreadCheckingProcessor processor, InMemoryEventSource<Object> events,
               AdminCommandProcessor admin, MongooseAuditCaptureService capture) {

        static Rig boot(Path dir, String... autoStart) throws Exception {
            ThreadCheckingProcessor processor = new ThreadCheckingProcessor();
            InMemoryEventSource<Object> events = new InMemoryEventSource<>();
            events.setName("events");
            AdminCommandProcessor admin = new AdminCommandProcessor();
            MongooseServerConfig config = MongooseServerConfig.builder()
                    .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(GROUP)
                            .put("quotes", EventProcessorConfig.builder().handler(processor).build()).build())
                    .addEventFeed(EventFeedConfig.builder().instance(events).name("events").broadcast(true)
                            .agent("events-agent", new BusySpinIdleStrategy()).build())
                    .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                    .addService(new ServiceConfig<>(new MongooseServerAdmin(), MongooseServerAdmin.class, "serverAdmin"))
                    .build();
            AuditCaptureConfig capture = new AuditCaptureConfig();
            capture.setEnabled(true);
            capture.setDirectory(dir.toString());
            capture.setAutoStart(new ArrayList<>(List.of(autoStart)));
            PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
            perf.setAuditCapture(capture);
            config.setPerformanceMonitoring(perf);
            MongooseServer server = MongooseServer.bootServer(config, rec -> { });
            Rig rig = new Rig(server, processor, events, admin, (MongooseAuditCaptureService)
                    server.registeredServices().get(MongooseAuditCaptureService.SERVICE_NAME).instance());
            events.offer("warm-up");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (processor.node.agentThread == null && System.nanoTime() < deadline) Thread.sleep(5);
            assertNotNull(processor.node.agentThread, "precondition: the feed reaches the processor");
            return rig;
        }

        boolean registered(String name) {
            return server.registeredProcessors().values().stream().flatMap(java.util.Collection::stream)
                    .anyMatch(n -> n.name().equals(name));
        }

        /** Run an admin command as a transport would, on this thread; the replies, out and err, in order. */
        List<String> command(String name, String... args) {
            List<String> replies = new CopyOnWriteArrayList<>();
            AdminCommandRequest request = new AdminCommandRequest();
            request.setCommand(name);
            request.setArguments(List.of(args));
            request.setOutput(o -> replies.add("out:" + o));
            request.setErrOutput(e -> replies.add("err:" + e));
            admin.processAdminCommandRequest(request);
            return replies;
        }
    }

    /**
     * The repro the issue asked for: a processor under continuous load, and audit.start / audit.stop in a loop from
     * another thread. Every sink change runs on the group's agent thread, no two onEvent calls overlap, and every
     * command is answered as applied.
     */
    @Test
    void auditStartStopUnderLoad_changesTheSinkOnlyOnTheAgentThread_neverOverlappingACycle(@TempDir Path dir) throws Exception {
        Rig rig = Rig.boot(dir);
        AtomicBoolean publishing = new AtomicBoolean(true);
        AtomicLong published = new AtomicLong(rig.processor().node.events.get());
        Thread feed = new Thread(() -> {
            // continuously, a few events ahead of the processor: the queues stay short, so the server stops cleanly
            while (publishing.get()) {
                if (published.get() - rig.processor().node.events.get() < 32) {
                    rig.events().offer("tick-" + published.incrementAndGet());
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "DEMO-feed-publisher");
        try {
            feed.start();
            int baselineControl = rig.processor().controlEvents.get();
            long eventsBefore = rig.processor().node.events.get();
            List<String> unexpected = new ArrayList<>();
            int cycles = 40;
            String adminThread = Thread.currentThread().getName();
            for (int i = 0; i < cycles; i++) {
                List<String> started = rig.command("audit.start", "quotes");
                if (!started.equals(List.of("out:audit: RECORDING quotes"))) unexpected.add("start " + i + ": " + started);
                List<String> stopped = rig.command("audit.stop", "quotes");
                if (!stopped.equals(List.of("out:audit: STOPPED quotes"))) unexpected.add("stop " + i + ": " + stopped);
            }
            publishing.set(false);
            feed.join(5_000);
            long drained = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (rig.processor().node.events.get() < published.get() && System.nanoTime() < drained) Thread.sleep(5);

            ThreadCheckingProcessor p = rig.processor();
            assertEquals(List.of(), unexpected, "every command answered as applied");
            assertTrue(p.node.events.get() - eventsBefore > cycles, "the processor was under load throughout: "
                    + (p.node.events.get() - eventsBefore) + " events");
            assertEquals(baselineControl + 2 * cycles, p.controlEvents.get(), "one sink change per command");
            String agent = p.node.agentThread.getName();
            assertTrue(agent.contains(GROUP), "the feed is delivered on the group's thread: " + agent);
            assertEquals(Set.of(agent), p.controlThreads,
                    "every sink change ran on the group's agent thread, never the admin caller's (" + adminThread + ")");
            assertEquals(0, p.overlaps.get(), "no two onEvent calls ever overlapped");
        } finally {
            publishing.set(false);
            rig.server().stop();
        }
    }

    /** The admin reply is sent only once the sink change has been applied on the agent thread. */
    @Test
    void theAdminReply_arrivesAfterTheChangeIsApplied(@TempDir Path dir) throws Exception {
        Rig rig = Rig.boot(dir);
        try {
            ThreadCheckingProcessor p = rig.processor();
            p.installDelayMs = 300;                    // the agent thread takes its time applying it
            LogRecordListener before = p.installed.get();
            AtomicReference<LogRecordListener> atStartReply = new AtomicReference<>();
            AdminCommandRequest start = new AdminCommandRequest();
            start.setCommand("audit.start");
            start.setArguments(List.of("quotes"));
            start.setOutput(o -> atStartReply.set(p.installed.get()));
            start.setErrOutput(e -> { throw new AssertionError("refused: " + e); });
            long t0 = System.nanoTime();
            rig.admin().processAdminCommandRequest(start);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertNotNull(atStartReply.get(), "replied");
            assertTrue(atStartReply.get() != before, "the capture listener was installed before the reply was sent");
            assertTrue(tookMs >= 250, "the reply waited for the agent thread: " + tookMs + " ms");
            assertTrue(rig.capture().isRecording("quotes"));

            LogRecordListener capturing = p.installed.get();
            AtomicReference<LogRecordListener> atStopReply = new AtomicReference<>();
            AdminCommandRequest stop = new AdminCommandRequest();
            stop.setCommand("audit.stop");
            stop.setArguments(List.of("quotes"));
            stop.setOutput(o -> atStopReply.set(p.installed.get()));
            stop.setErrOutput(e -> { throw new AssertionError("refused: " + e); });
            rig.admin().processAdminCommandRequest(stop);
            assertNotNull(atStopReply.get(), "replied");
            assertTrue(atStopReply.get() != capturing, "the configured listener was restored before the reply was sent");
            assertFalse(rig.capture().isRecording("quotes"));
        } finally {
            rig.server().stop();
        }
    }

    /**
     * The agent thread must never wait on an admin caller that is waiting on it. Here the group's thread starts the
     * capture of a processor added at runtime (autoStart, at registration, on that thread) while an admin
     * {@code audit.start} for another processor is waiting for the same thread. Before, the caller held the capture
     * service's lock while it waited, so the agent thread blocked on that lock, the caller timed out after 5 s, and its
     * command was refused for no reason.
     */
    @Test
    void anAutoStartOnTheAgentThread_doesNotStallAnAdminStartWaitingOnIt(@TempDir Path dir) throws Exception {
        Rig rig = Rig.boot(dir, "late");
        try {
            CountDownLatch lateInSupplier = new CountDownLatch(1);
            CountDownLatch releaseLate = new CountDownLatch(1);
            ThreadCheckingProcessor late = new ThreadCheckingProcessor() {
                final AtomicBoolean first = new AtomicBoolean(true);

                @Override
                public void onEvent(Object event) {
                    // the server installs its listener first, on the group's thread, before attach and autoStart
                    if (event instanceof EventLogControlEvent && first.getAndSet(false)) {
                        lateInSupplier.countDown();
                        try {
                            releaseLate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    super.onEvent(event);
                }
            };
            late.init();                               // a runtime add does not init the processor
            rig.server().addEventProcessor("late", GROUP, null, () -> late);
            assertTrue(lateInSupplier.await(5, TimeUnit.SECONDS), "the group's thread is registering 'late'");

            AtomicReference<List<String>> replies = new AtomicReference<>();
            AtomicLong tookMs = new AtomicLong();
            Thread admin = new Thread(() -> {
                long t0 = System.nanoTime();
                replies.set(rig.command("audit.start", "quotes"));
                tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
            }, "DEMO-admin");
            admin.start();
            Thread.sleep(300);                         // the admin call is now waiting on the group's thread
            releaseLate.countDown();                   // which goes on to autoStart 'late', then reaches the admin's change
            admin.join(15_000);

            assertEquals(List.of("out:audit: RECORDING quotes"), replies.get(), "the admin start was applied");
            assertTrue(tookMs.get() < 3_000, "and not held up behind a lock the agent thread needed: " + tookMs.get() + " ms");
            assertTrue(rig.capture().isRecording("late"), "the autoStart on the agent thread was applied too");
            assertTrue(rig.capture().isRecording("quotes"));
        } finally {
            rig.server().stop();
        }
    }

    /**
     * A group that is not running: its thread is wedged in a cycle. The admin caller is refused after the bound, not
     * left waiting, and the change it gave up on never runs later.
     */
    @Test
    void aGroupWhoseThreadIsWedged_refusesTheAdminCallAfterTheBound_andAppliesNothingLater(@TempDir Path dir) throws Exception {
        Rig rig = Rig.boot(dir);
        CountDownLatch release = new CountDownLatch(1);
        try {
            ThreadCheckingProcessor p = rig.processor();
            int controlBefore = p.controlEvents.get();
            p.node.blockOn = release;
            rig.events().offer("wedge");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (p.node.blockOn != null && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(p.node.blockOn == null, "precondition: the group's thread is wedged");

            long t0 = System.nanoTime();
            List<String> replies = rig.command("audit.start", "quotes");
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertEquals(1, replies.size(), replies.toString());
            assertTrue(replies.get(0).startsWith("err:audit: audit start for 'quotes' was not applied on its agent thread"),
                    "refused, by name, with the reason: " + replies);
            assertTrue(tookMs < 8_000, "bounded: " + tookMs + " ms");
            assertFalse(rig.capture().isRecording("quotes"), "nothing is recording");

            release.countDown();
            Thread.sleep(300);                         // the group's thread runs on
            assertEquals(controlBefore, p.controlEvents.get(), "the change the caller gave up on never ran");
            assertFalse(rig.capture().isRecording("quotes"));
        } finally {
            release.countDown();
            rig.server().stop();
        }
    }

    /**
     * A group that has closed: its processors are gone, so the change is refused at once. Before, it was applied on the
     * caller's thread and the caller was told the stopped processor was recording.
     */
    @Test
    void aClosedGroup_refusesTheChangeAtOnce(@TempDir Path dir) throws Exception {
        Rig rig = Rig.boot(dir);
        rig.server().stop();
        ThreadCheckingProcessor p = rig.processor();
        int controlBefore = p.controlEvents.get();
        long t0 = System.nanoTime();
        // refused as "not running" (the group closed) or, once server stop has ended every capture (#65's stopAll),
        // as "left its group": either way at once, and nothing is installed
        RuntimeException refused = assertThrows(RuntimeException.class, () -> rig.capture().start("quotes"));
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(refused.getMessage().contains("not running") || refused.getMessage().contains("left its group"),
                refused.getMessage());
        assertTrue(tookMs < 2_000, "refused at once, not after a timeout: " + tookMs + " ms");
        assertFalse(rig.capture().isRecording("quotes"));
        assertEquals(controlBefore, p.controlEvents.get(), "nothing was installed on the closed group's processor");
    }

    /**
     * A processor that has been stopped and removed from its group: audit.start is refused, by name, and nothing is
     * installed on the torn-down instance. Before, the change ran on it and the admin was told it was recording.
     */
    @Test
    void aStoppedProcessor_refusesAuditStart_andIsNotTouched(@TempDir Path dir) throws Exception {
        Rig rig = Rig.boot(dir);
        try {
            ThreadCheckingProcessor p = rig.processor();
            rig.server().stopProcessor(GROUP, "quotes");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (rig.registered("quotes") && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(rig.registered("quotes"), "precondition: the processor has left its group");
            Thread.sleep(100);
            int controlBefore = p.controlEvents.get();
            List<String> replies = rig.command("audit.start", "quotes");
            assertEquals(List.of("err:audit: audit start for 'quotes' refused: the processor has left its group"), replies);
            assertFalse(rig.capture().isRecording("quotes"));
            assertEquals(controlBefore, p.controlEvents.get(), "nothing was installed on the stopped processor");
        } finally {
            rig.server().stop();
        }
    }

    /**
     * A capture recording when its processor is stopped is still stopped cleanly: its queue is closed, and nothing is
     * installed on the torn-down instance.
     */
    @Test
    void aRecordingProcessorThatIsStopped_canStillBeStopped_withoutTouchingIt(@TempDir Path dir) throws Exception {
        Rig rig = Rig.boot(dir);
        try {
            ThreadCheckingProcessor p = rig.processor();
            assertEquals(List.of("out:audit: RECORDING quotes"), rig.command("audit.start", "quotes"));
            rig.server().stopProcessor(GROUP, "quotes");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (rig.registered("quotes") && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(rig.registered("quotes"), "precondition: the processor has left its group");
            Thread.sleep(100);
            int controlBefore = p.controlEvents.get();
            assertEquals(List.of("out:audit: STOPPED quotes"), rig.command("audit.stop", "quotes"));
            assertFalse(rig.capture().isRecording("quotes"));
            assertEquals(controlBefore, p.controlEvents.get(), "nothing was installed on the stopped processor");
        } finally {
            rig.server().stop();
        }
    }
}
