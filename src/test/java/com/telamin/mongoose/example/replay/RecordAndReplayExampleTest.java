package com.telamin.mongoose.example.replay;

import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.JavaSerializationCodec;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The docs' record-and-replay example (how-to-record-and-replay-a-processor.md): record what a processor received,
 * then replay it into a fresh server. The replay produces the same output, at the same instants.
 */
class RecordAndReplayExampleTest {

    static final String FEED = "prices", PROCESSOR = "pricer", AGENT = "processor-agent";

    /** One server, recording or replaying the processor as {@code replay} says. */
    static MongooseServer boot(ReplayConfig replay, InMemoryEventSource<Object> prices, InMemoryMessageSink sink) {
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(AGENT)
                        .put(PROCESSOR, new EventProcessorConfig(new RecordedPriceHandler(FEED))).build())
                .addEventFeed(EventFeedConfig.builder().instance(prices).name(FEED).broadcast(true)
                        .agent("prices-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay)
                .build();
        return MongooseServer.bootServer(config, rec -> { });
    }

    static InMemoryEventSource<Object> priceFeed() {
        InMemoryEventSource<Object> prices = new InMemoryEventSource<>();
        prices.setName(FEED);
        prices.setCacheEventLog(true);
        return prices;
    }

    static List<String> lines(InMemoryMessageSink sink) {
        return sink.getMessages().stream().map(String::valueOf).toList();
    }

    @Test
    void aRecordedRun_replaysToTheSameOutput() throws Exception {
        InMemoryEventJournal journal = new InMemoryEventJournal();   // the journalled items, once each
        InMemoryReplayStore store = new InMemoryReplayStore();       // what the processor received, in its order
        Map<String, com.telamin.mongoose.replay.EventCodec> journalled = Map.of(FEED, new JavaSerializationCodec());

        // record: a live run, with the price feed journalled
        InMemoryMessageSink liveSink = new InMemoryMessageSink();
        InMemoryEventSource<Object> livePrices = priceFeed();
        MongooseServer live = boot(ReplayConfig.record(Set.of(PROCESSOR), journalled, journal, store), livePrices, liveSink);
        List<String> recorded;
        try {
            Thread.sleep(200);                                        // the processor subscribes in start()
            livePrices.offer("DEMO-101.5");
            livePrices.offer("DEMO-101.7");
            livePrices.offer("DEMO-101.6");
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (liveSink.getMessages().size() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
            recorded = lines(liveSink);
        } finally {
            live.stop();
        }
        assertEquals(3, recorded.size(), recorded.toString());
        // a journalled feed's inputs are recorded as indexes into the journal, not as copies
        assertTrue(store.entries(PROCESSOR).stream().allMatch(e -> e instanceof ReplayEntry.Indexed), store.entries(PROCESSOR).toString());

        // replay: a fresh server and processor, fed from the store and the journal, nothing offered to the feed
        InMemoryMessageSink replaySink = new InMemoryMessageSink();
        MongooseServer replay = boot(ReplayConfig.replay(Set.of(PROCESSOR), journalled, journal, store), priceFeed(), replaySink);
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                GroupReplayer replayer = replay.replayers().get(AGENT);
                if (replayer != null && replayer.complete() && replayer.outputs(PROCESSOR).size() >= recorded.size()) break;
                Thread.sleep(5);
            }
            Thread.sleep(50);
            // a replay delivers nothing: what the processor sent is captured by the replayer, for comparison
            List<String> replayed = replay.replayers().get(AGENT).outputs(PROCESSOR).stream().map(String::valueOf).toList();
            assertEquals(recorded, replayed, "the replay does what the run did, at the same instants");
            assertEquals(List.of(), lines(replaySink), "and sends nothing to the real sink");
        } finally {
            replay.stop();
        }
    }
}
