/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.agrona.concurrent.YieldingIdleStrategy;
import com.fluxtion.runtime.EventProcessor;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseEventHandler;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Application-level configuration for Fluxtion Server.
 * <p>
 * This class encapsulates the full runtime configuration for:
 * <ul>
 *   <li>Event processor groups and their handlers</li>
 *   <li>Event feeds (sources)</li>
 *   <li>Event sinks</li>
 *   <li>Registered services (standard and agent-backed)</li>
 *   <li>Agent thread settings (per-group idle strategies, names, etc.)</li>
 * </ul>
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Fluent helpers for registering event sources, services, and worker services</li>
 *   <li>Convenience accessors for resolving an {@code IdleStrategy} with sensible defaults</li>
 *   <li>A minimal builder API for constructing immutable instances</li>
 *   <li>A default event-processor group created on demand when adding processors</li>
 * </ul>
 * <p>
 * Threading and performance:
 * <ul>
 *   <li>Global {@code idleStrategy} acts as a fallback when per-agent configuration is absent</li>
 *   <li>Per-agent overrides can be supplied via {@code agentThreads}</li>
 * </ul>
 */
@Data
public class MongooseServerConfig {
    /**
     * Construct an empty MongooseServerConfig with sensible defaults.
     */
    public MongooseServerConfig() {
    }

    /**
     * Event processor groups and their handler configurations.
     * Created lazily via {@link #getEventHandlers()} if accessed while {@code null}.
     * A default handler group may be added automatically when present.
     */
    private List<EventProcessorGroupConfig> eventHandlers;

    /**
     * Event feed (input/source) configurations. Each feed may be broadcast or unicast.
     */
    private List<EventFeedConfig<?>> eventFeeds;

    /**
     * Event sink configurations (downstream consumers/outputs).
     */
    private List<EventSinkConfig<?>> eventSinks;

    /**
     * Service registrations (standard and worker/agent-backed).
     */
    private List<ServiceConfig<?>> services;

    /**
     * Per-agent thread configuration, including agent name and idle strategy.
     */
    private List<ThreadConfig> agentThreads;

    /**
     * Global fallback idle strategy used when no per-agent override is supplied.
     */
    private IdleStrategy idleStrategy = new YieldingIdleStrategy();

    /**
     * Default event-processor group that is created on demand and used by
     * {@link #addProcessor(EventProcessor, String)} when explicit groups are not provided.
     */
    private EventProcessorGroupConfig defaultHandlerGroupConfig;

    /**
     * Optional mapping of callback types to event-to-invocation strategy factories.
     * If provided, these strategies will be registered with the EventFlowManager during server boot.
     */
    private Map<CallBackType, Supplier<EventToInvokeStrategy>> eventInvokeStrategies;

    /**
     * Gets the list of event handler groups, initializing if {@code null} and adding
     * {@link #defaultHandlerGroupConfig} if present and not already included.
     *
     * @return mutable list of {@link EventProcessorGroupConfig}
     */
    public List<EventProcessorGroupConfig> getEventHandlers() {
        if (eventHandlers == null) {
            eventHandlers = new ArrayList<>();
        }

        if (defaultHandlerGroupConfig != null && !eventHandlers.contains(defaultHandlerGroupConfig)) {
            eventHandlers.add(defaultHandlerGroupConfig);
        }

        return eventHandlers;
    }

    /**
     * Adds an event source instance to the configuration.
     *
     * @param <T>         event type published by the source
     * @param eventSource source instance to register
     * @param name        unique name for the source
     * @param isBroadcast true to broadcast published events to all subscribers; false for targeted delivery
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T> MongooseServerConfig addEventSource(T eventSource, String name, boolean isBroadcast) {
        if (eventFeeds == null) {
            eventFeeds = new ArrayList<>();
        }

        EventFeedConfig<T> eventFeedConfig = new EventFeedConfig<>();
        eventFeedConfig.setInstance(eventSource);
        eventFeedConfig.setName(name);
        eventFeedConfig.setBroadcast(isBroadcast);

        eventFeeds.add(eventFeedConfig);

        return this;
    }


    /**
     * Adds an event source instance to the configuration with worker thread configuration.
     * <p>
     * This method allows registering an event source that runs in a dedicated worker thread with
     * custom idle strategy. The source can be configured for broadcast or targeted event delivery.
     *
     * @param <T>          event type published by the source
     * @param eventSource  source instance to register
     * @param name         unique name for the source
     * @param isBroadcast  true to broadcast published events to all subscribers; false for targeted delivery
     * @param agentGroup   name of the agent group that will host this event source
     * @param idleStrategy idle strategy for the worker thread, may be null to use defaults
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T> MongooseServerConfig addEventSourceWorker(T eventSource, String name, boolean isBroadcast, String agentGroup, IdleStrategy idleStrategy) {
        if (eventFeeds == null) {
            eventFeeds = new ArrayList<>();
        }

        EventFeedConfig<T> eventFeedConfig = new EventFeedConfig<>();
        eventFeedConfig.setInstance(eventSource);
        eventFeedConfig.setName(name);
        eventFeedConfig.setBroadcast(isBroadcast);
        eventFeedConfig.setAgentName(agentGroup);
        eventFeedConfig.setIdleStrategy(idleStrategy);

        eventFeeds.add(eventFeedConfig);

        return this;
    }

    /**
     * Return an {@link IdleStrategy}, choosing from config when {@code preferredIdeIdleStrategy} is {@code null}.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>If a non-null preferred strategy is supplied, return it</li>
     *   <li>Else if {@code agentThreads} has an entry with {@code agentName}, return its strategy</li>
     *   <li>Else return the global {@link #idleStrategy} (or a {@link YieldingIdleStrategy} fallback)</li>
     * </ol>
     *
     * @param preferredIdeIdleStrategy preferred strategy, or {@code null} to resolve from configuration
     * @param agentName                agent name to look up in {@link ThreadConfig}
     * @return resolved {@link IdleStrategy}
     */
    public IdleStrategy lookupIdleStrategyWhenNull(IdleStrategy preferredIdeIdleStrategy, String agentName) {
        if (preferredIdeIdleStrategy == null && agentThreads == null) {
            return idleStrategy;
        } else if (preferredIdeIdleStrategy == null) {
            return agentThreads.stream().filter(cfg -> cfg.getAgentName().equals(agentName))
                    .findFirst()
                    .map(ThreadConfig::getIdleStrategy)
                    .orElse(new YieldingIdleStrategy());
        }
        return preferredIdeIdleStrategy;
    }

    /**
     * Resolve an {@link IdleStrategy} for a specific agent, using the supplied default if not configured.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>If {@code defaultIdeIdleStrategy} is null and {@code agentThreads} is null, return global {@link #idleStrategy}</li>
     *   <li>If {@code agentThreads} is null, return {@code defaultIdeIdleStrategy}</li>
     *   <li>Else return the per-agent strategy if present, otherwise {@code defaultIdeIdleStrategy}</li>
     * </ol>
     *
     * @param agentName              agent name in {@link ThreadConfig}
     * @param defaultIdeIdleStrategy default strategy to use when no per-agent value is found (may be null)
     * @return resolved {@link IdleStrategy}, never {@code null} if {@code defaultIdeIdleStrategy} is non-null
     */
    public IdleStrategy getIdleStrategyOrDefault(String agentName, IdleStrategy defaultIdeIdleStrategy) {
        if (defaultIdeIdleStrategy == null && agentThreads == null) {
            return idleStrategy;
        }
        if (agentThreads == null) {
            return defaultIdeIdleStrategy;
        }
        var idleStrategy = agentThreads.stream().filter(cfg -> cfg.getAgentName().equals(agentName))
                .findFirst()
                .map(ThreadConfig::getIdleStrategy)
                .orElse(defaultIdeIdleStrategy);
        return idleStrategy == null ? defaultIdeIdleStrategy : idleStrategy;
    }

    /**
     * Adds an {@link EventProcessor} to the lazily-created default group.
     * <p>
     * If no default group exists, one is created with:
     * <ul>
     *   <li>Agent name: {@code "defaultHandlerGroup"}</li>
     *   <li>Empty handler map</li>
     *   <li>Busy-spin idle strategy</li>
     * </ul>
     *
     * @param <T>       concrete processor type
     * @param processor processor instance to add
     * @param name      unique name/key under which to register the processor
     * @return the default {@link EventProcessorGroupConfig} containing the added processor
     */
    public <T extends EventProcessor<?>> EventProcessorGroupConfig addProcessor(T processor, String name) {
        if (defaultHandlerGroupConfig == null) {
            defaultHandlerGroupConfig = new EventProcessorGroupConfig();
            defaultHandlerGroupConfig.setAgentName("defaultHandlerGroup");
            defaultHandlerGroupConfig.setEventHandlers(new HashMap<>());
            defaultHandlerGroupConfig.setIdleStrategy(new BusySpinIdleStrategy());
        }

        EventProcessorConfig<T> processorConfig = new EventProcessorConfig<>();
        processorConfig.setEventHandler(processor);
        defaultHandlerGroupConfig.getEventHandlers().put(name, processorConfig);

        return defaultHandlerGroupConfig;
    }


    // ---- Improved service registration API ----

    /**
     * Adds an {@link EventProcessor} to a specified processor group.
     * If the group does not exist, it will be created.
     *
     * @param <T>       concrete processor type
     * @param groupName name of the processor group
     * @param processor processor instance to add
     * @param name      unique name/key under which to register the processor
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T extends EventProcessor<?>> MongooseServerConfig addProcessor(String groupName, T processor, String name) {
        var eventProcessorGroupConfig = getGroupConfig(groupName);

        EventProcessorConfig<T> processorConfig = new EventProcessorConfig<>();
        processorConfig.setEventHandler(processor);
        eventProcessorGroupConfig.getEventHandlers().put(name, processorConfig);

        return this;
    }

    /**
     * Adds a functional event processor to a specified processor group.
     * If the group does not exist, it will be created. The processor is wrapped in a MongooseEventHandler
     * that executes the provided handler function for each event.
     *
     * @param <T>             concrete processor type
     * @param groupName       name of the processor group
     * @param handlerFunction consumer function that processes each event
     * @param name            unique name/key under which to register the processor
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T extends EventProcessor<?>> MongooseServerConfig addProcessor(
            String groupName,
            Consumer<Object> handlerFunction,
            String name) {
        var eventProcessorGroupConfig = getGroupConfig(groupName);
        EventProcessorConfig<T> processorConfig = new EventProcessorConfig<>();
        T mongooseEventHandler = (T) (Object) new MongooseEventHandler(handlerFunction);
        processorConfig.setEventHandler(mongooseEventHandler);
        eventProcessorGroupConfig.getEventHandlers().put(name, processorConfig);
        return this;
    }

    /**
     * Adds an {@link ObjectEventHandlerNode} to a specified processor group.
     * If the group does not exist, it will be created.
     *
     * @param <T>       concrete handler node type
     * @param groupName name of the processor group
     * @param processor handler node instance to add
     * @param name      unique name/key under which to register the handler
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T extends ObjectEventHandlerNode> MongooseServerConfig addProcessor(String groupName, T processor, String name) {
        EventProcessorGroupConfig eventProcessorGroupConfig = getGroupConfig(groupName);

        EventProcessorConfig<?> processorConfig = new EventProcessorConfig<>();
        processorConfig.setCustomHandler(processor);
        Map<String, EventProcessorConfig<?>> eventHandlers1 = eventProcessorGroupConfig.getEventHandlers();
        eventHandlers1.put(name, processorConfig);

        return this;
    }


    /**
     * Gets or creates a processor group configuration for the specified group name.
     * If a group with the given name exists, it is returned. Otherwise, a new group
     * is created, added to the configuration, and returned.
     *
     * @param groupName name of the processor group to get or create
     * @return existing or newly created {@link EventProcessorGroupConfig} for the group
     */
    public EventProcessorGroupConfig getGroupConfig(String groupName) {

        return getEventHandlers().stream()
                .filter(cfg -> cfg.getAgentName().equals(groupName))
                .findFirst()
                .orElseGet(() -> {
                    var config = EventProcessorGroupConfig.builder().agentName(groupName).build();
                    getEventHandlers().add(config);
                    return config;
                });
    }

    /**
     * Register a simple service by instance and name.
     * The service class is inferred from the instance type.
     *
     * @param <T>     service type
     * @param service service instance
     * @param name    unique service name
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T> MongooseServerConfig addService(T service, String name) {
        if (services == null) {
            services = new ArrayList<>();
        }
        @SuppressWarnings("unchecked") Class<T> clazz = (Class<T>) service.getClass();
        ServiceConfig<T> cfg = new ServiceConfig<>(service, clazz, name);
        services.add(cfg);
        return this;
    }

    /**
     * Register a service by instance, explicit service class, and name.
     *
     * @param <T>          service type
     * @param service      service instance
     * @param serviceClass explicit service interface/class
     * @param name         unique service name
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T> MongooseServerConfig addService(T service, Class<T> serviceClass, String name) {
        if (services == null) {
            services = new ArrayList<>();
        }
        ServiceConfig<T> cfg = new ServiceConfig<>(service, serviceClass, name);
        services.add(cfg);
        return this;
    }

    /**
     * Register a worker (agent-backed) service with explicit class and optional idle strategy.
     *
     * @param <T>          service type
     * @param service      service instance
     * @param serviceClass explicit service interface/class
     * @param name         unique service name
     * @param agentGroup   agent group name to host the service
     * @param idleStrategy idle strategy for the agent thread (may be {@code null} to resolve later)
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T> MongooseServerConfig addWorkerService(T service, Class<T> serviceClass, String name, String agentGroup, IdleStrategy idleStrategy) {
        if (services == null) {
            services = new ArrayList<>();
        }
        ServiceConfig<T> cfg = new ServiceConfig<>(service, serviceClass, name);
        cfg.setAgentGroup(agentGroup);
        cfg.setIdleStrategy(idleStrategy);
        services.add(cfg);
        return this;
    }

    /**
     * Register a worker (agent-backed) service inferring the service class.
     *
     * @param <T>          service type
     * @param service      service instance
     * @param name         unique service name
     * @param agentGroup   agent group name to host the service
     * @param idleStrategy idle strategy for the agent thread (may be {@code null} to resolve later)
     * @return this {@link MongooseServerConfig} for fluent chaining
     */
    public <T> MongooseServerConfig addWorkerService(T service, String name, String agentGroup, IdleStrategy idleStrategy) {
        if (services == null) {
            services = new ArrayList<>();
        }
        @SuppressWarnings("unchecked") Class<T> clazz = (Class<T>) service.getClass();
        ServiceConfig<T> cfg = new ServiceConfig<>(service, clazz, name);
        cfg.setAgentGroup(agentGroup);
        cfg.setIdleStrategy(idleStrategy);
        services.add(cfg);
        return this;
    }

    // -------- Builder API --------

    /**
     * Create a new {@link Builder} for constructing an {@link MongooseServerConfig}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link MongooseServerConfig}.
     * All lists are accumulated within the builder and copied into the resulting {@link MongooseServerConfig}.
     * Omitted sections remain {@code null} in the built config unless explicitly provided.
     */
    public static final class Builder {
        private final List<EventProcessorGroupConfig> eventHandlers = new ArrayList<>();
        private final List<EventFeedConfig<?>> eventFeeds = new ArrayList<>();
        private final List<EventSinkConfig<?>> eventSinks = new ArrayList<>();
        private final List<ServiceConfig<?>> services = new ArrayList<>();
        private final List<ThreadConfig> agentThreads = new ArrayList<>();
        private IdleStrategy idleStrategy;
        private final Map<CallBackType, Supplier<EventToInvokeStrategy>> eventInvokeStrategies = new HashMap<>();

        private Builder() {
        }

        /**
         * Set the global fallback idle strategy.
         *
         * @param idleStrategy global idle strategy
         * @return this builder
         */
        public Builder idleStrategy(IdleStrategy idleStrategy) {
            this.idleStrategy = idleStrategy;
            return this;
        }

        /**
         * Add an event processor group configuration.
         *
         * @param group group configuration to add
         * @return this builder
         */
        public Builder addProcessorGroup(EventProcessorGroupConfig group) {
            this.eventHandlers.add(group);
            return this;
        }

        /**
         * Add an event feed configuration.
         *
         * @param feed feed configuration to add
         * @return this builder
         */
        public Builder addEventFeed(EventFeedConfig<?> feed) {
            this.eventFeeds.add(feed);
            return this;
        }

        /**
         * Add an event sink configuration.
         *
         * @param sink sink configuration to add
         * @return this builder
         */
        public Builder addEventSink(EventSinkConfig<?> sink) {
            this.eventSinks.add(sink);
            return this;
        }

        /**
         * Add a service configuration.
         *
         * @param svc service configuration to add
         * @return this builder
         */
        public Builder addService(ServiceConfig<?> svc) {
            this.services.add(svc);
            return this;
        }

        /**
         * Add an agent thread configuration entry.
         *
         * @param thread thread configuration to add
         * @return this builder
         */
        public Builder addThread(ThreadConfig thread) {
            this.agentThreads.add(thread);
            return this;
        }

        /**
         * Register an EventToInvokeStrategy factory for a specific callback type.
         *
         * @param type    callback type this strategy applies to
         * @param factory supplier that produces the strategy instance
         * @return this builder
         */
        public Builder eventInvokeStrategy(CallBackType type, Supplier<EventToInvokeStrategy> factory) {
            if (type != null && factory != null) {
                this.eventInvokeStrategies.put(type, factory);
            }
            return this;
        }

        /**
         * Convenience for the common ON_EVENT callback type.
         *
         * @param factory supplier that produces the strategy instance
         * @return this builder
         */
        public Builder onEventInvokeStrategy(Supplier<EventToInvokeStrategy> factory) {
            return eventInvokeStrategy(CallBackType.ON_EVENT_CALL_BACK, factory);
        }

        /**
         * Add a processor configuration into a group identified by agentName. If the
         * group does not yet exist in this builder, it is created implicitly and added.
         *
         * @param agentName   the agent name of the target EventProcessorGroupConfig
         * @param handlerName the key/name under which the processor config is registered
         * @param cfg         the processor configuration
         * @return this builder
         */
        public Builder addProcessor(String agentName, String handlerName, EventProcessorConfig<?> cfg) {
            if (agentName == null || handlerName == null || cfg == null) {
                throw new IllegalArgumentException("agentName, handlerName and cfg must be non-null");
            }
            // find or create group by agentName
            EventProcessorGroupConfig group = this.eventHandlers.stream()
                    .filter(g -> agentName.equals(g.getAgentName()))
                    .findFirst()
                    .orElseGet(() -> {
                        EventProcessorGroupConfig g = EventProcessorGroupConfig.builder()
                                .agentName(agentName)
                                .build();
                        this.eventHandlers.add(g);
                        return g;
                    });
            if (group.getEventHandlers() == null) {
                group.setEventHandlers(new java.util.HashMap<>());
            }
            group.getEventHandlers().put(handlerName, cfg);
            return this;
        }

        /**
         * Add a processor configuration into a group identified by agentName, using the processor's
         * configured name as the handler name. If the group does not exist, it will be created.
         *
         * @param agentName name of the target agent group
         * @param cfg       the processor configuration to add
         * @return this builder
         * @throws IllegalArgumentException if agentName or cfg is null, or if cfg has no name set
         */
        public Builder addProcessor(String agentName, EventProcessorConfig<?> cfg) {
            return addProcessor(agentName, cfg.getName(), cfg);
        }

        /**
         * Build an {@link MongooseServerConfig} instance from the accumulated values.
         *
         * @return a new {@link MongooseServerConfig}
         */
        public MongooseServerConfig build() {
            MongooseServerConfig cfg = new MongooseServerConfig();
            if (!eventHandlers.isEmpty()) cfg.setEventHandlers(new ArrayList<>(eventHandlers));
            if (!eventFeeds.isEmpty()) cfg.setEventFeeds(new ArrayList<>(eventFeeds));
            if (!eventSinks.isEmpty()) cfg.setEventSinks(new ArrayList<>(eventSinks));
            if (!services.isEmpty()) cfg.setServices(new ArrayList<>(services));
            if (!agentThreads.isEmpty()) cfg.setAgentThreads(new ArrayList<>(agentThreads));
            if (idleStrategy != null) cfg.setIdleStrategy(idleStrategy);
            if (!eventInvokeStrategies.isEmpty()) cfg.setEventInvokeStrategies(new HashMap<>(eventInvokeStrategies));
            return cfg;
        }
    }
}