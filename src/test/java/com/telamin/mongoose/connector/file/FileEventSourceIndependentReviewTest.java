/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.file;

import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.feedstatus.FeedState;
import com.telamin.mongoose.service.feedstatus.FeedStatus;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions from the independent review of 9567d6d (docs/design/feed-delivery.md): R1 a later unsubscribe and a
 * removed last consumer's hold, R4 the input file replaced or truncated while it is tailed, R5 read pointers of earlier
 * layouts, R6 the startup status of a cached start that fills its queue.
 */
class FileEventSourceIndependentReviewTest {

    @TempDir
    Path dir;

    private Path data() {
        return dir.resolve("events");
    }

    private Path pointerFile() {
        return Path.of(data() + ".readPointer");
    }

    private long pointer() throws Exception {
        return ByteBuffer.wrap(Files.readAllBytes(pointerFile()), 0, 8).getLong();
    }

    private long pointerEpoch() throws Exception {
        return ByteBuffer.wrap(Files.readAllBytes(pointerFile())).getLong(8);
    }

    private record Run(FileEventSource src, EventToQueuePublisher<String> pub, OneToOneConcurrentArrayQueue<Object> queue) {
        List<String> drain() {
            List<Object> out = new ArrayList<>();
            queue.drainTo(out, 10_000);
            return out.stream().filter(o -> !(o instanceof FeedStatus)).map(String::valueOf).toList();
        }

        void work(int cycles) {
            for (int i = 0; i < cycles; i++) src.doWork();
        }
    }

    private Run start(ReadStrategy strategy, boolean deliverStatus) {
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(64);
        p.addTargetQueue(q, "first");
        p.setDeliverFeedStatus(deliverStatus);
        FileEventSource s = new FileEventSource(8);
        s.setFilename(data().toString());
        s.setReadStrategy(strategy);
        s.setOutput(p);
        s.start();
        s.startComplete();
        return new Run(s, p, q);
    }

    private Run start() {
        return start(ReadStrategy.COMMITED, false);
    }

    // ---- R1 ----

    /**
     * R1 (the reviewer's case, with the default acknowledgement binding): A processed only a, then left; B and C
     * subscribed, and C left again. Neither has been given b or c, so nothing past a may be committed.
     */
    @Test
    void anUnsubscribeAfterTheLastConsumerLeftKeepsItsHold() throws Exception {
        Files.writeString(data(), "a\nb\nc\n");
        Run r = start();
        try {
            r.src().doWork();
            assertNotNull(r.queue().poll());
            r.src().doWork();
            assertEquals(2, r.src().committedOffset());
            r.pub().removeTargetQueueByName("first");
            r.pub().addTargetQueue(new OneToOneConcurrentArrayQueue<>(16), "second");
            r.pub().addTargetQueue(new OneToOneConcurrentArrayQueue<>(16), "third");
            r.pub().removeTargetQueueByName("third");
            r.work(3);
            assertEquals(2, r.src().committedOffset(), "b and c were never processed by anyone");
            assertEquals(2, pointer());
            assertNotNull(r.pub().stallReason());
        } finally {
            r.src().stop();
        }
    }

    // ---- R5 (fixed by 2d0597a; aPointerFileHoldingOnlyTheOffsetIsUpgraded covers the 8-byte ASCII case) ----

    /**
     * R5: an 8-byte pointer (an offset, no epoch) after non-ASCII text holds a v1.1.2 char count: aligned to the next
     * line start, so no partial line is delivered.
     */
    @Test
    void anEightBytePointerAfterNonAsciiTextResumesAtALineStart() throws Exception {
        Files.writeString(data(), "é\nnext\n", StandardCharsets.UTF_8);
        Files.write(pointerFile(), ByteBuffer.allocate(8).putLong(2).array());
        Run r = start();
        try {
            r.src().doWork();
            assertEquals(List.of("next"), r.drain());
            r.src().doWork();
            assertEquals(Files.size(data()), pointer());
            assertTrue(Files.size(pointerFile()) >= 1024);
        } finally {
            r.src().stop();
        }
    }

    /**
     * The legacy ambiguity, kept explicit (docs/design/feed-delivery.md, section 10): a v1.1.2 char count after non-ASCII
     * text is short of the byte offset, so lines already processed can be delivered again (at-least-once). Here "x" was
     * processed under v1.1.2 (char count 5, byte offset 7) and is delivered again, under a new epoch.
     */
    @Test
    void aLegacyCharCountPointerCanDeliverAProcessedLineAgain() throws Exception {
        Files.writeString(data(), "éé\nx\ny\n", StandardCharsets.UTF_8);
        Files.write(pointerFile(), ByteBuffer.allocate(1024).putLong(5).array());
        Run r = start();
        try {
            r.src().doWork();
            assertEquals(List.of("x", "y"), r.drain());
        } finally {
            r.src().stop();
        }
    }

    /** R5: a pointer file too short to hold an offset is explicit about it: read from the start, under a new epoch. */
    @Test
    void aPointerFileTooShortForAnOffsetReadsFromTheStart() throws Exception {
        Files.writeString(data(), "a\nb\n");
        Files.write(pointerFile(), new byte[]{0, 0, 0, 2});
        Run r = start();
        try {
            r.src().doWork();
            assertEquals(List.of("a", "b"), r.drain());
            assertNotEquals(0, r.src().epoch());
        } finally {
            r.src().stop();
        }
    }

    /** R5: an empty pointer file starts too. */
    @Test
    void anEmptyPointerFileReadsFromTheStart() throws Exception {
        Files.writeString(data(), "a\n");
        Files.write(pointerFile(), new byte[0]);
        Run r = start();
        try {
            r.src().doWork();
            assertEquals(List.of("a"), r.drain());
            r.src().doWork();
            assertEquals(2, pointer());
        } finally {
            r.src().stop();
        }
    }

    // ---- R6 ----

    /**
     * R6: nine cached lines and an eight-slot PAUSE queue. The startup status is neither dropped nor delivered ahead of
     * the ninth line: it follows it once there is room, and the feed is not drained meanwhile.
     */
    @Test
    void aCachedStartupStatusWaitsBehindTheCacheAndIsNotDropped() throws Exception {
        Files.writeString(data(), "a\nb\nc\nd\ne\nf\ng\nh\ni\n");
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        p.addTargetQueue(q, "first");
        p.setDeliverFeedStatus(true);
        FileEventSource s = new FileEventSource(8);
        s.setFilename(data().toString());
        s.setCacheEventLog(true);
        s.setOutput(p);
        try {
            s.start();
            s.startComplete();
            assertEquals(0, p.droppedCount(), "a PAUSE status waits behind cached data");
            assertTrue(p.feedPosition().cachedPending(), "the cached delivery, with its status, is not complete");
            List<Object> seen = new ArrayList<>();
            q.drainTo(seen, 100);
            for (int i = 0; i < 3; i++) {
                s.doWork();
                q.drainTo(seen, 100);
            }
            assertEquals(0, p.droppedCount());
            List<String> lines = seen.stream().filter(o -> !(o instanceof FeedStatus)).map(String::valueOf).toList();
            assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"), lines);
            int firstStatus = 0;
            while (!(seen.get(firstStatus) instanceof FeedStatus)) firstStatus++;
            assertEquals(9, firstStatus, "the startup status follows every cached line: " + seen);
            assertEquals(FeedState.CAUGHT_UP, ((FeedStatus) seen.get(seen.size() - 1)).state());
            assertFalse(p.feedPosition().cachedPending());
        } finally {
            s.stop();
        }
    }
}
