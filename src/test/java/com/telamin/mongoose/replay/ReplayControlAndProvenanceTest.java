package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design "Deterministic replay": control requests a recorded processor makes are recorded with their outcomes and,
 * in a replay, intercepted (never executed, matched, answered with the recorded outcome); each stream's provenance is
 * recorded beside it and checked. Both are configurable ({@link ReplayConfig#withControlInterception},
 * {@link ReplayConfig#withProvenance}).
 */
class ReplayControlAndProvenanceTest {

    static final String ORDERS = "orders", PROCESSOR = "quotes", VICTIM = "victim", GROUP = "processor-agent";

    /** On "kill", asks the server to stop another processor; on "kill-other", a different one. */
    public static class Killer extends ObjectEventHandlerNode {
        private MongooseServerController controller;
        private MessageSink<String> sink;

        @ServiceRegistered
        public void controller(MongooseServerController controller, String name) {
            this.controller = controller;
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed(ORDERS);
        }

        @Override
        protected boolean handleEvent(Object event) {
            if ("kill".equals(event)) {
                controller.stopProcessor(GROUP, VICTIM);
                if (sink != null) sink.accept("killed");
            } else if ("kill-other".equals(event)) {
                controller.stopProcessor(GROUP, "someone-else");
            }
            return true;
        }
    }

    public static class Spawner extends ObjectEventHandlerNode {
        protected MongooseServerController controller;
        @ServiceRegistered
        public void controller(MongooseServerController controller, String name) { this.controller = controller; }
        @Override public void start() { getContext().subscribeToNamedFeed(ORDERS); }
        @Override protected boolean handleEvent(Object event) {
            if ("spawn".equals(event)) {
                controller.addEventProcessor("spawned", GROUP, null, () -> {
                    var flow = new com.telamin.mongoose.internal.ConfigAwareEventProcessor(new Quiet());
                    flow.init();
                    return flow;
                });
            }
            return true;
        }
    }

    public static class FailureCatcher extends Spawner {
        @Override protected boolean handleEvent(Object event) {
            if ("register-duplicate".equals(event)) {
                try {
                    controller.registerService(new com.telamin.fluxtion.runtime.service.Service<>(
                            new Object(), Object.class, "memSink"));
                } catch (RuntimeException caught) {
                    // Both the real exception and an inaccurate replay substitute are caught by the processor.
                }
            }
            return true;
        }
    }

    public static class StartupSpawner extends Spawner {
        @Override public void start() {
            super.start();
            handleEvent("spawn");
        }
    }

    /** The same program in another class: a replay of Killer's stream into it is not the recorded program. */
    public static class Impostor extends Killer {
    }

    public static class Quiet extends ObjectEventHandlerNode {
        @Override
        protected boolean handleEvent(Object event) {
            return true;
        }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> orders) implements AutoCloseable {
        GroupReplayer replayer() {
            return server.replayers().get(GROUP);
        }

        boolean running(String name) {
            return server.registeredProcessors().getOrDefault(GROUP, List.of()).stream().anyMatch(n -> n.name().equals(name));
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    static Server boot(ReplayConfig replay, Supplier<ObjectEventHandlerNode> handler) throws Exception {
        return boot(replay, handler, false);
    }

    static Server boot(ReplayConfig replay, Supplier<ObjectEventHandlerNode> handler, boolean isolateStartFailure) throws Exception {
        InMemoryEventSource<Object> orders = new InMemoryEventSource<>();
        orders.setName(ORDERS);
        orders.setCacheEventLog(true);
        MongooseServerConfig config = MongooseServerConfig.builder()
                .isolateProcessorStartFailuresAtBoot(isolateStartFailure)
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(GROUP)
                        .put(PROCESSOR, new EventProcessorConfig(handler.get()))
                        .put(VICTIM, new EventProcessorConfig(new Quiet())).build())
                .addEventFeed(EventFeedConfig.builder().instance(orders).name(ORDERS).broadcast(true)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(new InMemoryMessageSink()).name("memSink").build())
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        Thread.sleep(200);                              // the processor subscribes in start()
        return new Server(server, orders);
    }

    private static ReplayConfig record(InMemoryReplayStore store, InMemoryEventJournal journal) {
        return ReplayConfig.record(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store);
    }

    private static ReplayConfig replay(InMemoryReplayStore store, InMemoryEventJournal journal) {
        return ReplayConfig.replay(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(c.getAsBoolean(), what);
    }

    /** A recorded run in which the processor stopped the victim. */
    private static void recordAKill(InMemoryReplayStore store, InMemoryEventJournal journal) throws Exception {
        try (Server s = boot(record(store, journal), Killer::new)) {
            s.orders().offer("kill");
            awaitTrue(() -> !s.running(VICTIM), "the recorded run stopped the victim");
        }
    }

    @Test
    void aRecordedControlRequestIsInterceptedInTheReplayNeverExecuted() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        recordAKill(store, journal);
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        ReplayEntry.ControlCall call = entries.stream().filter(e -> e instanceof ReplayEntry.ControlCall)
                .map(e -> (ReplayEntry.ControlCall) e).findFirst().orElseThrow(() -> new AssertionError(entries.toString()));
        assertEquals("stopProcessor", call.method());
        assertEquals(List.of(GROUP, VICTIM), call.args());
        assertEquals("ok", call.outcome());

        try (Server s = boot(replay(store, journal), Killer::new)) {
            awaitTrue(() -> s.replayer().complete(), "replayed: " + s.replayer().stopped(PROCESSOR));
            assertNull(s.replayer().stopped(PROCESSOR), "the replayed request matched the recorded one");
            assertEquals(List.of("stopProcessor[" + GROUP + ", " + VICTIM + "]"), s.replayer().controlRequests(PROCESSOR));
            Thread.sleep(100);
            assertTrue(s.running(VICTIM), "the replay never executed it: the victim still runs");
        }
    }

    @Test
    void replayNeverInvokesAnOpaqueProcessorSupplierOrAddsALiveProcessor() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(record(store, journal), Spawner::new)) {
            s.orders().offer("spawn");
            awaitTrue(() -> s.running("spawned"), "the recorded run spawned a processor");
        }
        try (Server s = boot(replay(store, journal), Spawner::new)) {
            awaitTrue(() -> s.replayer().complete(), "replay settled");
            Thread.sleep(100);       // a mistakenly queued live add has time to run
            assertFalse(s.running("spawned"), "replay must never execute addEventProcessor on the live controller");
            assertNotNull(s.replayer().stopped(PROCESSOR));
            assertTrue(s.replayer().stopped(PROCESSOR).contains("supplier"));
        }
    }

    @Test
    void startupControlRefusalRemainsVisibleAfterFailedProcessorIsDetached() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(record(store, journal), StartupSpawner::new)) {
            awaitTrue(() -> s.running("spawned"), "the original startup spawned a processor");
        }
        try (Server s = boot(replay(store, journal), StartupSpawner::new, true)) {
            assertFalse(s.running(PROCESSOR), "unsupported startup control caused the replayed add to fail");
            assertFalse(s.running("spawned"), "unsupported startup control never reached the live controller");
            assertTrue(s.replayer().complete(), "the failed replay is settled");
            String why = s.replayer().stopped(PROCESSOR);
            assertNotNull(why, "detaching the failed processor must preserve its replay refusal");
            assertTrue(why.contains("supplier"), why);
            assertEquals(com.telamin.mongoose.control.event.FailureKind.REPLAY_IDENTITY_MISMATCH, s.replayer().stopKind(PROCESSOR),
                    "the detached cursor keeps its stop kind with its reason");
        }
    }

    @Test
    void catchingAnInaccurateFailureSubstituteCannotMakeReplaySuccessful() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(record(store, journal), FailureCatcher::new)) {
            s.orders().offer("register-duplicate");
            awaitTrue(() -> store.entries(PROCESSOR).stream().anyMatch(e -> e instanceof ReplayEntry.Indexed),
                    "the processor caught the original failure and completed its event");
        }
        assertTrue(store.entries(PROCESSOR).stream().anyMatch(e -> e instanceof ReplayEntry.ControlCall call
                && call.outcome().startsWith("failed")));
        try (Server s = boot(replay(store, journal), FailureCatcher::new)) {
            awaitTrue(() -> s.replayer().complete(), "replay settled");
            assertNotNull(s.replayer().stopped(PROCESSOR), "catching the substitute exception cannot certify success");
            assertTrue(s.replayer().stopped(PROCESSOR).contains("exception cannot be reconstructed"));
            assertEquals(com.telamin.mongoose.control.event.FailureKind.REPLAY_STOPPED, s.replayer().stopKind(PROCESSOR));
        }
    }

    @Test
    void aReplayedControlRequestThatDiffersIsADivergence() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(record(store, journal), Killer::new)) {
            s.orders().offer("kill");
            awaitTrue(() -> !s.running(VICTIM), "stopped");
        }
        // the same input, a different program: the journal item is rewritten so the replayed processor asks otherwise
        InMemoryEventJournal altered = new InMemoryEventJournal();
        altered.append(ORDERS, 1, new JavaSerializationCodec().encode("kill-other"));
        try (Server s = boot(replay(store, altered), Killer::new)) {
            awaitTrue(() -> s.replayer().stopped(PROCESSOR) != null, "stopped with a reason");
            assertTrue(s.replayer().stopped(PROCESSOR).contains("control request differs"), s.replayer().stopped(PROCESSOR));
            assertEquals(com.telamin.mongoose.control.event.FailureKind.REPLAY_IDENTITY_MISMATCH, s.replayer().stopKind(PROCESSOR));
            assertTrue(s.running(VICTIM));
        }
    }

    @Test
    void interceptionCanBeSwitchedOff() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(record(store, journal).withControlInterception(false), Killer::new)) {
            s.orders().offer("kill");
            awaitTrue(() -> !s.running(VICTIM), "stopped");
        }
        assertTrue(store.entries(PROCESSOR).stream().noneMatch(e -> e instanceof ReplayEntry.ControlCall),
                "not recorded when switched off: " + store.entries(PROCESSOR));
    }

    @Test
    void aReplayIntoAnotherProgramIsRefusedByItsProvenance() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        recordAKill(store, journal);
        Provenance recorded = store.provenance(PROCESSOR);
        assertNotNull(recorded, "recorded beside the stream");
        assertEquals(Killer.class.getName(), recorded.handlerClass());
        assertTrue(store.entries(PROCESSOR).stream().noneMatch(e -> e.toString().contains("Provenance")),
                "never among the entries");

        try (Server s = boot(replay(store, journal), Impostor::new)) {
            awaitTrue(() -> s.replayer().stopped(PROCESSOR) != null, "stopped with a reason");
            assertTrue(s.replayer().stopped(PROCESSOR).contains("not the recorded one"), s.replayer().stopped(PROCESSOR));
            assertEquals(com.telamin.mongoose.control.event.FailureKind.REPLAY_IDENTITY_MISMATCH, s.replayer().stopKind(PROCESSOR));
        }
        try (Server s = boot(replay(store, journal).withProvenance(false), Impostor::new)) {
            awaitTrue(() -> s.replayer().complete(), "replayed without the check");
            assertNull(s.replayer().stopped(PROCESSOR), "the check is configurable");
        }
    }

    @Test
    void provenanceAloneIsAnExistingEmptyStreamAndPreventsRecordingOverIt(@TempDir Path dir) {
        var flow = new com.telamin.mongoose.internal.ConfigAwareEventProcessor(new Quiet());
        flow.init();
        Provenance provenance = Provenance.of(flow, 2, 99);
        String key = ReplayStreams.key("p#2", 2);
        InMemoryReplayStore memory = new InMemoryReplayStore();
        memory.began(key, provenance);
        assertEmptyRecordedStream(memory, key, flow);
        Path file = dir.resolve("empty-store.csv");
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec())) {
            store.began(key, provenance);
            assertEmptyRecordedStream(store, key, flow);
        }
        try (CsvReplayStore reopened = new CsvReplayStore(file, new JavaSerializationCodec())) {
            assertEmptyRecordedStream(reopened, key, flow);
        }
    }

    private void assertEmptyRecordedStream(ReplayStore store, String key,
                                           com.telamin.fluxtion.runtime.DataFlow flow) {
        assertTrue(store.has(key), "provenance proves the stream began even without an event");
        assertTrue(store.holdsRecording(), "a new RECORD run must not overwrite existing provenance");
        assertTrue(store.entries(key).isEmpty());
        GroupReplayer replayer = new GroupReplayer(ReplayConfig.replay(Set.of("p#2"), Map.of(), null, store),
                null, new ReplayScheduler());
        replayer.attach("p#2", 2, flow);
        assertNull(replayer.stopped(key), "an existing empty second-incarnation stream is valid");
        assertTrue(replayer.complete());
    }

    @Test
    void theCsvStoreKeepsControlCallsAndProvenance(@TempDir Path dir) {
        Path file = dir.resolve("store.csv");
        Provenance p = new Provenance("a.Processor", "a.Handler", 2, 99);
        ReplayEntry.ControlCall call = new ReplayEntry.ControlCall("stopProcessor", List.of("g", "v"), "failed: x, y", 120);
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec())) {
            store.began("p#2", p);
            store.append("p#2", call);
        }
        try (CsvReplayStore again = new CsvReplayStore(file, new JavaSerializationCodec())) {
            assertEquals(p, again.provenance("p#2"));
            assertEquals(List.of(call), again.entries("p#2"), "provenance is not an entry");
        }
    }
}
