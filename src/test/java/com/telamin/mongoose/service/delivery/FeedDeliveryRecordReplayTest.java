/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.delivery;

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
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Re-delivery and record/replay (docs/design/feed-delivery.md, section 8): items processed but not committed before a
 * stop are delivered again by the next run; that run's recording starts at the re-read position, holds the re-delivered
 * items as ordinary inputs, and replays to the same output. Each run's recording is self-consistent.
 */
class FeedDeliveryRecordReplayTest {

    static final String FEED = "lines", PROCESSOR = "reader", AGENT = "processor-agent";

    @TempDir
    Path tempDir;

    /** Writes each line it receives, with the instant it read from its clock. */
    public static class LineHandler extends ObjectEventHandlerNode {
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
            if (sink != null && event instanceof String line) {
                sink.accept("line=" + line + " time=" + getContext().getClock().getProcessTime());
            }
            return true;
        }
    }

    MongooseServer boot(ReplayConfig replay, Path file, ReadStrategy strategy, InMemoryMessageSink sink) {
        FileEventSource source = new FileEventSource();
        source.setFilename(file.toString());
        source.setReadStrategy(strategy);
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(AGENT)
                        .put(PROCESSOR, new EventProcessorConfig(new LineHandler())).build())
                .addEventFeed(EventFeedConfig.builder().instance(source).name(FEED).broadcast(true)
                        .agent("lines-agent", new SleepingMillisIdleStrategy(1)).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build())
                .replay(replay)
                .build();
        return MongooseServer.bootServer(config, rec -> { });
    }

    static void await(BooleanSupplier c, String what) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end && !c.getAsBoolean()) Thread.sleep(5);
        assertTrue(c.getAsBoolean(), "timed out waiting for " + what);
    }

    static List<String> lineNames(InMemoryMessageSink sink) {
        return sink.getMessages().stream().map(String::valueOf).map(s -> s.split(" ")[0]).toList();
    }

    @Test
    void reDeliveredItemsAreOrdinaryInputsOfTheRestartedRunsRecording() throws Exception {
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, "", StandardCharsets.UTF_8);
        Map<String, EventCodec> journalled = Map.of(FEED, new JavaSerializationCodec());

        // run 1: a..d processed, state durable through b only (the checkpoint), then stopped
        InMemoryMessageSink sink1 = new InMemoryMessageSink();
        InMemoryReplayStore store1 = new InMemoryReplayStore();
        MongooseServer run1 = boot(ReplayConfig.record(Set.of(PROCESSOR), journalled, new InMemoryEventJournal(), store1),
                file, ReadStrategy.COMMITED, sink1);
        try {
            ProcessingCheckpoint checkpoint = run1.processingCheckpoint(FEED, AGENT);
            Files.writeString(file, "a\nb\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            await(() -> sink1.getMessages().size() == 2, "a and b processed");
            Thread.sleep(20);
            ProcessingCheckpoint.Mark afterB = checkpoint.mark();
            Files.writeString(file, "c\nd\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            await(() -> run1.feedStatus(FEED).settled() && sink1.getMessages().size() == 4, "run 1 settled");
            checkpoint.durableThrough(afterB);
            await(() -> run1.feedStatus(FEED).feed(FEED).delivery().acknowledged() == 2, "a and b committed");
        } finally {
            run1.stop();
        }
        assertEquals(List.of("line=a", "line=b", "line=c", "line=d"), lineNames(sink1));
        assertEquals(4, store1.entries(PROCESSOR).size());

        // run 2: resumes at the committed pointer, so c and d are delivered again (at-least-once: duplicates)
        InMemoryMessageSink sink2 = new InMemoryMessageSink();
        InMemoryEventJournal journal2 = new InMemoryEventJournal();
        InMemoryReplayStore store2 = new InMemoryReplayStore();
        MongooseServer run2 = boot(ReplayConfig.record(Set.of(PROCESSOR), journalled, journal2, store2), file,
                ReadStrategy.COMMITED, sink2);
        try {
            await(() -> run2.feedStatus(FEED).settled() && sink2.getMessages().size() == 2, "run 2 settled");
        } finally {
            run2.stop();
        }
        assertEquals(List.of("line=c", "line=d"), lineNames(sink2), "the restarted run starts at the re-read position");
        List<ReplayEntry> entries2 = store2.entries(PROCESSOR);
        assertEquals(2, entries2.size(), entries2.toString());
        assertTrue(entries2.stream().allMatch(e -> e instanceof ReplayEntry.Indexed), "ordinary journalled inputs");

        // replaying run 2's recording reproduces run 2's output exactly
        InMemoryMessageSink replaySink = new InMemoryMessageSink();
        Path empty = tempDir.resolve("replay.txt");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        MongooseServer replay = boot(ReplayConfig.replay(Set.of(PROCESSOR), journalled, journal2, store2), empty,
                ReadStrategy.EARLIEST, replaySink);
        try {
            await(() -> {
                GroupReplayer replayer = replay.replayers().get(AGENT);
                return replayer != null && replayer.complete() && replayer.outputs(PROCESSOR).size() >= 2;
            }, "replay complete");
            List<String> replayed = replay.replayers().get(AGENT).outputs(PROCESSOR).stream().map(String::valueOf).toList();
            assertEquals(sink2.getMessages().stream().map(String::valueOf).toList(), replayed);
        } finally {
            replay.stop();
        }
    }
}
