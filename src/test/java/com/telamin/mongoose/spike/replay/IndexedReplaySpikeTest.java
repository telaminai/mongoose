package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPIKE (index or event, live clock): a processor fed by a journalled feed and a plain one. The journalled feed's
 * inputs are recorded as indexes into its journal (the feed's own cached event log), the plain feed's inline, each at
 * the instant the processor read, with the clock left live. The replay joins the indexes with the journal and delivers
 * everything back through its own source, in the processor's order across the two, and the processor does what it did.
 */
class IndexedReplaySpikeTest {

    static final long T0 = 1_767_258_000_000L;          // 2026-01-01T09:00:00Z, DEMO
    static final String ORDERS = "orders", CONTROLS = "controls";

    record Run(List<String> emitted, List<IndexingRecorder.Entry> recorded, Map<Long, NamedFeedEvent<?>> journal) { }

    interface Script {
        void play(ReplayableEventSource orders, ReplayableEventSource controls, Consumer<Integer> awaitLines) throws Exception;
    }

    static Run run(boolean recording, Script script) throws Exception {
        ReplayableEventSource orders = new ReplayableEventSource();
        orders.setName(ORDERS);
        orders.setCacheEventLog(true);                  // the journal: every published order, with its sequence number
        ReplayableEventSource controls = new ReplayableEventSource();
        controls.setName(CONTROLS);
        controls.setCacheEventLog(true);                // so a control published before the subscription is not lost
        InMemoryMessageSink sink = new InMemoryMessageSink();
        List<IndexingRecorder.Entry> recorded = new CopyOnWriteArrayList<>();
        Map<DataFlow, RecordingClock> clocks = new HashMap<>();
        AtomicLong live = new AtomicLong(T0);           // a live clock that moves 7 ms on every read

        var builder = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put("quotes", new EventProcessorConfig(new OrdersAndControlsHandler(ORDERS, CONTROLS))).build())
                .addEventFeed(EventFeedConfig.builder().instance(orders).name(ORDERS).broadcast(true).wrapWithNamedEvent(true)
                        .agent("orders-agent", new BusySpinIdleStrategy()).build())
                .addEventFeed(EventFeedConfig.builder().instance(controls).name(CONTROLS).broadcast(true)
                        .agent("controls-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build());
        if (recording) {
            builder.onEventInvokeStrategy(() -> new IndexingRecorder(new EventToOnEventInvokeStrategy(), recorded, clocks,
                    () -> live.addAndGet(7), event -> CONTROLS));
        }
        MongooseServer server = MongooseServer.bootServer(builder.build(), rec -> { });
        try {
            Thread.sleep(200);                          // the processor subscribes in start()
            script.play(orders, controls, n -> {
                try {
                    await(sink, n);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            Map<Long, NamedFeedEvent<?>> journal = new HashMap<>();
            for (NamedFeedEvent<?> e : orders.eventLog()) journal.put(e.sequenceNumber(), e);
            return new Run(RecordAtDispatchSpikeTest.await(sink, 0), List.copyOf(recorded), journal);
        } finally {
            server.stop();
        }
    }

    static void await(InMemoryMessageSink sink, int n) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (sink.getMessages().size() < n && System.nanoTime() < deadline) Thread.sleep(10);
    }

    /** Replay one entry: an index is looked up in the journal; either goes back through its own source. */
    static void replayEntry(IndexingRecorder.Entry e, Map<Long, NamedFeedEvent<?>> journal, ReplayableEventSource orders,
                            ReplayableEventSource controls) {
        ReplayRecord r = new ReplayRecord();
        if (e instanceof IndexingRecorder.Indexed i) {
            r.setEvent(journal.get(i.seq()));
            r.setWallClockTime(i.instant());
            orders.replay(r);
        } else if (e instanceof IndexingRecorder.Inline in) {
            r.setEvent(in.event());
            r.setWallClockTime(in.instant());
            controls.replay(r);
        }
    }

    @Test
    void indexesAndInlineEvents_atTheProcessorsOwnInstants_replayAcrossTwoSources() throws Exception {
        Run live = run(true, (orders, controls, awaitLines) -> {
            orders.offer("ord-1");     awaitLines.accept(1);
            controls.offer("suspend"); awaitLines.accept(2);
            orders.offer("ord-2");     awaitLines.accept(3);
            controls.offer("resume");  awaitLines.accept(4);
            orders.offer("ord-3");     awaitLines.accept(5);
        });
        assertEquals(5, live.emitted().size(), live.emitted().toString());
        assertTrue(live.emitted().get(2).startsWith("order=ord-2 suspended=true"), live.emitted().toString());

        // an index for each order (no payload: it is in the journal), the controls inline
        List<IndexingRecorder.Entry> r = live.recorded();
        assertEquals(5, r.size(), r.toString());
        assertInstanceOf(IndexingRecorder.Indexed.class, r.get(0));
        assertInstanceOf(IndexingRecorder.Inline.class, r.get(1));
        assertInstanceOf(IndexingRecorder.Indexed.class, r.get(2));
        assertInstanceOf(IndexingRecorder.Inline.class, r.get(3));
        assertInstanceOf(IndexingRecorder.Indexed.class, r.get(4));
        assertEquals(3, live.journal().size(), "the journal holds each order once");
        for (IndexingRecorder.Entry e : r) {
            if (e instanceof IndexingRecorder.Indexed i) assertTrue(live.journal().containsKey(i.seq()), i + " is in the journal");
        }
        // the recorded instant IS the processTime the processor read: the clock was never pinned
        List<Long> instants = r.stream().map(e -> e instanceof IndexingRecorder.Indexed i ? i.instant()
                : ((IndexingRecorder.Inline) e).instant()).toList();
        List<Long> readByProcessor = live.emitted().stream().map(l -> Long.parseLong(l.substring(l.indexOf("time=") + 5))).toList();
        assertEquals(readByProcessor, instants, "each entry's instant is the processor's own read");

        // the replay: the processor's order, across both sources
        Run replay = run(false, (orders, controls, awaitLines) -> {
            for (int k = 0; k < r.size(); k++) {
                replayEntry(r.get(k), live.journal(), orders, controls);
                awaitLines.accept(k + 1);
            }
        });
        assertEquals(live.emitted(), replay.emitted(), "the replay does what the run did, at the same instants");

        // witness: each source in its own order, not the processor's, does not
        List<IndexingRecorder.Entry> bySource = new ArrayList<>(r.stream().filter(e -> e instanceof IndexingRecorder.Indexed).toList());
        bySource.addAll(r.stream().filter(e -> e instanceof IndexingRecorder.Inline).toList());
        Run wrongOrder = run(false, (orders, controls, awaitLines) -> {
            for (int k = 0; k < bySource.size(); k++) {
                replayEntry(bySource.get(k), live.journal(), orders, controls);
                awaitLines.accept(k + 1);
            }
        });
        assertEquals(5, wrongOrder.emitted().size(), wrongOrder.emitted().toString());
        assertNotEquals(live.emitted(), wrongOrder.emitted(), "the order across sources is the processor's, and a replay needs it");
    }
}
