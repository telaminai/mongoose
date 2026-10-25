/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.mongoose.dispatch.FeedOverloadSettings;
import com.telamin.mongoose.dispatch.OverloadPolicy;
import com.telamin.mongoose.dispatch.OverloadSettings;
import com.telamin.mongoose.exception.ConfigurationException;
import com.telamin.mongoose.service.EventSource;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The {@code overload} block of an event feed or handler pipe (telaminai/mongoose#79): what the feed's publisher does
 * when a subscriber's queue is full, with optional overrides per subscriber group. YAML:
 * <pre>{@code
 * eventFeeds:
 *   - name: orders
 *     overload:
 *       policy: BLOCK_WITH_DEADLINE
 *       blockDeadlineMillis: 250
 *       subscribers:                 # keyed by processor group (agent) name
 *         telemetryGroup:
 *           policy: DROP_AFTER_SPIN
 *           spinBoundMillis: 0
 * }</pre>
 * Programmatic: {@code OverloadPolicyConfig.blockWithDeadline(Duration.ofMillis(250)).subscriber("telemetryGroup",
 * OverloadPolicyConfig.dropAfterSpin(Duration.ZERO))}. See {@code docs/design/overload-policy.md}.
 */
@Data
@NoArgsConstructor
public class OverloadPolicyConfig {

    private static final Logger LOG = Logger.getLogger(OverloadPolicyConfig.class.getName());
    /** Above this a block deadline is warned at boot (review finding 7): it behaves like the deferred unbounded BLOCK. */
    public static final long MAX_RECOMMENDED_DEADLINE_MILLIS = 60_000;

    /** Required in an {@code overload} block. */
    private OverloadPolicy policy;
    /** Required for, and only for, {@link OverloadPolicy#BLOCK_WITH_DEADLINE}; must be positive. */
    private Long blockDeadlineMillis;
    /** Spin before dropping (or, when blocking, before parking); default 10, may be 0. */
    private Long spinBoundMillis;
    /** Overrides keyed by subscriber (processor) group name; an override may not nest further overrides. */
    private Map<String, OverloadPolicyConfig> subscribers;
    /**
     * Feed level only: how long a drop-tolerant queue stays DEGRADED after its last loss (default 60000). Never-drop
     * queues latch until an operator clears them.
     */
    private Long quietPeriodMillis;
    /** Feed level only: call the server's overload kill switch when a never-drop queue latches (default false). */
    private Boolean killSwitch;

    public static OverloadPolicyConfig dropAfterSpin(Duration spinBound) {
        OverloadPolicyConfig cfg = new OverloadPolicyConfig();
        cfg.setPolicy(OverloadPolicy.DROP_AFTER_SPIN);
        cfg.setSpinBoundMillis(spinBound.toMillis());
        return cfg;
    }

    public static OverloadPolicyConfig blockWithDeadline(Duration deadline) {
        OverloadPolicyConfig cfg = new OverloadPolicyConfig();
        cfg.setPolicy(OverloadPolicy.BLOCK_WITH_DEADLINE);
        cfg.setBlockDeadlineMillis(deadline.toMillis());
        return cfg;
    }

    /** Add an override for the queues owned by {@code subscriberGroup}. */
    public OverloadPolicyConfig subscriber(String subscriberGroup, OverloadPolicyConfig override) {
        if (subscribers == null) {
            subscribers = new LinkedHashMap<>();
        }
        subscribers.put(subscriberGroup, override);
        return this;
    }

    /** Validates this block for {@code feed}; a bad value fails boot naming the feed. */
    public FeedOverloadSettings toFeedSettings(String feed) {
        OverloadSettings feedDefault = toSettings(feed, "overload");
        Map<String, OverloadSettings> overrides = new LinkedHashMap<>();
        if (subscribers != null) {
            for (Map.Entry<String, OverloadPolicyConfig> e : subscribers.entrySet()) {
                String where = "overload.subscribers." + e.getKey();
                if (e.getKey() == null || e.getKey().isBlank()) {
                    throw new ConfigurationException("feed '" + feed + "': overload.subscribers has a blank group name");
                }
                if (e.getValue() == null) {
                    throw new ConfigurationException("feed '" + feed + "': " + where + " is empty");
                }
                if (e.getValue().getSubscribers() != null && !e.getValue().getSubscribers().isEmpty()) {
                    throw new ConfigurationException("feed '" + feed + "': " + where + " may not nest subscribers");
                }
                if (e.getValue().getQuietPeriodMillis() != null || e.getValue().getKillSwitch() != null) {
                    throw new ConfigurationException("feed '" + feed + "': " + where
                            + " may not set quietPeriodMillis or killSwitch (feed level only)");
                }
                overrides.put(e.getKey(), e.getValue().toSettings(feed, where));
            }
        }
        long quietNanos = FeedOverloadSettings.DEFAULT_QUIET_PERIOD_NANOS;
        if (quietPeriodMillis != null) {
            if (quietPeriodMillis < 0) {
                throw new ConfigurationException("feed '" + feed + "': overload.quietPeriodMillis must be >= 0, was "
                        + quietPeriodMillis);
            }
            quietNanos = TimeUnit.MILLISECONDS.toNanos(quietPeriodMillis);
        }
        return new FeedOverloadSettings(feedDefault, overrides, quietNanos, Boolean.TRUE.equals(killSwitch));
    }

    /** Feed level: the quiet period before a drop-tolerant queue's health recovers. */
    public OverloadPolicyConfig quietPeriod(Duration quietPeriod) {
        this.quietPeriodMillis = quietPeriod.toMillis();
        return this;
    }

    /** Feed level: call the server's overload kill switch when a never-drop queue latches. */
    public OverloadPolicyConfig killSwitch(boolean on) {
        this.killSwitch = on;
        return this;
    }

    private OverloadSettings toSettings(String feed, String where) {
        if (policy == null) {
            throw new ConfigurationException("feed '" + feed + "': " + where + ".policy is required, one of "
                    + java.util.Arrays.toString(OverloadPolicy.values()));
        }
        long spinNanos = OverloadSettings.DEFAULT_SPIN_BOUND_NANOS;
        if (spinBoundMillis != null) {
            if (spinBoundMillis < 0) {
                throw new ConfigurationException("feed '" + feed + "': " + where + ".spinBoundMillis must be >= 0, was "
                        + spinBoundMillis);
            }
            spinNanos = TimeUnit.MILLISECONDS.toNanos(spinBoundMillis);
        }
        return switch (policy) {
            case DROP_AFTER_SPIN -> {
                if (blockDeadlineMillis != null) {
                    throw new ConfigurationException("feed '" + feed + "': " + where
                            + ".blockDeadlineMillis applies only to BLOCK_WITH_DEADLINE");
                }
                yield new OverloadSettings(OverloadPolicy.DROP_AFTER_SPIN, spinNanos, 0);
            }
            case BLOCK_WITH_DEADLINE -> {
                if (blockDeadlineMillis == null || blockDeadlineMillis <= 0) {
                    throw new ConfigurationException("feed '" + feed + "': " + where
                            + ".blockDeadlineMillis is required for BLOCK_WITH_DEADLINE and must be > 0, was "
                            + blockDeadlineMillis);
                }
                if (blockDeadlineMillis > MAX_RECOMMENDED_DEADLINE_MILLIS) {
                    LOG.warning("feed '" + feed + "': " + where + ".blockDeadlineMillis " + blockDeadlineMillis
                            + " exceeds " + MAX_RECOMMENDED_DEADLINE_MILLIS + " ms: a wedged subscriber holds the "
                            + "publisher that long (unsubscribe re-evaluates, only shutdown cancels)");
                }
                yield new OverloadSettings(OverloadPolicy.BLOCK_WITH_DEADLINE, spinNanos,
                        TimeUnit.MILLISECONDS.toNanos(blockDeadlineMillis));
            }
        };
    }

    /**
     * Resolves a configured feed's policy at boot (working decisions 2 and 3 of #79):
     * <ul>
     *     <li>an {@code overload} block wins; an explicit {@code slowConsumerStrategy} beside it is ignored, with a
     *     warning;</li>
     *     <li>else an explicit {@code slowConsumerStrategy: BACKOFF} maps to {@code DROP_AFTER_SPIN} (what it has always
     *     done), with a warning; {@code DISCONNECT} and {@code EXIT_PROCESS} fail boot, naming the feed, until they are
     *     implemented;</li>
     *     <li>else the feed gets {@link FeedOverloadSettings#DEFAULT}, with a one-time boot warning that it has no
     *     explicit policy.</li>
     * </ul>
     */
    @SuppressWarnings("deprecation")
    public static FeedOverloadSettings resolve(String feed, OverloadPolicyConfig overload,
                                               EventSource.SlowConsumerStrategy slowConsumerStrategy,
                                               boolean slowConsumerStrategyExplicit) {
        if (overload != null) {
            if (slowConsumerStrategyExplicit) {
                LOG.warning("feed '" + feed + "': slowConsumerStrategy " + slowConsumerStrategy
                        + " is ignored, the overload block sets the policy");
            }
            return overload.toFeedSettings(feed);
        }
        if (slowConsumerStrategyExplicit && slowConsumerStrategy != null) {
            switch (slowConsumerStrategy) {
                case BACKOFF -> {
                    LOG.warning("feed '" + feed + "': slowConsumerStrategy BACKOFF is deprecated and means "
                            + "DROP_AFTER_SPIN (spin 10 ms, then drop for that queue); set overload.policy explicitly");
                    return FeedOverloadSettings.DEFAULT;
                }
                case DISCONNECT, EXIT_PROCESS -> throw new ConfigurationException("feed '" + feed
                        + "': slowConsumerStrategy " + slowConsumerStrategy + " is not implemented; use an overload "
                        + "block (DROP_AFTER_SPIN or BLOCK_WITH_DEADLINE)");
            }
        }
        LOG.warning("feed '" + feed + "' has no explicit overload policy: using DROP_AFTER_SPIN (spin 10 ms, then "
                + "drop for that queue). Set overload.policy on the feed to make the choice explicit");
        return FeedOverloadSettings.DEFAULT;
    }
}
