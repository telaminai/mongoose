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

    // ---- R4 ----

    /** R4: a file replaced at the path while it is tailed is read, under a new epoch. */
    @Test
    void aFileReplacedWhileTailedIsReadUnderANewEpoch() throws Exception {
        Files.writeString(data(), "a\nb\n");
        Run r = start();
        try {
            r.src().doWork();
            assertEquals(List.of("a", "b"), r.drain());
            r.src().doWork();
            assertEquals(4, pointer());
            long epoch = r.src().epoch();
            Files.move(data(), dir.resolve("events.1"));
            Files.writeString(data(), "new\n");
            r.work(4);
            assertEquals(List.of("new"), r.drain(), "the replacement is delivered, not reported caught up on the old file");
            assertNotEquals(epoch, r.src().epoch());
            r.work(2);
            assertEquals(4, pointer(), "the new file's own offset");
            assertEquals(r.src().epoch(), pointerEpoch());
        } finally {
            r.src().stop();
        }
    }

    /** R4: a file truncated while it is tailed is read from its start, under a new epoch. */
    @Test
    void aFileTruncatedWhileTailedIsReadUnderANewEpoch() throws Exception {
        Files.writeString(data(), "old-line\n");
        Run r = start();
        try {
            r.src().doWork();
            r.drain();
            r.src().doWork();
            assertEquals(9, pointer());
            long epoch = r.src().epoch();
            Files.writeString(data(), "new\n", StandardOpenOption.TRUNCATE_EXISTING);
            r.work(4);
            assertEquals(List.of("new"), r.drain());
            assertNotEquals(epoch, r.src().epoch());
            r.work(2);
            assertEquals(4, pointer());
        } finally {
            r.src().stop();
        }
    }

    /**
     * R4, rotation: what the writer appended to the old file after it was renamed, before the source noticed, is read
     * first; then the new file. The host sees the feed BEHIND across the switch, never CAUGHT_UP on the old file alone.
     */
    @Test
    void aRotatedFileIsReadToItsEndBeforeTheNewOne() throws Exception {
        Files.writeString(data(), "a\n");
        Run r = start(ReadStrategy.COMMITED, true);
        try {
            r.src().doWork();
            assertEquals(List.of("a"), r.drain());
            r.src().doWork();
            Path rotated = dir.resolve("events.1");
            Files.move(data(), rotated);
            Files.writeString(rotated, "b\n", StandardOpenOption.APPEND);
            r.work(2);
            Files.writeString(data(), "new\n");
            List<Object> seen = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                r.src().doWork();
                r.queue().drainTo(seen, 100);
            }
            assertEquals(List.of("b", "new"), seen.stream().filter(o -> !(o instanceof FeedStatus)).map(String::valueOf).toList());
            List<FeedStatus> statuses = seen.stream().filter(o -> o instanceof FeedStatus).map(o -> (FeedStatus) o).toList();
            assertTrue(statuses.stream().anyMatch(s -> s.state() == FeedState.BEHIND && s.detail() != null
                    && s.detail().contains("replaced")), statuses.toString());
            assertEquals(FeedState.CAUGHT_UP, statuses.get(statuses.size() - 1).state(), statuses.toString());
        } finally {
            r.src().stop();
        }
    }

    /**
     * R4: lines of the old file still unprocessed when the file is replaced are delivered, but their acknowledgement is
     * of the old epoch: it never writes the old file's offsets into the new file's pointer.
     */
    @Test
    void anOldFileAcknowledgementIsNotCommittedAfterALiveReplacement() throws Exception {
        Files.writeString(data(), "aaaa\nbbbb\n");
        Run r = start();
        try {
            r.src().doWork();
            Files.delete(data());
            Files.writeString(data(), "n\n");
            r.work(4);
            assertEquals(0, pointer(), "reset: nothing of the new file processed yet");
            assertNotNull(r.queue().poll());
            assertNotNull(r.queue().poll());
            r.work(2);
            assertEquals(0, pointer(), "the old file's lines were processed: never committed as offsets of the new file");
            assertEquals(List.of("n"), r.drain());
            r.work(2);
            assertEquals(2, pointer());
        } finally {
            r.src().stop();
        }
    }

    /**
     * R4: a commit made after the file was replaced, for lines of the old file, fingerprints the file being read, never
     * the new file at the path. Fingerprinting the path made the next start take the new file for the old one and skip
     * its first bytes.
     */
    @Test
    void aCommitAfterAReplacementNeverFingerprintsTheNewFile() throws Exception {
        Files.writeString(data(), "a\nb\n");
        Run r = start();
        r.src().doWork();
        Files.delete(data());
        Files.writeString(data(), "new-1\nnew-2\n");
        assertEquals(List.of("a", "b"), r.drain());
        r.src().doWork();
        r.src().stop();
        Run next = start();
        try {
            next.work(2);
            assertEquals(List.of("new-1", "new-2"), next.drain(), "the new file is read from its start");
        } finally {
            next.src().stop();
        }
    }

    /** R4, a strategy without a pointer (EARLIEST): a truncated file is read again from its start. */
    @Test
    void anEarliestTailReadsATruncatedFileFromItsStart() throws Exception {
        Files.writeString(data(), "old-line\n");
        Run r = start(ReadStrategy.EARLIEST, false);
        try {
            r.src().doWork();
            assertEquals(List.of("old-line"), r.drain());
            Files.writeString(data(), "new\n", StandardOpenOption.TRUNCATE_EXISTING);
            r.work(4);
            assertEquals(List.of("new"), r.drain());
        } finally {
            r.src().stop();
        }
    }

    /** A partial line buffered when the file is reopened is read again, not appended to twice. */
    @Test
    void aPartialLineIsReadOnceAcrossAReopen() throws Exception {
        Files.writeString(data(), "a\npart");
        Run r = start();
        try {
            r.src().doWork();
            assertEquals(List.of("a"), r.drain());
            r.src().closeStreamForTesting();
            Files.writeString(data(), "ial\n", StandardOpenOption.APPEND);
            r.work(2);
            assertEquals(List.of("partial"), r.drain());
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
