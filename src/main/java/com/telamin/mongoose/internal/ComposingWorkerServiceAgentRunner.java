/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.dutycycle.ComposingServiceAgent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.IdleStrategy;

/**
 * Lightweight holder pairing a {@link ComposingServiceAgent}
 * with its executing {@link org.agrona.concurrent.AgentRunner} and the
 * {@link IdleStrategy} that drives it.
 * <p>
 * Used by MongooseServer to track worker service agent groups and their runners
 * for lifecycle management (start/stop) and introspection.
 *
 * @param group         the composing worker service agent group
 * @param groupRunner   the agent runner executing the group
 * @param idleStrategy  the idle strategy that drives this group's runner; retained
 *                      here because {@code AgentRunner} does not expose it
 */
public record ComposingWorkerServiceAgentRunner(ComposingServiceAgent group,
                                                AgentRunner groupRunner,
                                                IdleStrategy idleStrategy) {
}