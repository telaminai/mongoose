/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.delivery;

import com.telamin.fluxtion.runtime.DataFlow;
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
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.replay.EventCodec;
import com.telamin.mongoose.replay.EventJournal;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.JavaSerializationCodec;
import com.telamin.mongoose.replay.ReplayConfig;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The delivery-complete hook on a booted server (docs/design/feed-delivery.md, section 5b): a processor that sends
 * itself another event while handling a line (a sink calling {@code onEvent}) has its completion callback run once,
 * after that event, before the feed acknowledges the line; and a recording replays to the same callbacks, in the same
 * place, with the same delivery (replay determinism, with R3).
 */
class DeliveryCompletionServerTest {

    static final String FEED = "lines", PROCESSOR = "reader", AGENT = "processor-agent";

    @TempDir
    Path tempDir;

    /**
     * For a line "x" it outputs "apply x" and sends itself "x+": one queued event per line, applied in the same
     * delivery. It registers one completion callback per cycle (the same instance), which outputs what the delivery
     * applied and its position.
     */
    public static class Saver extends ObjectEventHandlerNode {
        private MessageSink<String> sink;
        private final List<String> applied = new ArrayList<>();
        /** The feed's acknowledged sequence, as seen inside each callback, and the delivery's sequence. */
        final List<long[]> ackSeenInCallback = new CopyOnWriteArrayList<>();
        volatile MongooseServer server;
        private final Runnable complete = this::complete;
        /** Live only: the first completion throws once, then the reader's retry succeeds. */
        boolean failFirstCompletion;

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
            if (sink == null || !(event instanceof String line)) return true;
            sink.accept("apply " + line);
            applied.add(line);
            DeliveryContext.onComplete(complete);
            if (!line.endsWith("+")) {
                DataFlow self = ProcessorContext.currentProcessor();
                self.onEvent(line + "+");    // reentrant: within this delivery
            }
            return true;
        }

        private void complete() {
            if (failFirstCompletion) {
                failFirstCompletion = false;
                throw new IllegalStateException("DEMO transient completion failure");
            }
            Delivery d = DeliveryContext.current();
            MongooseServer s = server;
            if (s != null) {
                try {
                    Thread.sleep(20);    // ample time for the source's duty cycle to acknowledge, were the line counted
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ackSeenInCallback.add(new long[]{s.feedStatus(FEED).feed(FEED).delivery().acknowledged(), d.sequence()});
            }
            sink.accept("complete " + applied + " position=" + d.position());
            applied.clear();
        }
    }

    MongooseServer boot(ReplayConfig replay, Path file, ReadStrategy strategy, InMemoryMessageSink sink, Saver handler) {
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

    private static List<String> strings(List<?> messages) {
        return messages.stream().map(String::valueOf).toList();
    }

    @Test
    void theCallbackRunsAfterTheQueuedEventAndBeforeTheFeedAcknowledgesTheLine() throws Exception {
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, "a\nb\n", StandardCharsets.UTF_8);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        Saver saver = new Saver();
        MongooseServer server = boot(null, file, ReadStrategy.COMMITED, sink, saver);
        saver.server = server;
        try {
            await(() -> sink.getMessages().size() == 6, "both lines complete");
            await(() -> server.feedStatus(FEED).feed(FEED).delivery().acknowledged() == 2, "both lines acknowledged");
        } finally {
            server.stop();
        }
        assertEquals(List.of("apply a", "apply a+", "complete [a, a+] position=2",
                "apply b", "apply b+", "complete [b, b+] position=4"), strings(sink.getMessages()));
        assertEquals(2, saver.ackSeenInCallback.size());
        for (long[] seen : saver.ackSeenInCallback) {
            assertTrue(seen[0] < seen[1], "inside the callback the feed has not acknowledged the line: acknowledged "
                    + seen[0] + ", line " + seen[1]);
        }
    }

    private List<List<String>> recordAndReplay(Map<String, EventCodec> journalled) throws Exception {
        return recordAndReplay(journalled, false, null);
    }

    private List<List<String>> recordAndReplay(Map<String, EventCodec> journalled, boolean liveCallbackFailsOnce,
                                               java.util.function.Consumer<GroupReplayer> stopped) throws Exception {
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, "a\nb\n", StandardCharsets.UTF_8);
        InMemoryMessageSink sink = new InMemoryMessageSink();
        EventJournal journal = new InMemoryEventJournal();
        InMemoryReplayStore store = new InMemoryReplayStore();
        Saver liveSaver = new Saver();
        liveSaver.failFirstCompletion = liveCallbackFailsOnce;
        MongooseServer live = boot(ReplayConfig.record(Set.of(PROCESSOR), journalled, journal, store), file,
                ReadStrategy.COMMITED, sink, liveSaver);
        try {
            await(() -> live.feedStatus(FEED).settled() && sink.getMessages().size() == 6, "live run settled");
        } finally {
            live.stop();
        }
        lastRecording = store.entries(PROCESSOR);
        Path empty = tempDir.resolve("replay.txt");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        InMemoryMessageSink replaySink = new InMemoryMessageSink();
        MongooseServer replay = boot(ReplayConfig.replay(Set.of(PROCESSOR), journalled, journal, store), empty,
                ReadStrategy.EARLIEST, replaySink, new Saver());
        try {
            if (stopped != null) {
                await(() -> {
                    GroupReplayer replayer = replay.replayers().get(AGENT);
                    return replayer != null && replayer.stopped(PROCESSOR) != null;
                }, "replay stopped");
                stopped.accept(replay.replayers().get(AGENT));
                return List.of(strings(sink.getMessages()));
            }
            await(() -> {
                GroupReplayer replayer = replay.replayers().get(AGENT);
                return replayer != null && replayer.complete() && replayer.outputs(PROCESSOR).size() >= 6;
            }, "replay complete");
            GroupReplayer replayer = replay.replayers().get(AGENT);
            assertNull(replayer.stopped(PROCESSOR), replayer.stopped(PROCESSOR));
            return List.of(strings(sink.getMessages()), strings(replayer.outputs(PROCESSOR)));
        } finally {
            replay.stop();
        }
    }

    @Test
    void anIndexedRecordingReplaysTheCallbacksWhereTheyRanLive() throws Exception {
        List<List<String>> out = recordAndReplay(Map.of(FEED, new JavaSerializationCodec()));
        assertEquals(List.of("apply a", "apply a+", "complete [a, a+] position=2",
                "apply b", "apply b+", "complete [b, b+] position=4"), out.get(0));
        assertEquals(out.get(0), out.get(1), "the replay runs each input's callbacks, in the same place");
    }

    @Test
    void anInlineRecordingReplaysTheCallbacksWhereTheyRanLive() throws Exception {
        List<List<String>> out = recordAndReplay(Map.of());
        assertEquals(6, out.get(0).size(), out.get(0).toString());
        assertEquals(out.get(0), out.get(1));
    }

    private List<com.telamin.mongoose.replay.ReplayEntry> lastRecording;

    /**
     * Hook review R2: a completion callback that throws live and succeeds on the reader's retry. A live retry is a
     * failure, marked and not reproduced (D4), as for a processor's dispatch: the input is recorded as Failed for the
     * callback's processor, never as an ordinary input, and a replay stops there saying why. Before, it was recorded as
     * an ordinary input, so the replay ran the callback once and diverged from the live run without a mark.
     */
    private void aCallbackFailureIsRecordedAsAFailure(Map<String, EventCodec> journalled) throws Exception {
        List<List<String>> out = recordAndReplay(journalled, true, replayer -> {
            String why = replayer.stopped(PROCESSOR);
            assertTrue(why.contains("the recorded run failed here") && why.contains("DEMO transient completion failure"),
                    why);
        });
        assertEquals(List.of("apply a", "apply a+", "complete [a, a+] position=2",
                "apply b", "apply b+", "complete [b, b+] position=4"), out.get(0), "live: the callback's retry succeeded");
        assertInstanceOf(com.telamin.mongoose.replay.ReplayEntry.Failed.class, lastRecording.get(0),
                "the input whose callback failed: " + lastRecording);
        assertTrue(((com.telamin.mongoose.replay.ReplayEntry.Failed) lastRecording.get(0)).description()
                .contains("DEMO transient completion failure"), lastRecording.toString());
        assertEquals(2, lastRecording.size(), "a Failed marker instead of the input, then line b: " + lastRecording);
        assertFalse(lastRecording.get(1) instanceof com.telamin.mongoose.replay.ReplayEntry.Failed, lastRecording.toString());
    }

    @Test
    void aCallbackThatFailedLiveIsRecordedAsAFailureIndexed() throws Exception {
        aCallbackFailureIsRecordedAsAFailure(Map.of(FEED, new JavaSerializationCodec()));
    }

    @Test
    void aCallbackThatFailedLiveIsRecordedAsAFailureInline() throws Exception {
        aCallbackFailureIsRecordedAsAFailure(Map.of());
    }
}
