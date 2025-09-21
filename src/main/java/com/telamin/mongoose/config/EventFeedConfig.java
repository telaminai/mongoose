/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.input.NamedFeed;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.dutycycle.ServiceAgent;
import com.telamin.mongoose.service.EventSource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import java.util.function.Function;

/**
 * Configuration class for event feeds in the server.
 * Supports configuration of event sources, wrapping strategies, consumer handling,
 * and optional agent-based execution.
 *
 * @param <IN> The input type for the event feed
 */
@Experimental
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EventFeedConfig<IN> {

    /**
     * The event source instance
     */
    private Object instance;

    /**
     * Name identifier for this feed
     */
    private String name;

    /**
     * Whether events should be broadcast to all subscribers
     */
    private boolean broadcast = false;

    /**
     * Whether events should be wrapped with name information
     */
    private boolean wrapWithNamedEvent = false;

    /**
     * Strategy for wrapping events before delivery
     */
    private EventSource.EventWrapStrategy eventWrapStrategy = EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT;

    /**
     * Strategy for handling slow consumers
     */
    private EventSource.SlowConsumerStrategy slowConsumerStrategy = EventSource.SlowConsumerStrategy.BACKOFF;

    /**
     * Function to transform event values before delivery
     */
    private Function<IN, ?> valueMapper = Function.identity();

    /**
     * Name of the agent if using agent-based execution
     */
    private String agentName;

    /**
     * Idle strategy for agent-based execution
     */
    private IdleStrategy idleStrategy;

    /**
     * Checks if this feed is configured for agent-based execution
     *
     * @return true if agent execution is configured
     */
    public boolean isAgent() {
        return agentName != null;
    }

    /**
     * Converts this configuration to a Service instance
     *
     * @return Service wrapper around the configured feed
     */
    @SneakyThrows
    public Service<NamedFeed> toService() {
        if (instance instanceof EventSource<?> eventSource) {
            if (wrapWithNamedEvent & broadcast) {
                eventWrapStrategy = EventSource.EventWrapStrategy.BROADCAST_NAMED_EVENT;
            } else if (!wrapWithNamedEvent & broadcast) {
                eventWrapStrategy = EventSource.EventWrapStrategy.BROADCAST_NOWRAP;
            } else if (wrapWithNamedEvent & !broadcast) {
                eventWrapStrategy = EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT;
            } else {
                eventWrapStrategy = EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP;
            }
            @SuppressWarnings("unchecked") EventSource<IN> eventSource_t = (EventSource<IN>) eventSource;
            eventSource_t.setEventWrapStrategy(eventWrapStrategy);
            eventSource_t.setSlowConsumerStrategy(slowConsumerStrategy);
            eventSource_t.setDataMapper(valueMapper);
        }
        Service<NamedFeed> svc = new Service<>((NamedFeed) instance, NamedFeed.class, name);
        return svc;
    }

    /**
     * Converts this configuration to a ServiceAgent instance for agent-based execution
     *
     * @return ServiceAgent wrapper around the configured feed
     * @throws IllegalArgumentException if instance is not an Agent
     */
    @SneakyThrows
    public ServiceAgent<NamedFeed> toServiceAgent() {
        Service<NamedFeed> svc = toService();
        if (!(instance instanceof Agent a)) {
            throw new IllegalArgumentException("Configured instance is not an Agent: " + instance);
        }
        return new ServiceAgent<>(agentName, idleStrategy, svc, a);
    }

    // -------- Builder API --------

    /**
     * Creates a new builder for EventFeedConfig
     *
     * @param <T> The input type for the event feed
     * @return A new builder instance
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Builder class for constructing EventFeedConfig instances
     *
     * @param <IN> The input type for the event feed
     */
    public static final class Builder<IN> {
        private Object instance;
        private String name;
        private boolean broadcast;
        private boolean wrapWithNamedEvent;
        private EventSource.SlowConsumerStrategy slowConsumerStrategy;
        private Function<IN, ?> valueMapper;
        private String agentName;
        private IdleStrategy idleStrategy;

        private Builder() {
        }

        /**
         * Sets the event source instance
         */
        public Builder<IN> instance(Object instance) {
            this.instance = instance;
            return this;
        }

        /**
         * Sets the feed name
         */
        public Builder<IN> name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets whether events should be broadcast
         */
        public Builder<IN> broadcast(boolean broadcast) {
            this.broadcast = broadcast;
            return this;
        }

        /**
         * Sets whether events should be wrapped
         */
        public Builder<IN> wrapWithNamedEvent(boolean wrap) {
            this.wrapWithNamedEvent = wrap;
            return this;
        }

        /**
         * Sets the slow consumer handling strategy
         */
        public Builder<IN> slowConsumerStrategy(EventSource.SlowConsumerStrategy strategy) {
            this.slowConsumerStrategy = strategy;
            return this;
        }

        /**
         * Sets the value mapping function
         */
        public Builder<IN> valueMapper(Function<IN, ?> mapper) {
            this.valueMapper = mapper;
            return this;
        }

        /**
         * Configures agent-based execution
         */
        public Builder<IN> agent(String agentName, IdleStrategy idleStrategy) {
            this.agentName = agentName;
            this.idleStrategy = idleStrategy;
            return this;
        }

        /**
         * Builds the EventFeedConfig instance
         */
        public EventFeedConfig<IN> build() {
            EventFeedConfig<IN> cfg = new EventFeedConfig<>();
            cfg.setInstance(instance);
            cfg.setName(name);
            cfg.setBroadcast(broadcast);
            cfg.setWrapWithNamedEvent(wrapWithNamedEvent);
            if (slowConsumerStrategy != null) cfg.setSlowConsumerStrategy(slowConsumerStrategy);
            if (valueMapper != null) cfg.setValueMapper(valueMapper);
            cfg.setAgentName(agentName);
            cfg.setIdleStrategy(idleStrategy);
            return cfg;
        }
    }
}