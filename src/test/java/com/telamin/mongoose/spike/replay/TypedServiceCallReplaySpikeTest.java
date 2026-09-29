package com.telamin.mongoose.spike.replay;

import com.telamin.fluxtion.runtime.event.ReplayRecord;
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
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * SPIKE (replay at dispatch, service calls): in a managed Mongoose a service call reaches a processor AFTER the queue,
 * through a typed invoke strategy. Recorded there with its source, it replays back through that source, and the
 * configured strategy makes the same service calls at the same instants.
 */
class TypedServiceCallReplaySpikeTest {

    static final long T0 = 1_767_258_000_000L;          // 2026-01-01T09:00:00Z, DEMO
    static final String SERVICE = "quoteControl";
    static final String FEED = "asEvents";

    record Run(List<String> emitted, List<RecordingEventToInvokeStrategy.Recorded> recorded) { }

    /**
     * A server with the typed service and a processor that exports {@link QuoteControl}. {@code calls} are published
     * as service calls; {@code replayed} go back through the service's queue; {@code asEvents} through a plain feed.
     */
    static Run run(List<String> calls, List<ReplayRecord> replayed, List<ReplayRecord> asEvents, int expected,
                   boolean recording) throws Exception {
        List<RecordingEventToInvokeStrategy.Recorded> recorded = new CopyOnWriteArrayList<>();
        AtomicLong clock = new AtomicLong(T0);
        QuoteControlService service = new QuoteControlService(SERVICE, recording
                ? () -> new RecordingEventToInvokeStrategy(new QuoteControlService.TypedInvoke(), recorded,
                        () -> clock.addAndGet(10), SERVICE)
                : QuoteControlService.TypedInvoke::new);
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(FEED);
        feed.setCacheEventLog(true);
        InMemoryMessageSink sink = new InMemoryMessageSink();

        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder()
                        .agentName("processor-agent")
                        .put("quotes", EventProcessorConfig.builder()
                                .handler(new QuoteControlProcessor(new QuoteControlProcessor.Node(FEED))).build())
                        .build())
                .addService(ServiceConfig.<QuoteControlService>builder()
                        .service(service).serviceClass(QuoteControlService.class).name(SERVICE).build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(FEED).broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        try {
            Thread.sleep(200);                              // the processor subscribes in start()
            for (String c : calls) service.publish(c);
            for (ReplayRecord r : replayed) service.replay(r);
            for (ReplayRecord r : asEvents) feed.offer(r);
            return new Run(RecordAtDispatchSpikeTest.await(sink, expected), List.copyOf(recorded));
        } finally {
            server.stop();
        }
    }

    @Test
    void aServiceCallRecordedAfterTheQueue_replaysAsTheSameCall() throws Exception {
        List<String> calls = List.of("suspend", "resume", "suspend");
        Run live = run(calls, List.of(), List.of(), 3, true);
        assertEquals(List.of("call=suspend suspended=true time=" + (T0 + 10), "call=resume suspended=false time=" + (T0 + 20),
                "call=suspend suspended=true time=" + (T0 + 30)), live.emitted(), "the processor received service calls");
        // recorded after the queue: each call's value, its instant, and the source it came from
        assertEquals(calls, live.recorded().stream().map(r -> r.record().getEvent()).toList());
        assertEquals(List.of(SERVICE, SERVICE, SERVICE), live.recorded().stream().map(RecordingEventToInvokeStrategy.Recorded::source).toList());

        List<ReplayRecord> records = live.recorded().stream().map(RecordingEventToInvokeStrategy.Recorded::record).toList();
        // replayed back through the service's own queue: the configured strategy makes the same calls, at the same instants
        Run replay = run(List.of(), records, List.of(), 3, false);
        assertEquals(live.emitted(), replay.emitted(), "the replay makes the same service calls");

        // witness: the same records through a plain feed arrive as events, not calls, and do not reproduce the run
        Run wrongPath = run(List.of(), List.of(), records, 3, false);
        assertEquals(3, wrongPath.emitted().size(), wrongPath.emitted().toString());
        assertNotEquals(live.emitted(), wrongPath.emitted(), "a replay must go back through the source it came from");
    }
}
