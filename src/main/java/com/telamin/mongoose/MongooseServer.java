/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose;

import com.fluxtion.agrona.ErrorHandler;
import com.fluxtion.agrona.concurrent.AgentRunner;
import com.fluxtion.agrona.concurrent.DynamicCompositeAgent;
import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.agrona.concurrent.UnsafeBuffer;
import com.fluxtion.agrona.concurrent.status.AtomicCounter;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.service.ServiceRegistryNode;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.ComposingServiceAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.dutycycle.ServiceAgent;
import com.telamin.mongoose.internal.*;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;


/**
 * MongooseServer is the central runtime that wires together event sources, event processors,
 * event sinks, and application services, and manages their lifecycle and threading model.
 * <p>
 * High-level architecture:
 * <ul>
 *   <li><b>Services</b>: Application components registered with the server. They may be standard
 *       services (executed in the server lifecycle) or <b>agent-hosted</b> worker services
 *       (executed on dedicated agent threads). Services can depend on each other via dependency
 *       injection. Services that participate directly in event routing can implement an event-flow
 *       contract to interact with the event flow manager.</li>
 *   <li><b>Agent-hosted services</b>: Worker services executed on their own {@code AgentRunner}.
 *       Each worker service is associated with an agent group and an {@code IdleStrategy}.
 *       Idle strategies can be provided directly or resolved from configuration using the group name.</li>
 *   <li><b>Event sources (feeds)</b>: Producers of events registered with the server. The event flow
 *       manager routes events from sources to interested processors, using mapping/dispatch strategies
 *       that can be customized.</li>
 *   <li><b>Event processors</b>: Instances grouped by a logical <i>processor group</i>. Each group runs
 *       on its own {@code AgentRunner} with a configurable {@code IdleStrategy}. Processors are registered
 *       by name within a group, and audit logging hooks can be attached. Groups provide isolation and
 *       parallelism.</li>
 *   <li><b>Event sinks</b>: Consumers of processed events. Sinks are typically modeled as services
 *       (standard or agent-hosted) and participate in the event flow by receiving output from processors
 *       or other services.</li>
 * </ul>
 * <p>
 * Lifecycle and management:
 * <ul>
 *   <li>{@link #init()} performs initialization: registers services, prepares agent groups, and wires the
 *       event flow manager and service registry.</li>
 *   <li>{@link #start()} launches agent groups (for processors and worker services) using {@code AgentRunner}
 *       with the resolved idle strategies, then starts services and marks the server as running.</li>
 *   <li>{@link #stop()} stops all agent groups and services and releases resources.</li>
 *   <li>At runtime you can query and control components (e.g., {@link #registeredProcessors()},
 *       {@link #stopProcessor(String, String)}, {@link #startService(String)}, {@link #stopService(String)}).</li>
 * </ul>
 * <p>
 * Configuration and bootstrapping:
 * <ul>
 *   <li>Servers can be bootstrapped from an {@link MongooseServerConfig}, a {@link Reader}, or from a YAML
 *       file path provided via the {@link #CONFIG_FILE_PROPERTY} system property.</li>
 *   <li>Threading policies are controlled via idle strategies that can be specified per agent group or
 *       fall back to a global default. These are resolved with helper methods in {@link MongooseServerConfig}.</li>
 *   <li>Custom event-to-callback mapping strategies can be registered to tailor routing behavior.</li>
 * </ul>
 * <p>
 * Error handling and observability:
 * <ul>
 *   <li>A default {@link com.fluxtion.agrona.ErrorHandler} can be supplied via {@link #setDefaultErrorHandler(ErrorHandler)}
 *       and is used by agent runners.</li>
 *   <li>Event processors can be bridged to a {@link LogRecordListener} for audit logging.</li>
 * </ul>
 * <p>
 * Typical usage pattern:
 * <ol>
 *   <li>Construct or load an {@link MongooseServerConfig}.</li>
 *   <li>Boot the server via one of the {@code bootServer(...)} overloads.</li>
 *   <li>Optionally register additional services, event sources, and event processors programmatically.</li>
 *   <li>Call {@link #init()} and {@link #start()} (when booting from config helpers, these may be invoked for you).</li>
 *   <li>Manage components at runtime and finally {@link #stop()} the server.</li>
 * </ol>
 */
@Log
public class MongooseServer implements MongooseServerController {

    public static final String CONFIG_FILE_PROPERTY = "mongooseServer.config.file";
    private static LogRecordListener logRecordListener = logRecord -> {log.info(logRecord.toString());};
    private final MongooseServerConfig mongooseServerConfig;
    private final EventFlowManager flowManager = new EventFlowManager();
    private final ConcurrentHashMap<String, ComposingEventProcessorAgentRunner> composingEventProcessorAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ComposingWorkerServiceAgentRunner> composingServiceAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Service<?>> registeredServices = new ConcurrentHashMap<>();
    private final Set<Service<?>> registeredAgentServices = ConcurrentHashMap.newKeySet();
    private ErrorHandler errorHandler = m -> log.severe(m.getMessage());
    private final ServiceRegistryNode serviceRegistry = new ServiceRegistryNode();
    private volatile boolean started = false;
    private final LifecycleManager lifecycleManager = new LifecycleManager(this);

    /**
     * Entry point for starting a MongooseServer instance with default configuration.
     * <p>
     * This method loads server configuration from a file specified by the
     * {@value #CONFIG_FILE_PROPERTY} system property and boots the server using
     * default settings. The server configuration path must be provided via
     * the system property {@code mongooseServer.config.file}.
     *
     * @param args command line arguments (not used)
     * @see #bootServer()
     * @see #CONFIG_FILE_PROPERTY
     */
    public static void main(String[] args) {
        log.info("starting server from MongooseServer.main() with args:" + Arrays.toString(args));
        MongooseServer.bootServer();  
    }
    
    /**
     * Construct a MongooseServer bound to a specific application configuration.
     * <p>
     * The supplied {@link MongooseServerConfig} provides definitions for services, event sources,
     * event processors, sinks, and agent thread settings used during initialization and start.
     *
     * @param mongooseServerConfig application configuration used for thread, service, and event-flow setup
     */
    public MongooseServer(MongooseServerConfig mongooseServerConfig) {
        this.mongooseServerConfig = mongooseServerConfig;
    }

    /**
     * Boots a MongooseServer instance by loading configuration from the provided reader.
     * The configuration is parsed and converted into an {@code MongooseServerConfig} instance, which is used
     * for initializing the server along with the specified log record listener.
     *
     * @param reader            A {@code Reader} instance used to read the configuration input.
     * @param logRecordListener A {@code LogRecordListener} instance for handling log messages during server operations.
     * @return A new instance of {@code MongooseServer} configured with the supplied {@code MongooseServerConfig} and log record listener.
     */
    public static MongooseServer bootServer(Reader reader, LogRecordListener logRecordListener) {
        log.info("booting server loading config from reader");
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setTagInspector(tag -> true);
        Yaml yaml = new Yaml(loaderOptions);
        MongooseServerConfig mongooseServerConfig = yaml.loadAs(reader, MongooseServerConfig.class);
        log.info("successfully loaded config from reader");

        return bootServer(mongooseServerConfig, logRecordListener);
    }

    /**
     * Boots a MongooseServer instance using configuration from the provided reader and default log record listener.
     * <p>
     * This is a convenience method that delegates to {@link #bootServer(Reader, LogRecordListener)} using the default
     * log record listener. The configuration data is read from the supplied reader and parsed as YAML to create
     * an {@link MongooseServerConfig} instance.
     *
     * @param reader A {@code Reader} instance containing YAML-formatted server configuration
     * @return A new {@code MongooseServer} instance configured from the reader's contents
     * @see #bootServer(Reader, LogRecordListener)
     */
    public static MongooseServer bootServer(Reader reader) {
        return bootServer(reader, logRecordListener);
    }

    /**
     * Boots a MongooseServer instance using a configuration file specified by a system property
     * and a log record listener. The configuration file path must be specified using the system property
     * {@value #CONFIG_FILE_PROPERTY} ({@code mongooseServer.config.file}). The configuration file
     * should contain YAML-formatted server configuration that will be parsed into an {@link MongooseServerConfig}
     * instance.
     *
     * @param logRecordListener A {@code LogRecordListener} instance for handling log messages during server operations.
     * @return A new instance of {@code MongooseServer} configured with the loaded configuration and supplied log record listener.
     * @throws NullPointerException if the configuration file name is not specified in the system property.
     * @throws IOException          if an error occurs while reading the configuration file.
     */
    @SneakyThrows
    public static MongooseServer bootServer(LogRecordListener logRecordListener) {
        String configFileName = System.getProperty(CONFIG_FILE_PROPERTY);
        Objects.requireNonNull(configFileName, "fluxtion config file must be specified by system property: " + CONFIG_FILE_PROPERTY);
        File configFile = new File(configFileName);
        log.info("booting fluxtion server with config file:" + configFile + " specified by system property:" + CONFIG_FILE_PROPERTY);
        try (FileReader reader = new FileReader(configFileName)) {
            return bootServer(reader, logRecordListener);
        }
    }

    /**
     * Boots a MongooseServer instance using the default log record listener and configuration
     * from a file specified by the {@value #CONFIG_FILE_PROPERTY} system property.
     * <p>
     * This is a convenience method that delegates to {@link #bootServer(LogRecordListener)}
     * using the default log record listener. The configuration file path must be specified
     * using the system property {@code mongooseServer.config.file}.
     *
     * @return A new {@code MongooseServer} instance configured from the specified config file
     * @throws NullPointerException if the configuration file name is not specified in the system property
     * @throws IOException          if an error occurs while reading the configuration file
     * @see #bootServer(LogRecordListener)
     * @see #CONFIG_FILE_PROPERTY
     */
    @SneakyThrows
    public static MongooseServer bootServer() {
        return bootServer(logRecordListener);
    }

    /**
     * Boots a MongooseServer instance using the provided application configuration and log record listener.
     * The server is initialized based on the configuration data, and the log record listener
     * is used to handle log messages during its operation.
     *
     * @param mongooseServerConfig An {@code MongooseServerConfig} instance containing the server configuration.
     * @param logRecordListener    A {@code LogRecordListener} instance for handling log messages.
     * @return A new {@code MongooseServer} instance configured with the given {@code MongooseServerConfig} and {@code LogRecordListener}.
     */
    public static MongooseServer bootServer(MongooseServerConfig mongooseServerConfig, LogRecordListener logRecordListener) {
        MongooseServer.logRecordListener = logRecordListener;
        log.info("booting fluxtion server");
        log.fine("config:" + mongooseServerConfig);
        return ServerConfigurator.bootFromConfig(mongooseServerConfig, logRecordListener);
    }
    
    /**
     * Boots a MongooseServer instance using the provided application configuration and default log record listener.
     * <p>
     * This is a convenience method that delegates to {@link #bootServer(MongooseServerConfig, LogRecordListener)}
     * using the default log record listener. The server is initialized based on the configuration data
     * and prepared for starting.
     *
     * @param mongooseServerConfig An {@code MongooseServerConfig} instance containing the server configuration
     * @return A new {@code MongooseServer} instance configured with the given {@code MongooseServerConfig}
     * and default {@code LogRecordListener}
     * @see #bootServer(MongooseServerConfig, LogRecordListener)
     */
    public static MongooseServer bootServer(MongooseServerConfig mongooseServerConfig) {
        return bootServer(mongooseServerConfig, logRecordListener);
    }

    /**
     * Set the default error handler used by agent runners and internal components.
     * <p>
     * The handler receives uncaught exceptions or error signals raised by agent threads.
     *
     * @param errorHandler handler to delegate errors to; must not be {@code null}
     */
    public void setDefaultErrorHandler(ErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    /**
     * Register a factory to produce event-to-callback mapping strategies for a given callback type.
     * <p>
     * This allows customization of how events are mapped to processor invocation strategies,
     * e.g., per subscription type or routing policy.
     *
     * @param eventMapper supplier that creates an {@link EventToInvokeStrategy}
     * @param type        callback type this mapper applies to
     */
    public void registerEventMapperFactory(Supplier<EventToInvokeStrategy> eventMapper, CallBackType type) {
        log.info("registerEventMapperFactory:" + eventMapper);
        flowManager.registerEventMapperFactory(eventMapper, type);
    }

    /**
     * Register an event feed service with optional data mapping.
     * <p>
     * This is a convenience method that:
     * <ol>
     *   <li>Injects registered services into the data mapper function (if any)</li>
     *   <li>Registers the feed service with the server</li>
     * </ol>
     *
     * @param services   the event feed service to register
     * @param dataMapper optional function to transform event data (may be null)
     */
    public void registerEventFeed(Service<?> services, Function<?, ?> dataMapper) {
        ServiceInjector.inject(dataMapper, registeredServices.values());
        registerService(services);
    }

    /**
     * Register an agent-hosted event feed service with optional data mapping.
     * <p>
     * This is a convenience method that:
     * <ol>
     *   <li>Injects registered services into the data mapper function (if any)</li>
     *   <li>Registers the feed as a worker service with its own agent thread</li>
     * </ol>
     *
     * @param services   the event feed service to register as an agent-hosted worker
     * @param dataMapper optional function to transform event data (may be null)
     */
    public void registerEventFeedWorker(ServiceAgent<?> services, Function<?, ?> dataMapper) {
        ServiceInjector.inject(dataMapper, registeredServices.values());
        registerWorkerService(services);
    }

    /**
     * Register a named event source (feed) with the server.
     * <p>
     * The source will be made available to the event flow manager for routing to
     * interested processors and services. This is a convenience method that delegates
     * to {@link #registerEventSource(String, EventSource, Function)} with a null data
     * mapper.
     *
     * @param <T>         The type of events published by the source
     * @param sourceName  The unique name that identifies this event source within the server
     * @param eventSource The event source instance to register
     * @see #registerEventSource(String, EventSource, Function)
     * @see EventSource
     */
    public <T> void registerEventSource(String sourceName, EventSource<T> eventSource) {
        registerEventSource(sourceName, eventSource, null);
    }

    /**
     * Register a named event source (feed) with optional data transformation.
     * <p>
     * This method:
     * <ol>
     *   <li>Creates a service wrapper around the event source</li>
     *   <li>Injects registered services into the data mapper if provided</li>
     *   <li>Registers the service with the server</li>
     * </ol>
     * <p>
     * The event source becomes available to the event flow manager for routing
     * events to interested processors and services. Events can optionally be
     * transformed by the data mapper before delivery.
     * <p>
     * Example usage:
     * <pre>{@code
     * // Register price feed that transforms raw ticks to normalized format
     * server.registerEventSource(
     *     "prices",
     *     new MarketDataFeed(),
     *     tick -> new NormalizedPrice(tick)
     * );
     * }</pre>
     *
     * @param <T>         event type published by the source
     * @param sourceName  unique name for the event source
     * @param eventSource source instance that will publish events
     * @param dataMapper  optional function to transform events before delivery (may be null)
     * @see EventSource
     * @see #registerEventSource(String, EventSource)
     */
    public <T> void registerEventSource(String sourceName, EventSource<T> eventSource, Function<T, ?> dataMapper) {
        log.info("registerEventSource name:" + sourceName + " eventSource:" + eventSource);
        Service<?> service = new Service<>(eventSource, sourceName);
        ServiceInjector.inject(dataMapper, registeredServices.values());
        registerService(service);
    }


    /**
     * Register an event sink service with optional data mapping.
     * <p>
     * This method:
     * <ol>
     *   <li>Injects registered services into the data mapper function (if provided)</li>
     *   <li>Registers the sink service with the server</li>
     * </ol>
     * <p>
     * Event sinks are typically services that consume processed events from event processors
     * or other services.
     *
     * @param services   the event sink service to register
     * @param dataMapper optional function to transform event data (may be null)
     * @see Service
     * @see #registerEventSinkWorker(ServiceAgent, Function)
     */
    public void registerEventSink(Service<?> services, Function<?, ?> dataMapper) {
        ServiceInjector.inject(dataMapper, registeredServices.values());
        registerService(services);
    }

    /**
     * Register an agent-hosted event sink service with optional data mapping.
     * <p>
     * This method:
     * <ol>
     *   <li>Injects registered services into the data mapper function (if provided)</li>
     *   <li>Registers the sink as a worker service with its own agent thread</li>
     * </ol>
     * <p>
     * Agent-hosted event sinks run on dedicated agent threads and are suitable for
     * scenarios requiring isolated processing or specific threading models.
     *
     * @param services   the event sink service to register as an agent-hosted worker
     * @param dataMapper optional function to transform event data (may be null)
     * @see ServiceAgent
     * @see #registerEventSink(Service, Function)
     */
    public void registerEventSinkWorker(ServiceAgent<?> services, Function<?, ?> dataMapper) {
        ServiceInjector.inject(dataMapper, registeredServices.values());
        registerWorkerService(services);
    }

    /**
     * Register one or more services with the server.
     * <p>
     * - Enforces unique service names.<br>
     * - Injects dependencies: newly registered services get other services injected into them,
     * and existing services receive the new service (single-target injection).<br>
     * - If a service participates in event flow, it is connected to the flow manager.
     *
     * @param services services to register
     * @throws com.telamin.mongoose.exception.ServiceRegistrationException if a service name is already in use
     */
    public void registerService(Service<?>... services) {
        for (Service<?> service : services) {
            String serviceName = service.serviceName();
            log.info("registerService:" + service);
            if (registeredServices.containsKey(serviceName)) {
                throw new com.telamin.mongoose.exception.ServiceRegistrationException("cannot register service name is already assigned:" + serviceName);
            }
            registeredServices.put(serviceName, service);
            Object instance = service.instance();
            //TODO set service name if not an EventFlow service
            if (instance instanceof EventFlowService) {
                ((EventFlowService) instance).setEventFlowManager(flowManager, serviceName);
            }
            // Dependency injection: inject other registered services into this instance
            ServiceInjector.inject(instance, registeredServices.values());
            // Also inject the newly registered service into existing services (single-target injection)
            for (Service<?> existing : registeredServices.values()) {
                Object existingInstance = existing.instance();
                if (existingInstance != instance) {
                    ServiceInjector.inject(existingInstance, Collections.singleton(service));
                }
            }
        }
    }

    /**
     * Register services and mark them as agent-hosted for lifecycle management.
     * <p>
     * This is a convenience that first registers the services, then flags them as
     * agent-backed so they are managed in the worker-service lifecycle.
     *
     * @param services services to register as agent-hosted
     */
    public void registerAgentService(Service<?>... services) {
        registerService(services);
        registeredAgentServices.addAll(Arrays.asList(services));
    }

    /**
     * Register an agent-hosted (worker) service that runs on a dedicated agent thread.
     * <p>
     * The supplied {@link ServiceAgent} advertises an agent group name and may provide an
     * {@link IdleStrategy}. If the service's idle strategy is {@code null}, an appropriate
     * strategy is resolved from configuration using the agent group via
     * {@link MongooseServerConfig#lookupIdleStrategyWhenNull(IdleStrategy, String)}.
     * <p>
     * Services registered under the same agent group are executed within a shared
     * {@code AgentRunner}. If no runner exists for the group, it is created on demand.
     * The service is then registered with the group's composite agent.
     *
     * @param service the worker service to register and host on its agent group
     */
    public void registerWorkerService(ServiceAgent<?> service) {
        String agentGroup = service.agentGroup();
        IdleStrategy idleStrategy = mongooseServerConfig.lookupIdleStrategyWhenNull(service.idleStrategy(), service.agentGroup());
        log.info("registerWorkerService:" + service + " agentGroup:" + agentGroup + " idleStrategy:" + idleStrategy);
        ComposingWorkerServiceAgentRunner composingAgentRunner = composingServiceAgents.computeIfAbsent(
                agentGroup,
                ket -> {
                    //build a subscriber group
                    ComposingServiceAgent group = new ComposingServiceAgent(agentGroup, flowManager, this, new DeadWheelScheduler());
                    //threading to be configured by file
                    AtomicCounter errorCounter = new AtomicCounter(new UnsafeBuffer(new byte[4096]), 0);
                    //run subscriber group
                    AgentRunner groupRunner = new AgentRunner(
                            idleStrategy,
                            errorHandler,
                            errorCounter,
                            group);
                    return new ComposingWorkerServiceAgentRunner(group, groupRunner);
                });

        composingAgentRunner.group().registerServer(service);
    }

    /**
     * Add a named event processor to a processor group, creating the group on demand.
     * <p>
     * Each processor group executes on its own {@code AgentRunner} with a configurable
     * {@link IdleStrategy}. If a specific strategy is not supplied, a suitable strategy
     * is resolved from configuration via {@link MongooseServerConfig#getIdleStrategyOrDefault(String, IdleStrategy)}.
     * <p>
     * The processor name must be unique within its group; attempting to register a duplicate
     * name results in an {@link IllegalArgumentException}. When added, the processor is wrapped
     * as a {@link NamedEventProcessor} and configured with the current {@link LogRecordListener}
     * for audit logging. If the server is already started and the group's thread is not yet
     * running, the group is started.
     *
     * @param processorName unique name of the processor within the group
     * @param groupName     the logical processor group to host the processor
     * @param idleStrategy  optional idle strategy override for the group (may be {@code null})
     * @param feedConsumer  supplier creating the {@link DataFlow} instance
     * @throws IllegalArgumentException if a processor with {@code processorName} already exists in the group
     */
    public void addEventProcessor(
            String processorName,
            String groupName,
            IdleStrategy idleStrategy,
            Supplier<DataFlow> feedConsumer) throws IllegalArgumentException {
        IdleStrategy idleStrategyOverride = mongooseServerConfig.getIdleStrategyOrDefault(groupName, idleStrategy);
        ComposingEventProcessorAgentRunner composingEventProcessorAgentRunner = composingEventProcessorAgents.computeIfAbsent(
                groupName,
                ket -> {
                    //build a subscriber group
                    ComposingEventProcessorAgent group = new ComposingEventProcessorAgent(groupName, flowManager, this, new DeadWheelScheduler(), registeredServices);
                    //threading to be configured by file
                    AtomicCounter errorCounter = new AtomicCounter(new UnsafeBuffer(new byte[4096]), 0);
                    //run subscriber group
                    AgentRunner groupRunner = new AgentRunner(
                            idleStrategyOverride,
                            errorHandler,
                            errorCounter,
                            group);
                    return new ComposingEventProcessorAgentRunner(group, groupRunner);
                });

        if (composingEventProcessorAgentRunner.group().isProcessorRegistered(processorName)) {
            throw new IllegalArgumentException("cannot add event processor name is already assigned:" + processorName);
        }

        composingEventProcessorAgentRunner.group().addNamedEventProcessor(() -> {
            DataFlow eventProcessor = feedConsumer.get();
            eventProcessor.setAuditLogProcessor(logRecordListener);
            if (started) {
                log.info("init event processor in already started server processor:'" + eventProcessor + "'");
//                eventProcessor.setAuditLogLevel(EventLogControlEvent.LogLevel.INFO);
            }
            return new NamedEventProcessor(processorName, eventProcessor);
        });

        if (started && composingEventProcessorAgentRunner.groupRunner().thread() == null) {
            log.info("staring event processor group:'" + groupName + "' for running server");
            AgentRunner.startOnThread(composingEventProcessorAgentRunner.groupRunner());
        }
    }

    /**
     * Get a snapshot of registered event processors grouped by processor group name.
     *
     * @return map of group name to the collection of named event processors in that group
     */
    @Override
    public Map<String, Collection<NamedEventProcessor>> registeredProcessors() {
        HashMap<String, Collection<NamedEventProcessor>> result = new HashMap<>();
        composingEventProcessorAgents.entrySet().forEach(entry -> {
            result.put(entry.getKey(), entry.getValue().group().registeredEventProcessors());
        });
        return result;
    }

    /**
     * Stop and remove an event processor from the specified group.
     *
     * @param groupName     the processor group containing the processor
     * @param processorName the unique name of the processor to stop
     */
    @Override
    public void stopProcessor(String groupName, String processorName) {
        log.info("stopProcessor:" + processorName + " in group:" + groupName);
        var processorAgent = composingEventProcessorAgents.get(groupName);
        if (processorAgent != null) {
            processorAgent.group().removeEventProcessorByName(processorName);
        }
    }

    /**
     * Start a previously registered service by name.
     * <p>
     * If the service is not registered, this is a no-op.
     *
     * @param serviceName the unique service name to start
     */
    @Override
    public void startService(String serviceName) {
        log.info("start service:" + serviceName);
        if (registeredServices.containsKey(serviceName)) {
            registeredServices.get(serviceName).start();
        }
    }

    /**
     * Stop a previously registered service by name.
     * <p>
     * If the service is not registered, this is a no-op.
     *
     * @param serviceName the unique service name to stop
     */
    @Override
    public void stopService(String serviceName) {
        log.info("stop service:" + serviceName);
        //check if registered and started
        if (registeredServices.containsKey(serviceName)) {
            registeredServices.get(serviceName).stop();
        }
    }

    /**
     * Get the registry of services by name.
     *
     * @return a mutable map of service name to service instance
     */
    @Override
    public Map<String, Service<?>> registeredServices() {
        return registeredServices;
    }

    /**
     * Initialize the server components and wire dependencies.
     * <p>
     * Prepares services, agent groups, the event flow manager, and the service registry.
     * Safe to call once before {@link #start()}.
     */
    public void init() {
        lifecycleManager.init(registeredServices, registeredAgentServices, flowManager, serviceRegistry);
    }

    /**
     * Start all configured components and agent groups.
     * <p>
     * Launches agent-hosted services and processor groups, then starts services and marks the server as running.
     *
     * @throws RuntimeException if startup fails
     */
    @SneakyThrows
    public void start() {
        ConcurrentHashMap<String, LifecycleManager.GroupRunner> serviceGroups = new ConcurrentHashMap<>();
        composingServiceAgents.forEach((k, v) -> serviceGroups.put(k, new LifecycleManager.GroupRunner() {
            @Override
            public AgentRunner getGroupRunner() {
                return v.groupRunner();
            }

            @Override
            public DynamicCompositeAgent getGroup() {
                return v.group();
            }

            @Override
            public void startCompleteIfSupported() {
                v.group().startComplete();
            }
        }));
        ConcurrentHashMap<String, LifecycleManager.GroupRunner> processorGroups = new ConcurrentHashMap<>();
        composingEventProcessorAgents.forEach((k, v) -> processorGroups.put(k, new LifecycleManager.GroupRunner() {
            @Override
            public AgentRunner getGroupRunner() {
                return v.groupRunner();
            }

            @Override
            public DynamicCompositeAgent getGroup() {
                return v.group();
            }
        }));
        lifecycleManager.start(registeredServices, serviceGroups, processorGroups, flowManager, registeredAgentServices);
        started = true;
    }

    /**
     * Get an unmodifiable view of all services currently registered.
     *
     * @return unmodifiable collection of registered services
     */
    public Collection<Service<?>> servicesRegistered() {
        return Collections.unmodifiableCollection(registeredServices.values());
    }

    /**
     * Resolve the configured CPU core id for a given agent group name, if any.
     * Returns null when no core pinning is configured for the agent.
     */
    public Integer resolveCoreIdForAgentName(String agentName) {
        if (mongooseServerConfig == null || mongooseServerConfig.getAgentThreads() == null) return null;
        return mongooseServerConfig.getAgentThreads().stream()
                .filter(t -> agentName != null && agentName.equals(t.getAgentName()))
                .map(com.telamin.mongoose.config.ThreadConfig::getCoreId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Stops the server and all its components.
     * This method stops all event processor agents, agent hosted services, the flowManager, and all registered services.
     * It should be called when the server is no longer needed to free up resources.
     */
    public void stop() {
        lifecycleManager.stop(started, toGroupRunnerMap(composingEventProcessorAgents), toGroupRunnerMap(composingServiceAgents), registeredServices);
        started = false;
    }

    private ConcurrentHashMap<String, LifecycleManager.GroupRunner> toGroupRunnerMap(ConcurrentHashMap<String, ? extends Object> source) {
        ConcurrentHashMap<String, LifecycleManager.GroupRunner> map = new ConcurrentHashMap<>();
        source.forEach((k, v) -> {
            if (v instanceof ComposingEventProcessorAgentRunner cep) {
                map.put(k, new LifecycleManager.GroupRunner() {
                    @Override
                    public AgentRunner getGroupRunner() {
                        return cep.groupRunner();
                    }

                    @Override
                    public DynamicCompositeAgent getGroup() {
                        return cep.group();
                    }
                });
            } else if (v instanceof ComposingWorkerServiceAgentRunner cws) {
                map.put(k, new LifecycleManager.GroupRunner() {
                    @Override
                    public AgentRunner getGroupRunner() {
                        return cws.groupRunner();
                    }

                    @Override
                    public DynamicCompositeAgent getGroup() {
                        return cws.group();
                    }

                    @Override
                    public void startCompleteIfSupported() {
                        cws.group().startComplete();
                    }
                });
            }
        });
        return map;
    }

    /**
     * Callback invoked when the AdminCommandRegistry service is registered.
     * <p>
     * This method can be used to perform any server-side initialization or wiring
     * related to administrative command handling once the admin registry becomes available.
     *
     * @param adminCommandRegistry the admin command registry service instance
     * @param name                 the registered service name
     */
    @ServiceRegistered
    public void adminClient(AdminCommandRegistry adminCommandRegistry, String name) {
        log.info("adminCommandRegistry registered:" + name);
    }
}
