/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.runtime.service.Service;

/**
 * Describes a service that is hosted on an Agrona Agent thread and managed by the server.
 * This is a lightweight holder tying together the agent group name, the agent's idle strategy,
 * the exported Service instance, and the Agent delegate that performs the work.
 *
 * @param <T> type of the exported service instance
 * @param agentGroup      unique identifier for the agent group this service belongs to
 * @param idleStrategy    idle strategy for the agent thread
 * @param exportedService the exported service proxy to register with the service registry
 * @param delegate        the underlying Agent implementation to be added to the composite agent
 */
public record ServiceAgent<T>(String agentGroup, IdleStrategy idleStrategy, Service<T> exportedService,
                             Agent delegate) {

}
