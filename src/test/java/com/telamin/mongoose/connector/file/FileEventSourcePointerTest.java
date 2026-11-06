/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.file;

import com.telamin.mongoose.config.ReadStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dispatch.PositionedItem;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The read pointer file across restarts (docs/design/feed-delivery.md section 4): what identifies the input file, a
 * reset in the middle of a run, and pointer files written by earlier releases.
 */
class FileEventSourcePointerTest {

    @TempDir
    Path dir;

    private Path data() {
        return dir.resolve("events.txt");
    }

    private Path pointerFile() {
        return Path.of(data() + ".readPointer");
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
        return ByteBuffer.wrap(Files.readAllBytes(pointerFile()), 0, 8).getLong();
    }

    private record Run(FileEventSource src, OneToOneConcurrentArrayQueue<Object> queue,
                       EventToQueuePublisher<String> pub) {
        List<String> drain() {
            List<Object> out = new ArrayList<>();
            queue.drainTo(out, 1024);
            return out.stream().map(String::valueOf).toList();
        }

        long epoch() {
            return ((PositionedItem) queue.peek()).epoch();
        }
    }

    /** A source whose only queue is judged by its consumer position: draining the queue processes it. */
    private Run run() {
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "q");
        FileEventSource src = new FileEventSource(256);
        src.setFilename(data().toString());
        src.setReadStrategy(ReadStrategy.COMMITED);
        src.setOutput(p);
        src.onStart();
        src.start();
        src.startComplete();
        return new Run(src, q, p);
    }

    /** Process everything read, commit it, and stop. */
    private static void processAndStop(Run r) {
        r.drain();
        r.src().doWork();
        r.src().stop();
    }

    /**
     * Finding 1 (BLOCKER): on Linux before JDK 21's statx support, and on file systems without a birth time,
     * {@code creationTime()} is the modification time, so every append changed the input file's "identity" and a
     * restart re-delivered the whole file under a new epoch. Here the creation time is moved explicitly (what such a
     * system reports after an append); the file is the same file, appended to: the pointer and epoch must hold.
     */
    @Test
    void anAppendThatMovesTheFileTimesIsNotAReplacement() throws Exception {
        write(1, 3);
        Run first = run();
        first.src().doWork();
        long epoch = first.epoch();
        processAndStop(first);
        assertEquals(offsetAfter(3), pointer());

        write(4, 4);
        FileTime later = FileTime.fromMillis(System.currentTimeMillis() + 3_600_000);
        // modified, accessed and (where the file system keeps one) created: all move, as on Linux without btime
        Files.getFileAttributeView(data(), BasicFileAttributeView.class).setTimes(later, later, later);

        Run second = run();
        second.src().doWork();
        assertEquals(epoch, second.epoch(), "the same file, appended to: the same epoch");
        assertEquals(List.of("line-4"), second.drain(), "only the appended line, not the whole file again");
        second.src().doWork();
        assertEquals(offsetAfter(4), pointer());
        second.src().stop();
    }

    /** A real replacement is still detected: a new file (another inode) at the same path, with other content. */
    @Test
    void aNewFileWithOtherContentAtTheSamePathIsAReplacement() throws Exception {
        write(1, 3);
        Run first = run();
        first.src().doWork();
        long epoch = first.epoch();
        processAndStop(first);

        Path tmp = dir.resolve("events.txt.new");
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 10; i++) b.append("other-").append(i).append('\n');
        Files.writeString(tmp, b, StandardCharsets.UTF_8);
        Files.move(tmp, data(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Run second = run();
        second.src().doWork();
        assertNotEquals(epoch, second.epoch(), "another file: a new epoch");
        List<String> lines = second.drain();
        assertEquals(10, lines.size(), "read from the start of the new file: " + lines);
        assertEquals("other-1", lines.get(0));
        second.src().stop();
    }

    /**
     * Review 3, item 4: a file key that changes while the content is the same (an NFS remount or a FUSE/overlay copy-up
     * changes the device id; a writer that appends by rewriting to a temporary file and renaming it changes the inode)
     * is not a replacement: the fingerprinted leading bytes match and the file is not shorter than the pointer. The
     * pointer and epoch are kept, with a warning, instead of re-delivering the whole file.
     */
    @Test
    void theSameContentUnderANewFileKeyKeepsThePointer() throws Exception {
        write(1, 3);
        Run first = run();
        first.src().doWork();
        long epoch = first.epoch();
        processAndStop(first);

        Path tmp = dir.resolve("events.txt.new");
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 5; i++) b.append("line-").append(i).append('\n');
        Files.writeString(tmp, b, StandardCharsets.UTF_8);
        Files.move(tmp, data(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Run second = run();
        second.src().doWork();
        assertEquals(epoch, second.epoch(), "the same content: the same epoch");
        assertEquals(List.of("line-4", "line-5"), second.drain(), "resumes at the pointer, nothing re-delivered");
        second.src().stop();
    }

    /**
     * Review 3, item 1: the fingerprint must cover what was read, not only what existed at the first open. The file
     * holds only a 13-byte header when the source opens it, then grows; the producer later truncates and rewrites it in
     * place (same inode) with the same header and other content, longer than the pointer. The restart must reset, not
     * resume at the old pointer inside the new content.
     */
    @Test
    void aHeaderOnlyFirstOpenThenAnInPlaceRewriteWithTheSameHeaderIsAReplacement() throws Exception {
        Files.writeString(data(), "time,sym,qty\n", StandardCharsets.UTF_8);
        Run a = run();
        a.src().doWork();                                  // opens with 13 bytes on disk
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 50; i++) b.append("day1-").append(i).append(",AAA,").append(i).append('\n');
        Files.writeString(data(), b, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        for (int i = 0; i < 5; i++) {
            a.src().doWork();
            a.drain();
        }
        a.src().doWork();
        a.src().stop();
        assertEquals(Files.size(data()), pointer(), "day 1 processed and committed");

        StringBuilder c = new StringBuilder("time,sym,qty\n");
        for (int i = 1; i <= 200; i++) c.append("d2-").append(i).append(",BBBBBB,").append(i).append('\n');
        Files.writeString(data(), c, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        Run r = run();
        r.src().doWork();
        List<String> got = r.drain();
        assertEquals("time,sym,qty", got.get(0), "a rewritten file is read from its start: " + got.subList(0, 3));
        assertEquals(201, got.size());
        r.src().stop();
    }

    /**
     * Rewritten in place (same inode), longer than the pointer, with different first bytes: not the file the pointer was
     * written for. Detected by the content fingerprint kept in the pointer file.
     */
    @Test
    void aFileRewrittenInPlaceWithOtherContentIsAReplacement() throws Exception {
        write(1, 3);
        Run first = run();
        first.src().doWork();
        long epoch = first.epoch();
        processAndStop(first);

        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 10; i++) b.append("other-").append(i).append('\n');
        Files.writeString(data(), b, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

        Run second = run();
        second.src().doWork();
        assertNotEquals(epoch, second.epoch(), "other content: a new epoch");
        List<String> lines = second.drain();
        assertEquals("other-1", lines.get(0), "read from the start of the rewritten file: " + lines);
        assertEquals(10, lines.size());
        second.src().stop();
    }

    /**
     * Finding 6: after a reset in the middle of a run, an acknowledgement of an item of the old file must never write
     * the old file's offset into the new file's pointer. Here the same source is restarted on a replaced file while
     * five lines of the old file are still unacknowledged, and they are processed after the reset.
     */
    @Test
    void anAcknowledgementOfTheOldFileIsNotCommittedAfterAReset() throws Exception {
        write(1, 10);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "g/feed/onEvent");
        AtomicLong dispatched = new AtomicLong();
        p.bindAcknowledgementPoint(q, dispatched::get);
        FileEventSource src = new FileEventSource(256);
        src.setFilename(data().toString());
        src.setReadStrategy(ReadStrategy.COMMITED);
        src.setOutput(p);
        src.onStart();
        src.start();
        src.startComplete();
        src.doWork();
        dispatched.set(5);
        src.doWork();
        assertEquals(offsetAfter(5), pointer());
        src.stop();

        // replaced by a longer file; the same source restarts (as after a reopen) and resets to 0 under a new epoch
        Files.delete(data());
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 20; i++) b.append("new-").append(i).append('\n');
        Files.writeString(data(), b, StandardCharsets.UTF_8);
        src.start();
        src.startComplete();
        src.doWork();
        assertEquals(0, pointer(), "reset: nothing of the new file processed yet");
        dispatched.set(10);          // the old file's lines 6..10 finish processing after the reset
        src.doWork();
        assertEquals(0, pointer(), "an acknowledgement of the old file is never committed into the new file's pointer");
        dispatched.set(11);          // the first line of the new file
        src.doWork();
        assertEquals("new-1\n".length(), pointer());
        src.stop();
    }

    /** Finding 7: a pointer file shorter than the current layout (an 8-byte offset only) starts, at its offset. */
    @Test
    void aPointerFileHoldingOnlyTheOffsetIsUpgraded() throws Exception {
        write(1, 4);
        Files.write(pointerFile(), ByteBuffer.allocate(8).putLong(0, offsetAfter(2)).array());
        Run r = run();
        r.src().doWork();
        assertEquals(List.of("line-3", "line-4"), r.drain());
        r.src().doWork();
        assertEquals(offsetAfter(4), pointer());
        assertTrue(Files.size(pointerFile()) >= 1024, "extended to the current layout: " + Files.size(pointerFile()));
        r.src().stop();
    }

    /**
     * Finding 8: v1.1.2 stored a count of decoded chars, which is less than the byte offset after non-ASCII text, so it
     * can point inside a line. A pointer without an epoch (written by v1.1.2) that is not at a line start resumes after
     * the next line terminator: a partial line is never delivered, and no line is lost (the char count is never past
     * the true byte offset).
     */
    @Test
    void aV112CharCountPointerAfterNonAsciiTextResumesAtALineStart() throws Exception {
        Files.writeString(data(), "café-1\nnext-2\nthird-3\n", StandardCharsets.UTF_8);
        byte[] legacy = new byte[1024];
        ByteBuffer.wrap(legacy).putLong(0, 7);        // "café-1\n": 7 chars, 8 bytes
        Files.write(pointerFile(), legacy);
        Run r = run();
        r.src().doWork();
        assertEquals(List.of("next-2", "third-3"), r.drain());
        r.src().doWork();
        assertEquals(Files.size(data()), pointer());
        r.src().stop();
    }

    /** A v1.1.2 pointer that is at a line start (ASCII text before it) resumes exactly there. */
    @Test
    void aV112PointerAtALineStartResumesThere() throws Exception {
        write(1, 4);
        byte[] legacy = new byte[1024];
        ByteBuffer.wrap(legacy).putLong(0, offsetAfter(2));
        Files.write(pointerFile(), legacy);
        Run r = run();
        r.src().doWork();
        assertEquals(List.of("line-3", "line-4"), r.drain());
        r.src().stop();
    }

    /**
     * Review 3, item 7 (finding 6 on the mid-run path): the input file is replaced while the source runs and the source
     * reopens it (as after a read error), with lines of the old file still unacknowledged. Their acknowledgement must
     * not write the old file's offset into the new file's pointer.
     */
    @Test
    void anAcknowledgementOfTheOldFileIsNotCommittedAfterAMidRunReopen() throws Exception {
        write(1, 10);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(256);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "g/feed/onEvent");
        AtomicLong dispatched = new AtomicLong();
        p.bindAcknowledgementPoint(q, dispatched::get);
        FileEventSource src = new FileEventSource(256);
        src.setFilename(data().toString());
        src.setReadStrategy(ReadStrategy.COMMITED);
        src.setOutput(p);
        src.onStart();
        src.start();
        src.startComplete();
        src.doWork();
        dispatched.set(5);
        src.doWork();
        assertEquals(offsetAfter(5), pointer());

        Files.delete(data());
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 20; i++) b.append("new-").append(i).append('\n');
        Files.writeString(data(), b, StandardCharsets.UTF_8);
        src.closeStreamForTesting();         // a read failure: the next duty cycle reopens the file
        src.doWork();
        assertEquals(0, pointer(), "reset: nothing of the new file processed yet");
        dispatched.set(10);
        src.doWork();
        assertEquals(0, pointer(), "an acknowledgement of the old file is never committed into the new file's pointer");
        dispatched.set(11);
        src.doWork();
        assertEquals("new-1\n".length(), pointer());
        src.stop();
    }

    /** A v1.1.2 pointer between the CR and LF of one terminator resumes after the LF. */
    @Test
    void aV112PointerBetweenCrAndLfResumesAfterTheTerminator() throws Exception {
        Files.writeString(data(), "\u00e91\r\nb2\r\nc3\r\n", StandardCharsets.UTF_8);
        byte[] legacy = new byte[1024];
        ByteBuffer.wrap(legacy).putLong(0, 4);      // C3 A9 31 0D | 0A
        Files.write(pointerFile(), legacy);
        Run r = run();
        r.src().doWork();
        assertEquals(List.of("b2", "c3"), r.drain());
        r.src().stop();
    }

    /**
     * Review 3, item 2: a v1.1.2 pointer whose first start stops before the file is opened (a later boot step failed)
     * must still be aligned at the next start: its epoch is written only once the alignment is committed.
     */
    @Test
    void aV112PointerIsAlignedEvenIfTheFirstStartStopsBeforeOpeningTheFile() throws Exception {
        Files.writeString(data(), "\u00e9\u00e9\u00e9-abc\nnext-2\n", StandardCharsets.UTF_8);
        byte[] legacy = new byte[1024];
        ByteBuffer.wrap(legacy).putLong(0, 8);      // 8 chars: byte 8 is inside the first line (11 bytes)
        Files.write(pointerFile(), legacy);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(64);
        EventToQueuePublisher<String> p = new EventToQueuePublisher<>("feed");
        p.addTargetQueue(q, "q");
        FileEventSource interrupted = new FileEventSource(256);
        interrupted.setFilename(data().toString());
        interrupted.setReadStrategy(ReadStrategy.COMMITED);
        interrupted.setOutput(p);
        interrupted.onStart();
        interrupted.start();
        interrupted.stop();                          // never opened the file
        Run r = run();
        r.src().doWork();
        assertEquals(List.of("next-2"), r.drain());
        r.src().stop();
    }
}
