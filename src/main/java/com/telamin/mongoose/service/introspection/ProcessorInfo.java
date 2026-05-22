/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.introspection;

import java.util.List;

/**
 * Per-processor snapshot inside an {@link AgentGroupSnapshot}. Returned by
 * {@link MongooseIntrospectionService#agentGroups()}.
 *
 * @param name          processor name (unique within the agent group)
 * @param className     fully qualified class name of the running
 *                      {@code DataFlow}, or {@code null} when not available
 * @param subscriptions feed subscriptions currently held by this processor;
 *                      empty list when the processor has none
 */
public record ProcessorInfo(String name, String className, List<SubscriptionInfo> subscriptions) {
}
