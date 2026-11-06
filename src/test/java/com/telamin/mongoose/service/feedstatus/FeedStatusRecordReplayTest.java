/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.feedstatus;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.connector.file.FileEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.replay.EventCodec;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.JavaSerializationCodec;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feed status and record/replay (docs/design/feed-caught-up.md): off by default, a recording holds exactly the feed's
 * items, as before; turned on, each status a processor receives is an ordinary input, recorded inline and replayed in
 * its place.
 */
class FeedStatusRecordReplayTest {

    static final String FEED = "lines", PROCESSOR = "reader", AGENT = "processor-agent";

    @TempDir
    Path tempDir;

    /** Writes each line and each feed status it receives, with the instant it read from its clock. */
    public static class StatusAwareHandler extends ObjectEventHandlerNode {
        private MessageSink<String> sink;

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
            long time = getContext().getClock().getProcessTime();
            if (sink == null) return true;
            if (event instanceof String line) {
                sink.accept("line=" + line + " time=" + time);
            } else if (event instanceof FeedStatus status) {
                sink.accept("status=" + status.state() + "@" + status.sequence() + " time=" + time);
            }
            return true;
        }
    }

    MongooseServer boot(ReplayConfig replay, Path file, boolean feedStatusEvents, InMemoryMessageSink sink) {
        FileEventSource source = new FileEventSource();
        source.setFilename(file.toString());
        source.setReadStrategy(ReadStrategy.EARLIEST);
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(AGENT)
                        .put(PROCESSOR, new EventProcessorConfig(new StatusAwareHandler())).build())
                .addEventFeed(EventFeedConfig.builder().instance(source).name(FEED).broadcast(true)
                        .feedStatusEvents(feedStatusEvents).agent("lines-agent", new SleepingMillisIdleStrategy(1)).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay)
                .build();
        return MongooseServer.bootServer(config, rec -> { });
    }

    static List<String> lines(InMemoryMessageSink sink) {
        return sink.getMessages().stream().map(String::valueOf).toList();
    }

    private List<ReplayEntry> record(boolean feedStatusEvents, InMemoryEventJournal journal, InMemoryReplayStore store,
                                     InMemoryMessageSink sink) throws Exception {
        Path file = tempDir.resolve("record-" + feedStatusEvents + ".txt");
        Files.writeString(file, "a\nb\n", StandardCharsets.UTF_8);
        Map<String, EventCodec> journalled = Map.of(FEED, new JavaSerializationCodec());
        MongooseServer live = boot(ReplayConfig.record(Set.of(PROCESSOR), journalled, journal, store), file,
                feedStatusEvents, sink);
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!live.feedStatus(FEED).settled() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(live.feedStatus(FEED).settled(), live.feedStatus(FEED).toString());
        } finally {
            live.stop();
        }
        return store.entries(PROCESSOR);
    }

    @Test
    void offByDefaultTheRecordingHoldsOnlyTheFeedsItems() throws Exception {
        InMemoryMessageSink sink = new InMemoryMessageSink();
        List<ReplayEntry> entries = record(false, new InMemoryEventJournal(), new InMemoryReplayStore(), sink);
        assertEquals(2, entries.size(), entries.toString());
        assertTrue(entries.stream().allMatch(e -> e instanceof ReplayEntry.Indexed), entries.toString());
        assertTrue(lines(sink).stream().noneMatch(l -> l.startsWith("status=")), lines(sink).toString());
    }

    @Test
    void turnedOnAStatusIsRecordedInlineAndReplayedInItsPlace() throws Exception {
        InMemoryEventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        InMemoryMessageSink liveSink = new InMemoryMessageSink();
        List<ReplayEntry> entries = record(true, journal, store, liveSink);
        List<String> recorded = lines(liveSink);
        assertEquals("status=CAUGHT_UP@2", recorded.get(recorded.size() - 1).split(" ")[0], recorded.toString());
        assertEquals(2, entries.stream().filter(e -> e instanceof ReplayEntry.Indexed).count(), entries.toString());
        assertTrue(entries.stream().filter(e -> !(e instanceof ReplayEntry.Indexed))
                .allMatch(e -> e instanceof ReplayEntry.Inline), entries.toString());
        assertEquals(recorded.size(), entries.size(), "one entry per input the processor received");

        InMemoryMessageSink replaySink = new InMemoryMessageSink();
        Path empty = tempDir.resolve("replay.txt");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        MongooseServer replay = boot(ReplayConfig.replay(Set.of(PROCESSOR), Map.of(FEED, new JavaSerializationCodec()),
                journal, store), empty, true, replaySink);
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                GroupReplayer replayer = replay.replayers().get(AGENT);
                if (replayer != null && replayer.complete() && replayer.outputs(PROCESSOR).size() >= recorded.size()) break;
                Thread.sleep(5);
            }
            Thread.sleep(50);
            List<String> replayed = replay.replayers().get(AGENT).outputs(PROCESSOR).stream().map(String::valueOf).toList();
            assertEquals(recorded, replayed, "the statuses replay in their places, at the same instants");
        } finally {
            replay.stop();
        }
    }
}
