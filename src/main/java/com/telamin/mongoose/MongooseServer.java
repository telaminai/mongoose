/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose;


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
import com.telamin.mongoose.dutycycle.ProcessorRegistration;
import com.telamin.mongoose.dutycycle.ServiceAgent;
import com.telamin.mongoose.internal.*;
import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.RecordingScheduler;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayScheduler;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import com.telamin.mongoose.service.servercontrol.PipeRegistration;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.DynamicCompositeAgent;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
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
 *   <li>A default {@link org.agrona.ErrorHandler} can be supplied via {@link #setDefaultErrorHandler(ErrorHandler)}
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
    /** Audit capture service — used during processor registration to attach + auto-start. NoOp when capture is off. */
    private com.telamin.mongoose.service.audit.MongooseAuditCaptureService auditCaptureService =
            com.telamin.mongoose.internal.NoOpAuditCaptureService.INSTANCE;
    /** Audit capture config — read for autoStart at processor registration. */
    private com.telamin.mongoose.config.AuditCaptureConfig auditCaptureConfig;
    private final Set<Service<?>> registeredAgentServices = ConcurrentHashMap.newKeySet();
    /**
     * mongoose#60: processor names taken, per group, reserved atomically when {@link #addEventProcessor} is called (the
     * group's own map only fills later, on its thread). Released by {@link #stopProcessor}, when building it fails, or when the group drops it after a failed
     * start ({@link #processorDropped}).
     */
    private final ConcurrentHashMap<ProcessorName, ProcessorRegistration> reservedProcessorNames = new ConcurrentHashMap<>();

    private record ProcessorName(String group, String name) {
    }

    /** mongoose#56: service agent groups hosting an event source, closed first by the ordered shutdown. */
    private final Set<String> eventSourceAgentGroups = ConcurrentHashMap.newKeySet();
    /** Service agent groups hosting a service that is not an event source: the ordered shutdown keeps them running. */
    private final Set<String> nonEventSourceAgentGroups = ConcurrentHashMap.newKeySet();
    /** Released once the ordered shutdown has finished. */
    private final java.util.concurrent.CountDownLatch stopCompleted = new java.util.concurrent.CountDownLatch(1);
    /** Serialises admission (adds) against shutdown's snapshot: adds read-lock, the stop write-locks once. */
    private final java.util.concurrent.locks.ReentrantReadWriteLock admission = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final Object stopAttempt = new Object();
    /** Design PR 4: service lifecycle is server-owned; registry changes and their broadcasts happen in one order. */
    private final java.util.concurrent.locks.ReentrantLock serviceLock = new java.util.concurrent.locks.ReentrantLock();
    /** This server's reply stages complete here, never on an agent thread; shut down once the server has stopped. */
    private final java.util.concurrent.ExecutorService replyExecutor = com.telamin.mongoose.control.ControlReplies.newExecutor();
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> serviceVersions = new ConcurrentHashMap<>();
    private volatile StopResult lastStopResult;
    /** Configured pipes — tracked separately from registeredServices because
     *  a pipe is one logical config entry but two service registrations.
     *  Order-preserving so the admin surface lists pipes in declaration order. */
    private final List<PipeRegistration> registeredPipes = Collections.synchronizedList(new ArrayList<>());
    private ErrorHandler errorHandler = m -> log.severe(m.getMessage());
    private final ServiceRegistryNode serviceRegistry = new ServiceRegistryNode();
    /**
     * mongoose#60: the server's lifecycle, NEW → STARTING → STARTED → STOPPING → STOPPED. A stop can land in STARTING
     * (it also cleans up after a failed start); start then fails and the server is never marked STARTED.
     */
    private enum LifecycleState { NEW, STARTING, STARTED, STOPPING, STOPPED }

    private final java.util.concurrent.atomic.AtomicReference<LifecycleState> lifecycleState =
            new java.util.concurrent.atomic.AtomicReference<>(LifecycleState.NEW);
    private final LifecycleManager lifecycleManager = new LifecycleManager(this);
    /** Startup and shutdown callbacks may request stop themselves; neither may wait for its own completion. */
    private volatile Thread startupThread;
    private volatile Thread stopThread;

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
        refuseRecordingOverARecording(mongooseServerConfig == null ? null : mongooseServerConfig.getReplay());
        flowManager.setReplayConfig(mongooseServerConfig == null ? null : mongooseServerConfig.getReplay());
        this.mongooseServerConfig = mongooseServerConfig;

        // Counters service — register FIRST, before any other service or
        // hot-path component construction. Per design-doc/mongoose-counters-
        // and-performance-monitor.md: hot-path components (publishers, agent
        // loops) receive the counter handles at construction time, not via
        // deferred @ServiceRegistered injection, so the impl must exist by
        // the time any feed / sink / processor wiring happens. Default is
        // the no-op; the Agrona-backed impl is selected via
        // performanceMonitoring.enabled in MongooseServerConfig.
        com.telamin.mongoose.config.PerformanceMonitoringConfig perfCfg =
                mongooseServerConfig == null ? null : mongooseServerConfig.getPerformanceMonitoring();
        com.telamin.mongoose.service.counters.MongooseCountersService counters =
                (perfCfg != null && perfCfg.isEnabled())
                        ? new com.telamin.mongoose.internal.AgronaCountersService(perfCfg.getCounterBufferKb())
                        : com.telamin.mongoose.internal.NoOpCountersService.INSTANCE;
        // Wire the chosen impl into EventFlowManager BEFORE any event source
        // registration happens — registerEventSource allocates the per-feed
        // counter handle at construction, and publishers cache the reference.
        // Doing the bind here keeps every publish-counter callsite monomorphic.
        flowManager.setCountersService(counters);
        registerService(new Service<>(
                counters,
                com.telamin.mongoose.service.counters.MongooseCountersService.class,
                com.telamin.mongoose.service.counters.MongooseCountersService.SERVICE_NAME));

        // Latency histograms — sibling of counters under the same YAML
        // block. Latency capture is meaningful only when the auditor is
        // bound (which is part of performanceMonitoring.enabled), so the
        // flag is gated on enabled=true too. When off, the no-op service
        // is registered so PerformanceMonitorAudit's recordNodeLatency
        // calls inline to nothing.
        com.telamin.mongoose.service.counters.MongooseLatencyService latency =
                (perfCfg != null && perfCfg.isEnabled() && perfCfg.isLatencyHistograms())
                        ? new com.telamin.mongoose.internal.HdrLatencyService()
                        : com.telamin.mongoose.internal.NoOpLatencyService.INSTANCE;
        registerService(new Service<>(
                latency,
                com.telamin.mongoose.service.counters.MongooseLatencyService.class,
                com.telamin.mongoose.service.counters.MongooseLatencyService.SERVICE_NAME));

        // Audit-log capture + introspection services — siblings of
        // counters and latency in the performanceMonitoring YAML block.
        // Phase 1 of the audit-log-viewer plugin installs the Chronicle
        // backend when auditCfg.isEnabled(); otherwise the NoOps stay so
        // downstream consumers (svc-admin-web REST + WS surfaces, admin
        // commands) can depend on the services being present.
        com.telamin.mongoose.config.AuditCaptureConfig auditCfg =
                perfCfg == null ? null : perfCfg.getAuditCapture();
        com.telamin.mongoose.service.audit.MongooseAuditCaptureService auditCapture;
        com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService auditIntrospection;
        if (auditCfg != null && auditCfg.isEnabled()) {
            com.telamin.mongoose.internal.ChronicleAuditCaptureService chronicle =
                    new com.telamin.mongoose.internal.ChronicleAuditCaptureService(auditCfg, counters);
            auditCapture = chronicle;
            // design "Audit capture": start and stop become requests to the processor's group
            chronicle.routeThrough(captureRouter());
            auditIntrospection = new com.telamin.mongoose.internal.DirAuditIntrospectionService(auditCfg, chronicle);
        } else {
            auditCapture = com.telamin.mongoose.internal.NoOpAuditCaptureService.INSTANCE;
            auditIntrospection = com.telamin.mongoose.internal.NoOpAuditIntrospectionService.INSTANCE;
        }
        registerService(new Service<>(
                auditCapture,
                com.telamin.mongoose.service.audit.MongooseAuditCaptureService.class,
                com.telamin.mongoose.service.audit.MongooseAuditCaptureService.SERVICE_NAME));
        registerService(new Service<>(
                auditIntrospection,
                com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService.class,
                com.telamin.mongoose.service.audit.MongooseAuditIntrospectionService.SERVICE_NAME));
        this.auditCaptureService = auditCapture;
        this.auditCaptureConfig = auditCfg;

        // Health service — sibling to counters. Verdict (UP/DEGRADED/DOWN/
        // UNKNOWN) per service that the admin UI badges + /api/health
        // surface. Phase 4.5a: skeleton (registration, silencing,
        // aggregation, error sinks). Phase 4.5b adds built-in checks +
        // counter-snapshot ring. Registered now so consumers can rely on
        // it being present even with the skeleton in place.
        com.telamin.mongoose.service.health.MongooseHealthService health =
                new com.telamin.mongoose.internal.DefaultMongooseHealth(counters);
        registerService(new Service<>(
                health,
                com.telamin.mongoose.service.health.MongooseHealthService.class,
                com.telamin.mongoose.service.health.MongooseHealthService.SERVICE_NAME));

        // Self-register the introspection service so consumers can pick it up
        // via @ServiceRegistered regardless of how the server is constructed
        // (programmatic or YAML-driven). The impl reads the composing-agent
        // maps and flow manager by reference, so every call sees current state.
        com.telamin.mongoose.service.introspection.MongooseIntrospectionService introspection =
                new com.telamin.mongoose.internal.DefaultMongooseIntrospection(
                        composingEventProcessorAgents, composingServiceAgents, flowManager);
        registerService(new Service<>(
                introspection,
                com.telamin.mongoose.service.introspection.MongooseIntrospectionService.class,
                com.telamin.mongoose.service.introspection.MongooseIntrospectionService.SERVICE_NAME));
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
     * Whether a processor whose {@code start()} throws while its group starts fails that start, and so the server's
     * boot (review of #66). True until the server has started, unless
     * {@link MongooseServerConfig#isIsolateProcessorStartFailuresAtBoot()} opts into isolating it; false once started,
     * when a processor that fails to start is always isolated (reported and dropped).
     */
    public boolean failsBootOnProcessorStartFailure() {
        LifecycleState state = lifecycleState.get();
        boolean booting = state == LifecycleState.NEW || state == LifecycleState.STARTING;
        return booting && (mongooseServerConfig == null || !mongooseServerConfig.isIsolateProcessorStartFailuresAtBoot());
    }

    /**
     * A processor group dropped {@code processorName} after its {@code start()} failed (mongoose#57). Called on the
     * group's thread, once the group has released what it holds for the processor; this releases what the server bound
     * to it by name ({@link #releaseProcessorBindings}) and its name reservation (mongoose#60), so the name can be
     * added again.
     */
    public void processorDropped(String groupName, String processorName, DataFlow eventProcessor, ProcessorRegistration registration) {
        log.info("processor '" + processorName + "' dropped from group '" + groupName + "' after a failed start");
        try {
            releaseProcessorBindings(processorName, eventProcessor);
        } finally {
            if (registration != null) reservedProcessorNames.remove(new ProcessorName(groupName, processorName), registration);
        }
    }

    /**
     * A processor group stopped {@code processorName} ({@link #stopProcessor}, mongoose#71). Called on the group's
     * thread, once the group has stopped the processor and released what it holds for it; this releases what the server
     * bound to it: the audit capture forgets the instance and a capture still recording is ended.
     * <p>
     * It does not free a processor-name reservation (mongoose#60): the accepted stop request frees its own registration
     * when consumed on the group's thread. Freeing by name here could free a newer registration under that name.
     */
    public void processorStopped(String groupName, String processorName, DataFlow eventProcessor) {
        log.info("processor '" + processorName + "' stopped in group '" + groupName + "'");
        // the audit capture forgets the torn-down instance first (mongoose#46), so nothing is installed on it again and
        // an audit.start for it is refused; then a capture still recording is ended and its queue closed without
        // touching the instance (mongoose#71), rather than left open for a processor that has gone
        // routed capture (design "Audit capture"): the group's control processor already uninstalled the capture and
        // handed it to the closer as part of the stop; the service only forgets the instance (and, unrouted, stops)
        auditCaptureService.processorStopped(processorName, eventProcessor);
    }

    /**
     * Release what the server bound to a processor that left its group, stopped or dropped: its audit-capture binding.
     * Anything else the server binds per processor is to be released here too, so both paths release it.
     */
    private void releaseProcessorBindings(String processorName, DataFlow eventProcessor) {
        auditCaptureService.detach(processorName, eventProcessor);
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
    @Override
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
    /** Single-service variant required by {@link MongooseServerController}.
     *  Delegates to the varargs form so the broadcast + injection
     *  logic stays in one place. */
    @Override
    public void registerService(Service<?> service) {
        registerService(new Service<?>[] { service });
    }

    public void registerService(Service<?>... services) {
        for (Service<?> service : services) {
            Map<String, com.telamin.mongoose.control.ControlRequest> sent;
            java.util.concurrent.locks.Lock read = admitServiceChange();
            if (read == null) {
                throw new IllegalStateException("server is stopping: service '" + service.serviceName() + "' was not registered");
            }
            try {
                // design PR 4, as reviewed: the service lock covers only bookkeeping and non-blocking sends; injection
                // and every other callback into user code runs outside it
                serviceLock.lock();
                try {
                    reserveServiceName(service);
                } finally {
                    serviceLock.unlock();
                }
                prepareService(service);
                serviceLock.lock();     // registry state and broadcast publication in one order
                try {
                    // removed meanwhile: its deregistration was published, so publishing this would resurrect it
                    sent = registeredServices.get(service.serviceName()) == service ? sendBroadcast(service, true) : Map.of();
                } finally {
                    serviceLock.unlock();
                }
            } finally {
                read.unlock();
            }
            awaitBroadcast(service, true, sent);   // never under a lock
        }
    }

    /**
     * Admission for a service change, as for an add (design "Bounded shutdown"): the read lock, taken without waiting on
     * an agent thread. @return the held lock, or null when the server is stopping (the stop owns the services then)
     */
    private java.util.concurrent.locks.Lock admitServiceChange() {
        java.util.concurrent.locks.Lock read = admission.readLock();
        boolean locked = isServerAgentThread(Thread.currentThread()) ? read.tryLock() : lockUninterruptibly(read);
        if (!locked) return null;
        if (isStopRequested()) {
            read.unlock();
            return null;
        }
        return read;
    }

    private void reserveServiceName(Service<?> service) {
        String serviceName = service.serviceName();
        log.info("registerService:" + service);
        // mongoose#60: reserve the name atomically, so of two concurrent registrations exactly one wins
        if (registeredServices.putIfAbsent(serviceName, service) != null) {
            throw new com.telamin.mongoose.exception.ServiceRegistrationException("cannot register service name is already assigned:" + serviceName);
        }
    }

    /** Inject and instrument a service whose name is reserved: user callbacks, so never under the service lock. */
    private void prepareService(Service<?> service) {
        {
            String serviceName = service.serviceName();
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
            // Phase 4.5b: auto-allocate the standard per-service counters and
            // register the built-in "up" check. Guarded against bootstrap
            // order — counters + health get registered first, but this
            // method also runs for them; skip self-registration cycles.
            ensureServiceCountersAndHealth(serviceName, instance);
            // Dynamic-service broadcast (sent by the caller, under the lock): notify every already-running processor
            // group so processor-internal nodes carrying @ServiceRegistered receive the new service via their own agent
            // thread. No-op for the boot path because no agents are running yet — those groups pick up
            // registeredServices at processor-join time via ComposingEventProcessorAgent.checkForAdded.
        }
    }

    /**
     * Queue a service (de)registration for every processor group. All or report (review of #67): every group is
     * attempted, so one stalled group does not keep the others from the service; a group whose control queue cannot
     * take it (its thread is not draining, see {@code ComposingEventProcessorAgent.enqueue}) is skipped, and the groups
     * skipped are reported together, once, through {@code ErrorReporting}. The registry is left as the call made it -
     * the service registered (or removed) - because it is the source of truth: a processor that joins any group later
     * is given the registry's services, and the injection into other services already done cannot be undone.
     */
    /** Publish a service transition to every processor group, with its version; called under the service lock. */
    private Map<String, com.telamin.mongoose.control.ControlRequest> sendBroadcast(Service<?> service, boolean register) {
        long version = serviceVersions.computeIfAbsent(service.serviceName(), n -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
        List<String> missed = new ArrayList<>();
        Map<String, com.telamin.mongoose.control.ControlRequest> sent = new java.util.LinkedHashMap<>();
        composingEventProcessorAgents.forEach((groupName, runner) -> {
            try {
                // never waits: a full mailbox is a group that missed it, reported below, not a wait under the lock
                sent.put(groupName, runner.group().tryBroadcastService(service, register, version));
            } catch (IllegalStateException notQueued) {
                missed.add(groupName);
                log.fine("group " + groupName + " not told of service " + service.serviceName() + ": " + notQueued);
            }
        });
        if (!missed.isEmpty()) {
            com.telamin.mongoose.service.error.ErrorReporting.report("MongooseServer",
                    "service '" + service.serviceName() + "' is " + (register ? "registered" : "removed")
                            + ", but processor group(s) " + missed + " could not be told (control queue full, group not"
                            + " draining): their running processors were not " + (register ? "given" : "relieved of")
                            + " it", null, com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
        }
        return sent;
    }

    /**
     * Await every group's reply to a broadcast under one deadline, where waiting is safe; never under the lock.
     *
     * @return true when every group confirmed it
     */
    private boolean awaitBroadcast(Service<?> service, boolean register, Map<String, com.telamin.mongoose.control.ControlRequest> sent) {
        // design PR 3: sent to every group first, then awaited together under one deadline, where waiting is safe
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(controlWaitMillis());
        boolean all = true;
        for (var entry : sent.entrySet()) {
            String groupName = entry.getKey();
            if (entry.getValue().reply().isDone()) continue;
            if (!safeToWait(groupName)) {
                all = false;
                continue;
            }
            long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0 || com.telamin.mongoose.control.ControlReplies.await(entry.getValue(), left) == null) {
                all = false;
                log.warning("group " + groupName + " did not confirm service " + service.serviceName()
                        + (register ? " registered" : " removed") + " within the deadline; it still applies");
            }
        }
        return all;
    }

    private long controlWaitMillis() {
        return mongooseServerConfig == null ? MongooseServerConfig.DEFAULT_CONTROL_WAIT_MILLIS
                : mongooseServerConfig.getControlWaitMillis();
    }


    /**
     * Deregister a previously registered service.
     * <p>
     * Removes the service from the global registry, calls {@code stop()}
     * on it, flips the {@code service.{name}.up} gauge to 0, and
     * broadcasts {@code @ServiceDeregistered} to every running processor
     * group so processor-internal nodes (SubscriptionManagerNode,
     * SinkPublisher, custom nodes) can unbind.
     * <p>
     * Distinct from {@link #stopService(String)}: stopService is the
     * lifecycle-pause callback (service can be restarted, stays in the
     * registry), removeService is the destructive deregistration.
     * <p>
     * No-op if {@code serviceName} isn't registered.
     */
    public void removeService(String serviceName) {
        log.info("removeService:" + serviceName);
        Service<?> removed;
        Map<String, com.telamin.mongoose.control.ControlRequest> sent;
        java.util.concurrent.locks.Lock read = admitServiceChange();
        if (read == null) {
            // the stop stops every registered service once; removing it here too would stop it twice, concurrently
            log.info("removeService:" + serviceName + " ignored: the server is stopping and stops it");
            return;
        }
        try {
            serviceLock.lock();     // design PR 4: registry mutation and broadcast publication in one order
            try {
                removed = registeredServices.remove(serviceName);
                if (removed == null) return;
                sent = sendBroadcast(removed, false);
            } finally {
                serviceLock.unlock();
            }
        } finally {
            read.unlock();
        }
        // processors unbind before the service stops: wait (bounded, where safe) for the groups to apply it; a caller
        // that cannot wait, or a group that did not confirm in time, defers the stop until every group has answered
        // (review of PR 4: stopping it at once would stop it beneath processors still holding it)
        if (awaitBroadcast(removed, false, sent)) {
            stopRemovedService(removed);
            return;
        }
        java.util.concurrent.CompletableFuture<?>[] replies = sent.values().stream()
                .map(com.telamin.mongoose.control.ControlRequest::reply).toArray(java.util.concurrent.CompletableFuture[]::new);
        java.util.concurrent.CompletableFuture.allOf(replies)
                .orTimeout(REMOVED_SERVICE_STOP_DEFER_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenCompleteAsync((done, timedOut) -> {
                    if (timedOut != null) {
                        log.warning("service '" + serviceName + "' is stopped although not every group confirmed it was"
                                + " withdrawn within " + REMOVED_SERVICE_STOP_DEFER_MILLIS + " ms");
                    }
                    stopRemovedService(removed);
                }, replyExecutor);
    }

    /** How long a deferred stop of a removed service waits for every group to confirm the withdrawal. */
    static final long REMOVED_SERVICE_STOP_DEFER_MILLIS = 60_000;

    private void stopRemovedService(Service<?> removed) {
        try {
            removed.stop();
        } catch (Exception ex) {
            log.warning("error stopping service '" + removed.serviceName() + "' during remove: " + ex);
        }
        flipServiceUp(removed.serviceName(), 0);
    }

    /**
     * Allocate the per-service {@code service.{name}.up} gauge (and the
     * {@code lastTickEpoch / eventsProcessed / errors} write convention
     * counters) on the counters service, and register the built-in "up"
     * health check via the health service. Both services live in
     * {@code registeredServices} — looked up dynamically so this works
     * regardless of registration order during bootstrap.
     *
     * <p>Skips when:
     * <ul>
     *     <li>the service being registered IS the counters or health service
     *         (bootstrap cycle);</li>
     *     <li>counters or health service isn't registered yet (won't happen
     *         in normal boot, but defensive against alternative orderings).</li>
     * </ul>
     */
    private void ensureServiceCountersAndHealth(String serviceName, Object instance) {
        if (instance instanceof com.telamin.mongoose.service.counters.MongooseCountersService
                || instance instanceof com.telamin.mongoose.service.health.MongooseHealthService) {
            return; // bootstrap services don't get their own auto-counters
        }
        Service<?> countersSvc = registeredServices.get(
                com.telamin.mongoose.service.counters.MongooseCountersService.SERVICE_NAME);
        Service<?> healthSvc = registeredServices.get(
                com.telamin.mongoose.service.health.MongooseHealthService.SERVICE_NAME);
        if (countersSvc == null || healthSvc == null) return;
        var counters = (com.telamin.mongoose.service.counters.MongooseCountersService) countersSvc.instance();
        var health = (com.telamin.mongoose.service.health.MongooseHealthService) healthSvc.instance();

        // Allocate all four standard counter handles. Even with the no-op
        // service this is cheap — the no-op returns the shared sentinel
        // counter. .up starts at 0 (service hasn't started yet).
        counters.counter(com.telamin.mongoose.internal.DefaultMongooseHealth.upGaugeLabel(serviceName));
        counters.counter(com.telamin.mongoose.internal.DefaultMongooseHealth.lastTickEpochLabel(serviceName));
        counters.counter(com.telamin.mongoose.internal.DefaultMongooseHealth.eventsProcessedLabel(serviceName));
        counters.counter(com.telamin.mongoose.internal.DefaultMongooseHealth.errorsLabel(serviceName));

        // Register the built-in checks. Currently just "up"; liveness +
        // error-spike + connected land in a follow-up.
        if (health instanceof com.telamin.mongoose.internal.DefaultMongooseHealth dh) {
            dh.registerBuiltinChecks(serviceName);
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
        java.util.concurrent.locks.Lock read = admitServiceChange();
        if (read == null) {
            // a group created after the stop's snapshot would never be closed
            throw new IllegalStateException("server is stopping: worker service " + service + " was not registered");
        }
        try {
            registerWorkerServiceAdmitted(service);
        } finally {
            read.unlock();
        }
    }

    private void registerWorkerServiceAdmitted(ServiceAgent<?> service) {
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
                    return new ComposingWorkerServiceAgentRunner(group, groupRunner, idleStrategy);
                });

        if (service.exportedService().instance() instanceof EventSource) {
            eventSourceAgentGroups.add(agentGroup);
        } else {
            nonEventSourceAgentGroups.add(agentGroup);
        }
        composingAgentRunner.group().registerServer(service);
    }

    /**
     * RECORD mode needs an empty journal and store: a run numbers its items from 1 again, so a second recording into
     * the same files would overwrite the first one's items and append after its entries, and replay one as the other.
     */
    private static void refuseRecordingOverARecording(ReplayConfig replay) {
        if (replay == null || replay.mode() != ReplayConfig.Mode.RECORD) return;
        if (replay.journal() != null && replay.journal().holdsRecording()) {
            throw new IllegalStateException("replay RECORD: the journal already holds a recording (or a torn one); record into an empty one");
        }
        if (replay.store().holdsRecording()) {
            throw new IllegalStateException("replay RECORD: the store already holds a recording (or a torn one, or one in an earlier format); record into an empty one");
        }
    }

    /** REPLAY mode: each group's replay driver, by group name (for status and tests). */
    public java.util.Map<String, GroupReplayer> replayers() {
        java.util.Map<String, GroupReplayer> out = new java.util.HashMap<>();
        composingEventProcessorAgents.forEach((name, runner) -> {
            if (runner.group().replayer() != null) out.put(name, runner.group().replayer());
        });
        return out;
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
        // admission against shutdown (design "Bounded shutdown"): agent threads never wait for the lock
        java.util.concurrent.locks.Lock read = admission.readLock();
        boolean locked = isServerAgentThread(Thread.currentThread()) ? read.tryLock() : lockUninterruptibly(read);
        if (!locked || isStopRequested()) {
            if (locked) read.unlock();
            throw new IllegalStateException("server is stopping: event processor '" + processorName + "' was not added to group '"
                    + groupName + "'");
        }
        com.telamin.mongoose.control.ControlRequest request;
        try {
            request = admitEventProcessor(processorName, groupName, idleStrategy, feedConsumer).request();
        } finally {
            read.unlock();
        }
        // design PR 3: wait for the outcome where waiting is safe; a timeout detaches, the add still applies
        com.telamin.mongoose.control.ControlReply reply = awaitWhereSafe(groupName, request);
        if (reply == null || reply.applied()) {
            return;
        }
        // deviation from the design's table (review of PR 3, finding 3): a failed start is isolated and reported, and
        // the void add returns, as since mongoose#57; callers that need the outcome use addProcessor's reply
        log.warning("event processor '" + processorName + "' in group '" + groupName + "' was not added: " + reply);
    }

    /**
     * Add a processor and return its outcome without waiting (design "Agent threads and replies"). Never throws for a
     * duplicate name or a stopping server: the reply says {@code REFUSED} or {@code NOT_RUNNING}. The stage completes off
     * every agent thread, so its dependents may block or call back into the server.
     */
    public AddedProcessor addProcessor(
            String processorName, String groupName, IdleStrategy idleStrategy, Supplier<DataFlow> feedConsumer) {
        java.util.concurrent.locks.Lock read = admission.readLock();
        boolean locked = isServerAgentThread(Thread.currentThread()) ? read.tryLock() : lockUninterruptibly(read);
        try {
            if (!locked || isStopRequested()) {
                return refusedAdd(processorName, com.telamin.mongoose.control.event.ReplyOutcome.NOT_RUNNING, "server is stopping");
            }
            Admission admission;
            try {
                admission = admitEventProcessor(processorName, groupName, idleStrategy, feedConsumer);
            } catch (IllegalArgumentException duplicate) {
                return refusedAdd(processorName, com.telamin.mongoose.control.event.ReplyOutcome.REFUSED, "name in use");
            } catch (IllegalStateException notQueued) {
                // a full mailbox or an ended group: refused, the name released by the admission
                return refusedAdd(processorName, com.telamin.mongoose.control.event.ReplyOutcome.REFUSED,
                        String.valueOf(notQueued.getMessage()));
            }
            // the handle of exactly this add's registration, not whatever answers to the name by the time it is asked for
            return new AddedProcessor(new ProcessorHandle(groupName, admission.registration()),
                    com.telamin.mongoose.control.ControlReplies.isolated(admission.request(), replyExecutor));
        } finally {
            if (locked) read.unlock();
        }
    }

    private static AddedProcessor refusedAdd(String processorName, com.telamin.mongoose.control.event.ReplyOutcome outcome,
                                             String reason) {
        return new AddedProcessor(null, java.util.concurrent.CompletableFuture.completedFuture(
                new com.telamin.mongoose.control.ControlReply(-1, outcome, reason, -1, processorName)));
    }

    /** An admitted add: the request sent, and the registration it was admitted under. */
    private record Admission(com.telamin.mongoose.control.ControlRequest request, ProcessorRegistration registration) { }

    /** The handle of the processor currently admitted as {@code processorName} in {@code groupName}, or null. */
    public ProcessorHandle processorHandle(String groupName, String processorName) {
        ProcessorRegistration registration = reservedProcessorNames.get(new ProcessorName(groupName, processorName));
        return registration == null ? null : new ProcessorHandle(groupName, registration);
    }

    /**
     * Stop exactly the processor a handle names, never a later one under the same name, and return the outcome without
     * waiting. A handle whose processor has gone is answered {@code APPLIED} ("alreadyStopped").
     */
    public java.util.concurrent.CompletionStage<com.telamin.mongoose.control.ControlReply> stopProcessor(ProcessorHandle handle) {
        var processorAgent = composingEventProcessorAgents.get(handle.group());
        if (processorAgent == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(new com.telamin.mongoose.control.ControlReply(
                    -1, com.telamin.mongoose.control.event.ReplyOutcome.REFUSED, "no such group", -1, handle.name()));
        }
        ProcessorName reservation = new ProcessorName(handle.group(), handle.name());
        ProcessorRegistration registration = handle.registration();
        return com.telamin.mongoose.control.ControlReplies.isolated(processorAgent.group().removeEventProcessorByName(
                handle.name(), registration, () -> reservedProcessorNames.remove(reservation, registration)), replyExecutor);
    }

    /**
     * Stop the processor admitted as {@code processorName}, waiting at most {@code waitMillis} for the outcome (admin
     * commands use 1 s). Returns the reply, or null if it did not come in time or nothing was admitted under the name.
     */
    public com.telamin.mongoose.control.ControlReply stopProcessor(String groupName, String processorName, long waitMillis) {
        com.telamin.mongoose.control.ControlRequest request = sendStop(groupName, processorName);
        if (request == null || !safeToWait(groupName)) return null;
        return com.telamin.mongoose.control.ControlReplies.await(request, waitMillis);
    }

    /** How long a void control method waits for its outcome before detaching (the request still applies). */
    static final long CONTROL_WAIT_MILLIS = 10_000;

    private com.telamin.mongoose.control.ControlReply awaitWhereSafe(String groupName,
                                                                     com.telamin.mongoose.control.ControlRequest request) {
        if (request == null || !safeToWait(groupName)) return null;
        com.telamin.mongoose.control.ControlReply reply = com.telamin.mongoose.control.ControlReplies.await(request, controlWaitMillis());
        if (reply == null) {
            log.warning("control request " + request.description() + " in group '" + groupName + "' not answered within "
                    + controlWaitMillis() + " ms; it still applies");
        }
        return reply;
    }

    /**
     * Waiting is safe only off every agent thread, startup and stop callback, on a group whose thread is running: a
     * group that has not started cannot answer yet, and its outcome surfaces when it starts.
     */
    private boolean safeToWait(String groupName) {
        Thread current = Thread.currentThread();
        if (com.telamin.mongoose.control.AgentThreads.isAgentThread() || isServerAgentThread(current)
                || current == startupThread || current == stopThread || lifecycleManager.isStopHelper(current)) {
            return false;
        }
        var runner = composingEventProcessorAgents.get(groupName);
        if (runner == null) return false;
        Thread agent = runner.groupRunner().thread();
        // a thread launched but not yet running (its runner shows no thread) will answer once it runs
        if (agent == null) return lifecycleManager.isLaunched(runner.groupRunner());
        return agent != AgentRunner.TOMBSTONE && agent.isAlive();
    }

    private static boolean lockUninterruptibly(java.util.concurrent.locks.Lock lock) {
        lock.lock();
        return true;
    }

    private Admission admitEventProcessor(String processorName, String groupName,
                                                                         IdleStrategy idleStrategy, Supplier<DataFlow> feedConsumer) {
        IdleStrategy idleStrategyOverride = mongooseServerConfig.getIdleStrategyOrDefault(groupName, idleStrategy);
        ComposingEventProcessorAgentRunner composingEventProcessorAgentRunner = composingEventProcessorAgents.computeIfAbsent(
                groupName,
                ket -> {
                    //build a subscriber group
                    // replay (spec-replay-recording R4): the group's scheduler follows the replay mode
                    ReplayConfig replayCfg = flowManager.getReplayConfig();
                    ComposingEventProcessorAgent group;
                    switch (replayCfg.mode()) {
                        case RECORD -> {
                            var recorder = new GroupRecorder(replayCfg, replayCfg.clock());
                            group = new ComposingEventProcessorAgent(groupName, flowManager, this,
                                    new RecordingScheduler(recorder), registeredServices, recorder, null);
                        }
                        case REPLAY -> {
                            var scheduler = new ReplayScheduler();
                            group = new ComposingEventProcessorAgent(groupName, flowManager, this, scheduler, registeredServices,
                                    null, routing -> new GroupReplayer(replayCfg, routing, scheduler));
                        }
                        default -> group = new ComposingEventProcessorAgent(groupName, flowManager, this, new DeadWheelScheduler(), registeredServices);
                    }
                    //threading to be configured by file
                    AtomicCounter errorCounter = new AtomicCounter(new UnsafeBuffer(new byte[4096]), 0);
                    //run subscriber group
                    AgentRunner groupRunner = new AgentRunner(
                            idleStrategyOverride,
                            errorHandler,
                            errorCounter,
                            group);
                    if (auditCaptureService instanceof com.telamin.mongoose.service.audit.AuditCaptureResources resources) {
                        group.setAuditCaptureResources(resources);
                    }
                    return new ComposingEventProcessorAgentRunner(group, groupRunner, idleStrategyOverride);
                });

        ProcessorName reservation = new ProcessorName(groupName, processorName);
        ProcessorRegistration registration = new ProcessorRegistration(processorName);
        // the control processor's ReleaseName effect frees the reservation, once its stop or failed start is complete
        registration.nameReleaser(() -> reservedProcessorNames.remove(new ProcessorName(groupName, processorName), registration));
        if (reservedProcessorNames.putIfAbsent(reservation, registration) != null) {
            log.warning("refusing to add event processor '" + processorName + "' to group '" + groupName
                    + "': the name is already assigned in that group");
            throw new IllegalArgumentException("cannot add event processor name is already assigned:" + processorName
                    + " in group:" + groupName);
        }

        com.telamin.mongoose.control.ControlRequest request;
        try {
            request = composingEventProcessorAgentRunner.group().addNamedEventProcessor(() -> {
                try {
                    return buildNamedEventProcessor(processorName, feedConsumer, composingEventProcessorAgentRunner);
                } catch (RuntimeException | Error e) {
                    reservedProcessorNames.remove(reservation, registration);
                    throw e;
                }
            }, registration);
        } catch (RuntimeException | Error e) {
            // Rejected before the supplier ran: its own failure cleanup cannot release this reservation.
            reservedProcessorNames.remove(reservation, registration);
            throw e;
        }

        if (isStarted() && composingEventProcessorAgentRunner.groupRunner().thread() == null) {
            log.info("staring event processor group:'" + groupName + "' for running server");
            if (isStopRequested()) {
                // a stop began after the admission check: the group is closed by the stop without its thread starting,
                // and its queued add is refused without running its supplier
                return new Admission(request, registration);
            }
            // two adds racing to a new group both get here; only one may start its thread
            lifecycleManager.startOnce(composingEventProcessorAgentRunner.groupRunner());
        }
        return new Admission(request, registration);
    }

    /**
     * Routes the capture service's start and stop to the group holding the processor, bound to the generation admitted
     * under the name, so a request can never reach a later processor under the same name.
     */
    private com.telamin.mongoose.internal.ChronicleAuditCaptureService.CaptureRouter captureRouter() {
        return new com.telamin.mongoose.internal.ChronicleAuditCaptureService.CaptureRouter() {
            @Override
            public com.telamin.mongoose.control.ControlRequest send(String processorName, boolean start) {
                for (var entry : reservedProcessorNames.entrySet()) {
                    if (!entry.getKey().name().equals(processorName)) continue;
                    var runner = composingEventProcessorAgents.get(entry.getKey().group());
                    if (runner == null) continue;
                    return start ? runner.group().auditStart(processorName, entry.getValue())
                            : runner.group().auditStop(processorName, entry.getValue());
                }
                return null;
            }

            @Override
            public boolean safeToWait(String processorName) {
                for (ProcessorName key : reservedProcessorNames.keySet()) {
                    if (key.name().equals(processorName)) return MongooseServer.this.safeToWait(key.group());
                }
                return false;
            }
        };
    }

    /** Build a processor from its supplier and wire it into the server (audit, perf monitor, capture). */
    private NamedEventProcessor buildNamedEventProcessor(String processorName, Supplier<DataFlow> feedConsumer,
                                                         ComposingEventProcessorAgentRunner composingEventProcessorAgentRunner) {
        DataFlow eventProcessor = feedConsumer.get();
        eventProcessor.setAuditLogProcessor(logRecordListener);
        // Auto-bind the PerformanceMonitorAudit (if present) to the YAML
        // processor name. Without this the audit's counter labels use
        // whatever string the SEP author passed to the ctor — which is
        // usually a different namespace from the YAML processorName and
        // breaks any UI / metric that joins the two by name.
        applyProcessorNameToPerfMonAuditor(eventProcessor, processorName);

        // Attach the audit-capture service to this processor. NoOp when
        // capture is disabled; the Chronicle impl remembers the DataFlow
        // so a later start(name) call can install a LogRecordListener
        // without the runtime needing to hand the DataFlow over again.
        // Hand the capture service the listener this server configured, so it can fan out to it
        // and restore it on stop. Taken here, at attach: logRecordListener is a static that each
        // bootServer call overwrites, so reading it later would restore another server's listener.
        // mongoose#46: the capture service changes this processor's audit sink on the group's own thread
        auditCaptureService.attach(eventProcessor, processorName, logRecordListener,
                composingEventProcessorAgentRunner.group()::runOnAgentThread);
        if (auditCaptureConfig != null && auditCaptureConfig.getAutoStart() != null
                && auditCaptureConfig.getAutoStart().contains(processorName)) {
            auditCaptureService.start(processorName);
        }
        if (isStarted()) {
            log.info("init event processor in already started server processor:'" + eventProcessor + "'");
//                eventProcessor.setAuditLogLevel(EventLogControlEvent.LogLevel.INFO);
        }
        return new NamedEventProcessor(processorName, eventProcessor);
    }

    /**
     * Look up the conventional {@code perfMon} auditor on the processor and,
     * if it's a {@link com.telamin.mongoose.service.counters.PerformanceMonitorAudit},
     * (a) align its counter-label prefix with the YAML processor name and
     * (b) bind it to the processor's own {@code clock} auditor as the
     * latency time source — so latency samples use the same replay-safe,
     * data-driven clock that the rest of the SEP uses, rather than the
     * static {@code Clock.DEFAULT_CLOCK} fallback.
     *
     * <p>Silent no-op when the auditor is absent (the user simply didn't
     * enable performance monitoring on this processor) or is some other
     * type.
     */
    private static void applyProcessorNameToPerfMonAuditor(DataFlow eventProcessor, String processorName) {
        try {
            Object auditor = eventProcessor.getAuditorById("perfMon");
            if (auditor instanceof com.telamin.mongoose.service.counters.PerformanceMonitorAudit pm) {
                pm.setProcessorName(processorName);
                // Bind the processor's clock as the latency time source.
                // Each generated SEP has a "clock" auditor; using it (rather
                // than Clock.DEFAULT_CLOCK) keeps latency samples coherent
                // with the processor's replay/data-driven clock policy.
                try {
                    Object clockAuditor = eventProcessor.getAuditorById("clock");
                    if (clockAuditor instanceof com.telamin.fluxtion.runtime.time.Clock clock) {
                        pm.setTimeSource(clock::getWallClockTime);
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {
                    // No "clock" on this processor — leave the auditor with
                    // its default time source (Clock.DEFAULT_CLOCK).
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // No "perfMon" auditor — performance monitoring not wired on this
            // processor. Counter writes go to the no-op service and stay
            // silent, which is the documented zero-overhead default.
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
        // design PR 3: wait for the outcome where waiting is safe; a timeout detaches, the stop still applies
        awaitWhereSafe(groupName, sendStop(groupName, processorName));
    }

    /** Send a stop bound, now, to the registration admitted under the name; null when nothing is admitted. */
    private com.telamin.mongoose.control.ControlRequest sendStop(String groupName, String processorName) {
        var processorAgent = composingEventProcessorAgents.get(groupName);
        if (processorAgent == null) return null;
        ProcessorName reservation = new ProcessorName(groupName, processorName);
        ProcessorRegistration registration = reservedProcessorNames.get(reservation);
        if (registration == null) return null;
        // A stop belongs to this accepted add, including before its supplier runs. Release the name only when that
        // stop is consumed, and never remove a newer reservation or stop a replacement under that name.
        return processorAgent.group().removeEventProcessorByName(processorName, registration,
                () -> reservedProcessorNames.remove(reservation, registration));
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
        // user code: never under the service lock (review of PR 4)
        Service<?> service = registeredServices.get(serviceName);
        if (service == null) return;
        service.start();
        // Per Phase 4.5b: up == 1 from startService return → stopService
        // call; 0 before/after. Built-in "up" check reads this gauge.
        flipServiceUp(serviceName, 1);
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
        Service<?> service = registeredServices.get(serviceName);
        if (service == null) return;
        service.stop();
        flipServiceUp(serviceName, 0);
    }

    /**
     * Set the {@code service.{name}.up} gauge. No-op when the counters
     * service isn't operational — keeps this method cheap on the cold path
     * of start/stopService regardless of the performance-monitoring flag.
     */
    private void flipServiceUp(String serviceName, long value) {
        Service<?> countersSvc = registeredServices.get(
                com.telamin.mongoose.service.counters.MongooseCountersService.SERVICE_NAME);
        if (countersSvc == null) return;
        var counters = (com.telamin.mongoose.service.counters.MongooseCountersService) countersSvc.instance();
        if (!counters.isOperational()) return;
        counters.counter(com.telamin.mongoose.internal.DefaultMongooseHealth.upGaugeLabel(serviceName))
                .setOrdered(value);
    }

    /**
     * Get the registry of services by name.
     *
     * @return a read-only view of service name to service instance (design D4: the registry is changed only through
     *         registerService/removeService, under the service lock)
     */
    @Override
    public Map<String, Service<?>> registeredServices() {
        return Collections.unmodifiableMap(registeredServices);
    }

    /** Records a configured pipe so {@link #registeredPipes()} can
     *  return it. Called by {@code ServerConfigurator} after both
     *  underlying service registrations succeed. */
    public void recordPipeRegistration(PipeRegistration registration) {
        registeredPipes.add(registration);
    }

    @Override
    public List<PipeRegistration> registeredPipes() {
        return Collections.unmodifiableList(new ArrayList<>(registeredPipes));
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
        if (!lifecycleState.compareAndSet(LifecycleState.NEW, LifecycleState.STARTING)) {
            throw new IllegalStateException("server cannot start: it is " + lifecycleState.get()
                    + " (a server starts once and is not restarted after stop)");
        }
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
        long startupTimeoutMillis = mongooseServerConfig == null
                ? MongooseServerConfig.DEFAULT_STARTUP_TIMEOUT_MILLIS
                : mongooseServerConfig.getStartupTimeoutMillis();
        try {
            startupThread = Thread.currentThread();
            lifecycleManager.start(registeredServices, serviceGroups, processorGroups, flowManager, registeredAgentServices,
                    startupTimeoutMillis);
        } catch (Throwable t) {
            if (isStopRequested() && !(t.getMessage() != null && t.getMessage().startsWith(LifecycleManager.STOPPED_DURING_START))) {
                throw new IllegalStateException(LifecycleManager.STOPPED_DURING_START, t);
            }
            throw t;   // the state stays STARTING: stop() still cleans up what a failed start left running
        } finally {
            startupThread = null;
        }
        // Flip the per-service "up" gauge to 1 for every service that's been
        // started via the lifecycle manager. The startService() entry point
        // handles per-call flips; this covers the global start() path that
        // the lifecycle uses on boot.
        for (String name : registeredServices.keySet()) {
            flipServiceUp(name, 1);
        }
        // a stop that landed while starting has already shut everything down: never mark that server started
        if (!lifecycleState.compareAndSet(LifecycleState.STARTING, LifecycleState.STARTED)) {
            throw new IllegalStateException(LifecycleManager.STOPPED_DURING_START);
        }

        // Pick up any event processor groups registered DURING service.start() — e.g. by
        // svc-loader-yaml's EventHandlerLoader, which calls addEventProcessor() from inside
        // its own start() hook. Those groups land in composingEventProcessorAgents after
        // the processorGroups snapshot above has already been passed to LifecycleManager,
        // so without this pass their agent thread would never be started and the loaded
        // processor would receive no events.
        for (var entry : composingEventProcessorAgents.entrySet()) {
            String groupName = entry.getKey();
            var runner = entry.getValue();
            if (!isStarted()) {
                log.info("server stopped after startup; skipping remaining late processor groups");
                return;
            }
            if (runner.groupRunner().thread() == null) {
                log.info("late-starting event processor agent registered during boot: " + groupName);
                try {
                    lifecycleManager.startAgent(runner.groupRunner());
                } catch (IllegalStateException refused) {
                    if (!isStopRequested()) throw refused;
                    log.info("server stopped after startup; skipping late processor group " + groupName);
                    return;
                }
            }
        }
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
     * Stops the server and all its components, in order (mongoose#56): the feeds stop (agent groups hosting only feeds
     * close, feeds sharing a group with other services are taken off it, then every event source is stopped and torn
     * down); events already queued to processors drain, bounded by
     * {@link MongooseServerConfig#getShutdownDrainTimeoutMillis()}; each processor group stops and tears down its
     * processors on its own thread; then the remaining agent hosted services and registered services stop.
     * <p>
     * The stop runs once. Called from an ordinary thread, it returns when the server has stopped, and so does a call
     * that races an in-progress stop. Called from one of the server's own agent threads (a processor's event handler, an
     * admin command, an agent hosted service), or from a synchronous startup callback, it cannot wait for itself: it
     * hands the stop to a new
     * {@code mongoose-server-stop} thread and returns at once. Use {@link #awaitStopped(long, java.util.concurrent.TimeUnit)} from another
     * thread to wait for it to finish.
     */
    public void stop() {
        shutdown();
    }

    /**
     * Stop the server, as {@link #stop()}, and say what the stop achieved (design "Bounded shutdown"). The whole stop
     * is bounded by {@link MongooseServerConfig#getShutdownTimeoutMillis()}. A stop that runs out returns an incomplete
     * result naming what is still running, leaves the services a running processor group may use running, and the server
     * stays STOPPING; calling stop again resumes it. Called on one of the server's own threads, the stop is handed to a
     * {@code mongoose-server-stop} thread and the result says so.
     */
    public StopResult shutdown() {
        // a thread that cannot wait for the stop: an agent thread, a startup or stop callback, or one inside an add
        // holding the admission lock (the stop's barrier needs the write lock, and a read lock cannot be upgraded)
        boolean onAgentThread = isServerAgentThread(Thread.currentThread()) || Thread.currentThread() == startupThread
                || Thread.currentThread() == stopThread || lifecycleManager.isStopHelper(Thread.currentThread())
                || admission.getReadHoldCount() > 0;
        while (true) {
            LifecycleState state = lifecycleState.get();
            if (state == LifecycleState.NEW) {
                if (!lifecycleState.compareAndSet(LifecycleState.NEW, LifecycleState.STOPPED)) continue;
                // groups created for adds that never ran: close them on this thread (as Agrona does for a runner that
                // never started), so their queued requests are answered NOT_RUNNING without any supplier running
                log.info("server not started: closing the groups created before start");
                admission.writeLock().lock();
                admission.writeLock().unlock();
                composingEventProcessorAgents.forEach((name, runner) -> {
                    try {
                        runner.groupRunner().close();
                    } catch (Throwable t) {
                        log.log(java.util.logging.Level.WARNING, "group " + name + " failed to close", t);
                    }
                });
                lastStopResult = StopResult.completed();
                stopCompleted.countDown();
                return lastStopResult;
            }
            if (state == LifecycleState.STOPPED) {
                return StopResult.completed();
            }
            if (state == LifecycleState.STOPPING) {
                log.info("server stop already in progress, or incomplete and resumed");
                // on an ordinary thread: wait for the attempt in progress, then resume it if it ran out
                if (!onAgentThread) return runOrderedStop();
                // an incomplete attempt is resumed by nobody unless this hands it on (review of PR 2, finding 1)
                return handOffStop();
            }
            // STARTING (a start in progress, or one that failed) or STARTED
            if (lifecycleState.compareAndSet(state, LifecycleState.STOPPING)) {
                break;
            }
        }
        if (onAgentThread) {
            log.info("stop called on lifecycle or agent thread " + Thread.currentThread().getName()
                    + ", running the ordered shutdown on a separate thread");
            return handOffStop();
        }
        return runOrderedStop();
    }

    /** True while a handed-off stop thread is running or about to: one is enough, as attempts are serialised. */
    private final java.util.concurrent.atomic.AtomicBoolean handedOffStopRunning = new java.util.concurrent.atomic.AtomicBoolean();

    private StopResult handOffStop() {
        if (handedOffStopRunning.compareAndSet(false, true)) {
            Thread stopper = new Thread(() -> {
                try {
                    runOrderedStop();
                } finally {
                    handedOffStopRunning.set(false);
                }
            }, "mongoose-server-stop");
            stopper.setDaemon(false);
            stopper.start();
        }
        return StopResult.handedOffToStopThread();
    }

    /** The result of the last stop attempt, or null if none has run. */
    public StopResult lastStopResult() {
        return lastStopResult;
    }

    /** True once {@link #start()} has completed and until {@link #stop()} begins. */
    public boolean isStarted() {
        return lifecycleState.get() == LifecycleState.STARTED;
    }

    private boolean isStopRequested() {
        LifecycleState state = lifecycleState.get();
        return state == LifecycleState.STOPPING || state == LifecycleState.STOPPED;
    }

    /**
     * Wait, at most {@code timeout}, for a stop to finish: useful after a {@link #stop()} that was called on an agent
     * thread and so returned before the server had stopped.
     *
     * @return true once the ordered shutdown has completed, false if it has not (or no stop was requested) in time;
     *         an incomplete stop leaves this false until a later stop completes
     */
    public boolean awaitStopped(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        return stopCompleted.await(timeout, unit);
    }

    /** One stop attempt at a time; a concurrent caller waits for it, then resumes it if it ran out. */
    private StopResult runOrderedStop() {
        synchronized (stopAttempt) {
            if (lifecycleState.get() == LifecycleState.STOPPED) {
                return StopResult.completed();
            }
            // admission barrier: an add that passed its STOPPING check before the flip has finished creating its group
            // once this returns, so the snapshot below includes it; every later add sees STOPPING and is refused
            admission.writeLock().lock();
            admission.writeLock().unlock();
            stopThread = Thread.currentThread();
            StopResult result;
            try {
                long drainTimeoutMillis = mongooseServerConfig == null
                        ? MongooseServerConfig.DEFAULT_SHUTDOWN_DRAIN_TIMEOUT_MILLIS
                        : mongooseServerConfig.getShutdownDrainTimeoutMillis();
                long shutdownTimeoutMillis = mongooseServerConfig == null
                        ? MongooseServerConfig.DEFAULT_SHUTDOWN_TIMEOUT_MILLIS
                        : mongooseServerConfig.getShutdownTimeoutMillis();
                result = lifecycleManager.stop(true, toGroupRunnerMap(composingEventProcessorAgents),
                        toGroupRunnerMap(composingServiceAgents), registeredServices, flowManager, drainTimeoutMillis,
                        shutdownTimeoutMillis);
            } catch (RuntimeException | Error e) {
                log.log(java.util.logging.Level.WARNING, "server stop failed; it stays STOPPING and may be resumed", e);
                result = StopResult.incomplete(null, java.util.List.of("<stop failed: " + e + ">"), java.util.List.of());
            } finally {
                stopThread = null;
            }
            if (result.complete()) {
                // the processors have stopped: close every audit capture still recording, so its queue is flushed and
                // closed rather than left open until the JVM exits
                try {
                    auditCaptureService.stopAll();
                } catch (RuntimeException e) {
                    log.log(java.util.logging.Level.WARNING, "audit capture could not be stopped at server stop", e);
                }
                lastStopResult = result;            // before the latch: a woken awaitStopped reads this attempt
                lifecycleState.set(LifecycleState.STOPPED);
                stopCompleted.countDown();
                replyExecutor.shutdown();           // queued completions still run; later ones get a thread of their own
            } else {
                log.warning("server stop incomplete: " + result);
                lastStopResult = result;
            }
            return result;
        }
    }

    private void awaitStopCompletedUninterruptibly() {
        boolean interrupted = false;
        while (true) {
            try {
                stopCompleted.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** True when {@code thread} runs one of this server's agent groups, including while that group closes. */
    private boolean isServerAgentThread(Thread thread) {
        if (thread == Thread.currentThread() && com.telamin.mongoose.control.AgentThreads.isAgentOf(this)) return true;
        for (ComposingEventProcessorAgentRunner runner : composingEventProcessorAgents.values()) {
            if (runner.groupRunner().thread() == thread) return true;
        }
        for (ComposingWorkerServiceAgentRunner runner : composingServiceAgents.values()) {
            if (runner.groupRunner().thread() == thread) return true;
        }
        return false;
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

                    @Override
                    public boolean hostsEventSource() {
                        return eventSourceAgentGroups.contains(k);
                    }

                    @Override
                    public boolean hostsOnlyEventSources() {
                        return eventSourceAgentGroups.contains(k) && !nonEventSourceAgentGroups.contains(k);
                    }

                    @Override
                    public boolean removeEventSources(long timeoutMillis) throws InterruptedException {
                        return cws.group().removeHostedAgents(
                                agent -> agent.exportedService().instance() instanceof EventSource, timeoutMillis);
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
