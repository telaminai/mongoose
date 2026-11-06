/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.file;

import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dispatch.FeedOverloadSettings;
import com.telamin.mongoose.dispatch.OverloadPolicy;
import com.telamin.mongoose.dispatch.OverloadSettings;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileEventSource delivery guarantees (docs/design/feed-delivery.md): the read pointer is written only for processed
 * lines, never ahead of the slowest subscriber, so a restart re-delivers what was read but not processed; and a full
 * queue pauses the source instead of dropping.
 */
class FileEventSourceDeliveryTest {

    @TempDir
    Path tempDir;

    private Path dataFile() {
        return tempDir.resolve("events.txt");
    }

    private Path pointerFile() {
        return Path.of(dataFile() + ".readPointer");
    }

    private void write(int from, int to) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = from; i <= to; i++) b.append("line-").append(i).append('\n');
        Files.writeString(dataFile(), b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The byte offset just after line {@code n} of a file written by {@link #write} from 1. */
    private static long offsetAfter(int n) {
        long off = 0;
        for (int i = 1; i <= n; i++) off += ("line-" + i + "\n").length();
        return off;
    }

    /** What a crashed process leaves behind: the pointer file, read without the source's help. */
    private long pointerOnDisk() throws Exception {
        byte[] bytes = Files.readAllBytes(pointerFile());
        return ByteBuffer.wrap(bytes, 0, 8).getLong();   // MappedByteBuffer.putLong: big-endian
    }

    private static FileEventSource source(Path file, EventToQueuePublisher<String> publisher, ReadStrategy strategy,
                                          boolean cache) {
        FileEventSource src = new FileEventSource(256);
        src.setFilename(file.toString());
        src.setReadStrategy(strategy);
        src.setCacheEventLog(cache);
        src.setOutput(publisher);
        return src;
    }

    private static List<String> drain(OneToOneConcurrentArrayQueue<Object> q) {
        List<Object> out = new ArrayList<>();
        q.drainTo(out, Integer.MAX_VALUE);
        return out.stream().map(String::valueOf).toList();
    }

    private static void start(FileEventSource src) {
        src.onStart();
        src.start();
        src.startComplete();
    }

    /**
     * (1) Read but not dispatched is not committed. The processor has taken all 10 lines from its queue but finished
     * dispatching only 4 when the process "crashes" (the source is abandoned, never stopped): the pointer on disk covers
     * exactly the 4, and a restart on the same pointer re-delivers lines 5..10.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void linesReadButNotDispatchedAreNotCommittedAndAreRedeliveredAfterACrash(boolean cache) throws Exception {
        write(1, 10);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(queue, "g/fileFeed/onEvent");
        AtomicLong dispatched = new AtomicLong();
        publisher.bindAcknowledgementPoint(queue, dispatched::get);
        FileEventSource first = source(dataFile(), publisher, ReadStrategy.COMMITED, cache);
        start(first);
        first.doWork();
        assertEquals(10, drain(queue).size(), "all 10 lines were read and taken by the reader");
        assertEquals(0, pointerOnDisk(), "nothing dispatched: nothing committed, although everything was read");

        dispatched.set(4);
        first.doWork();
        assertEquals(offsetAfter(4), pointerOnDisk(), "the pointer covers the 4 dispatched lines and no more");
        assertEquals(4, publisher.acknowledgedSequence());
        assertEquals(6, publisher.pendingAcknowledgements());
        // crash: no stop, no further acknowledgement; the pointer file is all that survives

        OneToOneConcurrentArrayQueue<Object> queue2 = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> publisher2 = new EventToQueuePublisher<>("fileFeed");
        publisher2.addTargetQueue(queue2, "g/fileFeed/onEvent");
        FileEventSource second = source(dataFile(), publisher2, ReadStrategy.COMMITED, cache);
        start(second);
        second.doWork();
        assertEquals(List.of("line-5", "line-6", "line-7", "line-8", "line-9", "line-10"), drain(queue2),
                "the restart resumes at line 5: the read-but-unprocessed lines are re-delivered, none is lost");
        second.doWork();
        assertEquals(offsetAfter(10), pointerOnDisk(), "re-delivered lines processed: committed");
        second.stop();
    }

    /** A clean stop commits only what was processed, too. */
    @Test
    void aCleanStopCommitsOnlyProcessedLines() throws Exception {
        write(1, 6);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(queue, "q");
        AtomicLong dispatched = new AtomicLong();
        publisher.bindAcknowledgementPoint(queue, dispatched::get);
        FileEventSource first = source(dataFile(), publisher, ReadStrategy.COMMITED, false);
        start(first);
        first.doWork();
        dispatched.set(2);
        first.stop();
        assertEquals(offsetAfter(2), pointerOnDisk());

        OneToOneConcurrentArrayQueue<Object> queue2 = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> publisher2 = new EventToQueuePublisher<>("fileFeed");
        publisher2.addTargetQueue(queue2, "q");
        FileEventSource second = source(dataFile(), publisher2, ReadStrategy.COMMITED, false);
        start(second);
        second.doWork();
        assertEquals(List.of("line-3", "line-4", "line-5", "line-6"), drain(queue2));
        second.stop();
    }

    /**
     * (2) Acknowledged lines are committed in order, and the pointer never runs ahead of the lowest unacknowledged line
     * across several subscriber queues.
     */
    @Test
    void thePointerFollowsTheSlowestOfSeveralSubscriberQueues() throws Exception {
        write(1, 20);
        OneToOneConcurrentArrayQueue<Object> fast = new OneToOneConcurrentArrayQueue<>(256);
        OneToOneConcurrentArrayQueue<Object> slow = new OneToOneConcurrentArrayQueue<>(256);
        OneToOneConcurrentArrayQueue<Object> third = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(fast, "fast");
        publisher.addTargetQueue(slow, "slow");
        publisher.addTargetQueue(third, "third");
        AtomicLong dFast = new AtomicLong(), dSlow = new AtomicLong(), dThird = new AtomicLong();
        publisher.bindAcknowledgementPoint(fast, dFast::get);
        publisher.bindAcknowledgementPoint(slow, dSlow::get);
        publisher.bindAcknowledgementPoint(third, dThird::get);
        List<Long> commits = new CopyOnWriteArrayList<>();
        FileEventSource src = source(dataFile(), publisher, ReadStrategy.COMMITED, false);
        start(src);
        src.doWork();
        assertEquals(20, publisher.getSequenceNumber());

        java.util.Random random = new java.util.Random(42);
        long lastPointer = 0;
        while (dFast.get() < 20 || dSlow.get() < 20 || dThird.get() < 20) {
            // each reader advances at its own pace; the slow one least
            dFast.set(Math.min(20, dFast.get() + random.nextInt(4)));
            dSlow.set(Math.min(20, dSlow.get() + random.nextInt(2)));
            dThird.set(Math.min(20, dThird.get() + random.nextInt(3)));
            src.doWork();
            long pointer = pointerOnDisk();
            long lowest = Math.min(dFast.get(), Math.min(dSlow.get(), dThird.get()));
            assertEquals(offsetAfter((int) lowest), pointer,
                    "the pointer is exactly at the lowest dispatched line across the queues: " + lowest);
            assertTrue(pointer >= lastPointer, "the pointer only moves forward");
            assertEquals(lowest, publisher.acknowledgedSequence());
            lastPointer = pointer;
            commits.add(pointer);
        }
        assertEquals(offsetAfter(20), pointerOnDisk());
        assertEquals(0, publisher.pendingAcknowledgements(), "the window is empty once everything is acknowledged");
        src.stop();
    }

    /** A subscriber removed (unsubscribed) no longer holds the pointer back. */
    @Test
    void aRemovedSubscriberNoLongerHoldsThePointerBack() throws Exception {
        write(1, 5);
        OneToOneConcurrentArrayQueue<Object> stays = new OneToOneConcurrentArrayQueue<>(256);
        OneToOneConcurrentArrayQueue<Object> leaves = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(stays, "stays");
        publisher.addTargetQueue(leaves, "leaves");
        AtomicLong dStays = new AtomicLong(), dLeaves = new AtomicLong();
        publisher.bindAcknowledgementPoint(stays, dStays::get);
        publisher.bindAcknowledgementPoint(leaves, dLeaves::get);
        FileEventSource src = source(dataFile(), publisher, ReadStrategy.COMMITED, false);
        start(src);
        src.doWork();
        dStays.set(5);
        src.doWork();
        assertEquals(0, pointerOnDisk());
        publisher.removeTargetQueueByName("leaves");
        src.doWork();
        assertEquals(offsetAfter(5), pointerOnDisk());
        src.stop();
    }

    /** A line dropped for a DROP_AFTER_SPIN queue is a loss: it does not hold the pointer back (and is counted). */
    @Test
    void anExplicitDropPolicyDropsCountsAndCommitsPastTheDrop() throws Exception {
        write(1, 10);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(4);
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(queue, "q");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO)));
        FileEventSource src = source(dataFile(), publisher, ReadStrategy.COMMITED, false);
        start(src);
        src.doWork();
        assertEquals(OverloadPolicy.DROP_AFTER_SPIN, publisher.overload().feedDefault().policy(), "explicit wins");
        assertEquals(6, publisher.droppedCount(), "a 4-slot queue took 4 of 10");
        assertEquals(4, drain(queue).size());
        src.doWork();
        assertEquals(offsetAfter(10), pointerOnDisk(), "the dropped lines are lost, not re-read: what a drop means");
        src.stop();
    }

    /**
     * (3) PAUSE, the default: a slow consumer and a file far larger than the queue (10,000 lines into 1,024 slots):
     * no drop, every line delivered once and in order; the source paused rather than dropped.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFullQueuePausesTheSourceAndNothingIsDropped(boolean cache) throws Exception {
        int lines = 10_000;
        write(1, lines);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(1024);
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(queue, "q");
        AtomicLong dispatched = new AtomicLong();
        publisher.bindAcknowledgementPoint(queue, dispatched::get);
        FileEventSource src = source(dataFile(), publisher, ReadStrategy.COMMITED, cache);
        assertEquals(OverloadPolicy.PAUSE, publisher.overload().feedDefault().policy(), "a file feed pauses by default");

        List<String> received = new ArrayList<>(lines);
        AtomicBoolean done = new AtomicBoolean();
        Thread consumer = new Thread(() -> {
            while (!done.get()) {
                Object o = queue.poll();
                if (o == null) {
                    Thread.onSpinWait();
                    continue;
                }
                synchronized (received) {
                    received.add(String.valueOf(o));
                }
                dispatched.lazySet(dispatched.get() + 1);
                if (dispatched.get() % 64 == 0) LockSupport.parkNanos(200_000);   // slow: ~3 lines per µs at best
            }
        }, "slow-consumer");
        consumer.start();
        try {
            start(src);
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (dispatched.get() < lines && System.nanoTime() < deadline) {
                src.doWork();
            }
            src.doWork();
        } finally {
            done.set(true);
            consumer.join(5_000);
        }
        assertEquals(0, publisher.droppedCount(), "PAUSE never drops");
        assertTrue(publisher.pausedCount() > 0, "the source paused at a full queue");
        synchronized (received) {
            assertEquals(lines, received.size());
            for (int i = 0; i < lines; i++) {
                assertEquals("line-" + (i + 1), received.get(i), "in order, exactly once");
            }
        }
        assertEquals(offsetAfter(lines), pointerOnDisk(), "everything processed is committed");
        src.stop();
    }

    /** With in-band feed status on, a status waits for room in a PAUSE queue too: nothing dropped, order kept. */
    @Test
    void anInBandStatusWaitsForRoomInAPausedQueue() throws Exception {
        write(1, 100);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(queue, "q");
        publisher.setDeliverFeedStatus(true);
        FileEventSource src = source(dataFile(), publisher, ReadStrategy.COMMITED, false);
        start(src);
        List<String> shape = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            src.doWork();
            Object o;
            int n = 0;
            while (n++ < 5 && (o = queue.poll()) != null) {
                shape.add(o instanceof com.telamin.mongoose.service.feedstatus.FeedStatus st
                        ? st.state() + "@" + st.sequence() : String.valueOf(o));
            }
        }
        assertEquals(0, publisher.droppedCount(), "neither a line nor a status was dropped");
        assertEquals("BEHIND@0", shape.get(0));
        assertEquals("CAUGHT_UP@100", shape.get(shape.size() - 1), shape.toString());
        List<String> lines = shape.stream().filter(l -> l.startsWith("line-")).toList();
        assertEquals(100, lines.size());
        for (int i = 0; i < 100; i++) assertEquals("line-" + (i + 1), lines.get(i));
        src.stop();
    }

    /** The pointer is a byte offset: a restart after non-ASCII lines resumes exactly at the next line. */
    @Test
    void thePointerCountsBytesSoARestartAfterNonAsciiLinesResumesAtALineStart() throws Exception {
        String text = "caf\u00e9\n\u20ac10\n\ud83d\ude00 smile\nplain\n";
        Files.writeString(dataFile(), text, StandardCharsets.UTF_8);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fileFeed");
        publisher.addTargetQueue(queue, "q");
        AtomicLong dispatched = new AtomicLong();
        publisher.bindAcknowledgementPoint(queue, dispatched::get);
        FileEventSource first = source(dataFile(), publisher, ReadStrategy.COMMITED, false);
        start(first);
        first.doWork();
        dispatched.set(3);
        first.doWork();
        assertEquals(("caf\u00e9\n\u20ac10\n\ud83d\ude00 smile\n").getBytes(StandardCharsets.UTF_8).length,
                pointerOnDisk());
        first.stop();

        OneToOneConcurrentArrayQueue<Object> queue2 = new OneToOneConcurrentArrayQueue<>(16);
        EventToQueuePublisher<String> publisher2 = new EventToQueuePublisher<>("fileFeed");
        publisher2.addTargetQueue(queue2, "q");
        FileEventSource second = source(dataFile(), publisher2, ReadStrategy.COMMITED, false);
        start(second);
        second.doWork();
        assertEquals(List.of("plain"), drain(queue2));
        second.stop();
    }
}
