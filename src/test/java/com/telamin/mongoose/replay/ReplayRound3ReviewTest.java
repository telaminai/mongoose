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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the re-review of #47 at cd52628 (F1-F4). Each shows a replay that completed with an input other than
 * the one received, a setup failure that ended the process, or an input delivered but recorded as nothing.
 */
class ReplayRound3ReviewTest {

    static final String FEED = "feed", GROUP = "processor-agent";

    // ---- F1's inputs: state only the feed's codec carries faithfully -----------------------------------------------

    /** Serializable, but its Java serial form writes 0 for its value; the feed's codec carries the value. */
    public static class CodecValue implements Serializable {
        public int value;

        public CodecValue(int value) {
            this.value = value;
        }

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeInt(0);                                    // deliberately not its state: only the codec is faithful
        }

        private void readObject(ObjectInputStream in) throws IOException {
            value = in.readInt();
        }
    }

    /** Not Serializable at all: only its feed's codec can carry it. */
    public static class CodecOnly {
        public int value;

        public CodecOnly(int value) {
            this.value = value;
        }
    }

    /**
     * A faithful codec whose bytes differ for the same state (a changing leading byte, as a timestamp or counter would):
     * decoding always restores the value. Bytes that differ must not stand for a changed input.
     */
    public static class NondeterministicCodec implements EventCodec {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public byte[] encode(Object item) {
            int value = item instanceof CodecValue v ? v.value : ((CodecOnly) item).value;
            byte kind = (byte) (item instanceof CodecValue ? 1 : 2);
            return new byte[]{(byte) sequence.incrementAndGet(), kind, (byte) value};
        }

        @Override
        public Object decode(byte[] bytes) {
            return bytes[1] == 1 ? new CodecValue(bytes[2]) : new CodecOnly(bytes[2]);
        }
    }

    /** A named event that carries an integer filter, so that copyFrom can give one to the exact base class (F2). */
    public static class FilteredNamedEvent extends NamedFeedEventImpl<Object> {
        public FilteredNamedEvent(int filterId) {
            super("DEMO-inner", "DEMO-topic", 5L, "DEMO-item");
            this.filterId = filterId;
        }
    }

    /** Emits every field of what it was given; a CodecValue or CodecOnly it changes after emitting, as a handler may. */
    public static class Probe extends ObjectEventHandlerNode {
        private MessageSink<String> sink;

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (sink == null) return true;
            if (event instanceof CodecValue v) {
                sink.accept("codec=" + v.value);
                v.value++;
            } else if (event instanceof CodecOnly o) {
                sink.accept("codecOnly=" + o.value);
                o.value++;
            } else if (event instanceof NamedFeedEvent<?> n) {
                sink.accept("named class=" + n.getClass().getSimpleName() + " feed=" + n.eventFeedName() + " topic=" + n.topic()
                        + " seq=" + n.sequenceNumber() + " time=" + n.getEventTime() + " delete=" + n.delete()
                        + " filterId=" + n.filterId() + " filterString=" + n.filterString() + " data=" + describe(n.data()));
            } else {
                sink.accept("bare=" + event);
            }
            return true;
        }

        static String describe(Object data) {
            return data instanceof CodecValue v ? "codec:" + v.value : String.valueOf(data);
        }
    }

    static DataFlow probe() {
        return new DefaultEventProcessor(new Probe());
    }

    /** A direct SPI implementation that delivers, but names no processor: registeredProcessors() is the default (F4). */
    public static class UnnamedTargetsStrategy implements EventToInvokeStrategy {
        private final EventToOnEventInvokeStrategy d = new EventToOnEventInvokeStrategy();

        @Override public void processEvent(Object event) { d.processEvent(event); }
        @Override public void processEvent(Object event, long time) { d.processEvent(event, time); }
        @Override public void setSyntheticTime(DataFlow target, long time) { d.setSyntheticTime(target, time); }
        @Override public void processEventFor(DataFlow target, Object event) { d.processEventFor(target, event); }
        @Override public void muteLive(DataFlow target) { d.muteLive(target); }
        @Override public void registerProcessor(DataFlow p) { d.registerProcessor(p); }
        @Override public void deregisterProcessor(DataFlow p) { d.deregisterProcessor(p); }
        @Override public int listenerCount() { return d.listenerCount(); }
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

    static Server boot(ReplayConfig replay, Map<String, Supplier<DataFlow>> processors, boolean wrapped,
                       Supplier<EventToInvokeStrategy> onEvent) throws Exception {
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(FEED);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        EventProcessorGroupConfig.Builder group = EventProcessorGroupConfig.builder().agentName(GROUP);
        processors.forEach((name, p) -> group.put(name, EventProcessorConfig.builder().handler(p.get()).build()));
        MongooseServerConfig.Builder b = MongooseServerConfig.builder()
                .addProcessorGroup(group.build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(FEED).broadcast(true).wrapWithNamedEvent(wrapped)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay);
        if (onEvent != null) b.onEventInvokeStrategy(onEvent);
        MongooseServer server = MongooseServer.bootServer(b.build(), r -> { });
        Thread.sleep(300);
        return new Server(server, feed, sink);
    }

    static Map<String, Supplier<DataFlow>> one() {
        return Map.of("probe", ReplayRound3ReviewTest::probe);
    }

    static Map<String, Supplier<DataFlow>> two() {
        Map<String, Supplier<DataFlow>> m = new LinkedHashMap<>();
        m.put("first", ReplayRound3ReviewTest::probe);
        m.put("second", ReplayRound3ReviewTest::probe);
        return m;
    }

    static List<String> record(ReplayConfig record, Map<String, Supplier<DataFlow>> processors, boolean wrapped,
                               Supplier<EventToInvokeStrategy> s, int lines, Object... offered) throws Exception {
        try (Server srv = boot(record, processors, wrapped, s)) {
            for (Object o : offered) srv.feed().offer(o);
            srv.awaitLive(lines);
            return srv.live();
        }
    }

    /** Replay each processor alone; its outputs, in processor order, and any stop reasons. */
    static List<String> replayEach(ReplayConfig replay, List<String> processors, boolean wrapped, List<String> stops,
                                   Supplier<EventToInvokeStrategy> s) throws Exception {
        List<String> out = new ArrayList<>();
        for (String processor : processors) {
            ReplayConfig one = ReplayConfig.replay(Set.of(processor), replay.journalledFeeds(), replay.journal(), replay.store());
            try (Server r = boot(one, Map.of(processor, ReplayRound3ReviewTest::probe), wrapped, s)) {
                r.awaitReplayDone();
                if (r.replayer().stopped(processor) != null) stops.add(r.replayer().stopped(processor));
                out.addAll(r.replayed(processor));
            }
        }
        return out;
    }

    // ---- F1: a journal fallback keeps the feed's codec ----------------------------------------------------------

    void codecFallbackReplaysWhatWasReceived(EventJournal journal, ReplayStore store, Supplier<ReplayStore> replayStore,
                                             Supplier<EventJournal> replayJournal) throws Exception {
        NondeterministicCodec codec = new NondeterministicCodec();
        Map<String, EventCodec> journalled = Map.of(FEED, codec);
        List<String> live = record(ReplayConfig.record(Set.of("probe"), journalled, journal, store), one(), false, null, 1,
                new CodecValue(17));
        assertEquals(List.of("codec=17"), live, "precondition");
        List<String> stops = new ArrayList<>();
        List<String> replayed = replayEach(ReplayConfig.replay(Set.of("probe"), journalled, replayJournal.get(), replayStore.get()),
                List.of("probe"), false, stops, null);
        assertEquals(List.of(), stops, "no stop");
        assertEquals(live, replayed, "the feed's codec, not Java serialisation, carries the input to the replay");
    }

    @Test
    void f1_aNondeterministicFaithfulCodec_replaysTheValue_memory() throws Exception {
        InMemoryEventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        codecFallbackReplaysWhatWasReceived(journal, store, () -> store, () -> journal);
    }

    @Test
    void f1_aNondeterministicFaithfulCodec_replaysTheValue_csv(@TempDir Path dir) throws Exception {
        Path j = dir.resolve("journal.csv"), st = dir.resolve("store.csv");
        List<AutoCloseable> opened = new ArrayList<>();
        try (CsvEventJournal journal = new CsvEventJournal(j); CsvReplayStore store = new CsvReplayStore(st, new JavaSerializationCodec())) {
            codecFallbackReplaysWhatWasReceived(journal, store, () -> {
                store.close();
                CsvReplayStore s = new CsvReplayStore(st, new JavaSerializationCodec());
                opened.add(s);
                return s;
            }, () -> {
                journal.close();
                CsvEventJournal again = new CsvEventJournal(j);
                opened.add(again);
                return again;
            });
        } finally {
            for (AutoCloseable c : opened) c.close();
        }
    }

    @Test
    void f1_aCodecOnlyInput_neverFallsBackToJavaSerialisation() throws Exception {
        NondeterministicCodec codec = new NondeterministicCodec();
        Map<String, EventCodec> journalled = Map.of(FEED, codec);
        InMemoryEventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = record(ReplayConfig.record(Set.of("probe"), journalled, journal, store), one(), false, null, 1,
                new CodecOnly(17));
        assertEquals(List.of("codecOnly=17"), live, "precondition");
        List<String> stops = new ArrayList<>();
        List<String> replayed = replayEach(ReplayConfig.replay(Set.of("probe"), journalled, journal, store), List.of("probe"),
                false, stops, null);
        assertEquals(List.of(), stops, "a codec-only input is not refused as not Serializable");
        assertEquals(live, replayed);
    }

    @Test
    void f1_aChangedRecipientInAFanOut_replaysWhatItReceived_throughTheCodec() throws Exception {
        NondeterministicCodec codec = new NondeterministicCodec();
        Map<String, EventCodec> journalled = Map.of(FEED, codec);
        InMemoryEventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = record(ReplayConfig.record(Set.of("first", "second"), journalled, journal, store), two(), false, null,
                2, new CodecValue(0));
        assertEquals(List.of("codec=0", "codec=1"), live, "precondition: the second received what the first left");
        List<String> stops = new ArrayList<>();
        List<String> replayed = replayEach(ReplayConfig.replay(Set.of("first", "second"), journalled, journal, store),
                List.of("first", "second"), false, stops, null);
        assertEquals(List.of(), stops);
        assertEquals(live, replayed.stream().sorted().toList(), "each recipient replays what it received, through the codec");
    }

    // ---- F2: a named event's metadata, indexed or not -----------------------------------------------------------

    void aWrappedJournalledInputReplaysItsWrapper(EventJournal journal, ReplayStore store, Supplier<ReplayStore> rs,
                                                  Supplier<EventJournal> rj) throws Exception {
        Map<String, EventCodec> journalled = Map.of(FEED, new JavaSerializationCodec());
        List<String> live = record(ReplayConfig.record(Set.of("probe"), journalled, journal, store), one(), true, null, 1,
                "DEMO-item");
        assertTrue(live.get(0).startsWith("named class=NamedFeedEventImpl feed=feed"), "precondition, wrapped: " + live);
        List<String> stops = new ArrayList<>();
        List<String> replayed = replayEach(ReplayConfig.replay(Set.of("probe"), journalled, rj.get(), rs.get()), List.of("probe"),
                true, stops, null);
        assertEquals(List.of(), stops);
        assertEquals(live, replayed, "the wrapper as received: its event time, name, topic, number, flag and filters");
    }

    @Test
    void f2_aWrappedJournalledInput_replaysTheWrapperItReceived_memory() throws Exception {
        InMemoryEventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        aWrappedJournalledInputReplaysItsWrapper(journal, store, () -> store, () -> journal);
    }

    @Test
    void f2_aWrappedJournalledInput_replaysTheWrapperItReceived_csv(@TempDir Path dir) throws Exception {
        Path j = dir.resolve("journal.csv"), st = dir.resolve("store.csv");
        List<AutoCloseable> opened = new ArrayList<>();
        try (CsvEventJournal journal = new CsvEventJournal(j); CsvReplayStore store = new CsvReplayStore(st, new JavaSerializationCodec())) {
            aWrappedJournalledInputReplaysItsWrapper(journal, store, () -> {
                store.close();
                CsvReplayStore s = new CsvReplayStore(st, new JavaSerializationCodec());
                opened.add(s);
                return s;
            }, () -> {
                journal.close();
                CsvEventJournal again = new CsvEventJournal(j);
                opened.add(again);
                return again;
            });
        } finally {
            for (AutoCloseable c : opened) c.close();
        }
    }

    /** A DataFlow that accepts anything and does nothing: the recorder's own boundary, driven directly. */
    static DataFlow inertFlow() {
        return (DataFlow) Proxy.newProxyInstance(DataFlow.class.getClassLoader(), new Class<?>[]{DataFlow.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "DEMO-flow";
                    default -> null;
                });
    }

    @Test
    void f2_aJournalledWrapperWithItsOwnMetadata_isRecordedWithIt_andReplaysIt() throws Exception {
        JavaSerializationCodec codec = new JavaSerializationCodec();
        Map<String, EventCodec> journalled = Map.of(FEED, codec);
        InMemoryEventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        journal.append(FEED, 7, codec.encode("DEMO-item"));
        NamedFeedEventImpl<Object> wrapper = new NamedFeedEventImpl<Object>("DEMO-original", "DEMO-topic", 7L, "DEMO-item").delete(true);
        wrapper.setEventTime(17);
        GroupRecorder recorder = new GroupRecorder(ReplayConfig.record(Set.of("probe"), journalled, journal, store), () -> 99L);
        DataFlow flow = inertFlow();
        recorder.attach("probe", flow);
        recorder.beforeDispatch(FEED, 7, List.of(flow));
        recorder.received(flow, wrapper);
        recorder.afterDispatch(FEED, "onEventCallBack", wrapper, 7, List.of(flow));
        ReplayEntry entry = store.entries("probe").get(0);
        ReplayEntry.Inline inline = assertInstanceOf(ReplayEntry.Inline.class, entry, "the wrapper's own fields are kept: " + entry);
        RecordedNamedEvent recorded = assertInstanceOf(RecordedNamedEvent.class, inline.event());
        assertEquals("DEMO-original", recorded.eventFeedName());
        assertEquals("DEMO-topic", recorded.topic());
        assertEquals(7, recorded.sequenceNumber());
        assertTrue(recorded.delete(), "delete");
        assertEquals(17, recorded.eventTime(), "event time");
        NamedFeedEvent<Object> rebuilt = recorded.rebuild();
        assertEquals("DEMO-original", rebuilt.eventFeedName());
        assertEquals(17, rebuilt.getEventTime());
        assertEquals(wrapper.filterId(), rebuilt.filterId(), "integer filter");
        assertEquals(wrapper.filterString(), rebuilt.filterString(), "string filter");
    }

    void anIntegerFilterOnTheExactBaseClassSurvives(ReplayStore into, Supplier<ReplayStore> from) throws Exception {
        NamedFeedEventImpl<Object> exact = new NamedFeedEventImpl<>("DEMO-inner", "DEMO-topic", 5L, "DEMO-item");
        exact.copyFrom(new FilteredNamedEvent(17));                 // a public method: the exact base class, filter 17
        assertEquals(NamedFeedEventImpl.class, exact.getClass(), "precondition: the supported class itself");
        assertEquals(17, exact.filterId(), "precondition: its integer filter is 17");
        List<String> live = record(ReplayConfig.record(Set.of("probe"), Map.of(), null, into), one(), false, null, 1, exact);
        assertTrue(live.get(0).contains("filterId=17"), "precondition: " + live);
        List<String> stops = new ArrayList<>();
        List<String> replayed = replayEach(ReplayConfig.replay(Set.of("probe"), Map.of(), null, from.get()), List.of("probe"),
                false, stops, null);
        if (stops.isEmpty()) {
            assertEquals(live, replayed, "replayed with its integer filter, which selects its handler");
        } else {
            assertTrue(stops.get(0).contains("filter"), "or refused, naming the filter: " + stops);
        }
    }

    @Test
    void f2_anIntegerFilterOnTheExactBaseClass_survives_memory() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        anIntegerFilterOnTheExactBaseClassSurvives(store, () -> store);
    }

    @Test
    void f2_anIntegerFilterOnTheExactBaseClass_survives_csv(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.csv");
        List<CsvReplayStore> opened = new ArrayList<>();
        try (CsvReplayStore into = new CsvReplayStore(file, new JavaSerializationCodec())) {
            anIntegerFilterOnTheExactBaseClassSurvives(into, () -> {
                into.close();
                CsvReplayStore from = new CsvReplayStore(file, new JavaSerializationCodec());
                opened.add(from);
                return from;
            });
        } finally {
            for (CsvReplayStore s : opened) s.close();
        }
    }

    // ---- F3: RECORD setup is contained --------------------------------------------------------------------------

    @Test
    void f3_aRecordingClockThatCannotBeInstalled_isANamedFailedRecording_whenAttachedDirectly() {
        InMemoryReplayStore store = new InMemoryReplayStore();
        GroupRecorder recorder = new GroupRecorder(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), () -> 99L);
        DataFlow refuses = new RecordSetupFailureChildMain.RefusesRecordingClock();
        assertDoesNotThrow(() -> recorder.attach("probe", refuses), "nothing escapes the agent's setup");
        String broken = recorder.broken("probe");
        assertNotNull(broken, "the recording is failed, and says so");
        assertTrue(broken.contains("DEMO cannot install recording clock"), broken);
        assertTrue(store.entries("probe").stream().anyMatch(e -> e instanceof ReplayEntry.Failed f
                && f.description().contains("DEMO cannot install recording clock")), "durably: " + store.entries("probe"));
    }

    static String runChild(Class<?> main) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("--add-opens") || a.startsWith("--add-exports")).forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
        Path out = Files.createTempFile("DEMO-child", ".out");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        try {
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);    // bounded BEFORE any output is read
            assertTrue(finished, "the child finished within 60 s");
            String text = Files.readString(out, StandardCharsets.UTF_8);
            String survived = text.lines().filter(l -> l.startsWith("SURVIVED")).findFirst().orElse(null);
            assertEquals(0, p.exitValue(), "the server survived (exit 0): " + (survived != null ? survived
                    : text.lines().filter(l -> l.contains("Exception") || l.contains("Error")).limit(3).toList()));
            assertNotNull(survived, text);
            return survived;
        } finally {
            if (p.isAlive()) p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            Files.deleteIfExists(out);
        }
    }

    @Test
    void f3_aRecordingClockThatCannotBeInstalled_neverEndsARealServer_andTheRecordingSaysSo() throws Exception {
        String survived = runChild(RecordSetupFailureChildMain.class);
        assertTrue(survived.contains("Failed[") && survived.contains("DEMO cannot install recording clock"),
                "the recording holds a named failure: " + survived);
        assertTrue(survived.contains("live=[bare=DEMO-LIVE]"), "and live delivery goes on, unrecorded (the stated policy): " + survived);
    }

    // ---- F4: a strategy that delivers but names no processor ----------------------------------------------------

    @Test
    void f4_aStrategyThatNamesNoProcessor_isNeverRecordedAsAnEmptyRecording() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = record(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), one(), false,
                UnnamedTargetsStrategy::new, 1, "DEMO-item");
        assertEquals(List.of("bare=DEMO-item"), live, "live delivery is undisturbed");
        List<ReplayEntry> entries = store.entries("probe");
        assertTrue(entries.stream().anyMatch(e -> e instanceof ReplayEntry.Failed f && f.description().contains("UnnamedTargetsStrategy")),
                "the input it delivered is a named capture failure, not an empty recording: " + entries);
        List<String> stops = new ArrayList<>();
        replayEach(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), List.of("probe"), false, stops,
                UnnamedTargetsStrategy::new);
        assertEquals(1, stops.size(), "and a replay stops there: " + stops);
    }

    /** Retained (F4 must not break it): a direct SPI that names its one processor is recorded and replayed as received. */
    @Test
    void f4_aDirectStrategyThatNamesItsOneProcessor_isStillRecordedAndReplayed() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live = record(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), one(), false,
                ReplayReReviewTest.DirectStrategy::new, 1, "DEMO-item");
        assertEquals(List.of("bare=DEMO-item"), live);
        List<String> stops = new ArrayList<>();
        List<String> replayed = replayEach(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), List.of("probe"), false,
                stops, ReplayReReviewTest.DirectStrategy::new);
        assertEquals(List.of(), stops);
        assertEquals(live, replayed);
    }
}
