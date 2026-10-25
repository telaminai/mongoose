package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.internal.ConfigAwareEventProcessor;
import com.telamin.mongoose.replay.ReplayRecordingAcceptanceTest.Server;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.telamin.mongoose.replay.ReplayRecordingAcceptanceTest.CONTROLS;
import static com.telamin.mongoose.replay.ReplayRecordingAcceptanceTest.ORDERS;
import static com.telamin.mongoose.replay.ReplayRecordingAcceptanceTest.PROCESSOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design "Deterministic replay" (control-plane PR 6, decision D3): a processor's stream is keyed by its per-name
 * incarnation, a stop ends a stream and is replayed in position, the next incarnation under the name records and
 * replays a stream of its own, and service changes are recorded per stream and replayed in position.
 */
class ReplayIdentityTest {

    private static final String GROUP = "processor-agent";

    private static void reAdd(Server s) {
        s.server().addEventProcessor(PROCESSOR, GROUP, null, () -> {
            ConfigAwareEventProcessor flow = new ConfigAwareEventProcessor(new ReplayDemoHandler(ORDERS, CONTROLS));
            flow.init();
            return flow;
        });
    }

    private static void awaitGone(Server s) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registered(s) && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(!registered(s), "the processor left its group");
    }

    private static boolean registered(Server s) {
        return s.server().registeredProcessors().getOrDefault(GROUP, List.of()).stream()
                .anyMatch(n -> n.name().equals(PROCESSOR));
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(c.getAsBoolean(), what);
    }

    @Test
    void aStopAndAReincarnationRecordTwoStreamsThatReplayInPosition() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        List<String> live;
        try (Server s = ReplayRecordingAcceptanceTest.boot(ReplayRecordingAcceptanceTest.recording(store, journal))) {
            s.orders().offer("ord-1");
            s.await(1);
            s.server().stopProcessor(GROUP, PROCESSOR);
            awaitGone(s);
            reAdd(s);
            awaitTrue(() -> registered(s), "the next incarnation started");
            Thread.sleep(200);                          // it subscribes in start()
            s.server().registerService(new Service<>(new Object(), Object.class, "extra"));
            s.orders().offer("ord-2");
            s.await(2);
            live = s.lines();
        }

        // two streams: the first ends with the stop; the second holds the service change and its own order
        List<ReplayEntry> first = store.entries(PROCESSOR);
        List<ReplayEntry> second = store.entries(ReplayStreams.key(PROCESSOR, 2));
        assertInstanceOf(ReplayEntry.Stopped.class, first.get(first.size() - 1), first.toString());
        assertTrue(second.stream().anyMatch(e -> e instanceof ReplayEntry.ServiceChanged sc && sc.service().equals("extra")
                && sc.register()), second.toString());
        assertTrue(second.stream().anyMatch(e -> e instanceof ReplayEntry.Indexed), second.toString());
        assertTrue(first.stream().noneMatch(e -> e instanceof ReplayEntry.ServiceChanged sc && sc.service().equals("extra")),
                "the service change belongs to the incarnation that received it: " + first);

        try (Server s = ReplayRecordingAcceptanceTest.boot(ReplayRecordingAcceptanceTest.replaying(store, journal))) {
            // the replay stops the first incarnation where the recorded run stopped it
            awaitGone(s);
            assertNull(s.replayer().stopped(PROCESSOR), "the first stream replayed to its end: " + s.replayer().stopped(PROCESSOR));
            List<Object> firstOutputs = s.replayer().outputs(PROCESSOR);
            assertEquals(live.subList(0, 1), firstOutputs.stream().map(String::valueOf).toList());

            // the next incarnation attaches to its own stream; the service is registered as in the recorded run
            s.server().registerService(new Service<>(new Object(), Object.class, "extra"));
            reAdd(s);
            String second2 = ReplayStreams.key(PROCESSOR, 2);
            awaitTrue(() -> s.replayer().outputs(second2).size() >= 1 && s.replayer().complete(),
                    "the second stream replayed: " + s.replayer().outputs(second2) + " stopped=" + s.replayer().stopped(second2));
            assertNull(s.replayer().stopped(second2));
            assertEquals(live.subList(1, 2), s.replayer().outputs(second2).stream().map(String::valueOf).toList());
        }
    }

    @Test
    void aStreamRecordedBeforeIncarnationsReadsAsTheFirstIncarnation() {
        assertEquals("quotes", ReplayStreams.key("quotes", 1));
        assertEquals("quotes", ReplayStreams.key("quotes", 0));
        assertEquals("quotes#3", ReplayStreams.key("quotes", 3));
    }

    @Test
    void theCsvStoreKeepsStopsAndServiceChanges(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path file = dir.resolve("store.csv");
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec())) {
            store.append("p#2", new ReplayEntry.ServiceChanged("extra", true, 7, 100));
            store.append("p", new ReplayEntry.Stopped(200));
        }
        try (CsvReplayStore again = new CsvReplayStore(file, new JavaSerializationCodec())) {
            assertEquals(List.of(new ReplayEntry.ServiceChanged("extra", true, 7, 100)), again.entries("p#2"));
            assertEquals(List.of(new ReplayEntry.Stopped(200)), again.entries("p"));
        }
    }
}
