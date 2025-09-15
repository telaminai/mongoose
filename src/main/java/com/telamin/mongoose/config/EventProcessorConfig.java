/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.runtime.EventProcessor;
import com.fluxtion.runtime.audit.EventLogControlEvent;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseEventHandler;
import com.telamin.mongoose.internal.ConfigAwareEventProcessor;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Configuration holder for EventProcessor instances.
 * Provides configuration options for event handlers, logging, and custom parameters.
 * Supports builder pattern for fluent configuration.
 *
 * @param <T> type of EventProcessor being configured
 */
@Data
public class EventProcessorConfig<T extends EventProcessor<?>> {
    /**
     * The configured event processor instance
     */
    private T eventHandler;

    /**
     * Name identifier for this event processor configuration
     */
    private String name;

    /**
     * Custom event handler node for specialized processing
     */
    private ObjectEventHandlerNode customHandler;

    /**
     * Supplier for lazy initialization of event handler
     */
    private Supplier<T> eventHandlerBuilder;

    /**
     * Controls the logging level for event processing
     */
    private EventLogControlEvent.LogLevel logLevel;

    /**
     * Map of configuration parameters for the event processor
     */
    private Map<String, Object> configMap = new HashMap<>();

    /**
     * Creates configuration with a custom handler
     *
     * @param customHandler the custom event handler node
     */
    public EventProcessorConfig(ObjectEventHandlerNode customHandler) {
        this.customHandler = customHandler;
    }

    /**
     * Creates empty configuration with default settings
     */
    public EventProcessorConfig() {
        configMap = new HashMap<>();
    }

    @SuppressWarnings({"unchecked"})
    public T getEventHandler() {
        if (eventHandler == null && customHandler != null) {
            ConfigAwareEventProcessor wrappingProcessor = new ConfigAwareEventProcessor(customHandler);
            eventHandler = (T) wrappingProcessor;
        }
        return eventHandler;
    }

    public ConfigMap getConfig() {
        return new ConfigMap(getConfigMap());
    }

    // -------- Builder API --------
    public static <T extends EventProcessor<?>> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Builder class for creating EventProcessorConfig instances
     *
     * @param <T> type of EventProcessor being configured
     */
    public static final class Builder<T extends EventProcessor<?>> {
        private T eventHandler;
        private String name;
        private ObjectEventHandlerNode customHandler;
        private Supplier<T> eventHandlerBuilder;
        private EventLogControlEvent.LogLevel logLevel;
        private final Map<String, Object> config = new HashMap<>();

        private Builder() {
        }

        /**
         * Sets the event handler instance
         *
         * @param handler the event processor instance
         * @return this builder
         */
        public Builder<T> handler(T handler) {
            this.eventHandler = handler;
            return this;
        }

        /**
         * Sets a function to handle events in a MongooseEventHandler
         *
         * @param handlerFunction consumer function that processes events
         * @return this builder
         */
        @SuppressWarnings({"unchecked"})
        public Builder<T> handlerFunction(Consumer<Object> handlerFunction) {
            return handler((T) new MongooseEventHandler(handlerFunction));
        }

        /**
         * Sets the name identifier for this event processor configuration
         *
         * @param name identifier for this event processor configuration
         * @return this builder
         */
        public Builder<T> name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets a custom handler node
         *
         * @param node the custom event handler node
         * @return this builder
         */
        public Builder<T> customHandler(ObjectEventHandlerNode node) {
            this.customHandler = node;
            return this;
        }

        /**
         * Sets a supplier for lazy handler initialization
         *
         * @param builder the handler supplier
         * @return this builder
         */
        public Builder<T> handlerBuilder(Supplier<T> builder) {
            this.eventHandlerBuilder = builder;
            return this;
        }

        /**
         * Sets the logging level
         *
         * @param level desired log level
         * @return this builder
         */
        public Builder<T> logLevel(EventLogControlEvent.LogLevel level) {
            this.logLevel = level;
            return this;
        }

        /**
         * Adds a configuration parameter
         *
         * @param key   configuration key
         * @param value configuration value
         * @return this builder
         */
        public Builder<T> putConfig(String key, Object value) {
            this.config.put(key, value);
            return this;
        }

        public EventProcessorConfig<T> build() {
            EventProcessorConfig<T> cfg = new EventProcessorConfig<>();
            cfg.setEventHandler(eventHandler);
            cfg.setName(name);
            cfg.setCustomHandler(customHandler);
            cfg.setEventHandlerBuilder(eventHandlerBuilder);
            cfg.setLogLevel(logLevel);
            cfg.getConfigMap().putAll(config);
            return cfg;
        }
    }
}
