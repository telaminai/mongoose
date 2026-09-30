package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.event.ReplayRecord;
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
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE (replay at dispatch): record at the point Mongoose hands an event to a processor, then replay the records
 * through Mongoose's existing ReplayRecord path, and the processor does exactly what it did.
 *
 * <p>The processor raises a Breach itself on the second order, and an external source also sends a Breach: the same
 * type. Recorded at dispatch, the external one is an input and the graph's is not, with no filter, no identity check
 * and nothing compiled into the processor.
 */
class RecordAtDispatchSpikeTest {

    static final long T0 = 1_767_258_000_000L;          // 2026-01-01T09:00:00Z, DEMO

    /** What the processor emitted, in order; and, when recording, what the strategy recorded. */
    record Run(List<String> emitted, List<ReplayRecord> recorded) { }

    static Run run(List<?> inputs, boolean recording) throws Exception {
        InMemoryEventSource<Object> source = new InMemoryEventSource<>();
        source.setName("orders");
        source.setCacheEventLog(true);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        List<RecordingEventToInvokeStrategy.Recorded> recorded = new CopyOnWriteArrayList<>();
        AtomicLong clock = new AtomicLong(T0);

        var builder = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder()
                        .agentName("processor-agent")
                        .put("breach", new EventProcessorConfig(new BreachHandler(source.getName())))
                        .build())
                .addEventFeed(EventFeedConfig.builder().instance(source).name(source.getName()).broadcast(true)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build());
        if (recording) {
            // a data-driven wall clock for the spike, so the instants are exact: 10 ms per input
            builder.onEventInvokeStrategy(() -> new RecordingEventToInvokeStrategy(new EventToOnEventInvokeStrategy(), recorded,
                    () -> clock.addAndGet(10), source.getName()));
        }
        MongooseServer server = MongooseServer.bootServer(builder.build(), rec -> { });
        try {
            for (Object in : inputs) source.offer(in);
            return new Run(await(sink, 5), recorded.stream().map(RecordingEventToInvokeStrategy.Recorded::record).toList());
        } finally {
            server.stop();
        }
    }

    @Test
    void recordedAtDispatch_theReplayDoesExactlyWhatTheRunDid() throws Exception {
        List<Object> inputs = List.of("ord-1", "ord-2", new BreachHandler.Breach("DEMO-external", 9), "ord-3");
        Run live = run(inputs, true);

        // the processor handled five events: the three orders, the graph's own breach, and the external breach
        assertEquals(5, live.emitted().size(), live.emitted().toString());
        assertTrue(live.emitted().get(2).startsWith("breach=Breach[orderId=ord-2, liveOrders=2]"), live.emitted().toString());
        // recorded at dispatch: exactly the four inputs, the external breach among them, never the graph's
        assertEquals(inputs, live.recorded().stream().map(ReplayRecord::getEvent).toList(), live.recorded().toString());
        assertEquals(List.of(T0 + 10, T0 + 20, T0 + 30, T0 + 40),
                live.recorded().stream().map(ReplayRecord::getWallClockTime).toList());

        // the replay: the recorded ReplayRecords through an ordinary source into a fresh server, no recorder
        Run replayed = run(new ArrayList<>(live.recorded()), false);
        assertEquals(live.emitted(), replayed.emitted(), "the replay does exactly what the run did, at the same instants");

        // witness: the same events WITHOUT their recorded instants read the live clock, and do not reproduce the run
        Run bare = run(live.recorded().stream().map(ReplayRecord::getEvent).toList(), false);
        assertEquals(5, bare.emitted().size(), bare.emitted().toString());
        assertNotEquals(live.emitted(), bare.emitted(), "without the recorded instant a replay reads another time");
    }

    static List<String> await(InMemoryMessageSink sink, int n) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (sink.getMessages().size() < n && System.nanoTime() < deadline) Thread.sleep(20);
        Thread.sleep(100);                                  // and nothing more arrives
        return sink.getMessages().stream().map(String::valueOf).toList();
    }
}
