/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.introspection;

import java.util.List;

/**
 * Dispatch topology for a single event feed, as reported by
 * {@link MongooseIntrospectionService#feedTopology()}. Captures every
 * agent-group consumer reading the feed and the processors that have
 * subscribed via each group.
 *
 * @param feed       event feed (source) name
 * @param consumers  list of agent-group consumers, in registration order
 */
public record FeedTopology(String feed, List<FeedConsumer> consumers) {
}
