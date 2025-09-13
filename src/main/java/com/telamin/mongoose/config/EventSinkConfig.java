/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.runtime.annotations.feature.Experimental;
import com.fluxtion.runtime.output.MessageSink;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.dutycycle.ServiceAgent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import java.util.function.Function;

/**
 * Configuration class for message sinks in the server.
 * Supports configuration of value mapping and optional agent-based execution
 * for message output handling.
 *
 * @param <S> The type of MessageSink being configured
 */
@Experimental
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EventSinkConfig<S extends MessageSink<?>> {

    /**
     * The message sink instance to be configured
     */
    private S instance;

    /**
     * Name identifier for this sink configuration
     */
    private String name;

    /**
     * Function to transform values before they are processed by the sink
     */
    private Function<Object, ?> valueMapper = Function.identity();

    /**
     * Name of the agent if using agent-based execution
     */
    private String agentName;

    /**
     * Idle strategy for agent-based execution
     */
    private IdleStrategy idleStrategy;

    /**
     * Checks if this sink is configured for agent-based execution
     *
     * @return true if agent execution is configured
     */
    public boolean isAgent() {
        return agentName != null;
    }

    /**
     * Converts this configuration to a Service instance
     *
     * @return Service wrapper around the configured sink
     */
    @SneakyThrows
    @SuppressWarnings({"unchecked", "all"})
    public Service<S> toService() {
        ((MessageSink<Object>) instance).setValueMapper(valueMapper);
        Service svc = new Service(instance, MessageSink.class, name);
        return svc;
    }

    /**
     * Converts this configuration to a ServiceAgent instance for agent-based execution
     *
     * @param <A> The Agent type
     * @return ServiceAgent wrapper around the configured sink
     */
    @SneakyThrows
    @SuppressWarnings({"unchecked", "all"})
    public <A extends Agent> ServiceAgent<A> toServiceAgent() {
        Service svc = toService();
        return new ServiceAgent<>(agentName, idleStrategy, svc, (A) instance);
    }

    // -------- Builder API --------
    public static <S extends MessageSink<?>> Builder<S> builder() {
        return new Builder<>();
    }

    /**
     * Builder class for constructing EventSinkConfig instances
     *
     * @param <S> The type of MessageSink being configured
     */
    public static final class Builder<S extends MessageSink<?>> {
        /**
         * The message sink instance to configure
         */
        private S instance;
        /**
         * Name identifier for the sink
         */
        private String name;
        /**
         * Function to transform values before processing
         */
        private Function<Object, ?> valueMapper;
        /**
         * Optional agent name for agent-based execution
         */
        private String agentName;
        /**
         * Optional idle strategy for agent-based execution
         */
        private IdleStrategy idleStrategy;

        private Builder() {
        }

        /**
         * Sets the message sink instance
         */
        public Builder<S> instance(S instance) {
            this.instance = instance;
            return this;
        }

        /**
         * Sets the sink name
         */
        public Builder<S> name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the value mapping function
         */
        public Builder<S> valueMapper(Function<Object, ?> mapper) {
            this.valueMapper = mapper;
            return this;
        }

        /**
         * Configures agent-based execution
         */
        public Builder<S> agent(String agentName, IdleStrategy idleStrategy) {
            this.agentName = agentName;
            this.idleStrategy = idleStrategy;
            return this;
        }

        public EventSinkConfig<S> build() {
            EventSinkConfig<S> cfg = new EventSinkConfig<>();
            cfg.setInstance(instance);
            cfg.setName(name);
            if (valueMapper != null) cfg.setValueMapper(valueMapper);
            cfg.setAgentName(agentName);
            cfg.setIdleStrategy(idleStrategy);
            return cfg;
        }
    }
}
