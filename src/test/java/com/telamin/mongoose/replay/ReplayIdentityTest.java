package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.internal.ConfigAwareEventProcessor;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design "Deterministic replay" (control-plane PR 6, decision D3), with a processor that is not blind to when a
 * service arrives nor to the clock in its {@code stop()} (the scenario of the PR 6 review): a processor's stream is
 * keyed by its per-name build ordinal, a stop ends a stream and is replayed in position with its clock readings, the
 * next incarnation under the name replays a stream of its own, and a service registered at runtime reaches the replayed
 * processor where it reached the recorded one, after its start, even when the replay run registered it first.
 */
class ReplayIdentityTest {

    static final String ORDERS = "orders", PROCESSOR = "quotes", GROUP = "processor-agent";

    public interface Extra { }

    public static class Handler extends ObjectEventHandlerNode {
        private MessageSink<String> sink;
        private boolean started;
        private int extraAfterStart;
        private int extraBeforeStart;

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @ServiceRegistered
        public void extra(Extra extra, String name) {
            if (started) extraAfterStart++;
            else extraBeforeStart++;
        }

        @Override
        public void start() {
            started = true;
            getContext().subscribeToNamedFeed(ORDERS);
        }

        @Override
        public void stop() {
            emit("stopped time=" + getContext().getClock().getWallClockTime());
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (event instanceof String s && s.startsWith("ord-")) {
                emit("order=" + s + " extraBefore=" + extraBeforeStart + " extraAfter=" + extraAfterStart);
            }
            return true;
        }

        private void emit(String line) {
            if (sink != null) sink.accept(line);
        }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> orders, InMemoryMessageSink sink) implements AutoCloseable {
        GroupReplayer replayer() {
            return server.replayers().get(GROUP);
        }

        List<String> sinkLines() {
            return sink.getMessages().stream().map(String::valueOf).toList();
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    static Server boot(ReplayConfig replay) throws Exception {
        InMemoryEventSource<Object> orders = new InMemoryEventSource<>();
        orders.setName(ORDERS);
        orders.setCacheEventLog(true);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(GROUP)
                        .put(PROCESSOR, new EventProcessorConfig(new Handler())).build())
                .addEventFeed(EventFeedConfig.builder().instance(orders).name(ORDERS).broadcast(true)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        return new Server(server, orders, sink);
    }

    private static void reAdd(Server s) {
        s.server().addEventProcessor(PROCESSOR, GROUP, null, () -> {
            ConfigAwareEventProcessor flow = new ConfigAwareEventProcessor(new Handler());
            flow.init();
            return flow;
        });
    }

    private static boolean registered(Server s) {
        return s.server().registeredProcessors().getOrDefault(GROUP, List.of()).stream()
                .anyMatch(n -> n.name().equals(PROCESSOR));
    }

    /** The processor's feed subscription is made in start(): published only once a live input arrives. */
    private static void publishUntilHandled(Server s, String order, int lines) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        s.orders().offer(order);
        while (s.sinkLines().size() < lines && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(s.sinkLines().size() >= lines, "handled " + order + ": " + s.sinkLines());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(c.getAsBoolean(), what);
    }

    @Test
    void aStopAndAReincarnationReplayInPositionWithTheirClocksAndServices() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        List<String> live;
        try (Server s = boot(ReplayConfig.record(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store))) {
            awaitTrue(() -> registered(s), "started");
            Thread.sleep(200);                                  // the processor subscribes in start()
            publishUntilHandled(s, "ord-1", 1);
            Thread.sleep(50);                                   // so the stop's clock reading differs from ord-1's
            s.server().stopProcessor(GROUP, PROCESSOR);
            awaitTrue(() -> !registered(s), "stopped");
            awaitTrue(() -> s.sinkLines().size() >= 2, "stop() emitted");
            reAdd(s);
            awaitTrue(() -> registered(s), "re-added");
            Thread.sleep(200);
            s.server().registerService(new Service<>(new Extra() { }, Extra.class, "extra"));   // after start: recorded
            publishUntilHandled(s, "ord-2", 3);
            live = s.sinkLines();
        }
        assertEquals("order=ord-2 extraBefore=0 extraAfter=1", live.get(2), "the recorded run had the service after start");

        List<ReplayEntry> first = store.entries(PROCESSOR);
        List<ReplayEntry> second = store.entries(ReplayStreams.key(PROCESSOR, 2));
        ReplayEntry.Stopped stop = assertInstanceOf(ReplayEntry.Stopped.class, first.get(first.size() - 1), first.toString());
        assertTrue(!stop.reads().isEmpty(), "the stop's clock readings are recorded with it: " + stop);
        assertTrue(second.stream().anyMatch(e -> e instanceof ReplayEntry.ServiceChanged sc && sc.service().equals("extra")
                && sc.register()), second.toString());

        try (Server s = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store))) {
            // the replay stops the first incarnation where the recorded run did, reading the recorded clock
            awaitTrue(() -> !registered(s), "the replay stopped the first incarnation");
            awaitTrue(() -> s.replayer().outputs(PROCESSOR).size() >= 2, "stop() replayed");
            assertEquals(live.subList(0, 2), s.replayer().outputs(PROCESSOR).stream().map(String::valueOf).toList());
            assertNull(s.replayer().stopped(PROCESSOR), "no divergence in the replayed stop");

            // registered BEFORE the re-add here: the replay still delivers it where the recorded run had it
            s.server().registerService(new Service<>(new Extra() { }, Extra.class, "extra"));
            reAdd(s);
            String secondKey = ReplayStreams.key(PROCESSOR, 2);
            awaitTrue(() -> s.replayer().outputs(secondKey).size() >= 1 && s.replayer().complete(),
                    "the second stream replayed: " + s.replayer().outputs(secondKey) + " " + s.replayer().stopped(secondKey));
            assertNull(s.replayer().stopped(secondKey));
            assertEquals(live.subList(2, 3), s.replayer().outputs(secondKey).stream().map(String::valueOf).toList());
        }
    }

    @Test
    void aLaterIncarnationWithNoRecordedStreamIsStoppedLoudly() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        try (Server s = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store))) {
            awaitTrue(() -> registered(s), "started");
            s.server().stopProcessor(GROUP, PROCESSOR);
            awaitTrue(() -> !registered(s), "stopped");
            reAdd(s);
            String secondKey = ReplayStreams.key(PROCESSOR, 2);
            awaitTrue(() -> s.replayer().stopped(secondKey) != null, "stopped with a reason");
            assertTrue(s.replayer().stopped(secondKey).contains("no stream was recorded"), s.replayer().stopped(secondKey));
        }
    }

    @Test
    void aStreamRecordedBeforeIncarnationsReadsAsTheFirstIncarnation() {
        assertEquals("quotes", ReplayStreams.key("quotes", 1));
        assertEquals("quotes", ReplayStreams.key("quotes", 0));
        assertEquals("quotes#3", ReplayStreams.key("quotes", 3));
    }

    @Test
    void processorNamesCannotAliasAnIncarnationSuffix() {
        List<String> names = List.of("p", "p#2", "p#", "p##2", "p#2#3", "p###");
        java.util.Set<String> keys = new java.util.HashSet<>();
        InMemoryReplayStore store = new InMemoryReplayStore();
        for (String name : names) {
            for (int incarnation = 1; incarnation <= 4; incarnation++) {
                String key = ReplayStreams.key(name, incarnation);
                assertTrue(keys.add(key), "aliased stream: " + name + " incarnation " + incarnation + " -> " + key);
                store.append(key, new ReplayEntry.Stopped(incarnation, List.of()));
            }
        }
        for (String name : names) {
            for (int incarnation = 1; incarnation <= 4; incarnation++) {
                assertEquals(List.of(new ReplayEntry.Stopped(incarnation, List.of())),
                        store.entries(ReplayStreams.key(name, incarnation)));
            }
        }
    }

    @Test
    void anUnescapedLegacyNameIsRefusedWithAMigrationReason() {
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.append("p#2", new ReplayEntry.Stopped(100, List.of()));
        GroupReplayer replayer = new GroupReplayer(ReplayConfig.replay(Set.of("p#2"), Map.of(), null, store),
                null, new ReplayScheduler());
        ConfigAwareEventProcessor flow = new ConfigAwareEventProcessor(new Handler());
        flow.init();
        replayer.attach("p#2", flow);
        assertTrue(replayer.stopped(ReplayStreams.key("p#2", 1)).contains("ambiguous legacy stream key"));
        assertTrue(replayer.complete());
    }

    @Test
    void theCsvStoreKeepsStopsAndServiceChanges(@TempDir Path dir) {
        Path file = dir.resolve("store.csv");
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec())) {
            store.append("p#2", new ReplayEntry.ServiceChanged("extra", true, 7, 100, List.of(100L)));
            store.append("p", new ReplayEntry.Stopped(200, List.of()));
        }
        try (CsvReplayStore again = new CsvReplayStore(file, new JavaSerializationCodec())) {
            assertEquals(List.of(new ReplayEntry.ServiceChanged("extra", true, 7, 100, List.of(100L))), again.entries("p#2"));
            assertEquals(List.of(new ReplayEntry.Stopped(200, List.of())), again.entries("p"));
            assertTrue(again.has("p") && !again.has("p#3"), "absent is not empty");
        }
    }
}
