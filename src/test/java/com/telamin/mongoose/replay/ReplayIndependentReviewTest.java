package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;
import com.telamin.fluxtion.runtime.input.EventFeed;
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
import com.telamin.mongoose.dispatch.AbstractEventToInvocationStrategy;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the independent review of #47 (at 90f0d9b): each reproduces a replay that completed with a wrong
 * result and no stop reason, or a failure that ended the process. Findings 5 (audit handoff interruption) are in
 * AgentHandoffTest and AuditSinkOnAgentThreadTest.
 */
class ReplayIndependentReviewTest {

    static final String FEED = "feed", GROUP = "processor-agent";

    /** A payload the processor can change: it emits what it received, then increments it. */
    public static class MutableValue implements Serializable {
        public int value;

        public MutableValue(int value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "MutableValue[" + value + "]";
        }
    }

    /** Emits what it received, so a replay's outputs show what the recording said it received. */
    public static class ProbeNode extends ObjectEventHandlerNode {
        /** Re-review N7: run between emitting a MutableValue and changing it, and once the handler is done with it. */
        static volatile Runnable afterEmit = () -> { }, afterHandled = () -> { };
        private MessageSink<String> sink;

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        private SchedulerService scheduler;

        @ServiceRegistered
        public void scheduler(SchedulerService scheduler, String name) {
            this.scheduler = scheduler;
        }

        @ServiceRegistered
        public void admin(AdminCommandRegistry registry, String name) {
            // the command itself reads no clock; with #48 it runs as the processor's own event cycle, whose receipt is
            // one reading (DefaultEventProcessor.runInEventCycle -> Clock.eventReceived), so its entry records one
            registry.registerCommand("probe.noop", (args, out, err) -> out.accept("noop"));
            // a timer whose action reads no clock and dispatches nothing: a firing that takes no reading at all
            registry.registerCommand("probe.arm", (args, out, err) -> {
                scheduler.scheduleAfterDelay(1, () -> { });
                out.accept("armed");
            });
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (sink == null) return true;
            if (event instanceof MutableValue m) {
                sink.accept("value=" + m.value);
                afterEmit.run();
                m.value++;                                              // the handler changes what it was given
                afterHandled.run();
            } else if (event instanceof NamedFeedEvent<?> n) {
                sink.accept("named=" + n.eventFeedName() + "/" + n.topic() + "#" + n.sequenceNumber() + ":" + n.data());
            } else {
                sink.accept("bare=" + event);
            }
            return true;
        }
    }

    /** A processor on the feed: by onEvent only, or also by a second callback type on the same source (finding 4). */
    public static class ProbeProcessor extends DefaultEventProcessor implements TypedRoute {
        private final List<EventFeed> feeds = new ArrayList<>();
        private final boolean twoRoutes;
        private final boolean typedFirst;

        public ProbeProcessor(boolean twoRoutes, boolean typedFirst) {
            super(new ProbeNode());
            this.twoRoutes = twoRoutes;
            this.typedFirst = typedFirst;
        }

        @Override
        public void addEventFeed(EventFeed eventFeed) {
            feeds.add(eventFeed);
            super.addEventFeed(eventFeed);
        }

        @Override
        public void start() {
            super.start();
            EventSubscriptionKey<Object> onEvent = EventSubscriptionKey.onEvent(FEED);
            EventSubscriptionKey<Object> typed = EventSubscriptionKey.of(FEED, CallBackType.forClass(TypedRoute.class));
            List<EventSubscriptionKey<Object>> keys = !twoRoutes ? List.of(onEvent)
                    : typedFirst ? List.of(typed, onEvent) : List.of(onEvent, typed);
            for (EventFeed f : feeds) {
                for (EventSubscriptionKey<Object> k : keys) f.subscribe(this, k);
            }
        }
    }

    /** The marker the typed route targets. */
    public interface TypedRoute {
    }

    /** The second route: delivers "TYPE:" + the item, so which route delivered it is visible in the output. */
    public static class TypedStrategy extends AbstractEventToInvocationStrategy {
        @Override
        protected void dispatchEvent(Object event, DataFlow eventProcessor) {
            eventProcessor.onEvent("TYPE:" + event);
        }

        @Override
        protected boolean isValidTarget(DataFlow eventProcessor) {
            return eventProcessor instanceof TypedRoute;
        }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> feed, AdminCommandProcessor admin, InMemoryMessageSink sink)
            implements AutoCloseable {
        List<String> live() {
            return sink.getMessages().stream().map(String::valueOf).toList();
        }

        GroupReplayer replayer() {
            return server.replayers().get(GROUP);
        }

        List<String> replayed(String processor) {
            return replayer().outputs(processor).stream().map(String::valueOf).toList();
        }

        void awaitLive(int n) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (live().size() < n && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(live().size() >= n, "expected " + n + " live lines: " + live());
        }

        void awaitReplayDone() throws InterruptedException {
            long deadline = System.nanoTime() + 8_000_000_000L;
            while ((replayer() == null || !replayer().complete()) && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(replayer() != null && replayer().complete(), "the replay neither completed nor stopped");
            Thread.sleep(50);
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    /** One group; the processors named, each a ProbeProcessor as configured; one feed, NOWRAP unless named. */
    static Server boot(ReplayConfig replay, boolean namedFeed, Map<String, ProbeProcessor> processors) throws Exception {
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(FEED);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        AdminCommandProcessor admin = new AdminCommandProcessor();
        EventProcessorGroupConfig.Builder group = EventProcessorGroupConfig.builder().agentName(GROUP);
        processors.forEach((name, p) -> group.put(name, EventProcessorConfig.builder().handler(p).build()));
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(group.build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(FEED).broadcast(true).wrapWithNamedEvent(namedFeed)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .addService(new ServiceConfig<>(admin, AdminCommandRegistry.class, "adminService"))
                .eventInvokeStrategy(CallBackType.forClass(TypedRoute.class), TypedStrategy::new)
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, r -> { });
        Thread.sleep(300);
        return new Server(server, feed, admin, sink);
    }

    static Map<String, ProbeProcessor> one() {
        return Map.of("probe", new ProbeProcessor(false, false));
    }

    // ---- 1: a replay that fails must not end the process -------------------------------------------------------

    static String runChild(String mode) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("--add-opens") || a.startsWith("--add-exports")).forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), ReplayFailureChildMain.class.getName(), mode));
        // bounded BEFORE reading: reading to EOF first did not bound a child that never closes its output
        Path file = Files.createTempFile("DEMO-child", ".out");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(file.toFile()).start();
        String out;
        try {
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the child finished within 60 s");
            out = Files.readString(file, StandardCharsets.UTF_8);
        } finally {
            if (p.isAlive()) p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            Files.deleteIfExists(file);
        }
        String survived = out.lines().filter(l -> l.startsWith("SURVIVED")).findFirst().orElse(null);
        assertEquals(0, p.exitValue(), "the server process survived the failed replay (exit 0): " + (survived != null ? survived
                : out.lines().filter(l -> l.contains("Exception") || l.contains("Error")).limit(3).toList()));
        assertNotNull(survived, out);
        return survived;
    }

    @Test
    void f1_aReplayStoreThatCannotBeRead_isAStoppedReplay_notAnExitedServer() throws Exception {
        String survived = runChild("store");
        assertTrue(survived.contains("stopped=") && survived.contains("DEMO replay store unavailable"), "named: " + survived);
        assertTrue(survived.contains("live=[]") && survived.contains("outputs=[]"),
                "and the processor whose replay failed receives no live input: " + survived);
    }

    @Test
    void f1_aDecoderThatThrowsAnError_isAStoppedReplay_notAnExitedServer() throws Exception {
        String survived = runChild("decoder");
        assertTrue(survived.contains("DEMO decoder failure"), "named: " + survived);
        assertTrue(survived.contains("live=[]") && survived.contains("outputs=[]"), "and it receives no live input: " + survived);
    }

    // ---- 2: an inline input is recorded as received ------------------------------------------------------------

    List<String> recordThenReplay(ReplayStore recordInto, java.util.function.Supplier<ReplayStore> replayFrom, String processor,
                                  Map<String, ProbeProcessor> recordWith, Map<String, ProbeProcessor> replayWith,
                                  java.util.function.Consumer<Server> drive, int lines, List<String> liveOut) throws Exception {
        try (Server s = boot(ReplayConfig.record(recordWith.keySet(), Map.of(), null, recordInto), false, recordWith)) {
            drive.accept(s);
            s.awaitLive(lines);
            liveOut.addAll(s.live());
        }
        try (Server r = boot(ReplayConfig.replay(Set.of(processor), Map.of(), null, replayFrom.get()), false, replayWith)) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped(processor));
            return r.replayed(processor);
        }
    }

    @Test
    void f2_anInputTheHandlerChanges_replaysAsReceived_memoryStore() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = new ArrayList<>();
        List<String> replayed = recordThenReplay(store, () -> store, "probe", one(), one(),
                s -> s.feed().offer(new MutableValue(0)), 1, live);
        assertEquals(List.of("value=0"), live);
        assertEquals(live, replayed, "the replay gives what the processor received, not what its handler left");
    }

    @Test
    void f2_anInputTheHandlerChanges_replaysAsReceived_csvStore(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.csv");
        List<String> live = new ArrayList<>();
        List<String> replayed;
        try (CsvReplayStore recordInto = new CsvReplayStore(file, new JavaSerializationCodec())) {
            replayed = null;
            try (Server s = boot(ReplayConfig.record(Set.of("probe"), Map.of(), null, recordInto), false, one())) {
                s.feed().offer(new MutableValue(0));
                s.awaitLive(1);
                live.addAll(s.live());
            }
        }
        try (CsvReplayStore from = new CsvReplayStore(file, new JavaSerializationCodec());
             Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, from), false, one())) {
            r.awaitReplayDone();
            replayed = r.replayed("probe");
        }
        assertEquals(List.of("value=0"), live);
        assertEquals(live, replayed);
    }

    @Test
    void f2_aPayloadTheApplicationReuses_replaysAsEachDeliveryWas() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        MutableValue reused = new MutableValue(10);
        List<String> live = new ArrayList<>();
        java.util.concurrent.CountDownLatch handled = new java.util.concurrent.CountDownLatch(1);
        List<String> replayed;
        ProbeNode.afterHandled = handled::countDown;
        try {
            replayed = recordThenReplay(store, () -> store, "probe", one(), one(), s -> {
                s.feed().offer(reused);
                // re-review N7: the reuse waits for the handler to be DONE with the object, not for its output, which
                // it emits before it changes the object (see n7_anOutputIsNotTheEndOfTheHandlersUse)
                try {
                    assertTrue(handled.await(5, java.util.concurrent.TimeUnit.SECONDS), "the first dispatch completed");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                reused.value = 50;                                      // the application reuses the object
                s.feed().offer(reused);
            }, 2, live);
        } finally {
            ProbeNode.afterHandled = () -> { };
        }
        assertEquals(List.of("value=10", "value=50"), live);
        assertEquals(live, replayed);
    }

    /**
     * Re-review N7, the barrier itself: with the handler held after its output and before it changes the object, the old
     * barrier (an output exists) is already met, so a producer reusing the object then is overwritten by the handler;
     * the completion barrier is not met until the handler is done. Latches, no sleeps.
     */
    @Test
    void n7_anOutputIsNotTheEndOfTheHandlersUse() throws Exception {
        java.util.concurrent.CountDownLatch emitted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch handled = new java.util.concurrent.CountDownLatch(1);
        ProbeNode.afterEmit = () -> {
            emitted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        ProbeNode.afterHandled = handled::countDown;
        MutableValue reused = new MutableValue(10);
        try (Server s = boot(ReplayConfig.OFF, false, one())) {
            s.feed().offer(reused);
            assertTrue(emitted.await(5, java.util.concurrent.TimeUnit.SECONDS), "the handler emitted and is held");
            assertEquals(List.of("value=10"), s.live(), "the old barrier, an output exists, is already met");
            assertEquals(1, handled.getCount(), "but the handler still owns the object: the completion barrier is not met");
            Thread producer = new Thread(() -> {
                try {
                    handled.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                reused.value = 50;
            });
            producer.start();
            long deadline = System.nanoTime() + 5_000_000_000L;   // bounded: a producer that TERMINATED instead is a failure
            while (producer.getState() != Thread.State.WAITING && producer.isAlive() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.WAITING, producer.getState(), "the producer is waiting behind the completion barrier");
            assertEquals(10, reused.value, "a producer behind the completion barrier has not written");
            release.countDown();
            producer.join(5_000);
            assertEquals(50, reused.value, "it writes once the handler is done, and the handler's change does not overwrite it");
        } finally {
            release.countDown();
            ProbeNode.afterEmit = () -> { };
            ProbeNode.afterHandled = () -> { };
        }
    }

    @Test
    void f2_fannedOut_eachProcessorsRecordingIsWhatItReceived() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        Map<String, ProbeProcessor> two = new java.util.LinkedHashMap<>();
        two.put("first", new ProbeProcessor(false, false));
        two.put("second", new ProbeProcessor(false, false));
        List<String> live = new ArrayList<>();
        try (Server s = boot(ReplayConfig.record(two.keySet(), Map.of(), null, store), false, two)) {
            s.feed().offer(new MutableValue(0));
            s.awaitLive(2);
            live.addAll(s.live());
        }
        assertEquals(List.of("value=0", "value=1"), live, "the second processor received what the first left");
        List<String> replayed = new ArrayList<>();
        for (String processor : List.of("first", "second")) {
            try (Server r = boot(ReplayConfig.replay(Set.of(processor), Map.of(), null, store), false,
                    Map.of(processor, new ProbeProcessor(false, false)))) {
                r.awaitReplayDone();
                assertEquals(null, r.replayer().stopped(processor));
                replayed.addAll(r.replayed(processor));
            }
        }
        assertEquals(live, replayed.stream().sorted().toList(), "each processor replays what it, not the other, received");
    }

    @Test
    void f2_aReplayedHandlerThatChangesItsInput_doesNotChangeTheRecording() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        try (Server s = boot(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), false, one())) {
            s.feed().offer(new MutableValue(0));
            s.awaitLive(1);
        }
        for (int run = 1; run <= 2; run++) {
            try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), false, one())) {
                r.awaitReplayDone();
                assertEquals(List.of("value=0"), r.replayed("probe"), "replay " + run + " gives what was received");
            }
        }
    }

    /** Not Serializable: it cannot be copied, so it cannot be recorded as received. */
    public static class Opaque {
        @Override
        public String toString() {
            return "DEMO-opaque";
        }
    }

    @Test
    void f2_anInputThatCannotBeCopied_isRecordedFailed_notByReference() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        try (Server s = boot(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), false, one())) {
            s.feed().offer(new Opaque());
            s.awaitLive(1);
            assertEquals(List.of("bare=DEMO-opaque"), s.live(), "the processor still receives it");
        }
        ReplayEntry recorded = store.entries("probe").get(0);
        assertTrue(recorded instanceof ReplayEntry.Failed f && f.description().contains("could not be recorded as received"),
                "marked, not held by reference: " + recorded);
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), false, one())) {
            r.awaitReplayDone();
            assertNotNull(r.replayer().stopped("probe"), "and a replay stops there");
        }
    }

    // ---- 3: an application's NamedFeedEvent on a NOWRAP feed --------------------------------------------------

    @Test
    void f3_anApplicationNamedFeedEvent_onANowrapFeed_replaysAsItself() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = new ArrayList<>();
        List<String> replayed = recordThenReplay(store, () -> store, "probe", one(), one(),
                s -> s.feed().offer(new NamedFeedEventImpl<>("DEMO-inner", "DEMO-topic", 123L, "DEMO-item")), 1, live);
        assertEquals(List.of("named=DEMO-inner/DEMO-topic#123:DEMO-item"), live);
        assertEquals(live, replayed, "the application's event, with its type and fields");
    }

    @Test
    void f3_anApplicationNamedFeedEvent_toACsvStore_replaysAsItself(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.csv");
        List<String> live = new ArrayList<>();
        try (CsvReplayStore recordInto = new CsvReplayStore(file, new JavaSerializationCodec());
             Server s = boot(ReplayConfig.record(Set.of("probe"), Map.of(), null, recordInto), false, one())) {
            s.feed().offer(new NamedFeedEventImpl<>("DEMO-inner", "DEMO-topic", 123L, "DEMO-item"));
            s.awaitLive(1);
            live.addAll(s.live());
        }
        assertEquals(List.of("named=DEMO-inner/DEMO-topic#123:DEMO-item"), live);
        try (CsvReplayStore from = new CsvReplayStore(file, new JavaSerializationCodec());
             Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, from), false, one())) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped("probe"));
            assertEquals(live, r.replayed("probe"));
        }
    }

    // ---- 4: two callback routes from one source ----------------------------------------------------------------

    void twoRoutes(boolean typedFirst) throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = new ArrayList<>();
        try (Server s = boot(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), false,
                Map.of("probe", new ProbeProcessor(true, typedFirst)))) {
            s.feed().offer("DEMO-item");
            s.feed().offer("DEMO-item");
            s.awaitLive(2);
            Thread.sleep(200);
            live.addAll(s.live());
        }
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), false,
                Map.of("probe", new ProbeProcessor(true, typedFirst)))) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped("probe"), "each entry names its route, so none is ambiguous");
            assertEquals(live, r.replayed("probe"), "each entry replays through the route that delivered it");
        }
    }

    static final String TYPED = CallBackType.forClass(TypedRoute.class).name(), ON_EVENT = CallBackType.ON_EVENT_CALL_BACK.name();

    /** Entries naming each route, in both subscription orders: the two routes' effects differ, and each is taken. */
    void entriesNamingTheirRoutes(boolean typedFirst) throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.append("probe", new ReplayEntry.Inline(FEED, TYPED, "DEMO-a", -1, 5, List.of(5L)));
        store.append("probe", new ReplayEntry.Inline(FEED, ON_EVENT, "DEMO-b", -1, 6, List.of(6L)));
        store.append("probe", new ReplayEntry.Inline(FEED, TYPED, "DEMO-c", -1, 7, List.of(7L)));
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), false,
                Map.of("probe", new ProbeProcessor(true, typedFirst)))) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped("probe"));
            assertEquals(List.of("bare=TYPE:DEMO-a", "bare=DEMO-b", "bare=TYPE:DEMO-c"), r.replayed("probe"),
                    "each entry through the route it names");
        }
    }

    @Test
    void f4_entriesReplayThroughTheRouteTheyName_typedSubscribedFirst() throws Exception {
        entriesNamingTheirRoutes(true);
    }

    @Test
    void f4_entriesReplayThroughTheRouteTheyName_onEventSubscribedFirst() throws Exception {
        entriesNamingTheirRoutes(false);
    }

    @Test
    void f4_anEntryNamingNoRoute_isRefused_whenTwoRoutesDeliverItsSource() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.append("probe", new ReplayEntry.Inline(FEED, "DEMO-a", List.of(5L)));          // no route: ambiguous here
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), false,
                Map.of("probe", new ProbeProcessor(true, false)))) {
            r.awaitReplayDone();
            String stopped = r.replayer().stopped("probe");
            assertNotNull(stopped, "an entry that names no route is refused when two routes could deliver it");
            assertTrue(stopped.contains("2 routes"), stopped);
            assertEquals(List.of(), r.replayed("probe"), "and nothing was delivered by a guessed route");
        }
    }

    @Test
    void f4_twoRoutesFromOneSource_typedSubscribedFirst() throws Exception {
        twoRoutes(true);
    }

    @Test
    void f4_twoRoutesFromOneSource_onEventSubscribedFirst() throws Exception {
        twoRoutes(false);
    }

    // ---- 6: the clock-read count, zero and one -----------------------------------------------------------------

    static List<Object> invoke(AdminCommandProcessor admin, String command) {
        List<Object> replies = new CopyOnWriteArrayList<>();
        AdminCommandRequest request = new AdminCommandRequest();
        request.setCommand(command);
        request.setArguments(List.of());
        request.setOutput(replies::add);
        request.setErrOutput(o -> replies.add("ERR " + o));
        admin.processAdminCommandRequest(request);
        return replies;
    }

    /** RECORD: arm the probe's timer and wait until its firing is recorded; the store holds the command, then the timer. */
    static List<ReplayEntry> recordAnArmedTimer() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        try (Server s = boot(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), false, one())) {
            assertEquals(List.of("armed"), invoke(s.admin(), "probe.arm"));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (store.entries("probe").size() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
        }
        List<ReplayEntry> entries = store.entries("probe");
        assertEquals(2, entries.size(), "the command and its timer's firing: " + entries);
        return entries;
    }

    /**
     * Integration with #48: an admin command runs as its processor's own event cycle, and a DefaultEventProcessor's
     * cycle takes one reading on receipt, so the vehicle for "a cycle that read no clock" is a timer firing whose action
     * reads none (main used a lambda admin command, which read none before #48 gave it a cycle).
     */
    @Test
    void f6_aCycleThatReadNoClock_replaysAsZeroReads() throws Exception {
        List<ReplayEntry> entries = recordAnArmedTimer();
        ReplayEntry.AdminInvoked command = (ReplayEntry.AdminInvoked) entries.get(0);
        assertEquals(1, command.reads().size(), "an admin command's own cycle takes exactly its receipt's reading");
        ReplayEntry.TimerFired timer = (ReplayEntry.TimerFired) entries.get(1);
        assertEquals(List.of(), timer.reads(), "a cycle that read no clock records no reading");
        InMemoryReplayStore store = new InMemoryReplayStore();
        entries.forEach(e -> store.append("probe", e));
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), false, one())) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped("probe"), "zero to zero is not a divergence");
        }
    }

    @Test
    void f6_aRecordedReadThatTheReplayDoesNotTake_isADivergence() throws Exception {
        List<ReplayEntry> entries = recordAnArmedTimer();
        ReplayEntry.TimerFired timer = (ReplayEntry.TimerFired) entries.get(1);
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.append("probe", entries.get(0));
        store.append("probe", new ReplayEntry.TimerFired(timer.seq(), 42L, List.of(42L)));   // one read the firing never takes
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), false, one())) {
            r.awaitReplayDone();
            String stopped = r.replayer().stopped("probe");
            assertNotNull(stopped, "one recorded read taken zero times is a divergence");
            assertTrue(stopped.contains("clock"), stopped);
        }
    }

    // ---- 7: a torn first record and the next append ------------------------------------------------------------

    @Test
    void f7_aTornTail_isNeverAppendedTo_andTheFileStaysReadable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.csv");
        Files.writeString(file, CsvReplayStore.HEADER + "\nprobe,INLINE,", StandardCharsets.UTF_8);    // torn first record
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec())) {
            assertEquals(List.of(), store.entries("probe"));
            Exception refused = assertThrows(Exception.class, () -> boot(ReplayConfig.record(Set.of("probe"), Map.of(), null, store),
                    false, one()).close(), "a file with a torn tail is not recorded into");
            assertTrue(String.valueOf(refused.getMessage()).contains("torn"), "refused naming the torn line: " + refused);
            Exception appended = assertThrows(IllegalStateException.class,
                    () -> store.append("probe", new ReplayEntry.TimerFired(1, List.of(5L))), "nor appended to directly");
            assertTrue(appended.getMessage().contains("torn"), appended.getMessage());
        }
        try (CsvReplayStore again = new CsvReplayStore(file, new JavaSerializationCodec())) {
            assertEquals(List.of(), again.entries("probe"), "and it still reads");
        }
    }

    @Test
    void f7_aJournalWithATornTail_isNeverAppendedTo_andStaysReadable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("journal.csv");
        Files.writeString(file, CsvEventJournal.HEADER + "\nfeed,1,AAE", StandardCharsets.UTF_8);    // torn first record
        try (CsvEventJournal journal = new CsvEventJournal(file)) {
            assertEquals(0, journal.size(FEED));
            assertTrue(journal.holdsRecording(), "a torn journal is not an empty one: RECORD refuses it");
            Exception appended = assertThrows(IllegalStateException.class, () -> journal.append(FEED, 1, new byte[]{1}),
                    "and it is not appended to");
            assertTrue(appended.getMessage().contains("torn"), appended.getMessage());
        }
        try (CsvEventJournal again = new CsvEventJournal(file)) {
            assertEquals(0, again.size(FEED), "and it still reads");
        }
    }
}
