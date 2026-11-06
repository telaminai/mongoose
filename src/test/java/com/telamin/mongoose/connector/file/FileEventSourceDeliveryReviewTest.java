/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.file;

import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.replay.EventCodec;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.service.delivery.SourceCommitter;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions from the independent review of the delivery work (docs/design/feed-delivery.md): a transient fence
 * (F1), the last subscriber gone (F2), bytes that are not UTF-8 (F4), and a publish nested in a positioned commit.
 */
class FileEventSourceDeliveryReviewTest {

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

    /** F1: a momentary containment fence must not acknowledge what the fenced queue has not processed. */
    @Test
    void aTransientFenceHoldsThePointer() throws Exception {
        write(1, 10);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "g/feed/onEvent");
        AtomicLong dispatched = new AtomicLong();
        p.bindAcknowledgementPoint(q, dispatched::get);
        AtomicBoolean fenced = new AtomicBoolean();
        p.bindContainment("g/feed/onEvent", fenced::get, () -> 0L);
        FileEventSource src = started(p);
        src.doWork();
        dispatched.set(2);
        src.doWork();
        assertEquals(offsetAfter(2), pointer());
        fenced.set(true);
        write(11, 12);
        src.doWork();
        assertEquals(offsetAfter(2), pointer(), "fenced: its 8 unprocessed lines are not committed");
        assertEquals(10, p.getSequenceNumber(), "fenced: a positioned feed pauses instead of publishing past it");
        fenced.set(false);
        src.doWork();
        assertEquals(offsetAfter(2), pointer(), "after the fence, still only what was processed");
        assertEquals(12, p.getSequenceNumber(), "the fence lifted: reading resumes");
        dispatched.set(12);
        src.doWork();
        assertEquals(offsetAfter(12), pointer());
        src.stop();
    }

    /** F2: the only subscriber removed: no reading, no commit; its unprocessed lines stay uncommitted. */
    @Test
    void theLastSubscriberGoneStopsReadingAndCommitting() throws Exception {
        write(1, 10);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "g/feed/onEvent");
        AtomicLong dispatched = new AtomicLong();
        p.bindAcknowledgementPoint(q, dispatched::get);
        FileEventSource src = started(p);
        src.doWork();
        dispatched.set(3);
        src.doWork();
        assertEquals(offsetAfter(3), pointer());
        p.removeTargetQueueByName("g/feed/onEvent");
        write(11, 20);
        src.doWork();
        src.doWork();
        assertEquals(offsetAfter(3), pointer(), "nothing the removed subscriber did not process is committed");
        assertEquals(10, p.getSequenceNumber(), "no consumer: the source does not read on");
        src.stop();
    }

    /** F4: the pointer is the byte offset on disk, whatever the bytes decode to. */
    @Test
    void thePointerIsTheByteOffsetEvenForBytesThatAreNotUtf8() throws Exception {
        Files.write(data(), "café\nnext\nthird\n".getBytes(StandardCharsets.ISO_8859_1));
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(16);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "q");
        AtomicLong dispatched = new AtomicLong();
        p.bindAcknowledgementPoint(q, dispatched::get);
        FileEventSource first = started(p);
        first.doWork();
        dispatched.set(1);
        first.doWork();
        assertEquals(5, pointer(), "\"caf\\xe9\\n\" is 5 bytes on disk");
        first.stop();

        OneToOneConcurrentArrayQueue<Object> q2 = new OneToOneConcurrentArrayQueue<>(16);
        EventToQueuePublisher<String> p2 = new EventToQueuePublisher<>("feed");
        p2.addTargetQueue(q2, "q");
        FileEventSource second = started(p2);
        second.doWork();
        List<Object> out = new ArrayList<>();
        q2.drainTo(out, 16);
        assertEquals(List.of("next", "third"), out.stream().map(String::valueOf).toList());
        second.stop();
    }

    /** A publish nested in a positioned commit (here from the journal codec) never acknowledges the outer item early. */
    @Test
    void aPublishNestedInAPositionedCommitDoesNotAcknowledgeTheOuterItem() {
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(16);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "q");
        p.setPauseByDefault(true);
        AtomicLong dispatched = new AtomicLong();
        p.bindAcknowledgementPoint(q, dispatched::get);
        List<Object> acked = new ArrayList<>();
        p.acknowledgeWith(new SourceCommitter<Object>() {
            @Override
            public void acknowledged(long sequence, Object position) {
                acked.add(position);
            }
        });
        List<Boolean> nested = new ArrayList<>();
        p.journal(new InMemoryEventJournal(), new EventCodec() {
            @Override
            public byte[] encode(Object item) {
                if ("outer".equals(item)) nested.add(p.tryPublish("nested", 99L));
                return new byte[0];
            }

            @Override
            public Object decode(byte[] bytes) {
                return null;
            }
        });
        p.startComplete();
        assertTrue(p.tryPublish("outer", 10L));
        p.pollAcknowledgements();
        assertEquals(List.of(), acked, "nothing processed: nothing acknowledged");
        assertEquals(List.of(false), nested, "the nested tryPublish is told to try again later");
        dispatched.set(1);
        p.pollAcknowledgements();
        assertEquals(List.of(10L), acked);
    }

    private static long epochOf(OneToOneConcurrentArrayQueue<Object> q) {
        Object o = q.peek();
        return ((com.telamin.mongoose.dispatch.PositionedItem) o).epoch();
    }

    private record Run(FileEventSource src, OneToOneConcurrentArrayQueue<Object> queue, EventToQueuePublisher<String> pub) {
        List<String> drain() {
            List<Object> out = new ArrayList<>();
            queue.drainTo(out, 1024);
            return out.stream().map(String::valueOf).toList();
        }
    }

    private Run run() {
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "q");
        return new Run(started(p), q, p);
    }

    /**
     * F5: the epoch names the position space. Stable across a restart on the same pointer and file; new when the pointer
     * file is deleted, and new (with the pointer back at 0) when the input file is replaced or truncated, so an
     * application's (epoch, position) guard never skips a line of a different file.
     */
    @Test
    void theEpochChangesWhenThePointerOrTheFileIsReset() throws Exception {
        write(1, 3);
        Run first = run();
        first.src().doWork();
        long epoch = epochOf(first.queue());
        assertNotEquals(0, epoch);
        first.drain();
        first.src().stop();

        write(4, 4);
        Run second = run();
        second.src().doWork();
        assertEquals(epoch, epochOf(second.queue()), "same pointer, same file: the same epoch");
        assertEquals(List.of("line-4"), second.drain());
        second.src().stop();

        // the input file replaced by a new, shorter file: the pointer restarts at 0 under a new epoch
        Files.delete(data());
        Files.writeString(data(), "new-1\nnew-2\n", StandardCharsets.UTF_8);
        Run third = run();
        third.src().doWork();
        long replaced = epochOf(third.queue());
        assertNotEquals(epoch, replaced, "a replaced file: a new epoch");
        assertEquals(List.of("new-1", "new-2"), third.drain(), "read from the start of the new file");
        third.src().stop();

        // truncated in place (same file, shorter than the pointer)
        Files.writeString(data(), "t-1\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        Run fourth = run();
        fourth.src().doWork();
        long truncated = epochOf(fourth.queue());
        assertNotEquals(replaced, truncated, "a truncated file: a new epoch");
        assertEquals(List.of("t-1"), fourth.drain());
        fourth.src().stop();

        // the pointer file deleted: a new epoch, everything delivered again
        Files.delete(Path.of(data() + ".readPointer"));
        Run fifth = run();
        fifth.src().doWork();
        assertNotEquals(truncated, epochOf(fifth.queue()), "a deleted pointer: a new epoch");
        assertEquals(List.of("t-1"), fifth.drain());
        fifth.src().stop();

        var d = new com.telamin.mongoose.service.delivery.Delivery("feed", 1, 7, 10L);
        assertTrue(d.alreadyApplied(7, 10L));
        assertFalse(d.alreadyApplied(7, 9L));
        assertFalse(d.alreadyApplied(8, 100L), "another epoch is never already applied");
    }
}
