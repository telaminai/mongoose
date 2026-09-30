package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;
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
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the independent re-review of #47 at 4a18003 (N1-N6; N7 is in ReplayIndependentReviewTest). Each shows a
 * replay that completed with the wrong input and no stop reason, a setup failure that ended the process, or a store the
 * next open could not read.
 */
class ReplayReReviewTest {

    static final String FEED = "feed", GROUP = "processor-agent";

    /** A named event with state of its own (N2): it cannot be rebuilt as the base class. */
    public static class DemoNamedEvent extends NamedFeedEventImpl<String> {
        public int extra;

        public DemoNamedEvent(int extra) {
            super("DEMO-inner", "DEMO-topic", 7L, "DEMO-item");
            this.extra = extra;
        }
    }

    /** Serializable, with state its serial form drops (N5). */
    public static class TransientCarrier implements Serializable {
        public transient int value;
        public int kept;

        public TransientCarrier(int value, int kept) {
            this.value = value;
            this.kept = kept;
        }
    }

    /** Emits every field of what it was given; a MutableValue it changes after emitting, as a handler may. */
    public static class FieldProbe extends ObjectEventHandlerNode {
        private MessageSink<String> sink;

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (sink == null) return true;
            if (event instanceof ReplayIndependentReviewTest.MutableValue m) {
                sink.accept("value=" + m.value);
                m.value++;
            } else if (event instanceof NamedFeedEvent<?> n) {
                sink.accept("named class=" + n.getClass().getSimpleName() + " feed=" + n.eventFeedName() + " topic="
                        + n.topic() + " seq=" + n.sequenceNumber() + " time=" + n.getEventTime() + " delete=" + n.delete()
                        + " data=" + n.data() + (n instanceof DemoNamedEvent d ? " extra=" + d.extra : ""));
            } else if (event instanceof TransientCarrier t) {
                sink.accept("transient=" + t.value + " kept=" + t.kept);
            } else {
                sink.accept("bare=" + event);
            }
            return true;
        }
    }

    static DataFlow probe() {
        return new DefaultEventProcessor(new FieldProbe());
    }

    /** A direct SPI implementation (N4): it delegates dispatch, and inherits processEventRecording's default. */
    public static class DirectStrategy implements EventToInvokeStrategy {
        private final EventToOnEventInvokeStrategy d = new EventToOnEventInvokeStrategy();

        @Override public void processEvent(Object event) { d.processEvent(event); }
        @Override public void processEvent(Object event, long time) { d.processEvent(event, time); }
        @Override public void setSyntheticTime(DataFlow target, long time) { d.setSyntheticTime(target, time); }
        @Override public void processEventFor(DataFlow target, Object event) { d.processEventFor(target, event); }
        @Override public void muteLive(DataFlow target) { d.muteLive(target); }
        @Override public void registerProcessor(DataFlow p) { d.registerProcessor(p); }
        @Override public void deregisterProcessor(DataFlow p) { d.deregisterProcessor(p); }
        @Override public int listenerCount() { return d.listenerCount(); }
        @Override public Collection<DataFlow> registeredProcessors() { return d.registeredProcessors(); }
    }

    record Server(MongooseServer server, InMemoryEventSource<Object> feed, InMemoryMessageSink sink) implements AutoCloseable {
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
            Thread.sleep(50);
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

    static Server boot(ReplayConfig replay, Map<String, Supplier<DataFlow>> processors, Supplier<EventToInvokeStrategy> onEvent)
            throws Exception {
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(FEED);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        EventProcessorGroupConfig.Builder group = EventProcessorGroupConfig.builder().agentName(GROUP);
        processors.forEach((name, p) -> group.put(name, EventProcessorConfig.builder().handler(p.get()).build()));
        MongooseServerConfig.Builder b = MongooseServerConfig.builder()
                .addProcessorGroup(group.build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(FEED).broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay);
        if (onEvent != null) b.onEventInvokeStrategy(onEvent);
        MongooseServer server = MongooseServer.bootServer(b.build(), r -> { });
        Thread.sleep(300);
        return new Server(server, feed, sink);
    }

    static Map<String, Supplier<DataFlow>> one() {
        return Map.of("probe", ReplayReReviewTest::probe);
    }

    static Map<String, Supplier<DataFlow>> two() {
        Map<String, Supplier<DataFlow>> m = new LinkedHashMap<>();
        m.put("first", ReplayReReviewTest::probe);
        m.put("second", ReplayReReviewTest::probe);
        return m;
    }

    /** Record {@code offered} into {@code store}; the live lines. */
    static List<String> record(ReplayConfig record, Map<String, Supplier<DataFlow>> processors, Supplier<EventToInvokeStrategy> s,
                               int lines, Object... offered) throws Exception {
        try (Server srv = boot(record, processors, s)) {
            for (Object o : offered) srv.feed().offer(o);
            srv.awaitLive(lines);
            return srv.live();
        }
    }

    // ---- N1: the whole replay setup is contained ----------------------------------------------------------------

    @Test
    void n1_aClockThatCannotBeInstalled_isAStoppedMutedReplay_whenAttachedDirectly() {
        DataFlow refuses = new ReplaySetupFailureChildMain.RefusesReplayClock();
        GroupReplayer replayer = new GroupReplayer(ReplayConfig.replay(Set.of("probe"), Map.of(), null, new InMemoryReplayStore()),
                new ReplayRouting() {
                    @Override public ReplayRoute routeFor(String source, String route, DataFlow flow) { return null; }
                    @Override public com.telamin.mongoose.service.EventSource.EventWrapStrategy wrapOf(String source) { return null; }
                    @Override public com.telamin.mongoose.service.admin.impl.AdminCommand adminCommand(String name) { return null; }
                }, new ReplayScheduler());
        replayer.attach("probe", refuses);                             // must not throw out of the agent's setup
        assertTrue(replayer.replays(refuses), "it stays replayed, so its live inputs are muted");
        String stopped = replayer.stopped("probe");
        assertNotNull(stopped, "stopped, with a reason");
        assertTrue(stopped.contains("DEMO cannot install replay clock"), stopped);
    }

    @Test
    void n1_aClockThatCannotBeInstalled_neverEndsARealServer_norLetsLiveInputsThrough() throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("--add-opens") || a.startsWith("--add-exports")).forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), ReplaySetupFailureChildMain.class.getName()));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the child finished");
        String survived = out.lines().filter(l -> l.startsWith("SURVIVED")).findFirst().orElse(null);
        assertEquals(0, p.exitValue(), "the server survived a replay clock it could not install (exit 0): " + (survived != null
                ? survived : out.lines().filter(l -> l.contains("Exception") || l.contains("Error")).limit(3).toList()));
        assertNotNull(survived, out);
        assertTrue(survived.contains("DEMO cannot install replay clock"), "stopped, naming the failure: " + survived);
        assertTrue(survived.contains("live=[]") && survived.contains("outputs=[]"),
                "and neither route delivered the live item, to the sink or to the capture: " + survived);
    }

    // ---- N2: a named event's time and type ----------------------------------------------------------------------

    static NamedFeedEventImpl<String> timedDeleted() {
        NamedFeedEventImpl<String> e = new NamedFeedEventImpl<String>("DEMO-inner", "DEMO-topic", 123L, "DEMO-item").delete(true);
        e.setEventTime(17);
        return e;
    }

    @Test
    void n2_aNamedEventsTimeAndDeleteFlag_replayAsReceived_memoryStore() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = record(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), one(), null, 1, timedDeleted());
        assertTrue(live.get(0).contains("time=17") && live.get(0).contains("delete=true"), "precondition: " + live);
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), one(), null)) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped("probe"));
            assertEquals(live, r.replayed("probe"), "its event time is the recorded one, not the replaying machine's");
        }
    }

    @Test
    void n2_aNamedEventsTimeAndDeleteFlag_replayAsReceived_csvStore(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.csv");
        List<String> live;
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec())) {
            live = record(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), one(), null, 1, timedDeleted());
        }
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec());
             Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), one(), null)) {
            r.awaitReplayDone();
            assertEquals(null, r.replayer().stopped("probe"));
            assertEquals(live, r.replayed("probe"), "its event time is the recorded one");
        }
    }

    void aNamedSubclassIsNeverFlattened(ReplayStore recordInto, Supplier<ReplayStore> replayFrom) throws Exception {
        List<String> live = record(ReplayConfig.record(Set.of("probe"), Map.of(), null, recordInto), one(), null, 1, new DemoNamedEvent(17));
        assertTrue(live.get(0).contains("class=DemoNamedEvent") && live.get(0).contains("extra=17"), "precondition: " + live);
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, replayFrom.get()), one(), null)) {
            r.awaitReplayDone();
            String stopped = r.replayer().stopped("probe");
            if (stopped == null) {
                assertEquals(live, r.replayed("probe"), "replayed as itself, with its own state");
            } else {
                assertTrue(stopped.contains("DemoNamedEvent"), "or refused by name: " + stopped);
                assertEquals(List.of(), r.replayed("probe"), "and never replayed as the base class");
            }
        }
    }

    @Test
    void n2_aNamedEventSubclass_isNeverReplayedAsTheBaseClass_memoryStore() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        aNamedSubclassIsNeverFlattened(store, () -> store);
    }

    @Test
    void n2_aNamedEventSubclass_isNeverReplayedAsTheBaseClass_csvStore(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.csv");
        try (CsvReplayStore into = new CsvReplayStore(file, new JavaSerializationCodec());
             CsvReplayStore from = new CsvReplayStore(file, new JavaSerializationCodec())) {
            aNamedSubclassIsNeverFlattened(into, () -> from);
        }
    }

    // ---- N3: journalled fan-out ---------------------------------------------------------------------------------

    @Test
    void n3_aJournalledItemFannedOut_replaysWhatEachRecipientReceived() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        Map<String, EventCodec> journalled = Map.of(FEED, new JavaSerializationCodec());
        List<String> live = record(ReplayConfig.record(Set.of("first", "second"), journalled, journal, store), two(), null, 2,
                new ReplayIndependentReviewTest.MutableValue(0));
        assertEquals(List.of("value=0", "value=1"), live, "precondition: the second received what the first left");
        List<String> replayed = new ArrayList<>();
        for (String processor : List.of("first", "second")) {
            try (Server r = boot(ReplayConfig.replay(Set.of(processor), journalled, journal, store),
                    Map.of(processor, ReplayReReviewTest::probe), null)) {
                r.awaitReplayDone();
                assertEquals(null, r.replayer().stopped(processor));
                replayed.addAll(r.replayed(processor));
            }
        }
        assertEquals(live, replayed.stream().sorted().toList(), "each recipient replays what it, not the journal, received");
    }

    // ---- N4: a strategy that cannot capture each recipient's input ----------------------------------------------

    @Test
    void n4_aDirectStrategyFanningOut_isNeverRecordedAsIfCaptured_andStillDeliversLive() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = record(ReplayConfig.record(Set.of("first", "second"), Map.of(), null, store), two(), DirectStrategy::new, 2,
                new ReplayIndependentReviewTest.MutableValue(0));
        assertEquals(List.of("value=0", "value=1"), live, "live delivery is undisturbed");
        List<String> replayed = new ArrayList<>();
        List<String> stops = new ArrayList<>();
        for (String processor : List.of("first", "second")) {
            try (Server r = boot(ReplayConfig.replay(Set.of(processor), Map.of(), null, store),
                    Map.of(processor, ReplayReReviewTest::probe), DirectStrategy::new)) {
                r.awaitReplayDone();
                if (r.replayer().stopped(processor) != null) stops.add(r.replayer().stopped(processor));
                replayed.addAll(r.replayed(processor));
            }
        }
        if (stops.isEmpty()) {
            assertEquals(live, replayed.stream().sorted().toList(), "replayed as each received it");
        } else {
            assertTrue(stops.stream().allMatch(s -> s.contains("DirectStrategy")), "or refused, naming the strategy: " + stops);
            assertTrue(replayed.isEmpty(), "and nothing replayed from a capture that was not taken: " + replayed);
        }
    }

    // ---- N5: an input whose serial form drops state ------------------------------------------------------------

    @Test
    void n5_anInputWithTransientState_isNeverRecordedAsReceived() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = record(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), one(), null, 1, new TransientCarrier(17, 5));
        assertEquals(List.of("transient=17 kept=5"), live, "precondition");
        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), one(), null)) {
            r.awaitReplayDone();
            String stopped = r.replayer().stopped("probe");
            if (stopped == null) {
                assertEquals(live, r.replayed("probe"), "replayed as received");
            } else {
                assertTrue(stopped.contains("transient"), "or refused, naming why: " + stopped);
                assertEquals(List.of(), r.replayed("probe"));
            }
        }
    }

    // ---- N6: a header-only store of the earlier format ---------------------------------------------------------

    static String sha(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    @Test
    void n6_anEmptyStoreOfTheEarlierFormat_isNeverCorruptedByAnAppend(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.csv");
        Files.writeString(file, CsvReplayStore.HEADER_6 + "\n", StandardCharsets.UTF_8);
        String before = sha(file);
        boolean appended;
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec())) {
            try {
                store.append("probe", new ReplayEntry.TimerFired(1, List.of(5L)));
                appended = true;
            } catch (IllegalStateException refused) {
                appended = false;
            }
        }
        if (!appended) {
            assertEquals(before, sha(file), "a refused append leaves the file byte for byte as it was");
        }
        try (CsvReplayStore again = new CsvReplayStore(file, new JavaSerializationCodec())) {
            assertEquals(appended ? 1 : 0, again.entries("probe").size(), "and the file reopens, holding what was written");
        }
    }

    @Test
    void n6_recordIntoAnEmptyStoreOfTheEarlierFormat_leavesAReadableFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.csv");
        Files.writeString(file, CsvReplayStore.HEADER_6 + "\n", StandardCharsets.UTF_8);
        String before = sha(file);
        boolean recorded;
        try (CsvReplayStore store = new CsvReplayStore(file, new JavaSerializationCodec())) {
            try {
                record(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), one(), null, 1, "DEMO-item");
                recorded = true;
            } catch (Exception refused) {
                recorded = false;
            }
        }
        if (!recorded) assertEquals(before, sha(file), "a refused RECORD leaves the file unchanged");
        try (CsvReplayStore again = new CsvReplayStore(file, new JavaSerializationCodec())) {
            assertEquals(recorded ? 1 : 0, again.entries("probe").size(), "the file reopens");
        }
    }
}
