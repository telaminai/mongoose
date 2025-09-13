/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.agrona.concurrent.YieldingIdleStrategy;
import lombok.Data;

/**
 * Configuration for an agent thread in the Fluxtion server.
 * This class encapsulates settings that control how agent threads behave, including:
 * <ul>
 *   <li>Thread naming for identification and monitoring</li>
 *   <li>Idle strategy for managing thread behavior during quiet periods</li>
 *   <li>CPU core affinity for optimizing performance</li>
 * </ul>
 */
@Data
public class ThreadConfig {
    /**
     * Name assigned to the agent thread for identification purposes
     */
    private String agentName;
    /**
     * Strategy determining thread behavior when no work is available. Defaults to yielding
     */
    private IdleStrategy idleStrategy = new YieldingIdleStrategy();
    /**
     * Optional zero-based CPU core index to pin the agent thread to for improved performance
     */
    private Integer coreId;

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for creating ThreadConfig instances with a fluent API.
     * Allows selective setting of configuration properties with method chaining.
     */
    public static final class Builder {
        private String agentName;
        private IdleStrategy idleStrategy;
        private Integer coreId;

        private Builder() {
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder idleStrategy(IdleStrategy idleStrategy) {
            this.idleStrategy = idleStrategy;
            return this;
        }

        /**
         * Set zero-based CPU core index to pin the agent thread to.
         */
        public Builder coreId(Integer coreId) {
            this.coreId = coreId;
            return this;
        }

        public ThreadConfig build() {
            ThreadConfig cfg = new ThreadConfig();
            cfg.setAgentName(agentName);
            if (idleStrategy != null) cfg.setIdleStrategy(idleStrategy);
            if (coreId != null) cfg.setCoreId(coreId);
            return cfg;
        }
    }
}
