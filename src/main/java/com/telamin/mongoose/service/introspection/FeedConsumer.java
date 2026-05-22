/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.introspection;

import java.util.List;

/**
 * A single agent-group consumer of an event feed, as reported by
 * {@link MongooseIntrospectionService#feedTopology()}.
 *
 * @param agentGroup   agent group name reading the feed
 * @param callback     callback-type label for this subscription (e.g.
 *                     {@code "onEventCallBack"})
 * @param queuePath    fully qualified queue path
 *                     ({@code agentGroup/feed/callback}) — useful for
 *                     diagnostics + log correlation
 * @param processors   processor names within {@code agentGroup} that are
 *                     subscribed via this queue. Inferred best-effort
 *                     from the per-processor subscriptions snapshot — when
 *                     that data is unavailable, falls back to the group's
 *                     full membership (i.e. group fanout)
 */
public record FeedConsumer(String agentGroup, String callback, String queuePath, List<String> processors) {
}
