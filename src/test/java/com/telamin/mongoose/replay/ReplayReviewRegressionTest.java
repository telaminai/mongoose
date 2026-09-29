package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ServiceConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import com.telamin.mongoose.spike.replay.ReplayableEventSource;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.output.MessageSink;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the review of #47, one per finding, each failing on the reviewed code (31ef97b): a replay that
 * diverged, stalled, recorded the wrong thing or corrupted its store, silently.
 */
class ReplayReviewRegressionTest {

    static final String ORDERS = "orders", CONTROLS = "controls", PROCESSOR = "quotes", OTHER = "other";

    record Server(MongooseServer server, InMemoryEventSource<Object> orders, ReplayableEventSource controls,
                  InMemoryMessageSink sink) implements AutoCloseable {
        List<String> lines() {
            return sink.getMessages().stream().map(String::valueOf).toList();
        }

        void await(int n) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (sink.getMessages().size() < n && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(sink.getMessages().size() >= n, "expected " + n + " lines: " + lines());
        }

        GroupReplayer replayer() {
            return server.replayers().get("processor-agent");
        }

        /** Until the replay completes or stops; fails if it does neither. */
        void awaitReplayDone() throws InterruptedException {
            long deadline = System.nanoTime() + 8_000_000_000L;
            while ((replayer() == null || !replayer().complete()) && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(replayer() != null && replayer().complete(),
                    "the replay neither completed nor stopped: stopped=" + (replayer() == null ? null : replayer().stopped(PROCESSOR)));
            Thread.sleep(50);
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    /** The demo processor, and optionally a second one in the same group that is never replayed. */
    static Server boot(ReplayConfig replay, boolean withOther, boolean namedOrders) throws Exception {
        InMemoryEventSource<Object> orders = new InMemoryEventSource<>();
        orders.setName(ORDERS);
        ReplayableEventSource controls = new ReplayableEventSource();
        controls.setName(CONTROLS);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        EventProcessorGroupConfig.Builder group = EventProcessorGroupConfig.builder().agentName("processor-agent")
                .put(PROCESSOR, new EventProcessorConfig(new ReplayDemoHandler(ORDERS, CONTROLS)));
        if (withOther) group.put(OTHER, new EventProcessorConfig(new ReplayDemoHandler(ORDERS, CONTROLS)));
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(group.build())
                .addEventFeed(EventFeedConfig.builder().instance(orders).name(ORDERS).broadcast(true).wrapWithNamedEvent(namedOrders)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventFeed(EventFeedConfig.builder().instance(controls).name(CONTROLS).broadcast(true)
                        .agent("controls-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .addService(new ServiceConfig<>(new AdminCommandProcessor(), AdminCommandRegistry.class, "adminService"))
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        Thread.sleep(200);
        return new Server(server, orders, controls, sink);
    }

    static ReplayConfig recording(ReplayStore store, EventJournal journal) {
        return ReplayConfig.record(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store);
    }

    static ReplayConfig replaying(ReplayStore store, EventJournal journal) {
        return ReplayConfig.replay(Set.of(PROCESSOR), Map.of(ORDERS, new JavaSerializationCodec()), journal, store);
    }

    static ReplayRecord replayRecord(Object event, long time) {
        ReplayRecord r = new ReplayRecord();
        r.setEvent(event);
        r.setWallClockTime(time);
        return r;
    }

    // ---- 1: a ReplayRecord input -------------------------------------------------------------------------------

    @Test
    void f1_aReplayRecordInput_isRecordedAsItsEvent_andTheReplayMatches() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        List<String> live;
        try (Server s = boot(recording(store, journal), false, false)) {
            s.orders().offer("ord-1");                                  s.await(1);
            s.controls().replay(replayRecord("suspend", 42));          s.await(2);
            s.orders().offer("ord-2");                                  s.await(4);
            live = s.lines();
        }
        assertTrue(live.get(1).equals("control=suspend time=42"), "the record's instant, live: " + live);
        try (Server r = boot(replaying(store, journal), false, false)) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped(PROCESSOR));
            assertEquals(live, r.lines(), "the replay does what the recorded run did, the ReplayRecord's input included");
        }
    }

    // ---- 2: a replay that cannot deliver an entry --------------------------------------------------------------

    @Test
    void f2_aMissingAdminCommand_stopsTheReplayWithAReason_ratherThanStalling() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.append(PROCESSOR, new ReplayEntry.AdminInvoked("quotes.nosuch", List.of(), List.of(1_000L)));
        try (Server r = boot(replaying(store, new InMemoryEventJournal()), false, false)) {
            r.awaitReplayDone();
            String stopped = r.replayer().stopped(PROCESSOR);
            assertNotNull(stopped, "the replay stops and says why");
            assertTrue(stopped.contains("quotes.nosuch"), stopped);
        }
    }

    // ---- 3: a recording failure is not a dispatch failure ------------------------------------------------------

    /** A store whose appends fail. */
    static final class BrokenStore implements ReplayStore {
        final List<ReplayEntry> appended = new ArrayList<>();

        @Override
        public void append(String processor, ReplayEntry entry) {
            appended.add(entry);
            throw new IllegalStateException("DEMO store full");
        }

        @Override
        public List<ReplayEntry> entries(String processor) {
            return List.of();
        }
    }

    @Test
    void f3_aStoreThatFails_doesNotMakeTheProcessorHandleTheInputAgain() throws Exception {
        BrokenStore store = new BrokenStore();
        try (Server s = boot(recording(store, new InMemoryEventJournal()), false, false)) {
            s.controls().offer("suspend");
            s.await(1);
            Thread.sleep(300);                                          // room for the agent's retries
            assertEquals(List.of("control=suspend"), s.lines().stream().map(l -> l.substring(0, l.indexOf(" time="))).toList(),
                    "the processor handled its input once: a recording failure is not a dispatch failure to retry");
            assertTrue(store.appended.stream().noneMatch(e -> e instanceof ReplayEntry.Failed),
                    "and it is not recorded as the processor failing: " + store.appended);
        }
    }

    @Test
    void f3_anInlineNamedEventInput_isRecordedToACsvStore_andReplays(@TempDir Path dir) throws Exception {
        Path storeFile = dir.resolve("store.csv");
        List<String> live;
        try (CsvReplayStore store = new CsvReplayStore(storeFile, new JavaSerializationCodec());
             Server s = boot(ReplayConfig.record(Set.of(PROCESSOR), Map.of(), null, store), false, true)) {
            s.orders().offer("ord-1");
            s.await(1);
            live = s.lines();
        }
        try (CsvReplayStore store = new CsvReplayStore(storeFile, new JavaSerializationCodec());
             Server r = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(), null, store), false, true)) {
            assertTrue(store.entries(PROCESSOR).stream().noneMatch(e -> e instanceof ReplayEntry.Failed),
                    "the named event was recorded, not failed: " + store.entries(PROCESSOR));
            r.awaitReplayDone();
            assertEquals(live, r.lines());
        }
    }

    // ---- 4, 5: CSV files ---------------------------------------------------------------------------------------

    @Test
    void f4_recordingIntoAJournalThatAlreadyHoldsARecording_isRefused(@TempDir Path dir) throws Exception {
        Path journalFile = dir.resolve("journal.csv"), storeFile = dir.resolve("store.csv");
        try (CsvEventJournal journal = new CsvEventJournal(journalFile);
             CsvReplayStore store = new CsvReplayStore(storeFile, new JavaSerializationCodec());
             Server s = boot(recording(store, journal), false, false)) {
            s.orders().offer("ord-1");
            s.await(1);
        }
        try (CsvEventJournal journal = new CsvEventJournal(journalFile);
             CsvReplayStore store = new CsvReplayStore(storeFile, new JavaSerializationCodec())) {
            Exception refused = assertThrows(Exception.class, () -> boot(recording(store, journal), false, false).close());
            assertTrue(String.valueOf(refused.getMessage()).contains("already"), "says why: " + refused);
        }
    }

    @Test
    void f5_aTornLastLine_isDropped_andTheRestIsRead(@TempDir Path dir) throws Exception {
        Path storeFile = dir.resolve("store.csv");
        try (CsvReplayStore store = new CsvReplayStore(storeFile, new JavaSerializationCodec())) {
            store.append(PROCESSOR, new ReplayEntry.TimerFired(1, List.of(5L)));
        }
        Files.writeString(storeFile, Files.readString(storeFile) + "quotes,TIMER,,2", StandardCharsets.UTF_8);   // torn
        try (CsvReplayStore store = new CsvReplayStore(storeFile, new JavaSerializationCodec())) {
            assertEquals(List.of(new ReplayEntry.TimerFired(1, List.of(5L))), store.entries(PROCESSOR));
        }
    }

    // ---- 6, 7: a replay beside live work -----------------------------------------------------------------------

    @Test
    void f6_aLiveInputDuringAReplay_doesNotReachTheReplayedProcessor() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        store.append(PROCESSOR, new ReplayEntry.Inline(CONTROLS, "suspend", List.of(1_000L)));
        try (Server r = boot(replaying(store, new InMemoryEventJournal()), false, false)) {
            r.awaitReplayDone();
            r.orders().offer("ord-LIVE");
            Thread.sleep(300);
            assertTrue(r.lines().stream().noneMatch(l -> l.contains("ord-LIVE")),
                    "the replayed processor sees only the replay: " + r.lines());
        }
    }

    @Test
    void f7_aProcessorNotReplayed_keepsItsLiveTimers() throws Exception {
        try (Server r = boot(replaying(new InMemoryReplayStore(), new InMemoryEventJournal()), true, false)) {
            r.controls().offer("arm");                                  // the OTHER processor arms a 40 ms timer
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (r.lines().stream().noneMatch(l -> l.startsWith("timeout")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("timeout")),
                    "a processor that is not replayed runs on the live scheduler: " + r.lines());
        }
    }

    // ---- 8: divergence ----------------------------------------------------------------------------------------

    @Test
    void f8_aProcessorReadingItsClockOtherThanRecorded_isReported() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        // the recorded cycle read the clock three times; this build reads it once
        store.append(PROCESSOR, new ReplayEntry.Inline(CONTROLS, "suspend", List.of(1_000L, 1_001L, 1_002L)));
        try (Server r = boot(replaying(store, new InMemoryEventJournal()), false, false)) {
            r.awaitReplayDone();
            String stopped = r.replayer().stopped(PROCESSOR);
            assertNotNull(stopped, "a different number of clock reads is a divergence, and is reported");
            assertTrue(stopped.contains("clock"), stopped);
        }
    }

    // ---- 9: a timer that throws --------------------------------------------------------------------------------

    public static class ThrowingTimerHandler extends ReplayDemoHandler {
        private SchedulerService scheduler;

        public ThrowingTimerHandler() {
            super(ORDERS, CONTROLS);
        }

        @Override
        @ServiceRegistered
        public void scheduler(SchedulerService scheduler, String name) {
            super.scheduler(scheduler, name);
            this.scheduler = scheduler;
        }

        @Override
        protected boolean handleEvent(Object event) {
            if ("arm-boom".equals(event)) {
                scheduler.scheduleAfterDelay(20, () -> {
                    throw new IllegalStateException("DEMO timer failure");
                });
                return true;
            }
            return super.handleEvent(event);
        }
    }

    @Test
    void f9_aTimerThatThrows_isRecordedAsAFailure() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventSource<Object> controls = new InMemoryEventSource<>();
        controls.setName(CONTROLS);
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put(PROCESSOR, new EventProcessorConfig(new ThrowingTimerHandler())).build())
                .addEventFeed(EventFeedConfig.builder().instance(controls).name(CONTROLS).broadcast(true)
                        .agent("controls-agent", new BusySpinIdleStrategy()).build())
                .replay(ReplayConfig.record(Set.of(PROCESSOR), Map.of(), null, store))
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        try {
            Thread.sleep(200);
            controls.offer("arm-boom");
            Thread.sleep(400);
        } finally {
            server.stop();
        }
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        assertTrue(entries.stream().noneMatch(e -> e instanceof ReplayEntry.TimerFired), "not recorded as fired: " + entries);
        assertTrue(entries.stream().anyMatch(e -> e instanceof ReplayEntry.Failed), "recorded as a failure: " + entries);
    }

    // ---- 10: a pooled item in a late subscriber's catch-up -----------------------------------------------------

    public static final class Pooled implements PoolAware, java.io.Serializable {
        private final transient PoolTracker<Pooled> tracker = new PoolTracker<>();
        private String value;

        public Pooled() {
        }

        static Pooled of(String value) {
            Pooled p = Pools.SHARED.getOrCreate(Pooled.class, Pooled::new, x -> x.value = null).acquire();
            p.value = value;
            return p;
        }

        @Override
        public PoolTracker<?> getPoolTracker() {
            return tracker;
        }

        @Override
        public String toString() {
            return "Pooled[" + value + "]";
        }
    }

    /**
     * The review inferred that a late subscriber's catch-up delivers a pooled item's String snapshot under the journalled
     * original's sequence number. It does not: a published item's snapshot stays in the event log (read by a node through
     * {@code eventLog()}) and is never dispatched again. This holds that invariant.
     */
    @Test
    void f10_aPublishedPooledItemsSnapshot_isNeverRedispatched() {
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>(ORDERS);
        publisher.setCacheEventLog(true);
        publisher.journal(new InMemoryEventJournal(), new JavaSerializationCodec());
        publisher.publish(Pooled.of("A"));                              // published: its String snapshot is only logged
        OneToOneConcurrentArrayQueue<Object> late = new OneToOneConcurrentArrayQueue<>(16);
        publisher.addTargetQueue(late, "late");
        publisher.dispatchCachedEventLog();
        assertEquals(null, late.poll(), "the snapshot is never dispatched, so it can never be recorded by index");
        assertEquals("Pooled[A]", String.valueOf(publisher.getEventLog().get(0).data()), "it stays in the event log");
    }
}
