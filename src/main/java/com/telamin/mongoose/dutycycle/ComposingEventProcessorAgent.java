/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.control.ControlAuditSink;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.control.ControlReply;
import com.telamin.mongoose.control.ControlRequest;
import com.telamin.mongoose.control.effect.ControlEffect;
import com.telamin.mongoose.control.effect.EffectAdapter;
import com.telamin.mongoose.control.event.AddIntent;
import com.telamin.mongoose.control.event.AuditStartIntent;
import com.telamin.mongoose.control.event.AuditStopIntent;
import com.telamin.mongoose.control.event.BroadcastIntent;
import com.telamin.mongoose.control.event.CaptureCompletion;
import com.telamin.mongoose.control.event.ControlOutcome;
import com.telamin.mongoose.control.event.EffectResult;
import com.telamin.mongoose.control.event.ReplyOutcome;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.event.StopIntent;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.ReplayRoute;
import com.telamin.mongoose.replay.ReplayRouting;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import lombok.extern.java.Log;
import org.agrona.concurrent.DynamicCompositeAgent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Composite agent that manages named DataFlow instances within an agent group.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Accepts registrations/removals of named event processors through one ordered control mailbox. Every lifecycle
 *       decision is made by the group's generated control processor ({@link ControlDriver},
 *       {@code src/main/fluxtion/control/control-processor.xml}); this class performs the effects it requests, as its
 *       {@link EffectAdapter}, and never decides whether to perform one</li>
 *   <li>Wires processors to event queues via the EventFlowManager on first subscription</li>
 *   <li>Registers shared services and a SchedulerService into each processor</li>
 *   <li>Adds/removes queue reader agents dynamically as subscriptions change</li>
 * </ul>
 * <h2>Failure isolation (mongoose#57)</h2>
 * One processor's failure is kept from the group's agent error handler (under bootServer, {@code GlobalErrorHandler},
 * which exits the JVM), reported once through {@code ErrorReporting} at {@code ERROR}, and the rest of the group runs on:
 * <ul>
 *   <li>a failed {@code start()} of a processor added at runtime: the processor is dropped and everything the group
 *       holds for it released (its tearDown, subscriptions, timers, replay bindings, the server's bindings);</li>
 *   <li>a failed {@code start()} at boot, among the processors queued before the group starts: <b>fails the boot by
 *       default</b>. The processor is dropped as above and a {@link ProcessorStartException} naming the group and the
 *       processor is thrown out of {@code onStart}, so it reaches the agent's error handler and the group does not
 *       become active. {@code MongooseServerConfig.isolateProcessorStartFailuresAtBoot} opts into isolating it
 *       instead, so the server boots without it;</li>
 *   <li>a failed {@code stop()}/{@code tearDown()}: the processor is still removed and everything the group holds for
 *       it released, as for a stop that succeeds (mongoose#71);</li>
 *   <li>a failed {@code @ServiceRegistered}/{@code @ServiceDeregistered} handler: reported; the processor keeps running;</li>
 *   <li>a failed timer callback: reported by the scheduler, naming the processor; the other timers fire.</li>
 * </ul>
 * Every {@code Throwable} is caught on these paths except {@link VirtualMachineError} ({@code OutOfMemoryError},
 * {@code StackOverflowError}, ...), which propagates to the agent's error handler as before.
 */
@Experimental
@Log
public class ComposingEventProcessorAgent extends DynamicCompositeAgent
        implements EventFeed<EventSubscriptionKey<?>>, EffectAdapter {

    private final EventFlowManager eventFlowManager;
    private final ConcurrentHashMap<String, Service<?>> registeredServices;
    private final ConcurrentHashMap<String, NamedEventProcessor> registeredEventProcessors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventSubscriptionKey<?>, EventQueueToEventProcessor> queueProcessorMap = new ConcurrentHashMap<>();
    /**
     * The control mailbox (mongoose#58, design "One mailbox"): fed from several threads at once - admin, loaders, and
     * every service-agent thread registering its service at boot - and drained by this group's thread alone. A full
     * mailbox is waited on, never dropped.
     */
    static final int CONTROL_QUEUE_CAPACITY = 4096;
    /**
     * At most this many requests per drain, so a burst of control cannot stall events. A time budget can also be set
     * ({@link #setControlDrainBudget}); it is off by default, because one slow effect (a processor's {@code start()},
     * first-use class loading) would otherwise push every request behind it to a later duty cycle.
     */
    static final int DRAIN_MAX = 64;
    private volatile long drainBudgetNanos = Long.MAX_VALUE;
    private final ControlDriver control;
    private final AtomicLong generations = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicInteger> incarnations = new ConcurrentHashMap<>();
    /** Registration identity is used only for lifecycle operations, never for event delivery. Agent thread only. */
    private final java.util.Map<NamedEventProcessor, ProcessorRegistration> registrations = new java.util.IdentityHashMap<>();
    /** Agent thread only: what each generation built, the registration it answers to, and the live name index. */
    private final Map<Long, NamedEventProcessor> processorsByGeneration = new HashMap<>();
    private final Map<Long, ProcessorRegistration> registrationsByGeneration = new HashMap<>();
    private final List<EventQueueToEventProcessor> queueReadersToAdd = new ArrayList<>();
    /**
     * mongoose#60 (item 1): the services each processor has been handed, by identity, so each is handed over once. A
     * service registered while the group starts is both in {@code registeredServices} (read when a processor joins)
     * and queued for broadcast; without this the processor got it from both. A register that threw counts as delivered
     * (see {@link #deliverService}). Agent thread only.
     */
    private final java.util.Map<DataFlow, java.util.Set<Service<?>>> servicesDelivered = new java.util.IdentityHashMap<>();
    /** mongoose#55: readers whose last listener left, waiting for the composite to take them out. Agent thread only. */
    private final List<EventQueueToEventProcessor> queueReadersToRemove = new ArrayList<>();
    private final MongooseServer mongooseServer;
    private final DeadWheelScheduler scheduler;
    private final Service<SchedulerService> schedulerService;
    // Counters allocated at construction so the doWork() hot path is one
    // cached-field deref + counter increment, no map lookup. Resolved via
    // the EFM-owned counters service which the MongooseServer ctor
    // installs before any agent is constructed.
    private final MongooseCounter eventsProcessedCounter;
    private final MongooseCounter idleCyclesCounter;
    /** Replay (spec-replay-recording): at most one of these is set, by the server's replay mode. */
    private final GroupRecorder recorder;
    private final GroupReplayer replayer;
    private volatile Thread agentThread;
    /** mongoose#46: set as the group closes; hand-offs are refused after it. */
    private volatile boolean closed;
    /** Without a server: isolate a start failure in onStart instead of failing the group's start (review of #66). */
    private volatile boolean isolateStartFailuresAtBoot;
    /** True only while onStart applies the requests accepted before the group started. Agent thread only. */
    private boolean bootDraining;
    /** Whether a boot start failure fails the group's start (else it is isolated and reported). Agent thread only. */
    private boolean bootFailFast;
    /** The first boot processor whose start failed, and why. Agent thread only. */
    private String bootFailureName;
    private Throwable bootFailure;
    /** Why the last BuildAndStart failed, for the boot failure report. Agent thread only. */
    private Throwable lastStartFailure;
    /** Guarded steps that failed during the current release. Agent thread only. */
    private int stepFailures;
    /** Audit capture (design "Audit capture"): the physical side, where helpers run, and the resources held by id. */
    private volatile com.telamin.mongoose.service.audit.AuditCaptureResources captureResources;
    private volatile com.telamin.mongoose.control.CaptureIo captureIo = com.telamin.mongoose.control.CaptureIo.shared();
    private volatile long captureOpenTimeoutMillis = 30_000;
    private final ConcurrentHashMap<Long, Object> captureHandles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, com.telamin.mongoose.control.CaptureIo.Cancellable> openTimeouts = new ConcurrentHashMap<>();
    private final AtomicLong captureResourceIds = new AtomicLong();
    private final AtomicLong completionIds = new AtomicLong();

    public ComposingEventProcessorAgent(String roleName,
                                        EventFlowManager eventFlowManager,
                                        MongooseServer mongooseServer,
                                        DeadWheelScheduler scheduler,
                                        ConcurrentHashMap<String, Service<?>> registeredServices) {
        this(roleName, eventFlowManager, mongooseServer, scheduler, registeredServices, null, null);
    }

    public ComposingEventProcessorAgent(String roleName,
                                        EventFlowManager eventFlowManager,
                                        MongooseServer mongooseServer,
                                        DeadWheelScheduler scheduler,
                                        ConcurrentHashMap<String, Service<?>> registeredServices,
                                        GroupRecorder recorder,
                                        java.util.function.Function<ReplayRouting,
                                                GroupReplayer> replayerFactory) {
        super(roleName, scheduler);
        this.recorder = recorder;
        this.replayer = replayerFactory == null ? null : replayerFactory.apply(routing());
        this.eventFlowManager = eventFlowManager;
        this.mongooseServer = mongooseServer;
        this.scheduler = scheduler;
        this.registeredServices = registeredServices;
        this.schedulerService = new Service<>(scheduler, SchedulerService.class);
        scheduler.setProcessorNames(this::nameOf);
        this.eventsProcessedCounter = eventFlowManager.getCountersService().agentEventsCounter(roleName);
        this.idleCyclesCounter = eventFlowManager.getCountersService().agentIdleCyclesCounter(roleName);
        this.control = new ControlDriver(roleName, CONTROL_QUEUE_CAPACITY, this, new ControlAuditSink());
        this.control.onOrphanCompletion(this::orphanCompletion);
    }

    // ============================================================================================ requests

    /** Add a processor whose name is only known once {@code initFunction} runs (no send-time admission). */
    public void addNamedEventProcessor(Supplier<NamedEventProcessor> initFunction) {
        addNamedEventProcessor(initFunction, null);
    }

    /**
     * Add a processor under a server-owned registration, so a delayed stop cannot address a replacement by name alone.
     * The registration's identity (generation, incarnation) is assigned here, at send time.
     *
     * @return the request, whose reply completes once the processor has started or failed
     */
    public ControlRequest addNamedEventProcessor(Supplier<NamedEventProcessor> initFunction, ProcessorRegistration registration) {
        Objects.requireNonNull(initFunction, "initFunction is null");
        if (registration != null) admit(registration, registration.name());
        ControlRequest request = new ControlRequest(ControlRequest.Kind.ADD,
                "add " + (registration == null || registration.name() == null ? "<unnamed>" : registration.name()),
                id -> addIntent(id, registration), new AddPayload(initFunction, registration));
        if (registration != null) request.onUnapplied(registration::releaseName);   // refused or swept unapplied
        return control.send(request);
    }

    /** Stop the processor currently registered as {@code name}, resolved when the stop is applied. */
    public void removeEventProcessorByName(String name) {
        removeEventProcessorByName(name, null, null);
    }

    /**
     * Stop the processor of {@code registration}, bound now. {@code completed} runs once the stop is answered,
     * including a stop of an add still queued, which cancels it.
     *
     * @return the request, whose reply completes once the stop is complete
     */
    public ControlRequest removeEventProcessorByName(String name, ProcessorRegistration registration, Runnable completed) {
        if (registration != null) admit(registration, name);
        ControlRequest request = new ControlRequest(ControlRequest.Kind.STOP, "stop " + name,
                id -> stopIntent(id, name, registration), registration);
        if (completed != null) request.onReply(completed);
        return control.send(request);
    }

    private void admit(ProcessorRegistration registration, String name) {
        if (registration.assigned()) {
            registration.assign(registration.generation(), registration.incarnation(), name);
            return;
        }
        int incarnation = name == null ? 0 : incarnations.computeIfAbsent(name, n -> new AtomicInteger()).incrementAndGet();
        registration.assign(generations.incrementAndGet(), incarnation, name);
    }

    /**
     * How long a producer waits on a full control mailbox for this group's thread to drain it before failing; 10 s by
     * default.
     */
    public void setControlQueueFullTimeout(java.time.Duration timeout) {
        control.setFullTimeout(Objects.requireNonNull(timeout, "timeout is null"));
    }

    /** Stop a drain early once this much time has passed (at least one request is always applied); null turns it off. */
    public void setControlDrainBudget(java.time.Duration budget) {
        this.drainBudgetNanos = budget == null ? Long.MAX_VALUE : budget.toNanos();
    }

    /** Queue a service for broadcast {@code @ServiceRegistered} dispatch on every processor in this group. Any thread. */
    public ControlRequest broadcastServiceRegistered(Service<?> service) {
        return broadcastService(service, true, 0);
    }

    /** Symmetric to {@link #broadcastServiceRegistered}: fires {@code @ServiceDeregistered} on every processor. */
    public ControlRequest broadcastServiceDeregistered(Service<?> service) {
        return broadcastService(service, false, 0);
    }

    /**
     * Deliver or withdraw a service with its registry version (design PR 4): a group applies a versioned broadcast only
     * if it is newer than the last it applied for that service; version 0 is unversioned and always applies.
     */
    public ControlRequest broadcastService(Service<?> service, boolean register, long version) {
        return control.send(broadcastRequest(service, register, version));
    }

    /** As {@link #broadcastService}, never waiting on a full mailbox: refused at once (sent under the service lock). */
    public ControlRequest tryBroadcastService(Service<?> service, boolean register, long version) {
        return control.trySend(broadcastRequest(service, register, version));
    }

    private ControlRequest broadcastRequest(Service<?> service, boolean register, long version) {
        return new ControlRequest(ControlRequest.Kind.BROADCAST,
                (register ? "register " : "deregister ") + service.serviceName(),
                id -> new BroadcastIntent(id, service.serviceName(), register, version), new BroadcastPayload(service, register));
    }

    // ------------------------------------------------------------------ audit capture

    /** Where this group's captures are opened, installed and closed; null leaves audit capture refused. */
    public void setAuditCaptureResources(com.telamin.mongoose.service.audit.AuditCaptureResources resources) {
        this.captureResources = resources;
    }

    /** Where opens, closes and open timeouts run; the shared helper threads by default. */
    public void setCaptureIo(com.telamin.mongoose.control.CaptureIo io) {
        this.captureIo = Objects.requireNonNull(io, "io is null");
    }

    /** How long an open may take before its audit start is answered Failed; 30 s by default. */
    public void setCaptureOpenTimeout(java.time.Duration timeout) {
        this.captureOpenTimeoutMillis = timeout.toMillis();
    }

    /**
     * Begin capturing a processor's audit: {@code registration}'s generation if given, else whichever processor answers
     * to {@code name} when the request is applied. The reply comes once the capture records, or not.
     */
    public ControlRequest auditStart(String name, ProcessorRegistration registration) {
        return control.send(new ControlRequest(ControlRequest.Kind.CAPTURE, "audit start " + name,
                id -> registration == null ? new AuditStartIntent(id, -1, name, false)
                        : new AuditStartIntent(id, registration.generation(), name, true), null));
    }

    /** End a processor's audit capture; the reply comes once it is uninstalled (its resource is closed after). */
    public ControlRequest auditStop(String name, ProcessorRegistration registration) {
        return control.send(new ControlRequest(ControlRequest.Kind.CAPTURE, "audit stop " + name,
                id -> registration == null ? new AuditStopIntent(id, -1, name, false)
                        : new AuditStopIntent(id, registration.generation(), name, true), null));
    }

    /** What an add needs to perform its effects: never seen by the control processor. */
    private record AddPayload(Supplier<NamedEventProcessor> init, ProcessorRegistration registration) { }

    private record BroadcastPayload(Service<?> service, boolean register) { }

    // ------------------------------------------------------------------ intents, built on the agent thread

    private AddIntent addIntent(long requestId, ProcessorRegistration registration) {
        if (registration == null) {
            long generation = generations.incrementAndGet();
            return new AddIntent(requestId, generation, 0, null, false, bootDraining, bootFailFast, true);
        }
        boolean cancelled = registration.cancelled;
        if (!cancelled) {
            // a cancelled add's registration was released by its cancel: never re-insert it
            registrationsByGeneration.put(registration.generation(), registration);
        }
        registration.startTaken = true;
        return new AddIntent(requestId, registration.generation(), registration.incarnation(), registration.name(),
                cancelled, bootDraining, bootFailFast, false);
    }

    private StopIntent stopIntent(long requestId, String name, ProcessorRegistration registration) {
        if (registration != null) {
            if (!registration.startTaken) {
                // a stop overtaking its add needs the registration for its CancelRegistration and ReleaseName effects;
                // a stale stop of a released registration must not re-insert it
                registrationsByGeneration.putIfAbsent(registration.generation(), registration);
            }
            return new StopIntent(requestId, registration.generation(), name, true, registration.startTaken);
        }
        // no registration: the control processor resolves the name against what is running when the stop is applied
        return new StopIntent(requestId, -1, name, false, false);
    }

    // ============================================================================================ agent lifecycle

    @Override
    public void onStart() {
        try {
            // Best-effort core pinning if configured for this agent group (guard for null during unit tests)
            if (mongooseServer != null) {
                Integer coreId = mongooseServer.resolveCoreIdForAgentName(roleName());
                if (coreId != null) {
                    com.telamin.mongoose.internal.CoreAffinity.pinCurrentThreadToCore(coreId);
                }
            }
            log.info("onStart " + roleName());
            agentThread = Thread.currentThread();
            com.telamin.mongoose.control.AgentThreads.enter(mongooseServer != null ? mongooseServer : this);
            control.starting(agentThread);
            // the requests accepted before the group started are its boot requests: by default a failed start among
            // them fails the group's start, so the server's boot fails, rather than boot on without it (review of #66)
            boolean failFast = failsBootOnStartFailure();
            bootFailFast = failFast;
            int boot = control.queued();
            bootDraining = true;
            try {
                for (int i = 0; i < boot && bootFailure == null; i++) {
                    control.drain(1, Long.MAX_VALUE);
                }
            } finally {
                bootDraining = false;
            }
            if (bootFailure != null) {
                Throwable cause = bootFailure;
                String name = bootFailureName;
                bootFailure = null;
                throw new ProcessorStartException(roleName(), name, cause);   // reported once, by the agent's error handler
            }
            control.running();
            checkQueueReaders();
            super.onStart();
        } catch (Error fatal) {
            control.markDead("fatal error in onStart: " + fatal);
            throw fatal;
        }
    }

    @Override
    public int doWork() throws Exception {
        try {
            int work = control.isEmpty() ? 0 : control.drain(DRAIN_MAX, drainBudgetNanos);
            checkQueueReaders();
            work += super.doWork();
            if (replayer != null) work += replayer.doWork();
            // Counter accounting:
            //   work > 0  → events were dispatched through the composed agents
            //   work == 0 → the duty cycle didn't find anything to do; idle strategy
            //               about to spin / yield / park.
            if (work > 0) {
                eventsProcessedCounter.increment();
            } else {
                idleCyclesCounter.increment();
            }
            return work;
        } catch (Error fatal) {
            control.markDead("fatal error in doWork: " + fatal);
            throw fatal;
        }
    }

    /**
     * Run {@code work} on this group's thread (mongoose#46): directly when called on it, or when the group has not
     * started yet (then nothing else drives its processors); otherwise sent as a {@code Run} request, applied at the
     * start of a later duty cycle. Refused with an {@link IllegalStateException} once the group has closed. Deprecated
     * in favour of a typed control request; kept for the audit-capture hand-off until it becomes one.
     */
    public void runOnAgentThread(Runnable work) {
        if (closed) {
            throw new IllegalStateException("group " + roleName() + " is not running: it has closed");
        }
        Thread agent = agentThread;
        if (agent == null || agent == Thread.currentThread() || !agent.isAlive()) {
            work.run();
            return;
        }
        ControlRequest request = control.send(new ControlRequest(ControlRequest.Kind.RUN, "run", RunIntent::new, work));
        ControlReply answer = request.reply().getNow(null);
        if (answer != null && answer.outcome() == ReplyOutcome.NOT_RUNNING) {
            throw new IllegalStateException("group " + roleName() + " is not running: it has closed");
        }
    }

    @Override
    public void onClose() {
        closed = true;                                                    // no work is taken after this
        try {
            control.close();       // every started processor stopped through the control processor, in add order
        } finally {
            stopAndTearDownProcessors();   // only what a dead or wedged control processor could not release
            if (Thread.currentThread() == control.agentThread()) com.telamin.mongoose.control.AgentThreads.exit();
            agentThread = null;
            log.info("onClose " + roleName());
            super.onClose();
        }
    }

    /**
     * Release what a dead group still holds: its thread ended without {@code onClose} (an {@code Error} escaped), so
     * nothing else will. Runs only once that thread has terminated (joined first, which gives this thread a
     * happens-before edge with everything the agent wrote); a no-op for a group that closed normally or never died.
     * Claimed requests are answered {@code FAILED}, unclaimed ones swept, and every processor stopped and torn down.
     */
    public void cleanupAfterDeath() throws InterruptedException {
        if (control.state() != ControlDriver.GroupState.DEAD || closed) {
            return;
        }
        Thread agent = control.agentThread();
        if (agent != null) {
            agent.join(5_000);
            if (agent.isAlive()) {
                return;   // still running (a wedged control processor): its own onClose releases what it holds
            }
        }
        closed = true;
        control.markDead("cleanup after the agent thread ended");
        control.failClaimed("group died");
        stopAndTearDownProcessors();
        // the dead group's processors are released: so are their names
        for (ProcessorRegistration registration : registrationsByGeneration.values()) {
            registration.releaseName();
        }
        registrationsByGeneration.clear();
        control.audit().fallback("CleanedUp", "group", roleName(), "processorsLeft", registeredEventProcessors.size());
    }

    /**
     * mongoose#56: what a closing group still hosts after its control processor has stopped everything it could: only
     * possible when the control processor is dead or wedged. Each processor is stopped and torn down once, here; one
     * that throws is reported and the others are still stopped; a {@link VirtualMachineError} is rethrown.
     */
    private void stopAndTearDownProcessors() {
        for (String name : new ArrayList<>(registeredEventProcessors.keySet())) {
            NamedEventProcessor named = registeredEventProcessors.remove(name);
            registrations.remove(named);
            if (named == null || !(named.eventProcessor() instanceof Lifecycle lifecycle)) {
                continue;
            }
            try {
                lifecycle.stop();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable t) {
                reportCloseFailure(name, "failed to stop", t);
            }
            try {
                lifecycle.tearDown();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable t) {
                reportCloseFailure(name, "failed to tear down", t);
            }
        }
    }

    private void reportCloseFailure(String processorName, String what, Throwable t) {
        com.telamin.mongoose.service.error.ErrorReporting.report(
                "ComposingEventProcessorAgent:" + roleName(),
                "processor '" + processorName + "' in group '" + roleName() + "' " + what + " on close: " + t, t,
                com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
    }

    // ============================================================================================ effects

    /**
     * Perform one effect the control processor requested and say what happened. Never decides whether to perform:
     * every decision is the control processor's, on the record before this runs.
     */
    @Override
    public EffectResult perform(ControlEffect effect) {
        return switch (effect) {
            case ControlEffect.BuildAndStart e -> buildAndStart(e);
            case ControlEffect.Release e -> releaseGeneration(e);
            case ControlEffect.ReleaseName e -> releaseName(e);
            case ControlEffect.CancelRegistration e -> cancelRegistration(e);
            case ControlEffect.DeliverService e -> deliver(e);
            case ControlEffect.RunTask e -> runTask(e);
            case ControlEffect.FailBoot e -> {
                bootFailure = lastStartFailure != null ? lastStartFailure : new IllegalStateException(e.reason());
                bootFailureName = e.name() == null ? "<not built>" : e.name();
                yield result(e, ControlOutcome.SUCCEEDED, null);
            }
            case ControlEffect.ReportFailure e -> {
                reportProcessorFailure(e.name() == null ? "<not built>" : e.name(), e.what(), lastStartFailure);
                yield result(e, ControlOutcome.SUCCEEDED, null);
            }
            case ControlEffect.OpenCapture e -> openCapture(e);
            case ControlEffect.InstallCapture e -> installCapture(e);
            case ControlEffect.UninstallCapture e -> uninstallCapture(e);
            case ControlEffect.CloseCapture e -> closeCapture(e);
            case ControlEffect.Reply e -> {
                boolean delivered = control.reply(e.requestId(), e.outcome(), e.reason(), e.generation(), e.name());
                yield delivered ? result(e, ControlOutcome.SUCCEEDED, e.outcome().name())
                        : result(e, ControlOutcome.FAILED, "not claimed: answered earlier");
            }
        };
    }

    private static EffectResult result(ControlEffect e, ControlOutcome outcome, String reason) {
        return new EffectResult(e.opId(), e.requestId(), e.effect(), outcome, e.generation(), e.name(), reason);
    }

    private static EffectResult result(ControlEffect e, String name, ControlOutcome outcome, String reason) {
        return new EffectResult(e.opId(), e.requestId(), e.effect(), outcome, e.generation(), name, reason);
    }

    /**
     * Build, wire and start one processor. A failure is the processor's, not the group's (mongoose#57): it is reported
     * as a failed result, and the control processor then drops it. At boot the first failure is remembered, so
     * {@code onStart} can fail the boot.
     */
    private EffectResult buildAndStart(ControlEffect.BuildAndStart effect) {
        ControlRequest request = control.claimedRequest(effect.requestId());
        AddPayload payload = request == null ? null : request.payload();
        if (payload == null) {
            return result(effect, ControlOutcome.FAILED, "no add payload for request " + effect.requestId());
        }
        NamedEventProcessor namedEventProcessor = null;
        try {
            namedEventProcessor = payload.init().get();
            DataFlow eventProcessor = namedEventProcessor.eventProcessor();
            String name = namedEventProcessor.name();
            if (registeredEventProcessors.containsKey(name)) {
                // an add with no send-time admission whose name is already running: never replace the running one
                NamedEventProcessor rejected = namedEventProcessor;
                namedEventProcessor = null;
                processorsByGeneration.put(effect.generation(), rejected);
                throw new IllegalArgumentException("processor name already in use in group " + roleName() + ": " + name);
            }
            registeredEventProcessors.put(name, namedEventProcessor);
            registrations.put(namedEventProcessor, payload.registration());
            processorsByGeneration.put(effect.generation(), namedEventProcessor);
            com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(eventProcessor);
            // replay: a recorded processor gets a live recording clock, a replayed one a pinned replay clock. Before
            // anything that can subscribe it (services, the feed): a replayed processor's live inputs are muted as
            // each subscription is made, so the replayer must already know it
            if (recorder != null) recorder.attach(name, eventProcessor);
            if (replayer != null) replayer.attach(name, eventProcessor);
            eventProcessor.registerService(schedulerService);
            for (Service<?> svc : registeredServices.values()) {
                if (servicesDeliveredTo(eventProcessor).add(svc)) {
                    eventProcessor.registerService(serviceFor(eventProcessor, svc));
                }
            }
            eventProcessor.addEventFeed(this);
            if (eventProcessor instanceof Lifecycle) {
                ((Lifecycle) eventProcessor).start();
                ((Lifecycle) eventProcessor).startComplete();
            }
            return result(effect, name, ControlOutcome.SUCCEEDED, null);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            String name = namedEventProcessor == null ? effect.name() : namedEventProcessor.name();
            lastStartFailure = t;   // whether it fails the boot or is reported is the control processor's decision
            return result(effect, name, ControlOutcome.FAILED, String.valueOf(t));
        } finally {
            com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
        }
    }

    /** Release everything the group holds for a generation: stopped ({@code stopping}) or dropped after a failed start. */
    private EffectResult releaseGeneration(ControlEffect.Release effect) {
        NamedEventProcessor named = processorsByGeneration.remove(effect.generation());
        if (named == null) {
            return result(effect, ControlOutcome.SUCCEEDED, "nothing built");
        }
        stepFailures = 0;
        if (registeredEventProcessors.get(named.name()) == named) {
            release(named, effect.stopping());
        } else {
            // built but never registered (a rejected duplicate): tear it down only
            if (named.eventProcessor() instanceof Lifecycle lifecycle) {
                guarded(named.name(), "failed to tear down a rejected processor", lifecycle::tearDown);
            }
        }
        return result(effect, named.name(), ControlOutcome.SUCCEEDED, stepFailures == 0 ? null : stepFailures + " steps failed");
    }

    private EffectResult releaseName(ControlEffect.ReleaseName effect) {
        ProcessorRegistration registration = registrationsByGeneration.remove(effect.generation());
        if (registration != null) registration.releaseName();
        return result(effect, ControlOutcome.SUCCEEDED, null);
    }

    private EffectResult cancelRegistration(ControlEffect.CancelRegistration effect) {
        ProcessorRegistration registration = registrationsByGeneration.get(effect.generation());
        if (registration == null) {
            return result(effect, ControlOutcome.FAILED, "no registration for generation " + effect.generation());
        }
        registration.cancelled = true;
        return result(effect, ControlOutcome.SUCCEEDED, null);
    }

    private EffectResult deliver(ControlEffect.DeliverService effect) {
        ControlRequest request = control.claimedRequest(effect.requestId());
        BroadcastPayload payload = request == null ? null : request.payload();
        NamedEventProcessor named = processorsByGeneration.get(effect.generation());
        if (payload == null || named == null) {
            return result(effect, ControlOutcome.FAILED, payload == null ? "no broadcast payload" : "processor gone");
        }
        String failure = deliverService(named, payload.service(), payload.register());
        return result(effect, failure == null ? ControlOutcome.SUCCEEDED : ControlOutcome.FAILED, failure);
    }

    private EffectResult runTask(ControlEffect.RunTask effect) {
        ControlRequest request = control.claimedRequest(effect.requestId());
        Runnable task = request == null ? null : request.payload();
        if (task == null) {
            return result(effect, ControlOutcome.FAILED, "no task");
        }
        task.run();
        return result(effect, ControlOutcome.SUCCEEDED, null);
    }

    // ------------------------------------------------------------------ audit capture effects

    /** Hand the open to a helper with a timeout; answered pending, then by exactly one completion from the helper. */
    private EffectResult openCapture(ControlEffect.OpenCapture effect) {
        var resources = captureResources;
        if (resources == null) {
            return result(effect, ControlOutcome.FAILED, "audit capture is not enabled");
        }
        long root = effect.requestId();
        String op = effect.opId();
        String name = effect.name();
        var io = captureIo;
        var timeout = io.schedule(() -> control.complete(new CaptureCompletion(root, op, completionIds.incrementAndGet(),
                CaptureCompletion.Kind.TIMED_OUT, -1, null)), captureOpenTimeoutMillis);
        openTimeouts.put(op, timeout);
        try {
            io.open(() -> {
                CaptureCompletion done;
                try {
                    Object handle = resources.openCapture(name);
                    long id = captureResourceIds.incrementAndGet();
                    captureHandles.put(id, handle);
                    done = new CaptureCompletion(root, op, completionIds.incrementAndGet(), CaptureCompletion.Kind.OPENED, id, null);
                } catch (Throwable t) {
                    done = new CaptureCompletion(root, op, completionIds.incrementAndGet(), CaptureCompletion.Kind.OPEN_FAILED,
                            -1, String.valueOf(t));
                }
                var pendingTimeout = openTimeouts.remove(op);
                if (pendingTimeout != null) pendingTimeout.cancel();
                control.complete(done);
            });
        } catch (RuntimeException rejected) {
            var pendingTimeout = openTimeouts.remove(op);
            if (pendingTimeout != null) pendingTimeout.cancel();
            return result(effect, ControlOutcome.FAILED, "the open could not be handed to a helper: " + rejected);
        }
        return result(effect, ControlOutcome.PENDING, null);
    }

    private EffectResult installCapture(ControlEffect.InstallCapture effect) {
        var resources = captureResources;
        Object handle = captureHandles.get(effect.resourceId());
        NamedEventProcessor named = processorsByGeneration.get(effect.generation());
        if (resources == null || handle == null || named == null) {
            return result(effect, ControlOutcome.FAILED, handle == null ? "unknown resource" : "processor gone");
        }
        resources.installCapture(handle, named.name(), named.eventProcessor());
        return result(effect, ControlOutcome.SUCCEEDED, null);
    }

    private EffectResult uninstallCapture(ControlEffect.UninstallCapture effect) {
        var resources = captureResources;
        Object handle = captureHandles.get(effect.resourceId());
        if (resources == null || handle == null) {
            return result(effect, ControlOutcome.FAILED, "unknown resource");
        }
        NamedEventProcessor named = processorsByGeneration.get(effect.generation());
        resources.uninstallCapture(handle, effect.name(), named == null ? null : named.eventProcessor());
        return result(effect, ControlOutcome.SUCCEEDED, null);
    }

    /** Hand the resource to the closer, which closes it exactly once: whoever removes it from the table closes it. */
    private EffectResult closeCapture(ControlEffect.CloseCapture effect) {
        Object handle = captureHandles.remove(effect.resourceId());
        if (handle == null) {
            return result(effect, ControlOutcome.FAILED, "unknown resource");
        }
        Runnable close = () -> control.complete(closeResource(effect.requestId(), effect.opId(), effect.resourceId(), handle));
        try {
            captureIo.close(close);
        } catch (RuntimeException rejected) {
            close.run();        // the closer would not take it: close here rather than leak it
        }
        return result(effect, ControlOutcome.PENDING, null);
    }

    private CaptureCompletion closeResource(long root, String op, long resourceId, Object handle) {
        try {
            captureResources.closeCapture(handle);
            return new CaptureCompletion(root, op, completionIds.incrementAndGet(), CaptureCompletion.Kind.CLOSED, resourceId, null);
        } catch (Throwable t) {
            return new CaptureCompletion(root, op, completionIds.incrementAndGet(), CaptureCompletion.Kind.CLOSE_FAILED,
                    resourceId, String.valueOf(t));
        }
    }

    /**
     * A completion that reached the group after it ended: no control processor will see it. An open that completed is
     * closed here (nothing else holds it); a close is only recorded. Both go to the fallback stream.
     */
    private void orphanCompletion(com.telamin.mongoose.control.event.ControlInput input) {
        if (!(input instanceof CaptureCompletion c)) return;
        ControlAuditSink audit = control.audit();
        switch (c.kind()) {
            case OPENED -> {
                Object handle = captureHandles.remove(c.resourceId());
                CaptureCompletion closed = handle == null ? null : closeResource(c.requestId(), c.opId(), c.resourceId(), handle);
                // the open's final outcome, then the compensating close: as the control processor would have recorded
                audit.fallback("EffectOutcome", "group", roleName(), "requestId", c.requestId(), "opId", c.opId(),
                        "effect", "OpenCapture", "outcome", "succeeded", "stale", true, "resourceId", c.resourceId());
                audit.fallback("CaptureCompensated", "group", roleName(), "opId", c.opId(), "resourceId", c.resourceId(),
                        "closed", closed != null && closed.kind() == CaptureCompletion.Kind.CLOSED);
            }
            case CLOSED, CLOSE_FAILED -> audit.fallback("EffectOutcome", "group", roleName(), "requestId", c.requestId(),
                    "opId", c.opId(), "effect", "CloseCapture", "outcome", c.kind() == CaptureCompletion.Kind.CLOSED
                            ? "succeeded" : "failed", "resourceId", c.resourceId(), "reason", c.reason());
            case OPEN_FAILED -> audit.fallback("EffectOutcome", "group", roleName(), "requestId", c.requestId(),
                    "opId", c.opId(), "effect", "OpenCapture", "outcome", "failed", "stale", true, "reason", c.reason());
            default -> audit.fallback("CaptureOrphan", "group", roleName(), "opId", c.opId(), "fact", c.fact());
        }
    }

    /** Capture resources the group still holds (opened and not handed to the closer): for tests and the stop result. */
    public int captureResourcesHeld() {
        return captureHandles.size();
    }

    // ============================================================================================ queries

    /** The group's control driver: its audit and its outstanding requests, for tests and introspection. */
    public ControlDriver control() {
        return control;
    }

    @Override
    public void registerSubscriber(DataFlow subscriber) {
        log.info("registerSubscriber:" + subscriber + " " + roleName());
    }

    @Override
    public void subscribe(DataFlow subscriber, EventSubscriptionKey<?> subscriptionKey) {
        Objects.requireNonNull(subscriber, "subscriber is null");
        Objects.requireNonNull(subscriptionKey, "subscriptionKey is null");
        log.info("subscribe subscriptionKey:" + subscriptionKey + " subscriber:" + subscriber);

        EventQueueToEventProcessor eventQueueToEventProcessor = queueProcessorMap.get(subscriptionKey);

        if (eventQueueToEventProcessor == null) {
            eventQueueToEventProcessor = eventFlowManager.getMappingAgent(subscriptionKey, this);
            if (recorder != null && eventQueueToEventProcessor instanceof EventQueueToEventProcessorAgent agent) {
                agent.recordWith(recorder);
            }
            queueProcessorMap.put(subscriptionKey, eventQueueToEventProcessor);
            queueReadersToAdd.add(eventQueueToEventProcessor);
            log.info("added new subscribe subscriptionKey:" + subscriptionKey + " subscriber:" + subscriber);
        }

        eventQueueToEventProcessor.registerProcessor(subscriber);
        // REPLAY: a replayed processor receives only the replay; its live inputs are muted, not delivered beside it
        if (replayer != null && replayer.replays(subscriber)
                && eventQueueToEventProcessor instanceof EventQueueToEventProcessorAgent agent) {
            agent.muteLiveInputs(subscriber);
        }
        eventFlowManager.subscribe(subscriptionKey);
    }

    @Override
    public void unSubscribe(DataFlow subscriber, EventSubscriptionKey<?> subscriptionKey) {
        EventQueueToEventProcessor eventQueueToEventProcessor = queueProcessorMap.get(subscriptionKey);
        if (eventQueueToEventProcessor != null) {
            deregister(subscriber, subscriptionKey, eventQueueToEventProcessor);
        }
    }

    @Override
    public void removeAllSubscriptions(DataFlow subscriber) {
        log.info("removing all subscriptions for:" + subscriber + " " + roleName());
        for (var entry : queueProcessorMap.entrySet()) {
            deregister(subscriber, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Deregister {@code subscriber} from one reader. When that leaves the reader with no listeners it is dead: its
     * queue is no longer a target of the feed (the reader's unsubscribe action removed it), so it is released here
     * (mongoose#55) - the map entry goes, so a later subscribe builds a fresh reader rather than reuse this one; the
     * source subscription is released once no group reads the key; and the reader leaves the composite rather than
     * poll a queue nobody fills.
     */
    private void deregister(DataFlow subscriber, EventSubscriptionKey<?> subscriptionKey, EventQueueToEventProcessor reader) {
        if (reader.deregisterProcessor(subscriber) > 0) {
            return;
        }
        if (!queueProcessorMap.remove(subscriptionKey, reader)) {
            return; // already released
        }
        log.info("EventQueueToEventProcessor listener count = 0, removing subscription:" + subscriptionKey);
        // the group's own bookkeeping first, so a failing source cannot leak the reader: a reader not yet added to the
        // composite is simply never added; one already in it is taken out
        if (!queueReadersToAdd.remove(reader)) {
            queueReadersToRemove.add(reader);
        } else {
            // Never added to the composite, so no later composite removal will close it and release its backlog.
            reader.onClose();
        }
        // the source is unsubscribed only when no group reads the key any more (EventFlowManager counts readers across
        // groups). Its failure is the source's: reported, and it neither stops this release nor escapes the duty cycle
        try {
            eventFlowManager.releaseReader(subscriptionKey);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            String message = "group '" + roleName() + "' failed to unsubscribe the source of " + subscriptionKey + ": " + t;
            com.telamin.mongoose.service.error.ErrorReporting.report("ComposingEventProcessorAgent:" + roleName(),
                    message, t, com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
        }
    }

    /** REPLAY mode's driver for this group, or null. */
    public GroupReplayer replayer() {
        return replayer;
    }

    /** The routes a replay takes: each source's queue agent in this group, as this group's config made it (D2). */
    private ReplayRouting routing() {
        return new ReplayRouting() {
            @Override
            public ReplayRoute routeFor(String source, String route, DataFlow flow) {
                // by source AND route: one source can reach a processor by several callback types, and the first match
                // replayed every entry through whichever route the map listed first (review of 90f0d9b, finding 4)
                List<String> matched = new ArrayList<>();
                ReplayRoute found = null;
                for (var e : queueProcessorMap.entrySet()) {
                    if (e.getKey().eventSourceKey().sourceName().equals(source)
                            && e.getValue() instanceof EventQueueToEventProcessorAgent agent
                            && agent.subscribers().contains(flow)
                            && (route.isEmpty() || route.equals(agent.route()))) {
                        matched.add(agent.route());
                        found = agent;
                    }
                }
                if (matched.size() > 1) {
                    throw new IllegalStateException("the entry names no route, and " + source + " reaches the processor by "
                            + matched.size() + " routes " + matched + ": a replay does not choose one");
                }
                return found;
            }

            @Override
            public com.telamin.mongoose.service.EventSource.EventWrapStrategy wrapOf(String source) {
                return eventFlowManager.wrapStrategyOf(source);
            }

            @Override
            public com.telamin.mongoose.service.admin.impl.AdminCommand adminCommand(String name) {
                for (Service<?> svc : registeredServices.values()) {
                    if (svc.instance() instanceof com.telamin.mongoose.service.admin.impl.AdminCommandProcessor admin) {
                        return admin.registeredCommand(name);
                    }
                }
                return null;
            }
        };
    }

    public Collection<NamedEventProcessor> registeredEventProcessors() {
        return registeredEventProcessors.values();
    }

    /**
     * Whether a processor start that fails in this group's {@code onStart} fails the group's start (and so the server's
     * boot) instead of being isolated. True while the server is booting unless
     * {@link com.telamin.mongoose.config.MongooseServerConfig#isIsolateProcessorStartFailuresAtBoot()} is set; false once
     * the server has started (a group started for a processor added at runtime isolates). Without a server (unit use)
     * it follows {@link #setIsolateStartFailuresAtBoot(boolean)}.
     */
    private boolean failsBootOnStartFailure() {
        return mongooseServer == null ? !isolateStartFailuresAtBoot : mongooseServer.failsBootOnProcessorStartFailure();
    }

    /**
     * Isolate, rather than fail on, a processor start failure in this group's {@code onStart}. Consulted only when the
     * group has no server; with one, {@code MongooseServerConfig.isolateProcessorStartFailuresAtBoot} decides.
     */
    public void setIsolateStartFailuresAtBoot(boolean isolateStartFailuresAtBoot) {
        this.isolateStartFailuresAtBoot = isolateStartFailuresAtBoot;
    }

    /** Readers joining and leaving the composite: one of each per duty cycle, as the composite takes them. */
    private void checkQueueReaders() {
        if (!queueReadersToAdd.isEmpty()) {
            if (status() == Status.ACTIVE && tryAdd(queueReadersToAdd.get(0))) {
                queueReadersToAdd.remove(0);
            }
        }
        // one at a time, as the composite takes one pending removal per duty cycle; it closes the reader once removed
        if (!queueReadersToRemove.isEmpty()) {
            if (status() == Status.ACTIVE && tryRemove(queueReadersToRemove.get(0))) {
                queueReadersToRemove.remove(0);
            }
        }
    }

    /**
     * The one path a processor leaves the group by, whether stopped ({@code stopping}) or dropped after a failed start
     * (mongoose#71, review of #66). Releases everything the group holds for it, each step guarded so one failure does
     * not stop the rest:
     * <ul>
     *   <li>it leaves the registry, and the record of the services it was given, so a processor added again under its
     *       name is delivered every service once (mongoose#60);</li>
     *   <li>its {@code stop()} runs (a stop only: a processor whose start failed never ran), then its
     *       {@code tearDown()};</li>
     *   <li>its subscriptions are released (a no-op for those its own tearDown already released);</li>
     *   <li>its timers are cancelled, so none fires into it ({@link DeadWheelScheduler#cancelTimersOf});</li>
     *   <li>the replay recorder and replayer forget it;</li>
     *   <li>the server is told ({@link MongooseServer#processorStopped} / {@link MongooseServer#processorDropped}),
     *       which releases what the server bound to it by name: its audit-capture binding.</li>
     * </ul>
     */
    private void release(NamedEventProcessor named, boolean stopping) {
        String name = named.name();
        DataFlow eventProcessor = named.eventProcessor();
        registeredEventProcessors.remove(name, named);
        ProcessorRegistration registration = registrations.remove(named);
        servicesDelivered.remove(eventProcessor);
        if (eventProcessor instanceof Lifecycle lifecycle) {
            if (stopping) {
                guarded(name, "failed to stop cleanly, removed from the group", lifecycle::stop);
            }
            guarded(name, stopping ? "failed to tear down, removed from the group"
                    : "failed to tear down after its failed start", lifecycle::tearDown);
        }
        releaseSubscriptions(name, eventProcessor);
        guarded(name, "failed to cancel its timers", () -> scheduler.cancelTimersOf(eventProcessor));
        if (recorder != null) guarded(name, "failed to leave the replay recording", () -> recorder.detach(eventProcessor));
        if (replayer != null) guarded(name, "failed to leave the replay", () -> replayer.detach(eventProcessor));
        if (mongooseServer != null) {
            guarded(name, "failed to release its server bindings", stopping
                    ? () -> mongooseServer.processorStopped(roleName(), name, eventProcessor)
                    : () -> mongooseServer.processorDropped(roleName(), name, eventProcessor, registration));
        }
    }

    private void guarded(String name, String what, Runnable step) {
        try {
            step.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            stepFailures++;
            reportProcessorFailure(name, what, t);
        }
    }

    /** The name {@code flow} has in this group, or null: names a timer's processor in a failure report. */
    private String nameOf(DataFlow flow) {
        if (flow == null) return null;
        for (NamedEventProcessor np : registeredEventProcessors.values()) {
            if (np.eventProcessor() == flow) return np.name();
        }
        return null;
    }

    /** Release what a failed processor subscribed to, so its readers do not outlive it. */
    private void releaseSubscriptions(String name, DataFlow eventProcessor) {
        try {
            removeAllSubscriptions(eventProcessor);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            reportProcessorFailure(name, "failed to release its subscriptions", t);
        }
    }

    private void reportProcessorFailure(String processorName, String what, Throwable t) {
        // reported once: the default reporter logs it, so it is not also logged here
        String message = "processor '" + processorName + "' in group '" + roleName() + "' " + what + ": " + t;
        com.telamin.mongoose.service.error.ErrorReporting.report(
                "ComposingEventProcessorAgent:" + roleName(), message, t,
                com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
    }

    /** The service a processor is given: REPLAY swaps a replayed processor's sinks for a capture (L2); else itself. */
    private Service<?> serviceFor(DataFlow eventProcessor, Service<?> service) {
        return replayer == null ? service : replayer.serviceFor(eventProcessor, service);
    }

    /**
     * Hand one processor a service (de)registration. A processor that throws is reported and keeps running; the
     * broadcast still reaches every other processor in the group (mongoose#57).
     * <p>
     * Delivery is recorded before the call (mongoose#60), so a {@code registerService} that throws still counts as
     * delivered: a later broadcast of the same service is not offered to that processor again, and a later deregister
     * of it does reach the processor. Likewise a {@code deRegisterService} that throws counts as done.
     *
     * @return null when delivered (or already held), else why it failed
     */
    private String deliverService(NamedEventProcessor np, Service<?> svc, boolean register) {
        DataFlow eventProcessor = np.eventProcessor();
        // each service once (mongoose#60 item 1): a register the processor already has - it joined after the service
        // was in the registry - is skipped, and a deregister reaches only a processor that was handed the service
        if (register ? !servicesDeliveredTo(eventProcessor).add(svc) : !servicesDeliveredTo(eventProcessor).remove(svc)) {
            return null;
        }
        com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(eventProcessor);
        try {
            if (register) {
                eventProcessor.registerService(serviceFor(eventProcessor, svc));
            } else {
                eventProcessor.deRegisterService(serviceFor(eventProcessor, svc));
            }
            return null;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            reportProcessorFailure(np.name(), (register ? "failed to register" : "failed to deregister")
                    + " service '" + svc.serviceName() + "'", t);
            return String.valueOf(t);
        } finally {
            com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
        }
    }

    private java.util.Set<Service<?>> servicesDeliveredTo(DataFlow eventProcessor) {
        return servicesDelivered.computeIfAbsent(eventProcessor,
                k -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    public boolean isProcessorRegistered(String processorName) {
        return registeredEventProcessors.containsKey(processorName);
    }

    /**
     * Snapshot of which processors in this group are subscribed to which
     * {@link EventSubscriptionKey}s. Built by cross-referencing the per-
     * subscription processor list (held by {@link EventQueueToEventProcessor})
     * against the {@code DataFlow} → name map kept here. Returned for
     * introspection only.
     * <p>
     * Outer key is the processor name, value is a list of subscription keys
     * (feed + callback) that the processor receives via this agent group.
     * Processors that don't appear in any subscription are still present
     * with an empty list, so admin UIs can show "no feeds subscribed"
     * without re-merging registry views.
     *
     * @return mutable, ordered map from processor name to subscription keys
     */
    public java.util.Map<String, java.util.List<EventSubscriptionKey<?>>> subscriptionsByProcessorName() {
        // Identity-keyed reverse map: which DataFlow corresponds to which named
        // processor. registeredEventProcessors is keyed by name, so we invert.
        java.util.Map<DataFlow, String> namesByFlow = new java.util.IdentityHashMap<>();
        for (NamedEventProcessor np : registeredEventProcessors.values()) {
            namesByFlow.put(np.eventProcessor(), np.name());
        }
        java.util.Map<String, java.util.List<EventSubscriptionKey<?>>> out = new java.util.LinkedHashMap<>();
        // Seed every known processor so empty-subscription rows are visible.
        for (NamedEventProcessor np : registeredEventProcessors.values()) {
            out.put(np.name(), new java.util.ArrayList<>());
        }
        for (java.util.Map.Entry<EventSubscriptionKey<?>, EventQueueToEventProcessor> e : queueProcessorMap.entrySet()) {
            EventSubscriptionKey<?> key = e.getKey();
            for (DataFlow flow : e.getValue().subscribers()) {
                String name = namesByFlow.get(flow);
                if (name != null) {
                    out.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(key);
                }
            }
        }
        return out;
    }
}
