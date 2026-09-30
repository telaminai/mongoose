package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.DefaultEventProcessor;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.fluxtion.runtime.time.Clock;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.admin.impl.AdminCommand;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.telamin.mongoose.replay.ReplayRound3ReviewTest.CodecOnly;
import static com.telamin.mongoose.replay.ReplayRound3ReviewTest.FEED;
import static com.telamin.mongoose.replay.ReplayRound3ReviewTest.NondeterministicCodec;
import static com.telamin.mongoose.replay.ReplayRound3ReviewTest.Probe;
import static com.telamin.mongoose.replay.ReplayRound3ReviewTest.inertFlow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressions for the targeted re-review of #47 at 8211858 (G1, G2), adapted from the reviewer's reproductions: the real
 * recorder and replayer, and a real queue agent with the built-in strategy.
 */
class ReplayRound4ReviewTest {

    record Run(List<Object> items, String stopped) { }

    /** Replays {@code store} into a route that collects what it is given. */
    static Run replay(ReplayStore store, EventJournal journal, EventCodec codec) {
        List<Object> items = new ArrayList<>();
        DataFlow flow = inertFlow();
        ReplayRouting routing = new ReplayRouting() {
            @Override public ReplayRoute routeFor(String source, String route, DataFlow target) { return (p, e) -> items.add(e); }
            @Override public EventSource.EventWrapStrategy wrapOf(String source) { return EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP; }
            @Override public AdminCommand adminCommand(String name) { return null; }
        };
        GroupReplayer replayer = new GroupReplayer(ReplayConfig.replay(Set.of("probe"), Map.of(FEED, codec), journal, store),
                routing, new ReplayScheduler());
        replayer.attach("probe", flow);
        for (int i = 0; i < 20 && !replayer.complete(); i++) replayer.doWork();
        assertTrue(replayer.complete(), "the replay completes or stops");
        return new Run(items, replayer.stopped("probe"));
    }

    /** Records one journalled input of {@code item}, through the recorder's own boundary. */
    static void capture(Object item, EventCodec codec, EventJournal journal, ReplayStore store) {
        GroupRecorder recorder = new GroupRecorder(ReplayConfig.record(Set.of("probe"), Map.of(FEED, codec), journal, store), () -> 99L);
        DataFlow flow = inertFlow();
        recorder.attach("probe", flow);
        recorder.beforeDispatch(FEED, 1, List.of(flow));
        recorder.received(flow, item);
        recorder.afterDispatch(FEED, "DEMO-route", item, 1, List.of(flow));
    }

    // ---- G1: the recorded bytes are the recording's own ------------------------------------------------------

    /** A faithful codec that reuses one buffer for every encode, as a performance-minded codec may. */
    static final class ReusedBufferCodec implements EventCodec {
        final byte[] buffer = new byte[2];
        int calls;

        @Override
        public byte[] encode(Object item) {
            buffer[0] = (byte) ++calls;                          // a changing byte: the recipient's bytes differ from the journal's
            buffer[1] = (byte) ((CodecOnly) item).value;
            return buffer;
        }

        @Override
        public Object decode(byte[] bytes) {
            return new CodecOnly(bytes[1]);
        }
    }

    @Test
    void g1_aReusedEncoderBuffer_neverRewritesARecordedInput() {
        ReusedBufferCodec codec = new ReusedBufferCodec();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        journal.append(FEED, 1, codec.encode(new CodecOnly(17)).clone());
        InMemoryReplayStore store = new InMemoryReplayStore();
        capture(new CodecOnly(17), codec, journal, store);
        codec.encode(new CodecOnly(23));                          // the codec's next use of its buffer
        Run r = replay(store, journal, codec);
        assertNull(r.stopped(), "the replay does not stop");
        assertEquals(17, ((CodecOnly) r.items().get(0)).value, "the recorded bytes are the recording's own, not the encoder's buffer");
    }

    @Test
    void g1_aDecoderThatConsumesItsInput_neverRewritesTheNextReplay() {
        EventCodec codec = new NondeterministicCodec() {
            @Override
            public Object decode(byte[] bytes) {
                Object value = super.decode(bytes);
                bytes[2] = 0;                                    // a decoder that consumes (clears) its input
                return value;
            }
        };
        InMemoryEventJournal journal = new InMemoryEventJournal();
        journal.append(FEED, 1, codec.encode(new CodecOnly(17)));
        InMemoryReplayStore store = new InMemoryReplayStore();
        capture(new CodecOnly(17), codec, journal, store);
        Run first = replay(store, journal, codec), second = replay(store, journal, codec);
        assertNull(first.stopped(), "the first replay does not stop");
        assertNull(second.stopped(), "the second replay does not stop");
        assertEquals(17, ((CodecOnly) first.items().get(0)).value, "the first replay gives the recorded value");
        assertEquals(17, ((CodecOnly) second.items().get(0)).value, "and so does the second, however the decoder treats its input");
    }

    /** A deterministic codec whose decoder clears its input: an unchanged input is recorded by index. */
    static final class ConsumingCodec implements EventCodec {
        @Override
        public byte[] encode(Object item) {
            return new byte[]{(byte) ((CodecOnly) item).value};
        }

        @Override
        public Object decode(byte[] bytes) {
            Object value = new CodecOnly(bytes[0]);
            bytes[0] = 0;
            return value;
        }
    }

    @Test
    void g1_anIndexedInputsDecoderThatConsumesItsInput_neverRewritesTheJournalForTheNextReplay() {
        ConsumingCodec codec = new ConsumingCodec();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        journal.append(FEED, 1, codec.encode(new CodecOnly(17)));
        InMemoryReplayStore store = new InMemoryReplayStore();
        capture(new CodecOnly(17), codec, journal, store);
        assertTrue(store.entries("probe").get(0) instanceof ReplayEntry.Indexed, "precondition: recorded by index");
        Run first = replay(store, journal, codec), second = replay(store, journal, codec);
        assertEquals(17, ((CodecOnly) first.items().get(0)).value, "the first replay gives the journalled value");
        assertEquals(17, ((CodecOnly) second.items().get(0)).value, "and so does the second: the journal's bytes are decoded from a copy");
    }

    /**
     * Found by the local independent review of d2c6428: the contract above says a codec may reuse its buffers, but the
     * real publisher journalled the codec's array as returned, so the feed's next item rewrote the journalled one.
     */
    @Test
    void g1_thePublishersJournal_ownsItsBytes_whenTheCodecReusesItsBuffer() {
        ReusedBufferCodec codec = new ReusedBufferCodec();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        com.telamin.mongoose.dispatch.EventToQueuePublisher<Object> publisher = new com.telamin.mongoose.dispatch.EventToQueuePublisher<>(FEED);
        publisher.journal(journal, codec);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(queue, "DEMO-queue");
        publisher.publish(new CodecOnly(17));
        JournalledItem first = (JournalledItem) queue.poll();
        InMemoryReplayStore store = new InMemoryReplayStore();
        capture(first.item(), codec, journal, store);
        publisher.publish(new CodecOnly(23));                     // the feed's next item, through the same codec buffer
        Run r = replay(store, journal, codec);
        assertNull(r.stopped(), "the replay does not stop");
        assertEquals(17, ((CodecOnly) r.items().get(0)).value, "the journalled input is replayed as journalled, not as the next item");
    }

    /** The JournalRef path (a named wrapper around a journalled payload) decodes from a copy too: two replays agree. */
    @Test
    void g1_aNamedJournalledInputsDecoderThatConsumesItsInput_neverRewritesTheJournal() {
        ConsumingCodec codec = new ConsumingCodec();
        InMemoryEventJournal journal = new InMemoryEventJournal();
        journal.append(FEED, 1, codec.encode(new CodecOnly(17)));
        InMemoryReplayStore store = new InMemoryReplayStore();
        capture(new com.telamin.fluxtion.runtime.event.NamedFeedEventImpl<Object>(FEED, null, 1L, new CodecOnly(17)), codec, journal, store);
        assertTrue(store.entries("probe").get(0) instanceof ReplayEntry.Inline in && in.event() instanceof RecordedNamedEvent n
                && n.data() instanceof JournalRef, "precondition: a wrapper around a JournalRef: " + store.entries("probe"));
        Run first = replay(store, journal, codec), second = replay(store, journal, codec);
        Object a = ((com.telamin.fluxtion.runtime.event.NamedFeedEvent<?>) first.items().get(0)).data();
        Object b = ((com.telamin.fluxtion.runtime.event.NamedFeedEvent<?>) second.items().get(0)).data();
        assertEquals(17, ((CodecOnly) a).value, "the first replay gives the journalled payload");
        assertEquals(17, ((CodecOnly) b).value, "and so does the second: the journal's bytes are decoded from a copy");
    }

    // ---- G2: a failed recording leaves live time handling as it was --------------------------------------------

    /** A processor whose clock reads 99, which refuses the recording's clock (when {@code refuses}), and notes each input's time. */
    static final class TimeNoting extends DefaultEventProcessor {
        final List<Long> times = new ArrayList<>();
        final boolean refuses;

        TimeNoting(boolean refuses) {
            super(new Probe());
            this.refuses = refuses;
            init();
            setClockStrategy(() -> 99L);
        }

        @Override
        public void setClockStrategy(ClockStrategy strategy) {
            if (refuses && strategy instanceof RecordingClock) throw new IllegalStateException("DEMO no recording clock");
            super.setClockStrategy(strategy);
        }

        @Override
        public void onEvent(Object event) {
            super.onEvent(event);
            if (event instanceof String) {
                try {
                    times.add(((Clock) getAuditorById("clock")).getProcessTime());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** A live ReplayRecord at 42 through a real queue agent: OFF, a failed recording, or a working one. */
    static List<Long> liveReplayRecordTime(boolean recording, boolean refuses) {
        TimeNoting flow = new TimeNoting(refuses);
        InMemoryReplayStore store = new InMemoryReplayStore();
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(8);
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "DEMO", FEED);
        agent.registerProcessor(flow);
        if (recording) {
            GroupRecorder recorder = new GroupRecorder(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), () -> 99L);
            recorder.attach("probe", flow);
            if (refuses) assertNotNull(recorder.broken("probe"), "precondition: the recording failed visibly");
            agent.recordWith(recorder);
        }
        ReplayRecord input = new ReplayRecord();
        input.setEvent("DEMO");
        input.setWallClockTime(42L);
        queue.offer(input);
        agent.doWork();
        return flow.times;
    }

    @Test
    void g2_replayOff_aLiveReplayRecordCarriesItsTime() {
        assertEquals(List.of(42L), liveReplayRecordTime(false, true), "the positive: OFF, the record's time");
    }

    @Test
    void g2_aFailedRecording_leavesALiveReplayRecordsTimeAsItIsOff() {
        assertEquals(List.of(42L), liveReplayRecordTime(true, true),
                "a recording that failed at setup does not take the processor's time handling");
    }

    @Test
    void g2_aWorkingRecording_givesALiveReplayRecordItsTime() {
        assertEquals(List.of(42L), liveReplayRecordTime(true, false), "a recorded processor keeps the record's time (pinned)");
    }
}
