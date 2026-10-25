/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import java.util.Map;
import java.util.Objects;

/**
 * A feed's overload policy and its overrides per subscriber group (telaminai/mongoose#79). A subscriber group is the
 * processor group (agent) name that owns the subscribing queue; a queue whose group has no override uses
 * {@link #feedDefault()}.
 *
 * @param feedDefault        the feed's own policy
 * @param bySubscriberGroup  overrides keyed by subscriber group name; never null
 */
public record FeedOverloadSettings(OverloadSettings feedDefault, Map<String, OverloadSettings> bySubscriberGroup) {

    /** Every queue on {@link OverloadSettings#DEFAULT}: the behaviour before #79. */
    public static final FeedOverloadSettings DEFAULT = new FeedOverloadSettings(OverloadSettings.DEFAULT, Map.of());

    public FeedOverloadSettings {
        Objects.requireNonNull(feedDefault, "feedDefault must be non-null");
        bySubscriberGroup = bySubscriberGroup == null ? Map.of() : Map.copyOf(bySubscriberGroup);
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
        return new FeedOverloadSettings(feedDefault, copy);
    }
}
