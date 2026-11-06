/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.delivery;

/**
 * Where the event a processor is handling came from (docs/design/feed-delivery.md, section 5a).
 *
 * @param feed     the feed name
 * @param sequence the feed's publish sequence number in this run (restarts at 1 with the server: never use it to
 *                 de-duplicate across a restart)
 * @param epoch    the source's epoch: identifies the position space. A {@code FileEventSource} creates a new epoch with
 *                 its read pointer and whenever it resets it (pointer file deleted, the input file replaced or
 *                 truncated); positions of different epochs are unrelated
 * @param position the source's position after the item, stable across restarts and increasing within an epoch
 *                 (a {@code FileEventSource}: the {@code Long} byte offset just after the line). Store
 *                 {@code (epoch, position)} atomically with the state the event changed; after a restart an event is
 *                 skipped only when {@link #alreadyApplied} says so
 */
public record Delivery(String feed, long sequence, long epoch, Object position) {

    /** {@code position} as a long, for sources whose position is a number (files, Chronicle indices). */
    public long positionAsLong() {
        return ((Number) position).longValue();
    }

    /**
     * True when the event was already applied to state saved with {@code (storedEpoch, storedPosition)}: the same epoch
     * and this position at or before the stored one. A different epoch is never already applied (the source was reset
     * or replaced: its positions say nothing about the stored state). Positions must be comparable.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean alreadyApplied(long storedEpoch, Object storedPosition) {
        if (storedPosition == null || position == null || storedEpoch != epoch) return false;
        if (position instanceof Number p && storedPosition instanceof Number s) {
            return p.longValue() <= s.longValue();
        }
        return ((Comparable) position).compareTo(storedPosition) <= 0;
    }
}
