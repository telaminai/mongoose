/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.delivery;

/**
 * How a source learns that items it published with a position have been processed, so it may commit the position
 * (docs/design/feed-delivery.md). Registered with {@code EventToQueuePublisher.acknowledgeWith}; called only from
 * {@code EventToQueuePublisher.pollAcknowledgements}, which the source calls on its own thread (its agent's duty
 * cycle), so a committer needs no locking against the source's reads.
 * <p>
 * Contract:
 * <ul>
 *     <li>{@link #acknowledged} is called in publish order. An item is acknowledged once every subscriber queue the
 *     feed delivered it to has dispatched it (its event cycle finished, or it was skipped after a failure) and every
 *     {@link ProcessingCheckpoint} held on those queues is durable through it.</li>
 *     <li>Acknowledgements may be coalesced: of a run of items whose acknowledgement became due together, only the
 *     last may be reported. A source whose positions are totally ordered (a file offset, a Chronicle index) commits the
 *     last; a source with several independent positions (Kafka partitions) commits what it was told, and an item it was
 *     not told about is re-delivered after a crash, never lost.</li>
 *     <li>{@link #commit} follows each run of acknowledgements: the moment to write the position durably (a file
 *     pointer, {@code commitSync}, {@code Message.acknowledge}).</li>
 *     <li>An item dropped for a queue (a DROP_AFTER_SPIN queue that was full) does not hold acknowledgement back for
 *     that queue: it is a loss, counted and reported by {@code feed.status}, and committing past it is what a drop
 *     means.</li>
 * </ul>
 *
 * @param <P> the source's position type: a byte offset, a partition and offset, a message to acknowledge
 */
public interface SourceCommitter<P> {

    /**
     * The item published at {@code sequence} with {@code position}, and every item published before it, has been
     * processed.
     */
    void acknowledged(long sequence, P position);

    /** A run of {@link #acknowledged} calls is complete: commit the last acknowledged position durably. */
    default void commit() {
    }

    /**
     * True when this committer may also be called after its source has stopped, on the server's stop thread, once the
     * processor groups have drained their queues and closed: the final commit of a clean stop
     * (docs/design/feed-delivery.md section 4). The ordered shutdown stops the feeds before the groups drain, so without
     * it the items the groups finish after the source stopped are re-delivered at the next start (never lost). Return
     * true only if {@link #acknowledged} and {@link #commit} are safe from another thread once the source's own thread
     * has stopped (a file pointer: yes; a consumer that was closed at stop: no).
     */
    default boolean commitsAfterStop() {
        return false;
    }
}
