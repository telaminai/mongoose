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
import com.telamin.mongoose.replay.EventJournal;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.JavaSerializationCodec;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import com.telamin.mongoose.replay.ReplayStore;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent review R3 (docs/design/feed-delivery.md, section 8): a processor reads its input's delivery (feed,
 * sequence, epoch, source position) through {@link DeliveryContext}. That is input to it, so a recording holds it with
 * the input, indexed or inline, and a replay restores it for the replayed cycle: a processor that skips positions its
 * state has already applied makes the same decisions in the replay as it did live.
 */
class FeedDeliveryReplayContextTest {

    static final String FEED = "lines", PROCESSOR = "reader", AGENT = "processor-agent";

    @TempDir
    Path tempDir;

    /** Writes each line with the delivery it read for it. */
    public static class ContextHandler extends ObjectEventHandlerNode {
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
                sink.accept("line=" + line + " delivery=" + DeliveryContext.current());
            }
            return true;
        }
    }

    /**
     * Keeps (epoch, position) with its state, restored at start from {@code state}, and skips what that state has
     * already applied ({@link Delivery#alreadyApplied}); with {@code persist} it saves its state after each line.
     */
    public static class DeduplicatingHandler extends ObjectEventHandlerNode {
        private final String state;
        private final boolean persist;
        private MessageSink<String> sink;
        private long storedEpoch;
        private Object storedPosition;

        public DeduplicatingHandler(String state, boolean persist) {
            this.state = state;
            this.persist = persist;
        }

        @ServiceRegistered
        public void sink(MessageSink<String> sink, String name) {
            this.sink = sink;
        }

        @Override
        public void start() {
            try {
                Path file = Path.of(state);
                if (Files.exists(file)) {
                    String[] saved = Files.readString(file).split(",");
                    storedEpoch = Long.parseLong(saved[0]);
                    storedPosition = Long.parseLong(saved[1]);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            getContext().subscribeToNamedFeed(FEED);
        }

        @Override
        protected boolean handleEvent(Object event) {
            if (sink == null || !(event instanceof String line)) return true;
            Delivery delivery = DeliveryContext.current();
            if (delivery != null && delivery.alreadyApplied(storedEpoch, storedPosition)) {
                sink.accept("skip " + line);
                return true;
            }
            sink.accept("apply " + line);
            if (delivery != null) {
                storedEpoch = delivery.epoch();
                storedPosition = delivery.position();
                if (persist) {
                    try {
                        Files.writeString(Path.of(state), storedEpoch + "," + storedPosition);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return true;
        }
    }

    MongooseServer boot(ReplayConfig replay, Path file, ReadStrategy strategy, InMemoryMessageSink sink,
                        ObjectEventHandlerNode handler) {
        FileEventSource source = new FileEventSource();
        source.setFilename(file.toString());
        source.setReadStrategy(strategy);
        MongooseServerConfig.Builder builder = MongooseServerConfig.builder()
                .addProcessorGroup(EventProcessorGroupConfig.builder().agentName(AGENT)
                        .put(PROCESSOR, new EventProcessorConfig(handler)).build())
                .addEventFeed(EventFeedConfig.builder().instance(source).name(FEED).broadcast(true)
                        .agent("lines-agent", new SleepingMillisIdleStrategy(1)).build())
                .addEventSink(EventSinkConfig.<MessageSink<?>>builder().instance(sink).name("memSink").build());
        if (replay != null) builder.replay(replay);
        return MongooseServer.bootServer(builder.build(), rec -> { });
    }

    static void await(BooleanSupplier c, String what) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end && !c.getAsBoolean()) Thread.sleep(5);
        assertTrue(c.getAsBoolean(), "timed out waiting for " + what);
    }

    private Path lines(String text) throws Exception {
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** Record a run that reads {@code file} to {@code expected} outputs; replay it; return both outputs. */
    private List<List<String>> recordAndReplay(Path file, int expected, Map<String, EventCodec> journalled,
                                               Function<Boolean, ObjectEventHandlerNode> handler,
                                               java.util.function.Consumer<ReplayStore> recorded) throws Exception {
        InMemoryMessageSink sink = new InMemoryMessageSink();
        EventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        MongooseServer live = boot(ReplayConfig.record(Set.of(PROCESSOR), journalled, journal, store), file,
                ReadStrategy.COMMITED, sink, handler.apply(false));
        try {
            await(() -> live.feedStatus(FEED).settled() && sink.getMessages().size() == expected, "live run settled");
        } finally {
            live.stop();
        }
        recorded.accept(store);
        InMemoryMessageSink replaySink = new InMemoryMessageSink();
        Path empty = tempDir.resolve("replay.txt");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        MongooseServer replay = boot(ReplayConfig.replay(Set.of(PROCESSOR), journalled, journal, store), empty,
                ReadStrategy.EARLIEST, replaySink, handler.apply(false));
        try {
            await(() -> {
                GroupReplayer replayer = replay.replayers().get(AGENT);
                return replayer != null && replayer.complete() && replayer.outputs(PROCESSOR).size() >= expected;
            }, "replay complete");
            GroupReplayer replayer = replay.replayers().get(AGENT);
            assertNull(replayer.stopped(PROCESSOR), replayer.stopped(PROCESSOR));
            return List.of(sink.getMessages().stream().map(String::valueOf).toList(),
                    replayer.outputs(PROCESSOR).stream().map(String::valueOf).toList());
        } finally {
            replay.stop();
        }
    }

    /** A journalled feed: indexed entries carry the delivery, and the replayed cycle reads it. */
    @Test
    void anIndexedRecordingReplaysTheDeliveryItsCycleRead() throws Exception {
        Path file = lines("a\nb\n");
        List<List<String>> out = recordAndReplay(file, 2, Map.of(FEED, new JavaSerializationCodec()),
                persist -> new ContextHandler(), store -> {
                    List<ReplayEntry> entries = store.entries(PROCESSOR);
                    assertEquals(2, entries.size(), entries.toString());
                    ReplayEntry.Indexed first = (ReplayEntry.Indexed) entries.get(0);
                    assertNotNull(first.delivery(), "the delivery is recorded with the input");
                    assertEquals(FEED, first.delivery().feed());
                    assertEquals(2L, first.delivery().positionAsLong());
                });
        assertTrue(out.get(0).get(0).contains("position=2"), out.get(0).toString());
        assertEquals(out.get(0), out.get(1), "the replay reads the same delivery as the live run");
    }

    /** A feed with no journal: inline entries carry the delivery too. */
    @Test
    void anInlineRecordingReplaysTheDeliveryItsCycleRead() throws Exception {
        Path file = lines("a\nb\n");
        List<List<String>> out = recordAndReplay(file, 2, Map.of(), persist -> new ContextHandler(), store -> {
            List<ReplayEntry> entries = store.entries(PROCESSOR);
            assertTrue(entries.stream().allMatch(e -> e instanceof ReplayEntry.Inline in && in.delivery() != null),
                    entries.toString());
        });
        assertTrue(out.get(0).get(1).contains("position=4"), out.get(0).toString());
        assertEquals(out.get(0), out.get(1));
    }

    /**
     * Restored state and re-delivery: run 1 applies a..d and saves its state through d, but only b is committed. Run 2,
     * recorded, restores that state and is given c and d again: it skips both as already applied. Its replay restores the
     * same state and must skip them too; without the recorded delivery it would apply them.
     */
    @Test
    void aProcessorThatSkipsAppliedPositionsMakesTheSameDecisionsInItsReplay() throws Exception {
        Path file = lines("");
        String state = tempDir.resolve("state.txt").toString();
        InMemoryMessageSink sink1 = new InMemoryMessageSink();
        MongooseServer run1 = boot(null, file, ReadStrategy.COMMITED, sink1, new DeduplicatingHandler(state, true));
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
        assertEquals(List.of("apply a", "apply b", "apply c", "apply d"),
                sink1.getMessages().stream().map(String::valueOf).toList());

        List<List<String>> out = recordAndReplay(file, 2, Map.of(FEED, new JavaSerializationCodec()),
                persist -> new DeduplicatingHandler(state, false), store -> { });
        assertEquals(List.of("skip c", "skip d"), out.get(0), "re-delivered, already applied to the restored state");
        assertEquals(out.get(0), out.get(1), "the replay makes the same decisions");
    }
}
