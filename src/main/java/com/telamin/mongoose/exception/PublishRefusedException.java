/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.exception;

/**
 * A {@link com.telamin.mongoose.dispatch.OverloadPolicy#BLOCK_WITH_DEADLINE BLOCK_WITH_DEADLINE} publish was refused
 * (telaminai/mongoose#79): a queue under that policy had no room by the deadline, or the server began to stop while the
 * publish waited.
 * <p>
 * <b>The refusal is all-or-nothing.</b> No queue of the feed holds the item, no sequence number was consumed and no
 * journal entry was written, so a caller that retries does not duplicate it.
 * <p>
 * <b>Pooled items.</b> The publisher has released the reference that was handed over with the item, so a pooled item
 * has gone back to its pool, and been reset by it, before this is thrown: the caller's instance no longer holds its
 * payload. A caller that may retry must <b>copy the payload before publishing</b>, then on refusal acquire a fresh
 * instance and fill it from that copy. It must never publish the refused instance again (its tracker would report
 * "already returned", or, if the pool has reissued it, the retry would corrupt the new holder's item).
 * {@link #item()} is a description taken before the release, never the instance.
 */
public class PublishRefusedException extends FluxtionServerException {

    /** Why the publish was refused. */
    public enum Reason {
        /** A queue under BLOCK_WITH_DEADLINE still had no room when the deadline passed. */
        DEADLINE,
        /** The server began to stop (the feed's publisher was closed) while the publish waited, or before it. */
        SHUTDOWN,
        /** The publishing thread was interrupted while it waited; its interrupt status is left set. */
        INTERRUPTED
    }

    private final String feed;
    private final String queue;
    private final Reason reason;
    private final long waitedNanos;
    private final String item;

    public PublishRefusedException(String feed, String queue, Reason reason, long waitedNanos, String item) {
        super("publish refused on feed '" + feed + "': queue '" + queue + "' " + (reason == Reason.DEADLINE
                ? "had no room after " + (waitedNanos / 1_000_000) + " ms"
                : reason == Reason.SHUTDOWN ? "is closing (server stopping)" : "wait was interrupted") + "; the item was delivered to no queue: " + item);
        this.feed = feed;
        this.queue = queue;
        this.reason = reason;
        this.waitedNanos = waitedNanos;
        this.item = item;
    }

    public String feed() {
        return feed;
    }

    /** The first blocking queue found without room. */
    public String queue() {
        return queue;
    }

    public Reason reason() {
        return reason;
    }

    public long waitedNanos() {
        return waitedNanos;
    }

    /** {@code String.valueOf} of the refused (mapped) item, taken before its pooled reference was released. */
    public String item() {
        return item;
    }
}
