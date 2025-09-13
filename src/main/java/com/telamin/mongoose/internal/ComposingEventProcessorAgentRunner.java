/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.fluxtion.agrona.concurrent.AgentRunner;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;

/**
 * Lightweight holder pairing a {@link ComposingEventProcessorAgent}
 * with its executing {@link com.fluxtion.agrona.concurrent.AgentRunner}.
 * <p>
 * Used by MongooseServer to track event processor agent groups and their runners
 * for lifecycle management (start/stop).
 *
 * @param group       the composing event processor agent group
 * @param groupRunner the agent runner executing the group
 */
public record ComposingEventProcessorAgentRunner(ComposingEventProcessorAgent group, AgentRunner groupRunner) {
}
