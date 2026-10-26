/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * One resolved overload policy for a queue binding (telaminai/mongoose#79).
 *
 * @param policy             what to do on a full queue
 * @param spinBoundNanos     how long to spin on a full queue before dropping ({@link OverloadPolicy#DROP_AFTER_SPIN}, or
 *                           a downgraded {@link OverloadPolicy#BLOCK_WITH_DEADLINE}); also the busy-spin part of a
 *                           blocking wait before it parks
 * @param blockDeadlineNanos how long a {@link OverloadPolicy#BLOCK_WITH_DEADLINE} publish waits for room before it is
 *                           refused; zero for {@link OverloadPolicy#DROP_AFTER_SPIN}
 */
public record OverloadSettings(OverloadPolicy policy, long spinBoundNanos, long blockDeadlineNanos) {

    /** The spin bound every release before #79 used, and the default. */
    public static final long DEFAULT_SPIN_BOUND_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    /** Today's behaviour: spin 10 ms, then drop for that queue. */
    public static final OverloadSettings DEFAULT =
            new OverloadSettings(OverloadPolicy.DROP_AFTER_SPIN, DEFAULT_SPIN_BOUND_NANOS, 0);

    public OverloadSettings {
        Objects.requireNonNull(policy, "policy must be non-null");
        if (spinBoundNanos < 0) {
            throw new IllegalArgumentException("spin bound must not be negative: " + spinBoundNanos + "ns");
        }
        if (policy == OverloadPolicy.BLOCK_WITH_DEADLINE && blockDeadlineNanos <= 0) {
            throw new IllegalArgumentException("BLOCK_WITH_DEADLINE needs a positive block deadline, was "
                    + blockDeadlineNanos + "ns");
        }
        if (policy == OverloadPolicy.DROP_AFTER_SPIN && blockDeadlineNanos != 0) {
            throw new IllegalArgumentException("a block deadline applies only to BLOCK_WITH_DEADLINE");
        }
    }

    /** Spin for {@code spinBound}, then drop for that queue. */
    public static OverloadSettings dropAfterSpin(Duration spinBound) {
        return new OverloadSettings(OverloadPolicy.DROP_AFTER_SPIN, spinBound.toNanos(), 0);
    }

    /** Wait up to {@code deadline} for room, then refuse; spins for the default bound before parking. */
    public static OverloadSettings blockWithDeadline(Duration deadline) {
        return new OverloadSettings(OverloadPolicy.BLOCK_WITH_DEADLINE, DEFAULT_SPIN_BOUND_NANOS, deadline.toNanos());
    }

    /** True when a publish may wait for room for a queue under these settings. */
    public boolean blocks() {
        return policy == OverloadPolicy.BLOCK_WITH_DEADLINE;
    }
}
