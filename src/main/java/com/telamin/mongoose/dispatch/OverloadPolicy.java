/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

/**
 * What a feed's publisher does when a subscriber's queue is full (telaminai/mongoose#79). Set per feed, with an optional
 * override per (feed, subscriber group); see {@link OverloadSettings} and {@code docs/design/overload-policy.md}.
 */
public enum OverloadPolicy {
    /**
     * Spin for the spin bound (10 ms by default) holding the publish lock, then drop the item for that queue only,
     * count it and carry on with the next queue. The default, and the behaviour of every release before #79.
     */
    DROP_AFTER_SPIN,
    /**
     * Before the item is committed (no sequence number, no journal entry, no queue holds it), wait up to the deadline,
     * without holding the publish lock, until every queue under this policy has room; then deliver to all of them, or
     * refuse the publish with {@link com.telamin.mongoose.exception.PublishRefusedException} and deliver to none. Only a
     * non-agent thread waits: on an agent thread, or a publish nested inside another, the queue falls back to
     * {@link #DROP_AFTER_SPIN} with the same spin bound, and the downgrade is counted. A re-readable source that
     * publishes with {@link EventToQueuePublisher#tryPublish} pauses instead of downgrading (see {@link #PAUSE}).
     */
    BLOCK_WITH_DEADLINE,
    /**
     * Never drop, never wait: when a queue under this policy has no room, a re-readable source's
     * {@link EventToQueuePublisher#tryPublish} commits nothing and returns false; the source keeps the item and its read
     * position and tries again on its next duty cycle (docs/design/feed-delivery.md). Safe on an agent thread: nothing
     * blocks, so there is no deadlock and no head-of-line wait on the source's agent. The default for
     * {@link com.telamin.mongoose.connector.file.FileEventSource} when its feed configures no policy. A plain
     * {@code publish} (a push source that cannot pause) into such a queue falls back to {@link #DROP_AFTER_SPIN} with
     * the spin bound; the drop is counted and the fallback counted as a downgrade.
     */
    PAUSE
}
