package com.telamin.mongoose.replay;

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
import com.telamin.mongoose.spike.replay.QuoteControlProcessor;
import com.telamin.mongoose.spike.replay.QuoteControlService;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spec-replay-recording R5 for service calls: a typed invoke strategy turns a queued value into a service call on the
 * processor. Recorded at dispatch (the strategy is the service's own), replayed by the driver through that source's
 * configured strategy, so it is a service call again.
 */
class TypedCallReplayAcceptanceTest {

    static final String SERVICE = "quoteControl", FEED = "asEvents", PROCESSOR = "quotes";

    record Server(MongooseServer server, QuoteControlService service, InMemoryMessageSink sink) implements AutoCloseable {
        /** A replay's result is what the replayed processor sent, captured (L2); a live run's is the real sink's. */
        List<String> lines() {
            GroupReplayer replayer = server.replayers().get("processor-agent");
            if (replayer != null) return replayer.outputs(PROCESSOR).stream().map(String::valueOf).toList();
            return sink.getMessages().stream().map(String::valueOf).toList();
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    static Server boot(ReplayConfig replay) throws Exception {
        QuoteControlService service = new QuoteControlService(SERVICE, QuoteControlService.TypedInvoke::new);
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName(FEED);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("processor-agent")
                        .put(PROCESSOR, EventProcessorConfig.builder()
                                .handler(new QuoteControlProcessor(new QuoteControlProcessor.Node(FEED))).build()).build())
                .addService(ServiceConfig.<QuoteControlService>builder()
                        .service(service).serviceClass(QuoteControlService.class).name(SERVICE).build())
                .addEventFeed(EventFeedConfig.builder().instance(feed).name(FEED).broadcast(true)
                        .agent("feed-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay)
                .build();
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        Thread.sleep(200);
        return new Server(server, service, sink);
    }

    static void await(Server s, int n) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (s.lines().size() < n && System.nanoTime() < deadline) Thread.sleep(5);
        Thread.sleep(50);
    }

    @Test
    void aServiceCallRecordedAtDispatch_isReplayedAsTheSameCall() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        List<String> live;
        try (Server s = boot(ReplayConfig.record(Set.of(PROCESSOR), Map.of(), null, store))) {
            s.service().publish("suspend"); await(s, 1);
            s.service().publish("resume");  await(s, 2);
            live = s.lines();
        }
        assertEquals(2, live.size(), live.toString());
        assertTrue(live.stream().allMatch(l -> l.startsWith("call=")), "received as service calls: " + live);
        List<ReplayEntry> entries = store.entries(PROCESSOR);
        ReplayEntry.Inline first = assertInstanceOf(ReplayEntry.Inline.class, entries.get(0));
        assertEquals(SERVICE, first.source(), "the source it came from; the callback is the config's");

        try (Server r = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(), null, store))) {
            await(r, 2);
            assertEquals(live, r.lines(), "the driver makes the same service calls, at the same instants");
        }
        // witness: the same calls, recorded in the other order, replay in that order - so the store decides it
        InMemoryReplayStore reversed = new InMemoryReplayStore();
        java.util.List<ReplayEntry> backwards = new java.util.ArrayList<>(entries);
        java.util.Collections.reverse(backwards);
        backwards.forEach(e -> reversed.append(PROCESSOR, e));
        try (Server w = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(), null, reversed))) {
            await(w, 2);
            assertEquals(2, w.lines().size(), "the witness replays both calls: " + w.lines());
            assertEquals(List.of(live.get(1), live.get(0)).stream().map(l -> l.replaceAll(" time=\\d+", "")).toList(),
                    w.lines().stream().map(l -> l.replaceAll(" time=\\d+", "")).toList(), "in the store's order");
        }
    }
}
