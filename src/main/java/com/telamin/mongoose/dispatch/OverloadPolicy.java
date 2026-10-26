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
     * {@link #DROP_AFTER_SPIN} with the same spin bound, and the downgrade is counted.
     */
    BLOCK_WITH_DEADLINE
}
