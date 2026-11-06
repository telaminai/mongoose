/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.delivery;

/**
 * A processor's hold on the acknowledgement of one feed's items (docs/design/feed-delivery.md, section 5). By default
 * an item is acknowledged to its source, which may then commit its read position, once the processor's event cycle for
 * it has finished. A processor whose state is persisted asynchronously or in batches holds acknowledgements back until
 * its state is durable, so a source never commits past state that a crash would lose:
 * <pre>{@code
 * // on the processor's thread, when taking a snapshot of state:
 * ProcessingCheckpoint.Mark mark = checkpoint.mark();
 * saveAsync(state).thenRun(() -> checkpoint.durableThrough(mark));   // any thread
 * }</pre>
 * While held, nothing on this processor group's queue for the feed is acknowledged beyond the last
 * {@link #durableThrough} mark. Holds are per (feed, processor group): the group's one queue for the feed. A mark
 * belongs to the queue it was taken on: after an unsubscribe and a resubscribe (a new queue) the new queue starts
 * held at nothing durable, and a mark of the old queue releases nothing on it.
 */
public interface ProcessingCheckpoint {

    /**
     * How far a queue had dispatched when the mark was taken.
     *
     * @param binding which binding of the group's queue the mark belongs to (changes on each resubscribe); -1: none
     * @param count   items that queue had finished dispatching
     */
    record Mark(long binding, long count) {
    }

    /** The feed this checkpoint holds. */
    String feedName();

    /** The processor group (agent name) whose queue it holds. */
    String processorGroup();

    /**
     * The items this group's queue for the feed has finished dispatching so far. Call on the processor's thread when
     * capturing state: the state then includes at least every item the mark covers. Conservative inside an event
     * handler: the item being handled is not counted, so it is re-delivered after a crash rather than lost.
     */
    Mark mark();

    /**
     * State is durable through {@code mark}: items it covers may be acknowledged. Any thread; marks only advance. A mark
     * of a binding that is no longer current is ignored.
     */
    void durableThrough(Mark mark);

    /** Stop holding: acknowledgement follows dispatch again. */
    void release();
}
