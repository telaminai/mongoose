/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A feed's overload policy and its overrides per subscriber group (telaminai/mongoose#79). A subscriber group is the
 * processor group (agent) name that owns the subscribing queue; a queue whose group has no override uses
 * {@link #feedDefault()}.
 *
 * @param feedDefault       the feed's own policy
 * @param bySubscriberGroup overrides keyed by subscriber group name; never null
 * @param quietPeriodNanos  how long a drop-tolerant queue (DROP_AFTER_SPIN) stays DEGRADED after its last loss before
 *                          its health returns to UP; never-drop queues (BLOCK_WITH_DEADLINE) latch until an operator
 *                          clears them
 * @param killSwitchOnLoss  invoke the server's overload kill switch when a never-drop queue of this feed latches
 *                          (off by default)
 * @param explicit          false only for {@link #DEFAULT}, the feed that configured no policy: its queues keep the
 *                          health semantics of PR #76 (DEGRADED after a loss, no quiet-period recovery), as decision
 *                          D2 requires; any configured or programmatic setting is explicit
 */
public record FeedOverloadSettings(OverloadSettings feedDefault, Map<String, OverloadSettings> bySubscriberGroup,
                                   long quietPeriodNanos, boolean killSwitchOnLoss, boolean explicit) {

    /** Default quiet period before a drop-tolerant queue's health recovers. */
    public static final long DEFAULT_QUIET_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(60);

    /** The shortest quiet period, enforced here and at boot. */
    public static final long MIN_QUIET_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** Every queue on {@link OverloadSettings#DEFAULT}: the behaviour before #79. */
    public static final FeedOverloadSettings DEFAULT = new FeedOverloadSettings(OverloadSettings.DEFAULT, Map.of(),
            DEFAULT_QUIET_PERIOD_NANOS, false, false);

    public FeedOverloadSettings {
        Objects.requireNonNull(feedDefault, "feedDefault must be non-null");
        bySubscriberGroup = bySubscriberGroup == null ? Map.of() : Map.copyOf(bySubscriberGroup);
        if (quietPeriodNanos < MIN_QUIET_PERIOD_NANOS) {
            // a shorter period lets a flapping queue write two state records per health poll (review findings 9, 9)
            throw new IllegalArgumentException("quiet period must be at least 1000 ms, was "
                    + TimeUnit.NANOSECONDS.toMillis(quietPeriodNanos) + " ms");
        }
    }

    public FeedOverloadSettings(OverloadSettings feedDefault, Map<String, OverloadSettings> bySubscriberGroup) {
        this(feedDefault, bySubscriberGroup, DEFAULT_QUIET_PERIOD_NANOS, false, true);
    }

    public FeedOverloadSettings(OverloadSettings feedDefault, Map<String, OverloadSettings> bySubscriberGroup,
                                long quietPeriodNanos, boolean killSwitchOnLoss) {
        this(feedDefault, bySubscriberGroup, quietPeriodNanos, killSwitchOnLoss, true);
    }

    /** One policy for every subscriber of the feed. */
    public static FeedOverloadSettings of(OverloadSettings feedDefault) {
        return new FeedOverloadSettings(feedDefault, Map.of());
    }

    /** The settings for a queue owned by {@code subscriberGroup} (null: the feed default). */
    public OverloadSettings forGroup(String subscriberGroup) {
        if (subscriberGroup == null) {
            return feedDefault;
        }
        return bySubscriberGroup.getOrDefault(subscriberGroup, feedDefault);
    }

    /** A copy with {@code settings} for {@code subscriberGroup}. */
    public FeedOverloadSettings withSubscriberGroup(String subscriberGroup, OverloadSettings settings) {
        java.util.HashMap<String, OverloadSettings> copy = new java.util.HashMap<>(bySubscriberGroup);
        copy.put(Objects.requireNonNull(subscriberGroup, "subscriberGroup"), Objects.requireNonNull(settings, "settings"));
        return new FeedOverloadSettings(feedDefault, copy, quietPeriodNanos, killSwitchOnLoss);
    }

    /** A copy with this quiet period for drop-tolerant queues. */
    public FeedOverloadSettings withQuietPeriod(Duration quietPeriod) {
        return new FeedOverloadSettings(feedDefault, bySubscriberGroup, quietPeriod.toNanos(), killSwitchOnLoss);
    }

    /** A copy with the kill switch on or off. */
    public FeedOverloadSettings withKillSwitch(boolean killSwitchOnLoss) {
        return new FeedOverloadSettings(feedDefault, bySubscriberGroup, quietPeriodNanos, killSwitchOnLoss);
    }
}
