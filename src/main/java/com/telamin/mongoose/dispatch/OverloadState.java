/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

/**
 * The loss state of one subscriber queue of a feed (telaminai/mongoose#79, decision D4). Every change is logged once and
 * written as an {@code OverloadState} audit record; see {@code docs/design/overload-policy.md} §8.
 */
public enum OverloadState {
    /** No loss, or a drop-tolerant queue whose quiet period has passed since its last loss, or cleared by an operator. */
    UP,
    /** A drop-tolerant (DROP_AFTER_SPIN) queue lost items; it returns to UP after the feed's quiet period. */
    DEGRADED,
    /**
     * A never-drop (BLOCK_WITH_DEADLINE) queue lost or refused an item: a refusal, a downgraded drop or a dropped cache
     * replay. It stays here until an operator clears it ({@code overload.clear} or {@code clearOverloadDegraded}).
     */
    LATCHED
}
