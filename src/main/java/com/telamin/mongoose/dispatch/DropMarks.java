/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where items were dropped from one subscriber queue, so a RECORD-mode reader can mark the loss in its processors'
 * recordings at the right point (telaminai/mongoose#79): a drop keeps its journal entry and sequence number, and the
 * recording gets a {@code ReplayEntry.Dropped} entry naming it where it happened. A replay reports the loss
 * ({@code GroupReplayer.losses}, {@code lossless()}) and carries on: the processor never received those items.
 * <p>
 * The publisher adds a mark, under its lock, with the queue's producer position (addedCount) at the drop: the dropped item would
 * have followed every item offered before it. The reader, on its own thread, takes a mark once its consumer position (removedCount)
 * reaches that point, that is, once every item offered before the drop has been dispatched. Bounded: past
 * {@link #CAPACITY} marks not yet taken, further drops are counted into the next taken mark instead.
 */
public final class DropMarks {

    public static final int CAPACITY = 1024;

    /** One drop: the queue's producer position (addedCount) at the drop, the feed sequence number, and why. */
    public record Mark(long position, long sequence, String reason) {
    }

    private final ConcurrentLinkedQueue<Mark> marks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong overflow = new AtomicLong();
    /**
     * Test hook: runs just before an overflowed drop is counted, so a test can drain the marks in that window and show
     * the count is still exported (external review of d9eec79, finding 5). Null in production.
     */
    volatile Runnable beforeOverflowCount;

    void add(long position, long sequence, String reason) {
        if (size.incrementAndGet() > CAPACITY) {
            size.decrementAndGet();
            Runnable hook = beforeOverflowCount;
            if (hook != null) {
                hook.run();
            }
            overflow.incrementAndGet();
            return;
        }
        marks.add(new Mark(position, sequence, reason));
    }

    /** The oldest mark not yet taken, or null. */
    public Mark peek() {
        return marks.peek();
    }

    /** Take the oldest mark. */
    public Mark poll() {
        Mark m = marks.poll();
        if (m != null) {
            size.decrementAndGet();
        }
        return m;
    }

    /**
     * Drops not marked individually because the marks were full; reset by the read. The reader takes it on every pass,
     * with or without a pending mark, so a count made after the last mark was taken is exported on the next pass.
     */
    public long takeOverflow() {
        return overflow.getAndSet(0);
    }
}
