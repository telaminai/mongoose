/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.IdleStrategy;

/**
 * Lightweight holder pairing a {@link ComposingEventProcessorAgent}
 * with its executing {@link org.agrona.concurrent.AgentRunner} and the
 * {@link IdleStrategy} that drives it.
 * <p>
 * Used by MongooseServer to track event processor agent groups and their runners
 * for lifecycle management (start/stop) and introspection (admin UIs report the
 * configured idle strategy and the underlying thread state).
 *
 * @param group         the composing event processor agent group
 * @param groupRunner   the agent runner executing the group
 * @param idleStrategy  the idle strategy that drives this group's runner; retained
 *                      here because {@code AgentRunner} does not expose it
 */
public record ComposingEventProcessorAgentRunner(ComposingEventProcessorAgent group,
                                                 AgentRunner groupRunner,
                                                 IdleStrategy idleStrategy) {
}