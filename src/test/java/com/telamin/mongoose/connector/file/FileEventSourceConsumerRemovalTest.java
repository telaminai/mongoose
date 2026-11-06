/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.file;

import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A consumer of a positioned feed removed while it has unprocessed items (docs/design/feed-delivery.md section 3):
 * quarantine, deregistration, a group restart or an unsubscribe. Whatever the reason, the items it was given and did
 * not process are never committed in this run, and the feed reports why it holds.
 */
class FileEventSourceConsumerRemovalTest {

    @TempDir
    Path dir;

    private Path data() {
        return dir.resolve("events.txt");
    }

    private void write(int from, int to) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = from; i <= to; i++) b.append("line-").append(i).append('\n');
        Files.writeString(data(), b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static long offsetAfter(int n) {
        long off = 0;
        for (int i = 1; i <= n; i++) off += ("line-" + i + "\n").length();
        return off;
    }

    private long pointer() throws Exception {
        return ByteBuffer.wrap(Files.readAllBytes(Path.of(data() + ".readPointer")), 0, 8).getLong();
    }

    private FileEventSource started(EventToQueuePublisher<String> publisher) {
        FileEventSource src = new FileEventSource(256);
        src.setFilename(data().toString());
        src.setReadStrategy(ReadStrategy.COMMITED);
        src.setOutput(publisher);
        src.onStart();
        src.start();
        src.startComplete();
        return src;
    }

    private static AtomicLong consumer(EventToQueuePublisher<String> p, String name) {
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(256);
        p.addTargetQueue(q, name);
        AtomicLong dispatched = new AtomicLong();
        p.bindAcknowledgementPoint(q, dispatched::get);
        return dispatched;
    }

    /**
     * Finding 2: one of two consumers removed with a backlog (its only processor quarantined, say) must not commit the
     * backlog because the other consumer processed it.
     */
    @Test
    void removingOneOfTwoConsumersDoesNotCommitItsBacklog() throws Exception {
        write(1, 10);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        AtomicLong a = consumer(p, "ga/feed/onEvent");
        AtomicLong b = consumer(p, "gb/feed/onEvent");
        FileEventSource src = started(p);
        src.doWork();
        a.set(2);
        b.set(10);
        src.doWork();
        assertEquals(offsetAfter(2), pointer());

        p.removeTargetQueueByName("ga/feed/onEvent");
        src.doWork();
        src.doWork();
        assertEquals(offsetAfter(2), pointer(), "A's 8 unprocessed lines are not committed");
        assertNotNull(p.stallReason(), "the feed says why it holds");
        assertTrue(p.stallReason().contains("removed consumer"), p.stallReason());
        assertTrue(p.droppedCount() == 0, "a hold is not a loss");

        // B goes on receiving and processing; the pointer stays before A's backlog for the rest of the run
        write(11, 12);
        src.doWork();
        b.set(12);
        src.doWork();
        assertEquals(12, p.getSequenceNumber(), "the remaining consumer is still fed");
        assertEquals(offsetAfter(2), pointer());

        // A's reader did finish its backlog (it was mid-way when removed): the hold ends, the point is dropped
        a.set(10);
        src.doWork();
        assertEquals(offsetAfter(12), pointer());
        assertNull(p.stallReason());
        src.stop();
    }

    /** A consumer removed after it processed everything it was given holds nothing back. */
    @Test
    void aConsumerRemovedWithNothingUnprocessedHoldsNothing() throws Exception {
        write(1, 5);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        AtomicLong a = consumer(p, "ga/feed/onEvent");
        AtomicLong b = consumer(p, "gb/feed/onEvent");
        FileEventSource src = started(p);
        src.doWork();
        a.set(5);
        p.removeTargetQueueByName("ga/feed/onEvent");
        b.set(5);
        src.doWork();
        assertEquals(offsetAfter(5), pointer());
        assertNull(p.stallReason());
        src.stop();
    }

    /**
     * Finding 3: the held point of a removed last consumer must survive a later, unrelated unsubscribe while other
     * consumers remain: lines 4..10, never processed by anyone, must not be committed.
     */
    @Test
    void theHeldPointOfARemovedLastConsumerSurvivesALaterUnsubscribe() throws Exception {
        write(1, 10);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        AtomicLong a = consumer(p, "ga/feed/onEvent");
        FileEventSource src = started(p);
        src.doWork();
        a.set(3);
        src.doWork();
        assertEquals(offsetAfter(3), pointer());
        p.removeTargetQueueByName("ga/feed/onEvent");     // the last consumer gone: held at 3
        src.doWork();
        assertEquals(offsetAfter(3), pointer());

        AtomicLong b = consumer(p, "gb/feed/onEvent");
        AtomicLong c = consumer(p, "gc/feed/onEvent");
        write(11, 14);
        src.doWork();
        b.set(4);
        c.set(4);
        src.doWork();
        assertEquals(offsetAfter(3), pointer(), "held by A's point: " + p.stallReason());

        p.removeTargetQueueByName("gc/feed/onEvent");     // an unrelated unsubscribe while B remains
        src.doWork();
        assertEquals(offsetAfter(3), pointer(), "lines 4..10, processed by nobody, are still not committed");
        assertNotNull(p.stallReason());
        src.stop();
    }
}
