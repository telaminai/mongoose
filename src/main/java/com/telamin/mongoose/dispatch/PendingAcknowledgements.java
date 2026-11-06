/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

/**
 * The window of a feed's published but not yet acknowledged items (docs/design/feed-delivery.md, section 4): per item,
 * its sequence number, the source's position, and once it has been dispatched, each target queue's producer position
 * just after it was offered. The item is acknowledged when each of those queues has dispatched up to that position.
 * Entries are kept in publish order; positions only grow, so only the window between the last acknowledged item and the
 * last published one is held. An entry whose condition equals the one before it (the queues it reached did not change:
 * a drop, or a filtered item) is merged into it, so the window is bounded by what the queues actually hold, not by how
 * many items were dropped. Guarded by the owning publisher's lock.
 */
final class PendingAcknowledgements {

    private static final long[] NO_COUNTS = new long[0];
    private static final OneToOneConcurrentArrayQueue<?>[] NO_QUEUES = new OneToOneConcurrentArrayQueue<?>[0];

    private final int capacity;
    private long[] seqs = new long[64];
    private Object[] positions = new Object[64];
    private OneToOneConcurrentArrayQueue<?>[][] queues = new OneToOneConcurrentArrayQueue<?>[64][];
    private long[][] after = new long[64][];
    private int head;
    private int size;
    /** Entries before this offset from head are dispatched; dispatch arrives in publish order. */
    private int undispatchedFrom;

    PendingAcknowledgements(int capacity) {
        this.capacity = capacity;
    }

    int size() {
        return size;
    }

    boolean full() {
        return size >= capacity;
    }

    private int index(int offset) {
        return (head + offset) & (seqs.length - 1);
    }

    /** A published or cached item with a position, not yet dispatched. */
    void add(long seq, Object position) {
        ensureRoom();
        int i = index(size++);
        seqs[i] = seq;
        positions[i] = position;
        queues[i] = null;
        after[i] = null;
    }

    /** An item no queue will ever hold (filtered by the data mapper, or cached without an event log): due in order. */
    void addDone(long seq, Object position) {
        if (size > 0 && undispatchedFrom == size) {
            // the item before it is dispatched: this one is due exactly when it is
            int last = index(size - 1);
            seqs[last] = seq;
            positions[last] = position;
            return;
        }
        add(seq, position);
        markDispatched(size - 1, NO_QUEUES, NO_COUNTS);
    }

    /** Item {@code seq} was offered to {@code targets}; {@code counts[j]} is target j's producer position after it. */
    void dispatched(long seq, OneToOneConcurrentArrayQueue<?>[] targets, long[] counts) {
        // dispatch follows publish order: the entry is at or after the first undispatched one
        while (undispatchedFrom < size) {
            int offset = undispatchedFrom;
            int i = index(offset);
            if (queues[i] != null) {
                undispatchedFrom++;
                continue;
            }
            if (seqs[i] > seq) {
                return;          // an item published without a position: nothing to record
            }
            if (seqs[i] < seq) {
                // dispatch out of publish order: never expected (a nested publish is refused or deferred while a
                // positioned item commits). The earlier entry is left undispatched, so it holds acknowledgement
                // rather than being acknowledged before it reached any queue
                return;
            }
            markDispatched(offset, targets, counts);
            return;
        }
    }

    private void markDispatched(int offset, OneToOneConcurrentArrayQueue<?>[] targets, long[] counts) {
        int i = index(offset);
        if (offset > 0 && offset == size - 1) {
            int prev = index(offset - 1);
            if (queues[prev] != null && sameCondition(queues[prev], after[prev], targets, counts)) {
                // the same condition as the item before it: one entry, reporting the later position
                seqs[prev] = seqs[i];
                positions[prev] = positions[i];
                positions[i] = null;
                size--;
                undispatchedFrom = size;
                return;
            }
        }
        queues[i] = targets;
        after[i] = counts;
        advance();
    }

    /** Move the first-undispatched cursor past entries already dispatched (filtered entries recorded out of turn). */
    private void advance() {
        while (undispatchedFrom < size && queues[index(undispatchedFrom)] != null) undispatchedFrom++;
    }

    private static boolean sameCondition(OneToOneConcurrentArrayQueue<?>[] q1, long[] c1,
                                         OneToOneConcurrentArrayQueue<?>[] q2, long[] c2) {
        if (q2.length == 0) return true;      // no condition of its own: due with the item before it
        if (q1 != q2) return false;
        return java.util.Arrays.equals(c1, c2);
    }

    /** The oldest entry has been dispatched: its condition is known. */
    boolean headDispatched() {
        return size > 0 && undispatchedFrom > 0;
    }

    long headSeq() {
        return seqs[head];
    }

    Object headPosition() {
        return positions[head];
    }

    OneToOneConcurrentArrayQueue<?>[] headQueues() {
        return queues[head];
    }

    long[] headCounts() {
        return after[head];
    }

    /**
     * Whether a dispatched entry still waits for {@code queue} to process beyond {@code processed}: its condition for
     * that queue is greater. O(window); used only when a consumer is removed and while a removed one is retained.
     */
    boolean waitsFor(OneToOneConcurrentArrayQueue<?> queue, long processed) {
        for (int k = 0; k < undispatchedFrom; k++) {
            int i = index(k);
            OneToOneConcurrentArrayQueue<?>[] q = queues[i];
            if (q == null) continue;
            long[] c = after[i];
            for (int j = 0; j < q.length; j++) {
                if (q[j] == queue && c[j] > processed) return true;
            }
        }
        return false;
    }

    void removeHead() {
        positions[head] = null;
        queues[head] = null;
        after[head] = null;
        head = (head + 1) & (seqs.length - 1);
        size--;
        if (undispatchedFrom > 0) undispatchedFrom--;
        advance();
    }

    private void ensureRoom() {
        if (size < seqs.length) return;
        int n = seqs.length * 2;
        long[] s = new long[n];
        Object[] p = new Object[n];
        OneToOneConcurrentArrayQueue<?>[][] q = new OneToOneConcurrentArrayQueue<?>[n][];
        long[][] a = new long[n][];
        for (int k = 0; k < size; k++) {
            int i = index(k);
            s[k] = seqs[i];
            p[k] = positions[i];
            q[k] = queues[i];
            a[k] = after[i];
        }
        seqs = s;
        positions = p;
        queues = q;
        after = a;
        head = 0;
    }
}
