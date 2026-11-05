/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.input.NamedFeed;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.dutycycle.ServiceAgent;
import com.telamin.mongoose.service.EventSource;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.SneakyThrows;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.IdleStrategy;

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
     * Superseded by {@link #overload} (telaminai/mongoose#79). When set explicitly and there is no {@code overload}
     * block, {@code BACKOFF} means {@code DROP_AFTER_SPIN} (with a boot warning), and {@code DISCONNECT} or
     * {@code EXIT_PROCESS} fail boot until they are implemented.
     * @deprecated Use {@link #overload}.
     */
    @Deprecated
    private EventSource.SlowConsumerStrategy slowConsumerStrategy = EventSource.SlowConsumerStrategy.BACKOFF;

    /** Whether {@link #slowConsumerStrategy} was set, rather than left at its default. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private boolean slowConsumerStrategyExplicit;

    /**
     * What the feed's publisher does when a subscriber's queue is full, with overrides per subscriber group
     * (telaminai/mongoose#79). Null: {@code DROP_AFTER_SPIN} with a one-time boot warning. YAML key {@code overload}.
     */
    private OverloadPolicyConfig overload;

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
     * The all-arguments constructor as it was before {@link #overload} was added (telaminai/mongoose#79), kept for
     * source and binary compatibility; the overload policy is then unset.
     */
    @SuppressWarnings("deprecation")
    public EventFeedConfig(Object instance, String name, boolean broadcast, boolean wrapWithNamedEvent,
                           EventSource.EventWrapStrategy eventWrapStrategy,
                           EventSource.SlowConsumerStrategy slowConsumerStrategy, Function<IN, ?> valueMapper,
                           String agentName, IdleStrategy idleStrategy) {
        this.instance = instance;
        this.name = name;
        this.broadcast = broadcast;
        this.wrapWithNamedEvent = wrapWithNamedEvent;
        this.eventWrapStrategy = eventWrapStrategy;
        setSlowConsumerStrategy(slowConsumerStrategy);
        this.valueMapper = valueMapper;
        this.agentName = agentName;
        this.idleStrategy = idleStrategy;
    }

    /**
     * @deprecated Use {@link #setOverload(OverloadPolicyConfig)}; see {@link #slowConsumerStrategy}.
     */
    @Deprecated
    public void setSlowConsumerStrategy(EventSource.SlowConsumerStrategy slowConsumerStrategy) {
        this.slowConsumerStrategy = slowConsumerStrategy;
        this.slowConsumerStrategyExplicit = slowConsumerStrategy != null;
    }

    /**
     * This feed's overload policy, resolved and validated for boot (telaminai/mongoose#79); see
     * {@link OverloadPolicyConfig#resolve}. Logs the boot warnings and throws a
     * {@link com.telamin.mongoose.exception.ConfigurationException} naming the feed on a bad value.
     */
    @SuppressWarnings("deprecation")
    public com.telamin.mongoose.dispatch.FeedOverloadSettings resolveOverload() {
        return OverloadPolicyConfig.resolve(name, overload, slowConsumerStrategy, slowConsumerStrategyExplicit);
    }

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
        private OverloadPolicyConfig overload;
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
         * @deprecated Use {@link #overload(OverloadPolicyConfig)}: {@code BACKOFF} maps to {@code DROP_AFTER_SPIN},
         * {@code DISCONNECT} and {@code EXIT_PROCESS} fail boot.
         */
        @Deprecated
        public Builder<IN> slowConsumerStrategy(EventSource.SlowConsumerStrategy strategy) {
            this.slowConsumerStrategy = strategy;
            return this;
        }

        /** Sets the overload policy (telaminai/mongoose#79). */
        public Builder<IN> overload(OverloadPolicyConfig overload) {
            this.overload = overload;
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
            cfg.setOverload(overload);
            if (valueMapper != null) cfg.setValueMapper(valueMapper);
            cfg.setAgentName(agentName);
            cfg.setIdleStrategy(idleStrategy);
            return cfg;
        }
    }
}