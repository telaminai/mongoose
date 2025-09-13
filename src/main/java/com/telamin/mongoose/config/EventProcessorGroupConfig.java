/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.runtime.audit.EventLogControlEvent;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class for managing a group of event processors in the server.
 * This class provides configuration settings for event processing agents including
 * idle strategies, logging levels, and event handler mappings.
 */
@Data
public class EventProcessorGroupConfig {
    /**
     * Name identifier for the event processing agent
     */
    private String agentName;

    /**
     * Strategy for handling idle time between event processing
     */
    private IdleStrategy idleStrategy;

    /**
     * Logging level configuration for event processing
     */
    private EventLogControlEvent.LogLevel logLevel;

    /**
     * Map of event handler configurations keyed by handler name
     */
    private Map<String, EventProcessorConfig<?>> eventHandlers = new HashMap<>();

    /**
     * Constructs a new EventProcessorGroupConfig with the specified agent name
     *
     * @param agentName name identifier for the event processing agent
     */
    public EventProcessorGroupConfig(String agentName) {
        this.agentName = agentName;
        eventHandlers = new HashMap<>();
    }

    /**
     * Default constructor creating an empty configuration
     */
    public EventProcessorGroupConfig() {
        eventHandlers = new HashMap<>();
    }

    /**
     * Returns the map of event handler configurations, creating it if null
     *
     * @return map of event handler configurations keyed by handler name
     */
    public Map<String, EventProcessorConfig<?>> getEventHandlers() {
        if (eventHandlers == null) {
            eventHandlers = new HashMap<>();
        }
        return eventHandlers;
    }

    /**
     * Creates a new builder instance for fluent configuration
     *
     * @return new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing EventProcessorGroupConfig instances
     * using a fluent API
     */
    public static final class Builder {
        private String agentName;
        private IdleStrategy idleStrategy;
        private EventLogControlEvent.LogLevel logLevel;
        private Map<String, EventProcessorConfig<?>> eventHandlers = new HashMap<>();

        private Builder() {
        }

        /**
         * Sets the agent name
         *
         * @param agentName name identifier for the event processing agent
         * @return this builder instance
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * Sets the idle strategy
         *
         * @param idleStrategy strategy for handling idle time
         * @return this builder instance
         */
        public Builder idleStrategy(IdleStrategy idleStrategy) {
            this.idleStrategy = idleStrategy;
            return this;
        }

        /**
         * Sets the logging level
         *
         * @param logLevel desired logging level for events
         * @return this builder instance
         */
        public Builder logLevel(EventLogControlEvent.LogLevel logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        /**
         * Sets the map of event handlers
         *
         * @param handlers map of event handler configurations
         * @return this builder instance
         */
        public Builder eventHandlers(Map<String, EventProcessorConfig<?>> handlers) {
            this.eventHandlers = handlers;
            return this;
        }

        /**
         * Adds a single event handler configuration
         *
         * @param name name identifier for the handler
         * @param cfg  handler configuration
         * @return this builder instance
         */
        public Builder put(String name, EventProcessorConfig<?> cfg) {
            if (this.eventHandlers == null) this.eventHandlers = new HashMap<>();
            this.eventHandlers.put(name, cfg);
            return this;
        }

        /**
         * Builds and returns the configured EventProcessorGroupConfig instance
         *
         * @return new EventProcessorGroupConfig instance
         */
        public EventProcessorGroupConfig build() {
            EventProcessorGroupConfig cfg = new EventProcessorGroupConfig();
            cfg.setAgentName(agentName);
            cfg.setIdleStrategy(idleStrategy);
            cfg.setLogLevel(logLevel);
            cfg.setEventHandlers(eventHandlers);
            return cfg;
        }
    }
}
