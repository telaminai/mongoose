/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.stress;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.OverloadPolicyConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.replay.EventCodec;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.JavaSerializationCodec;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * telaminai/mongoose#79: a recorded run that really drops (a slow processor behind a full queue, on a feed with a
 * zero spin bound), replayed. The recording names each loss with a {@code Dropped} entry; the replay reproduces the
 * recorded output line for line, completes, and reports the losses: {@code losses()} non-empty and
 * {@code lossless()} false beside {@code complete()}.
 */
@EnabledIfSystemProperty(named = "mongoose.stress", matches = "true")
class OverloadReplayLossStressTest {

    static final String FEED = "ticks", PROCESSOR = "slowPricer", AGENT = "slow-agent";
    static final int OFFERED = 5_000;

    /** Sleeps on its first inputs, so the feed overruns its queue while it does. */
    public static class SlowHandler extends ObjectEventHandlerNode {
        private MessageSink<String> sink;
        private int seen;

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        public void start() {
            getContext().subscribeToNamedFeed(FEED);
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (seen++ < 40) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (sink != null) sink.accept(event + "@" + getContext().getClock().getProcessTime());
            return true;
        }
    }

    static MongooseServer boot(ReplayConfig replay, InMemoryEventSource<Object> ticks, InMemoryMessageSink sink) {
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(AGENT)
                        .put(PROCESSOR, new EventProcessorConfig(new SlowHandler())).build())
                .addEventFeed(EventFeedConfig.builder().instance(ticks).name(FEED).broadcast(true)
                        .overload(OverloadPolicyConfig.dropAfterSpin(Duration.ZERO))
                        .agent("ticks-agent", new BusySpinIdleStrategy()).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay)
                .build();
        return MongooseServer.bootServer(config, rec -> { });
    }

    static InMemoryEventSource<Object> ticks() {
        InMemoryEventSource<Object> ticks = new InMemoryEventSource<>();
        ticks.setName(FEED);
        return ticks;
    }

    @Test
    void aRunThatDroppedReplaysLineForLineAndReportsItsLosses() throws Exception {
        InMemoryEventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        Map<String, EventCodec> journalled = Map.of(FEED, new JavaSerializationCodec());

        InMemoryMessageSink liveSink = new InMemoryMessageSink();
        InMemoryEventSource<Object> liveTicks = ticks();
        MongooseServer live = boot(ReplayConfig.record(Set.of(PROCESSOR), journalled, journal, store), liveTicks, liveSink);
        List<String> recorded;
        try {
            Thread.sleep(300);
            for (int i = 0; i < OFFERED; i++) liveTicks.offer("t" + i);
            int last = -1;
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.sleep(200);
                int now = liveSink.getMessages().size();
                if (now == last && now > 0) break;
                last = now;
            }
            recorded = liveSink.getMessages().stream().map(String::valueOf).toList();
        } finally {
            live.stop();
        }
        long droppedEntries = store.entries(PROCESSOR).stream().filter(e -> e instanceof ReplayEntry.Dropped).count();
        assertTrue(recorded.size() < OFFERED, "the slow processor lost inputs: " + recorded.size() + " of " + OFFERED);
        assertTrue(droppedEntries > 0, "the recording names the losses");

        InMemoryMessageSink replaySink = new InMemoryMessageSink();
        MongooseServer replay = boot(ReplayConfig.replay(Set.of(PROCESSOR), journalled, journal, store), ticks(), replaySink);
        try {
            GroupReplayer replayer = null;
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (System.nanoTime() < deadline) {
                replayer = replay.replayers().get(AGENT);
                if (replayer != null && replayer.complete() && replayer.outputs(PROCESSOR).size() >= recorded.size()) break;
                Thread.sleep(20);
            }
            assertNotNull(replayer);
            Thread.sleep(100);
            List<String> replayed = replayer.outputs(PROCESSOR).stream().map(String::valueOf).toList();
            assertEquals(recorded, replayed, "the replay reproduces the recorded run, gaps and all");
            assertTrue(replayer.complete(), "it ran to the end");
            assertNull(replayer.stopped(PROCESSOR), "a loss does not stop the replay");
            assertFalse(replayer.lossless(), "but it is not lossless");
            assertEquals(droppedEntries, replayer.losses(PROCESSOR).size(), "every recorded loss reported");

            // the loss history outlives the processor (external review of d9eec79, finding 2): after an actual stop
            // of the processor, and after the server (and its group) shut down
            var reply = replay.stopProcessor(AGENT, PROCESSOR, 5_000);
            assertNotNull(reply, "the processor stopped");
            assertFalse(replayer.lossless(), "still not lossless after the processor stopped");
            assertEquals(droppedEntries, replayer.losses(PROCESSOR).size());
            replay.stop();
            assertFalse(replayer.lossless(), "still not lossless after the group shut down");
            assertEquals(droppedEntries, replayer.losses(PROCESSOR).size());
        } finally {
            replay.stop();
        }
    }
}
