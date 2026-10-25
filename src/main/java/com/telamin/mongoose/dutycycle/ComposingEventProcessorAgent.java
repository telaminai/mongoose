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
    /**
     * System property sizing each group's control audit ring ({@link ControlAuditSink#DEFAULT_CAPACITY} records by
     * default). A ring that overflows drops its oldest records and reports itself incomplete, after which the
     * invariants that need the whole record are unverifiable; a long run that must stay verifiable sizes it up.
     */
    public static final String CONTROL_AUDIT_CAPACITY_PROPERTY = "mongoose.control.auditCapacity";

    private static int controlAuditCapacity() {
        Integer configured = Integer.getInteger(CONTROL_AUDIT_CAPACITY_PROPERTY);
        return configured == null || configured < 1 ? ControlAuditSink.DEFAULT_CAPACITY : configured;
    }
    private volatile long drainBudgetNanos = Long.MAX_VALUE;
    private final ControlDriver control;
    private final AtomicLong generations = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicInteger> incarnations = new ConcurrentHashMap<>();
    /** Registration identity is used only for lifecycle operations, never for event delivery. Agent thread only. */
    private final java.util.Map<NamedEventProcessor, ProcessorRegistration> registrations = new java.util.IdentityHashMap<>();
    /** Agent thread only: what each generation built, the registration it answers to, and the live name index. */
    private final Map<Long, NamedEventProcessor> processorsByGeneration = new HashMap<>();
    private final Map<Long, ProcessorRegistration> registrationsByGeneration = new HashMap<>();
    /** Releases remain owned after removal from the live index, including when a fatal callback kills the agent. */
    private final Map<NamedEventProcessor, ReleaseProgress> releasesInProgress = new java.util.IdentityHashMap<>();

    private static final class ReleaseProgress {
        final NamedEventProcessor named;
        final ProcessorRegistration registration;
        final boolean stopping;
        final boolean recordStop;
        int nextStep;

        ReleaseProgress(NamedEventProcessor named, ProcessorRegistration registration, boolean stopping,
                        boolean recordStop) {
            this.named = named;
            this.registration = registration;
            this.stopping = stopping;
            this.recordStop = recordStop;
        }
    }
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
    /** Closed prevents new hand-offs; cleanup completion is separate because a cleanup callback may fail fatally. */
    private boolean deathCleanupComplete;
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
    private ProcessorDispatchException terminalProcessorFailure;
    /** Dispatch quarantine: generated lifecycle callbacks may drain abandoned events, so never re-enter this graph. */
    private final Map<NamedEventProcessor, Throwable> quarantined = new ConcurrentHashMap<>();
    /** Guarded steps that failed during the current release. Agent thread only. */
    private int stepFailures;
    /** Audit capture (design "Audit capture"): the physical side, where helpers run, and the resources held by id. */
    private volatile com.telamin.mongoose.service.audit.AuditCaptureResources captureResources;
    private volatile com.telamin.mongoose.control.CaptureIo captureIo = com.telamin.mongoose.control.CaptureIo.shared();
    private volatile long captureOpenTimeoutMillis = 30_000;
    /**
     * Physical opens a helper is still performing, bounded (follow-up review, finding 2): a timed-out open settles its
     * request, but its helper may still be blocked opening, so its permit is returned only when the helper returns.
     */
    private volatile int captureOpenLimit = 8;
    private final java.util.concurrent.atomic.AtomicInteger opensInFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final ConcurrentHashMap<Long, Object> captureHandles = new ConcurrentHashMap<>();
    /** Includes queued opens, installed captures, handed-off closes and unresolved failed physical closes. */
    private volatile int captureResourceLimit = 1024;
    private final AtomicInteger captureResourceSlots = new AtomicInteger();
    private final Object captureResourceCompletion = new Object();
    private final ConcurrentHashMap<Long, CaptureResourcePermit> captureResourcePermits = new ConcurrentHashMap<>();
    private final AtomicLong physicalCleanupOps = new AtomicLong();

    private final class CaptureResourcePermit {
        private final java.util.concurrent.atomic.AtomicBoolean held = new java.util.concurrent.atomic.AtomicBoolean(true);
        private volatile Object handle;
        private volatile String closeFailure;
        private volatile String detachFailure;
        void release() {
            if (held.compareAndSet(true, false)) {
                handle = null;
                captureResourceSlots.decrementAndGet();
                signalCaptureResourceCompletion();
            }
        }
    }
    /** Resources whose live detach failed: retry without the instance only after its release. */
    private final java.util.Set<Long> captureDetachFailures = ConcurrentHashMap.newKeySet();
    /**
     * Resources first reclaimed by the offline cleanup with no requested uninstall having failed for them: the control
     * processor never released them. They stay leftovers on later attempts (issue #77, I19).
     */
    private final java.util.Set<Long> leftoverCaptures = ConcurrentHashMap.newKeySet();
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
        scheduler.setProcessorFailureHandler(this::isolateFailedProcessor);
        this.eventsProcessedCounter = eventFlowManager.getCountersService().agentEventsCounter(roleName);
        this.idleCyclesCounter = eventFlowManager.getCountersService().agentIdleCyclesCounter(roleName);
        this.control = new ControlDriver(roleName, CONTROL_QUEUE_CAPACITY, this, new ControlAuditSink(controlAuditCapacity()));
        this.control.onOrphanCompletion(this::orphanCompletion);
        // a control request is in the processor's input order only on this group's own agent thread (fix review 7)
        java.util.function.BooleanSupplier ownThread = () -> {
            Thread agent = agentThread;
            return agent == null ? com.telamin.mongoose.control.AgentThreads.isAgentOf(this)
                    || com.telamin.mongoose.control.AgentThreads.isAgentThread() : agent == Thread.currentThread();
        };
        if (recorder != null) recorder.onOwnThread(ownThread);
        if (replayer != null) replayer.onOwnThread(ownThread);
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
        return addNamedEventProcessor(initFunction, registration, Long.MAX_VALUE);
    }

    /** As above, admitted within the server call's absolute deadline, including its later reply wait. */
    public ControlRequest addNamedEventProcessor(Supplier<NamedEventProcessor> initFunction, ProcessorRegistration registration,
                                                 long admitByNanos) {
        Objects.requireNonNull(initFunction, "initFunction is null");
        if (registration != null) admit(registration, registration.name());
        ControlRequest request = new ControlRequest(ControlRequest.Kind.ADD,
                "add " + (registration == null || registration.name() == null ? "<unnamed>" : registration.name()),
                id -> addIntent(id, registration), new AddPayload(initFunction, registration));
        if (registration != null) request.onUnapplied(registration::releaseName);   // refused or swept unapplied
        return control.send(request, admitByNanos);
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
        return removeEventProcessorByName(name, registration, completed, Long.MAX_VALUE);
    }

    /** As above, admitted by {@code admitByNanos} or answered {@code REFUSED}: one deadline for admission and reply. */
    public ControlRequest removeEventProcessorByName(String name, ProcessorRegistration registration, Runnable completed,
                                                     long admitByNanos) {
        if (registration != null) admit(registration, name);
        ControlRequest request = new ControlRequest(ControlRequest.Kind.STOP, "stop " + name,
                id -> stopIntent(id, name, registration), registration);
        // only a stop that was applied completes: one refused for a full mailbox, or swept unapplied, changed nothing
        // and must not run what follows a stop, such as freeing a name (final review, finding 4)
        if (completed != null) {
            request.reply().thenAccept(reply -> {
                if (reply.applied()) completed.run();
            });
        }
        return control.send(request, admitByNanos);
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
        if (version > 0) {
            // Publication exists even when no processor currently receives a DeliverService effect. Keep the exact
            // wrapper with its version, rather than combining a version with a later registry lookup during replay.
            publishedServices.compute(service.serviceName(), (name, previous) -> previous == null || version > previous.version()
                    ? new PublishedService(version, register ? service : null) : previous);
        }
        return new ControlRequest(ControlRequest.Kind.BROADCAST,
                (register ? "register " : "deregister ") + service.serviceName(),
                id -> new BroadcastIntent(id, service.serviceName(), register, version),
                // whether this is a runtime change is decided when it is sent, not when it is applied (review of PR 6, 6)
                new BroadcastPayload(service, register, version, mongooseServer == null || mongooseServer.isStarted()));
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

    /** At most this many physical opens in flight per group (8 by default); an audit start beyond it is refused. */
    public void setCaptureOpenLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive: " + limit);
        this.captureOpenLimit = limit;
    }

    /** Physical opens a helper is still performing, whatever their requests' state. */
    public int captureOpensInFlight() {
        return opensInFlight.get();
    }

    /** Maximum admitted physical capture resources, including closes still queued or failed; default 1024. */
    public void setCaptureResourceLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("resource limit must be positive: " + limit);
        captureResourceLimit = limit;
    }

    public int captureResourceSlotsInUse() { return captureResourceSlots.get(); }

    /** Failed closes remain owned and consume budget; the adapter's close is not blindly retried. */
    public Map<Long, String> captureCloseFailures() {
        Map<Long, String> failures = new HashMap<>();
        captureResourcePermits.forEach((id, permit) -> {
            if (permit.closeFailure != null) failures.put(id, permit.closeFailure);
        });
        return java.util.Collections.unmodifiableMap(failures);
    }

    /** All retained cleanup failures, including a detach that prevented handoff to the physical closer. */
    public Map<Long, String> captureCleanupFailures() {
        Map<Long, String> failures = new HashMap<>();
        captureResourcePermits.forEach((id, permit) -> {
            if (permit.detachFailure != null) failures.put(id, "detach: " + permit.detachFailure);
            if (permit.closeFailure != null) failures.put(id, "physical close: " + permit.closeFailure);
        });
        return java.util.Collections.unmodifiableMap(failures);
    }

    private void captureDetachFailed(long id, Throwable failure) {
        captureDetachFailures.add(id);
        CaptureResourcePermit permit = captureResourcePermits.get(id);
        if (permit != null) permit.detachFailure = String.valueOf(failure);
        signalCaptureResourceCompletion();
    }

    private void captureDetached(long id) {
        captureDetachFailures.remove(id);
        CaptureResourcePermit permit = captureResourcePermits.get(id);
        if (permit != null) permit.detachFailure = null;
    }

    /** Only a tracked shutdown helper waits here; its caller joins that helper with the shutdown deadline. */
    public boolean awaitCaptureResourceCleanup() throws InterruptedException {
        if (com.telamin.mongoose.control.AgentThreads.isAgentThread()) {
            throw new IllegalStateException("an agent thread cannot wait for physical capture cleanup");
        }
        synchronized (captureResourceCompletion) {
            while (captureResourceSlots.get() != 0 && captureCleanupFailures().isEmpty()) {
                captureResourceCompletion.wait();
            }
            return captureResourceSlots.get() == 0;
        }
    }

    private void signalCaptureResourceCompletion() {
        synchronized (captureResourceCompletion) {
            captureResourceCompletion.notifyAll();
        }
    }

    private CaptureResourcePermit admitCaptureResource() {
        int current;
        do {
            current = captureResourceSlots.get();
            if (current >= captureResourceLimit) return null;
        } while (!captureResourceSlots.compareAndSet(current, current + 1));
        return new CaptureResourcePermit();
    }

    /** How long an open may take before its audit start is answered Failed; 30 s by default. */
    public void setCaptureOpenTimeout(java.time.Duration timeout) {
        this.captureOpenTimeoutMillis = timeout.toMillis();
    }

    private boolean admitCaptureOpen() {
        int current;
        do {
            current = opensInFlight.get();
            if (current >= captureOpenLimit) return false;
        } while (!opensInFlight.compareAndSet(current, current + 1));
        return true;
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

    /** Per name, the processors built in this group: a replay stream's key. Agent thread only. */
    private final Map<String, AtomicInteger> builtOrdinals = new HashMap<>();
    /** Per service, the registry version of the last broadcast applied here. Agent thread only. */
    /** Latest published registry binding per name; a removed incarnation does not retain its service object. */
    private record PublishedService(long version, Service<?> service) { }
    private final ConcurrentHashMap<String, PublishedService> publishedServices = new ConcurrentHashMap<>();

    /** What an add needs to perform its effects: never seen by the control processor. */
    private record AddPayload(Supplier<NamedEventProcessor> init, ProcessorRegistration registration) { }

    private record BroadcastPayload(Service<?> service, boolean register, long version, boolean runtime) { }

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
            // Establish liveness before any callback or logging can fail. Agrona may skip onClose when its error
            // handler also throws, so even the earliest startup failure must remain visible to the death checker.
            agentThread = Thread.currentThread();
            control.starting(agentThread);
            com.telamin.mongoose.control.AgentThreads.enter(mongooseServer != null ? mongooseServer : this);
            // Best-effort core pinning if configured for this agent group (guard for null during unit tests)
            if (mongooseServer != null) {
                Integer coreId = mongooseServer.resolveCoreIdForAgentName(roleName());
                if (coreId != null) {
                    com.telamin.mongoose.internal.CoreAffinity.pinCurrentThreadToCore(coreId);
                }
            }
            log.info("onStart " + roleName());
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
            if (terminalProcessorFailure != null) throw terminalProcessorFailure;
            control.running();
            checkQueueReaders();
            super.onStart();
        } catch (ProcessorDispatchException fatal) {
            terminateFailedProcessor(fatal);
            throw fatal;
        } catch (Error fatal) {
            control.markDead("fatal error in onStart: " + fatal);
            throw fatal;
        }
    }

    @Override
    public int doWork() throws Exception {
        try {
            if (terminalProcessorFailure != null) throw terminalProcessorFailure;
            int work = control.isEmpty() ? 0 : control.drain(DRAIN_MAX, drainBudgetNanos);
            if (terminalProcessorFailure != null) throw terminalProcessorFailure;
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
        } catch (ProcessorDispatchException fatal) {
            terminateFailedProcessor(fatal);
            throw fatal;
        } catch (Error fatal) {
            control.markDead("fatal error in doWork: " + fatal);
            control.failClaimed("group died: " + fatal);    // on the agent thread, which owns what it claimed
            throw fatal;
        }
    }

    /** Containment only: lifecycle release is still decided and audited by the control processor. */
    private void isolateFailedProcessor(DataFlow flow, Throwable failure) {
        NamedEventProcessor named = registeredEventProcessors.values().stream()
                .filter(p -> p.eventProcessor() == flow).findFirst().orElse(null);
        if (named == null) named = processorsByGeneration.values().stream()
                .filter(p -> p.eventProcessor() == flow).findFirst().orElse(null);
        if (named == null) {
            terminalProcessorFailure = new ProcessorDispatchException(flow, "unowned failed subscriber", failure);
            throw terminalProcessorFailure;
        }
        if (quarantined.putIfAbsent(named, failure) != null) return;
        // A stop may already have removed registry entries before its generated callback failed.
        // Restore ownership, not execution: the name cannot be reused while cleanup is unresolved.
        registeredEventProcessors.putIfAbsent(named.name(), named);
        ReleaseProgress releasing = releasesInProgress.get(named);
        if (releasing != null && releasing.registration != null) registrations.putIfAbsent(named, releasing.registration);
        // Fence all reader routes and timers before another event can reach the partially executed graph.
        try {
            removeAllSubscriptions(flow);
            scheduler.cancelTimersOf(flow);
            if (replayer != null) replayer.detach(flow); // preserve the reason and settle the failed replay cursor
            ProcessorRegistration registration = registrations.get(named);
            control.audit().fallback("ProcessorQuarantined", "group", roleName(), "name", named.name(),
                    "generation", registration == null ? -1 : registration.generation(),
                    "reason", String.valueOf(failure), "automaticRestart", false, "kind", com.telamin.mongoose.control.event.FailureKind.QUARANTINED);
            reportProcessorFailure(named.name(), "quarantined after generated callback failure; no retry", failure);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable isolationFailure) {
            terminalProcessorFailure = new ProcessorDispatchException(flow, "failed quarantine", isolationFailure);
            throw terminalProcessorFailure;
        }
    }

    /** Failed graphs remain owned; no callback is invoked to guess whether their partial state recovered. */
    public Map<String, String> quarantinedProcessors() {
        Map<String, String> failures = new java.util.LinkedHashMap<>();
        quarantined.forEach((named, failure) -> failures.put(named.name(), String.valueOf(failure)));
        return Map.copyOf(failures);
    }

    /** Agent thread only. Preserve terminal admission/replies even when an error handler permits the JVM to live. */
    private void terminateFailedProcessor(ProcessorDispatchException failure) {
        terminalProcessorFailure = failure;
        try {
            control.markDead("generated processor execution failed: " + failure.getMessage());
            control.failClaimed("group died: generated processor execution failed");
        } finally {
            // AgentRunner recognises this exception and ends its loop, even with a logging-only error handler.
            throw failure;
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
        if (agent == null || agent == Thread.currentThread()) {
            work.run();                 // never started (nothing else drives its processors), or already on its thread
            return;
        }
        if (!agent.isAlive()) {
            // its thread has ended: refused, never run on the caller's thread against a dead group (FM-6)
            control.detectDeath();
            throw new IllegalStateException("group " + roleName() + " is not running: its thread has ended");
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
     * Release what an ended group still holds: its thread may have ended without {@code onClose}, or its close may
     * have left incomplete resource cleanup. Runs only once that thread has terminated (joined first, which gives
     * this thread a happens-before edge with everything the agent wrote). Subsequent attempts resume outstanding
     * work without repeating lifecycle callbacks already attempted.
     * Claimed requests are answered {@code FAILED}, unclaimed ones swept, and every processor stopped and torn down.
     */
    public void cleanupAfterDeath() throws InterruptedException {
        ControlDriver.GroupState state = control.state();
        if ((state != ControlDriver.GroupState.DEAD && state != ControlDriver.GroupState.CLOSED) || deathCleanupComplete) {
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
        if (state == ControlDriver.GroupState.DEAD) control.markDead("cleanup after the agent thread ended");
        control.failClaimed(control.state() == ControlDriver.GroupState.DEAD
                ? com.telamin.mongoose.control.event.FailureKind.FAILED_DEAD
                : com.telamin.mongoose.control.event.FailureKind.FAILED_SHUTDOWN, "group died");
        stopAndTearDownProcessors();
        // the dead group's processors are released: so are their names
        registrationsByGeneration.entrySet().removeIf(entry -> {
            ProcessorRegistration registration = entry.getValue();
            if (quarantined.keySet().stream().anyMatch(named -> registrations.get(named) == registration)) return false;
            registration.releaseName();
            return true;
        });
        deathCleanupComplete = quarantined.isEmpty() && captureHandles.isEmpty() && captureResourceSlots.get() == 0;
        control.audit().fallback(deathCleanupComplete ? "CleanedUp" : "CleanupPending", "group", roleName(),
                "processorsLeft", registeredEventProcessors.size(), "captureResourcesLeft", captureResourceSlots.get(),
                "failedPhysicalCloses", captureCloseFailures().size(),
                "failedCaptureDetaches", captureResourcePermits.values().stream().filter(p -> p.detachFailure != null).count());
    }

    /**
     * mongoose#56: what a closing group still hosts after its control processor has stopped everything it could: only
     * possible when the control processor is dead or wedged. Each processor is stopped and torn down once, here; one
     * that throws is reported and the others are still stopped; a {@link VirtualMachineError} is rethrown.
     */
    private void stopAndTearDownProcessors() {
        // A release removes the live name before calling user code. Resume its remaining steps even though that
        // processor is no longer in registeredEventProcessors. A callback already attempted is never repeated.
        for (ReleaseProgress release : new ArrayList<>(releasesInProgress.values())) {
            continueRelease(release);
        }
        for (String name : new ArrayList<>(registeredEventProcessors.keySet())) {
            NamedEventProcessor named = registeredEventProcessors.get(name);
            if (named != null && !quarantined.containsKey(named)) release(named, true, false);
        }
        processorsByGeneration.entrySet().removeIf(entry -> !quarantined.containsKey(entry.getValue()));
        // As in the normal release effects, close capture resources only after lifecycle callbacks have finished.
        // A fatal callback leaves both the remaining release progress and the resource owned for the next attempt.
        releaseLeftoverCaptures();
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
        try {
            return performEffect(effect);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException | Error failure) {
            // an effect whose failure quarantined its processor (a routed capture install/restore on a generated graph)
            // is classified QUARANTINED, so the rule's reply says so without reading text
            NamedEventProcessor named = processorsByGeneration.get(effect.generation());
            if (named != null && quarantined.containsKey(named)) {
                return quarantinedResult(effect, failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage());
            }
            throw failure;
        }
    }

    private EffectResult performEffect(ControlEffect effect) {
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
                boolean delivered = control.reply(e.requestId(), e.outcome(), e.reason(), e.generation(), e.name(), e.kind());
                // a delivered reply's result carries the reply's own kind, so the Reply record classifies it
                yield delivered ? new EffectResult(e.opId(), e.requestId(), e.effect(), ControlOutcome.SUCCEEDED,
                        e.generation(), e.name(), e.outcome().name(), e.kind())
                        : result(e, ControlOutcome.FAILED, "not claimed: answered earlier");
            }
        };
    }

    private static EffectResult quarantinedResult(ControlEffect e, String reason) {
        return new EffectResult(e.opId(), e.requestId(), e.effect(), ControlOutcome.FAILED, e.generation(), e.name(), reason,
                com.telamin.mongoose.control.event.FailureKind.QUARANTINED);
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
            // replay streams are keyed by the per-name build ordinal: the processors of this name built in this group,
            // so an add refused, cancelled or never built consumes no number (review of PR 6, finding 5)
            int incarnation = builtOrdinals.computeIfAbsent(name, n -> new AtomicInteger()).incrementAndGet();
            if (recorder != null) recorder.attach(name, incarnation, eventProcessor);
            if (replayer != null) replayer.attach(name, incarnation, eventProcessor);
            eventProcessor.registerService(schedulerService);
            for (Service<?> svc : registeredServices.values()) {
                // a replayed processor whose stream records this service arriving later gets it there (review of PR 6, 1)
                if (replayer != null && replayer.defersService(eventProcessor, svc.serviceName())) continue;
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
            if (recorder != null && namedEventProcessor != null) {
                try { recorder.startFailed(namedEventProcessor.eventProcessor(), fatal); }
                catch (Throwable recordingFailure) {
                    if (recordingFailure != fatal) fatal.addSuppressed(recordingFailure);
                }
            }
            throw fatal;
        } catch (Throwable t) {
            if (namedEventProcessor != null && ProcessorDispatchException.requiresIsolation(namedEventProcessor.eventProcessor())) {
                isolateFailedProcessor(namedEventProcessor.eventProcessor(), t);
            }
            if (recorder != null && namedEventProcessor != null) {
                recorder.startFailed(namedEventProcessor.eventProcessor(), t);
            }
            String name = namedEventProcessor == null ? effect.name() : namedEventProcessor.name();
            lastStartFailure = t;   // whether it fails the boot or is reported is the control processor's decision
            boolean isolated = namedEventProcessor != null && quarantined.containsKey(namedEventProcessor);
            return new EffectResult(effect.opId(), effect.requestId(), effect.effect(), ControlOutcome.FAILED,
                    effect.generation(), name, String.valueOf(t), isolated ? com.telamin.mongoose.control.event.FailureKind.QUARANTINED : com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT);
        } finally {
            com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
        }
    }

    /** Release everything the group holds for a generation: stopped ({@code stopping}) or dropped after a failed start. */
    private EffectResult releaseGeneration(ControlEffect.Release effect) {
        NamedEventProcessor named = processorsByGeneration.get(effect.generation());
        if (named != null && quarantined.containsKey(named)) {
            return quarantinedResult(effect, "processor quarantined; lifecycle cleanup requires operator resolution");
        }
        if (named == null) {
            return result(effect, ControlOutcome.SUCCEEDED, "nothing built");
        }
        stepFailures = 0;
        if (registeredEventProcessors.get(named.name()) == named) {
            release(named, effect.stopping(), effect.byRequest());
        } else {
            // built but never registered (a rejected duplicate): tear it down only
            if (named.eventProcessor() instanceof Lifecycle lifecycle) {
                guarded(named.name(), "failed to tear down a rejected processor", lifecycle::tearDown);
            }
        }
        if (quarantined.containsKey(named)) {
            return quarantinedResult(effect, "processor quarantined; lifecycle cleanup requires operator resolution");
        }
        processorsByGeneration.remove(effect.generation());
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
        DataFlow flow = named.eventProcessor();
        // the services a server registers while it boots (its feeds among them) are configuration, delivered live and
        // never recorded; a service change is what was sent once the server had started
        boolean runtimeChange = payload.runtime();
        if (replayer != null && (runtimeChange && replayer.suppressesLiveServices(flow)
                || replayer.suppressesLiveService(flow, payload.service().serviceName()))) {
            // replayed from a stream that records its service changes: delivered where the recorded run had it. A
            // withdrawal is not confirmed to its sender: the processor still holds the service until the stream withdraws
            // it, so removeService must keep the instance (fix review 3)
            return payload.register() ? result(effect, ControlOutcome.SUCCEEDED, "replayed: delivered in position")
                    : result(effect, ControlOutcome.FAILED, "replayed: withdrawn in position by the stream, not yet");
        }
        if (runtimeChange && recorder != null) recorder.beforeControl(flow);
        ServiceDelivery delivery = deliverService(named, payload.service(), payload.register());
        if (runtimeChange && recorder != null) {
            String name = payload.service().serviceName();
            if (delivery.failure() != null) recorder.serviceFailed(flow, name, delivery.failure());
            else if (delivery.invoked()) recorder.serviceChanged(flow, name, payload.register(), payload.version());
            else recorder.discardControl(flow);
        }
        if (delivery.failure() != null && quarantined.containsKey(named)) {
            return quarantinedResult(effect, delivery.failure());
        }
        return result(effect, delivery.failure() == null ? ControlOutcome.SUCCEEDED : ControlOutcome.FAILED, delivery.failure());
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
        CaptureResourcePermit initialResourcePermit = admitCaptureResource();
        if (initialResourcePermit == null) return result(effect, ControlOutcome.FAILED, "physical capture resource limit reached");
        if (!admitCaptureOpen()) {
            initialResourcePermit.release();
            return result(effect, ControlOutcome.FAILED, "unresolved capture open limit reached");
        }
        var firstExecution = new java.util.concurrent.atomic.AtomicBoolean(true);
        long root = effect.requestId();
        String op = effect.opId();
        String name = effect.name();
        var io = captureIo;
        var permitHeld = new java.util.concurrent.atomic.AtomicBoolean(true);
        Runnable releasePermit = () -> {
            if (permitHeld.compareAndSet(true, false)) opensInFlight.decrementAndGet();
        };
        try {
            var timeoutFired = new java.util.concurrent.atomic.AtomicBoolean();
            var timeout = io.schedule(() -> {
                timeoutFired.set(true);
                openTimeouts.remove(op);
                control.complete(new CaptureCompletion(root, op, completionIds.incrementAndGet(),
                        CaptureCompletion.Kind.TIMED_OUT, -1, null));
            }, captureOpenTimeoutMillis);
            openTimeouts.put(op, timeout);
            // A zero-delay/custom timer may fire before its cancellation handle was published.
            if (timeoutFired.get()) openTimeouts.remove(op, timeout);
            io.open(() -> {
                // A custom helper that repeats a task creates another resource; it needs its own slot.
                boolean initial = firstExecution.compareAndSet(true, false);
                if (!initial && !admitCaptureOpen()) {
                    control.audit().fallback("CaptureDuplicateSuppressed", "group", roleName(), "requestId", root,
                            "opId", op, "reason", "unresolved capture open limit reached");
                    return;
                }
                Runnable releaseThisOpen = initial ? releasePermit : () -> opensInFlight.decrementAndGet();
                CaptureResourcePermit resourcePermit = initial ? initialResourcePermit : admitCaptureResource();
                if (resourcePermit == null) {
                    releaseThisOpen.run();
                    control.audit().fallback("CaptureDuplicateSuppressed", "group", roleName(), "requestId", root,
                            "opId", op, "reason", "physical capture resource limit reached");
                    return;
                }
                try {
                    CaptureCompletion done;
                    try {
                        Object handle = Objects.requireNonNull(resources.openCaptureScoped(
                                new com.telamin.mongoose.service.audit.AuditCaptureIdentity(roleName(), name, effect.generation())),
                                "capture open returned null");
                        long id = captureResourceIds.incrementAndGet();
                        resourcePermit.handle = handle;
                        captureResourcePermits.put(id, resourcePermit);
                        captureHandles.put(id, handle);
                        done = new CaptureCompletion(root, op, completionIds.incrementAndGet(), CaptureCompletion.Kind.OPENED, id, null);
                    } catch (Throwable t) {
                        resourcePermit.release();
                        done = new CaptureCompletion(root, op, completionIds.incrementAndGet(), CaptureCompletion.Kind.OPEN_FAILED,
                                -1, String.valueOf(t));
                    }
                    var pendingTimeout = openTimeouts.remove(op);
                    if (pendingTimeout != null) pendingTimeout.cancel();
                    control.complete(done);
                } finally {
                    releaseThisOpen.run();
                }
            });
        } catch (RuntimeException | Error rejected) {
            var pendingTimeout = openTimeouts.remove(op);
            try {
                if (pendingTimeout != null) pendingTimeout.cancel();
            } finally {
                initialResourcePermit.release();
                releasePermit.run();
            }
            if (rejected instanceof Error fatal) throw fatal;
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
        if (quarantined.containsKey(named)) return quarantinedResult(effect, "processor quarantined");
        // not framed: swapping the audit listener is already its own audited cycle in the processor (an
        // EventLogControlEvent), and a frame around it would flush to the listener being replaced (review of PR 6, 3)
        try {
            resources.installCapture(handle, named.name(), named.eventProcessor());
            long resourceId = effect.resourceId();
            String processorName = named.name();
            long generation = effect.generation();
            resources.observeWriteFailures(handle, failure -> captureWriteFailed(resourceId, generation, processorName, failure));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException | Error failure) {
            if (ProcessorDispatchException.requiresIsolation(named.eventProcessor())) {
                isolateFailedProcessor(named.eventProcessor(), failure);
            }
            throw failure;
        }
        return result(effect, ControlOutcome.SUCCEEDED, null);
    }

    private EffectResult uninstallCapture(ControlEffect.UninstallCapture effect) {
        var resources = captureResources;
        Object handle = captureHandles.get(effect.resourceId());
        if (resources == null || handle == null) {
            return result(effect, ControlOutcome.FAILED, "unknown resource");
        }
        NamedEventProcessor named = processorsByGeneration.get(effect.generation());
        DataFlow flow = named == null || quarantined.containsKey(named) ? null : named.eventProcessor();
        try {
            resources.uninstallCapture(handle, effect.name(), flow);
            captureDetached(effect.resourceId());
        } catch (RuntimeException | Error failed) {
            captureDetachFailed(effect.resourceId(), failed);
            if (failed instanceof VirtualMachineError fatal) throw fatal;
            if (ProcessorDispatchException.requiresIsolation(flow)) isolateFailedProcessor(flow, failed);
            throw failed;
        }
        return result(effect, ControlOutcome.SUCCEEDED, null);
    }

    /** Hand the resource to the closer, which closes it exactly once: whoever removes it from the table closes it. */
    private EffectResult closeCapture(ControlEffect.CloseCapture effect) {
        Object handle = captureHandles.get(effect.resourceId());
        if (handle == null) {
            return result(effect, ControlOutcome.FAILED, "unknown resource");
        }
        if (captureDetachFailures.contains(effect.resourceId())) {
            if (processorsByGeneration.containsKey(effect.generation())) {
                return result(effect, ControlOutcome.FAILED, "capture is still attached to a running processor");
            }
            // Release has finished: retry without touching the released processor. A failure retains both ownership
            // and its diagnostic until a later group/death cleanup confirms the detach.
            String detachOp = effect.opId() + ".detach." + physicalCleanupOps.incrementAndGet();
            control.audit().fallback("EffectRequested", "group", roleName(), "requestId", effect.requestId(),
                    "opId", detachOp, "effect", "UninstallCapture", "resourceId", effect.resourceId(),
                    "generation", effect.generation(), "offline", true, "causeOp", effect.opId());
            try {
                captureResources.uninstallCapture(handle, effect.name(), null);
                captureDetached(effect.resourceId());
                control.audit().fallback("EffectOutcome", "group", roleName(), "requestId", effect.requestId(),
                        "opId", detachOp, "effect", "UninstallCapture", "resourceId", effect.resourceId(),
                        "generation", effect.generation(), "offline", true, "causeOp", effect.opId(), "outcome", "succeeded");
            } catch (RuntimeException | Error failed) {
                captureDetachFailed(effect.resourceId(), failed);
                control.audit().fallback("EffectOutcome", "group", roleName(), "requestId", effect.requestId(),
                        "opId", detachOp, "effect", "UninstallCapture", "resourceId", effect.resourceId(),
                        "generation", effect.generation(), "offline", true, "causeOp", effect.opId(),
                        "outcome", "failed", "reason", String.valueOf(failed), "kind", com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT);
                throw failed;
            }
        }
        if (!captureHandles.remove(effect.resourceId(), handle)) {
            return result(effect, ControlOutcome.FAILED, "resource already released");
        }
        captureDetached(effect.resourceId());
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
            CaptureResourcePermit permit = captureResourcePermits.remove(resourceId);
            if (permit != null) permit.release();
            return new CaptureCompletion(root, op, completionIds.incrementAndGet(), CaptureCompletion.Kind.CLOSED, resourceId, null);
        } catch (Throwable t) {
            CaptureResourcePermit permit = captureResourcePermits.get(resourceId);
            if (permit != null) permit.closeFailure = String.valueOf(t);
            signalCaptureResourceCompletion();
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
                // the open's final outcome, then the compensating close: as the control processor would have recorded
                // an open its timeout already settled has had its final outcome: this one is a duplicate. The group has
                // ended, so its control state no longer changes and is read safely here
                boolean settled = control.processor().controlState.pendingOpen(c.opId()) == null;
                audit.fallback("EffectOutcome", "group", roleName(), "requestId", c.requestId(), "opId", c.opId(),
                        "effect", "OpenCapture", "outcome", "succeeded", "stale", true, "resourceId", c.resourceId(),
                        "duplicate", settled ? Boolean.TRUE : null);
                if (handle == null) {
                    audit.fallback("CaptureCompensated", "group", roleName(), "requestId", c.requestId(),
                            "opId", c.opId(), "resourceId", c.resourceId(), "duplicate", true,
                            "closed", false, "reason", "resource already handed to a closer");
                    break;
                }
                String closeOp = c.requestId() + ".orphan-close." + c.resourceId();
                audit.fallback("EffectRequested", "group", roleName(), "requestId", c.requestId(), "opId", closeOp,
                        "effect", "CloseCapture", "resourceId", c.resourceId(), "causeOp", c.opId());
                CaptureCompletion closed = closeResource(c.requestId(), closeOp, c.resourceId(), handle);
                audit.fallback("EffectOutcome", "group", roleName(), "requestId", c.requestId(), "opId", closeOp,
                        "effect", "CloseCapture", "resourceId", c.resourceId(), "causeOp", c.opId(),
                        "outcome", closed.kind() == CaptureCompletion.Kind.CLOSED ? "succeeded" : "failed",
                        "reason", closed.reason(), "kind", closed.kind() == CaptureCompletion.Kind.CLOSED ? null : com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT);
                audit.fallback("CaptureCompensated", "group", roleName(), "requestId", c.requestId(),
                        "opId", closeOp, "resourceId", c.resourceId(), "causeOp", c.opId(),
                        "closed", closed.kind() == CaptureCompletion.Kind.CLOSED, "reason", closed.reason());
            }
            case CLOSED, CLOSE_FAILED -> audit.fallback("EffectOutcome", "group", roleName(), "requestId", c.requestId(),
                    "opId", c.opId(), "effect", "CloseCapture", "outcome", c.kind() == CaptureCompletion.Kind.CLOSED
                            ? "succeeded" : "failed", "resourceId", c.resourceId(), "reason", c.reason(),
                    "kind", c.kind() == CaptureCompletion.Kind.CLOSED ? null : com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT);
            case OPEN_FAILED -> audit.fallback("EffectOutcome", "group", roleName(), "requestId", c.requestId(),
                    "opId", c.opId(), "effect", "OpenCapture", "outcome", "failed", "stale", true, "reason", c.reason(),
                    "kind", com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT,
                    "duplicate", control.processor().controlState.pendingOpen(c.opId()) == null ? Boolean.TRUE : null);
            default -> audit.fallback("CaptureOrphan", "group", roleName(), "opId", c.opId(), "fact", c.fact());
        }
    }

    /**
     * Captures a dead or wedged control processor could not release (review of PR 5, finding 1): each is uninstalled,
     * touching no processor instance, and closed here, once, and recorded in the fallback stream. Nothing else would
     * close them, and an operator's audit stop to a group that has ended is answered NOT_RUNNING.
     */
    private void releaseLeftoverCaptures() {
        var resources = captureResources;
        if (resources == null) return;
        for (Long id : new ArrayList<>(captureHandles.keySet())) {
            Object handle = captureHandles.get(id);
            if (handle == null) continue;
            String detachOp = "group-detach." + id + "." + physicalCleanupOps.incrementAndGet();
            // A handle whose requested uninstall failed is being retried, not recovered: the control processor did ask
            // for its release. Only a handle with no recorded detach failure is a leftover (review 77, F2).
            boolean leftover = leftoverCaptures.contains(id) || !captureDetachFailures.contains(id);
            if (leftover) leftoverCaptures.add(id);
            Object leftoverMark = leftover ? Boolean.TRUE : null;
            Object leftoverKind = leftover ? com.telamin.mongoose.control.event.FailureKind.LEFTOVER_RECOVERED : null;
            Object retryMark = leftover ? null : Boolean.TRUE;
            control.audit().fallback("EffectRequested", "group", roleName(), "requestId", -1, "opId", detachOp,
                    "effect", "UninstallCapture", "resourceId", id, "generation", -1, "offline", true,
                    "reason", leftover ? "released without the control processor" : "retrying a requested uninstall that failed",
                    "leftover", leftoverMark, "retry", retryMark, "kind", leftoverKind);
            try {
                resources.uninstallCapture(handle, null, null);
                captureDetached(id);
                control.audit().fallback("EffectOutcome", "group", roleName(), "requestId", -1, "opId", detachOp,
                        "effect", "UninstallCapture", "outcome", "succeeded", "resourceId", id,
                        "generation", -1, "offline", true, "leftover", leftoverMark, "retry", retryMark, "kind", leftoverKind);
            } catch (Throwable t) {
                captureDetachFailed(id, t);
                control.audit().fallback("EffectOutcome", "group", roleName(), "requestId", -1, "opId", detachOp,
                        "effect", "UninstallCapture", "outcome", "failed", "resourceId", id,
                        "generation", -1, "offline", true, "reason", String.valueOf(t), "leftover", leftoverMark,
                        "retry", retryMark, "kind", leftover ? leftoverKind : com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT);
                if (t instanceof VirtualMachineError fatal) throw fatal;
                reportCloseFailure("<capture " + id + ">", "could not be detached", t);
                continue;
            }
            if (!captureHandles.remove(id, handle)) continue;
            captureDetached(id);
            String closeOp = "group-close." + id;
            control.audit().fallback("EffectRequested", "group", roleName(), "requestId", -1, "opId", closeOp,
                    "effect", "CloseCapture", "resourceId", id, "generation", -1, "offline", true, "causeOp", detachOp,
                    "leftover", leftoverMark, "retry", retryMark, "kind", leftoverKind);
            CaptureCompletion closed = closeResource(-1, closeOp, id, handle);
            control.audit().fallback("EffectOutcome", "group", roleName(), "requestId", -1, "opId", closeOp,
                    "effect", "CloseCapture", "outcome", closed.kind() == CaptureCompletion.Kind.CLOSED ? "succeeded" : "failed",
                    "resourceId", id, "generation", -1, "offline", true, "causeOp", detachOp,
                    "reason", closed.reason(), "leftover", leftoverMark, "retry", retryMark,
                    "kind", leftover ? leftoverKind : closed.kind() == CaptureCompletion.Kind.CLOSED ? null : com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT);
            leftoverCaptures.remove(id);
        }
    }

    private final java.util.concurrent.atomic.AtomicLong captureWriteFailures = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Fail open (issue #77, M3): a capture's record writes failed. The processor keeps running and nothing is retried;
     * the failure is a control-audit record and raises the group's audit-capture health. Called once per recording, on
     * the thread that wrote; never throws.
     */
    private void captureWriteFailed(long resourceId, long generation, String processorName, String failure) {
        captureWriteFailures.incrementAndGet();
        control.audit().fallback("CaptureWriteFailed", "group", roleName(), "resourceId", resourceId,
                "generation", generation, "name", processorName, "reason", failure, "processorStopped", false,
                "kind", com.telamin.mongoose.control.event.FailureKind.FAILED_EFFECT);
    }

    /** Recordings whose capture writes failed (fail open): the processor kept running. */
    public long captureWriteFailures() {
        return captureWriteFailures.get();
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
        if (quarantined.keySet().stream().anyMatch(p -> p.eventProcessor() == subscriber))
            throw new IllegalStateException("processor is quarantined; no new subscriptions");
        log.info("subscribe subscriptionKey:" + subscriptionKey + " subscriber:" + subscriber);

        EventQueueToEventProcessor eventQueueToEventProcessor = queueProcessorMap.get(subscriptionKey);

        if (eventQueueToEventProcessor == null) {
            eventQueueToEventProcessor = eventFlowManager.getMappingAgent(subscriptionKey, this);
            if (eventQueueToEventProcessor instanceof EventQueueToEventProcessorAgent agent) {
                agent.withProcessorFailureHandler(this::isolateFailedProcessor);
                if (recorder != null) agent.recordWith(recorder);
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
            public boolean stopProcessor(DataFlow flow) {
                for (NamedEventProcessor np : registeredEventProcessors.values()) {
                    if (np.eventProcessor() != flow) continue;
                    ProcessorRegistration registration = registrations.get(np);
                    // never waits on its own full mailbox: the replay retries (review of PR 6, finding 8)
                    control.trySend(new ControlRequest(ControlRequest.Kind.STOP, "replayed stop " + np.name(),
                            id -> stopIntent(id, np.name(), registration), registration));
                    return true;
                }
                return false;
            }

            @Override
            public String deliverService(DataFlow flow, String service, boolean register, long version) {
                for (NamedEventProcessor np : registeredEventProcessors.values()) {
                    if (np.eventProcessor() != flow) continue;
                    // The server publishes the registry before the group's versioned binding. Observe that binding
                    // first, so its publication makes the preceding registry write visible; the reverse read order
                    // could combine an old registry value with a newly published version and falsely stop replay.
                    PublishedService published = register && version > 0 ? publishedServices.get(service) : null;
                    Service<?> svc = register ? registeredServices.get(service) : null;
                    if (register && version > 0) {
                        if (published == null) {
                            // A visible service may still be between registry publication and this group's broadcast.
                            // Wait within deliveryTimeout, never substitute an unverified configured instance.
                            return "wait:no registry binding identifies recorded service " + service + " at version "
                                    + version + "; awaiting version publication";
                        }
                        if (published.version() < version) {
                            return "wait:service " + service + " version " + version + " is not registered yet";
                        }
                        // A retained historical Java object may already have been stopped. Only the currently live,
                        // versioned binding can be injected; reconstructing old incarnations needs an isolated registry.
                        if (published.version() != version || svc != published.service() || svc == null) {
                            return com.telamin.mongoose.replay.ReplayRouting.MISMATCH + "recorded service " + service + " at version " + version
                                    + " is unavailable; current published version is " + published.version();
                        }
                    }
                    if (!register) {
                        for (Service<?> held : servicesDeliveredTo(flow)) {
                            if (held.serviceName().equals(service)) svc = held;
                        }
                        if (svc == null) return com.telamin.mongoose.replay.ReplayRouting.MISMATCH + "recorded withdrawal of service " + service
                                + " has no delivered incarnation";
                    }
                    if (svc == null) return "wait:service " + service + " is not registered";
                    ServiceDelivery delivery = ComposingEventProcessorAgent.this.deliverService(np, svc, register);
                    if (!delivery.invoked() || delivery.failure() != null) {
                        return "recorded service callback for " + service + " was not delivered: "
                                + (delivery.failure() == null ? "already held" : delivery.failure());
                    }
                    return null;
                }
                return "wait:the replayed processor is not running";
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
        return registeredEventProcessors.values().stream().filter(p -> !quarantined.containsKey(p)).toList();
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
    private void release(NamedEventProcessor named, boolean stopping, boolean byRequest) {
        // a stop by request ends the processor's stream, its clock readings recorded from the moment it begins; its
        // group closing does not (the run simply ends there). Which it is comes from the control processor's decision
        ReleaseProgress release = releasesInProgress.computeIfAbsent(named,
                n -> new ReleaseProgress(n, registrations.get(n), stopping, recorder != null && stopping && byRequest));
        continueRelease(release);
    }

    /** Agent-owned until death, then resumed only after join. Advance before user code: an attempted callback is final. */
    private void continueRelease(ReleaseProgress release) {
        NamedEventProcessor named = release.named;
        String name = named.name();
        DataFlow eventProcessor = named.eventProcessor();
        while (release.nextStep < 10) {
            if (quarantined.containsKey(named)) return;
            switch (release.nextStep++) {
                case 0 -> {
                    if (release.recordStop) guarded(name, "failed to frame its stop", () -> recorder.beforeControl(eventProcessor));
                }
                case 1 -> {
                    registeredEventProcessors.remove(name, named);
                    registrations.remove(named);
                    servicesDelivered.remove(eventProcessor);
                }
                case 2 -> {
                    if (release.stopping && eventProcessor instanceof Lifecycle lifecycle) {
                        lifecycleStep(named, "failed to stop cleanly", lifecycle::stop);
                    }
                }
                case 3 -> {
                    if (eventProcessor instanceof Lifecycle lifecycle) {
                        lifecycleStep(named, release.stopping ? "failed to tear down"
                                : "failed to tear down after its failed start", lifecycle::tearDown);
                    }
                }
                case 4 -> releaseSubscriptions(name, eventProcessor);
                case 5 -> guarded(name, "failed to cancel its timers", () -> scheduler.cancelTimersOf(eventProcessor));
                case 6 -> {
                    if (release.recordStop) guarded(name, "failed to record its stop", () -> recorder.stopped(eventProcessor));
                }
                case 7 -> {
                    if (recorder != null) guarded(name, "failed to leave the replay recording", () -> recorder.detach(eventProcessor));
                }
                case 8 -> {
                    if (replayer != null) guarded(name, "failed to leave the replay", () -> replayer.detach(eventProcessor));
                }
                case 9 -> {
                    if (mongooseServer != null) {
                        guarded(name, "failed to release its server bindings", release.stopping
                                ? () -> mongooseServer.processorStopped(roleName(), name, eventProcessor)
                                : () -> mongooseServer.processorDropped(roleName(), name, eventProcessor, release.registration));
                    } else if (captureResources != null && release.registration != null) {
                        guarded(name, "failed to release its capture binding", () -> captureResources.processorReleased(
                                new com.telamin.mongoose.service.audit.AuditCaptureIdentity(
                                        roleName(), name, release.registration.generation()), eventProcessor));
                    }
                }
            }
        }
        releasesInProgress.remove(named);
    }

    private void lifecycleStep(NamedEventProcessor named, String what, Runnable step) {
        try {
            step.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            stepFailures++;
            if (ProcessorDispatchException.requiresIsolation(named.eventProcessor())) {
                isolateFailedProcessor(named.eventProcessor(), failure);
            } else {
                reportProcessorFailure(named.name(), what + ", removed from the group", failure);
            }
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
        if (recorder != null) return recorder.serviceFor(eventProcessor, service);     // control requests recorded
        return replayer == null ? service : replayer.serviceFor(eventProcessor, service);
    }

    private record ServiceDelivery(boolean invoked, String failure) { }

    /**
     * Hand one processor a service (de)registration. A processor that throws is reported and keeps running; the
     * broadcast still reaches every other processor in the group (mongoose#57).
     * <p>
     * Delivery is recorded before the call (mongoose#60), so a {@code registerService} that throws still counts as
     * delivered: a later broadcast of the same service is not offered to that processor again, and a later deregister
     * of it does reach the processor. Likewise a {@code deRegisterService} that throws counts as done.
     *
     * @return whether the callback ran, and its failure if it threw; a skipped broadcast is not a processor input
     */
    private ServiceDelivery deliverService(NamedEventProcessor np, Service<?> svc, boolean register) {
        DataFlow eventProcessor = np.eventProcessor();
        if (quarantined.containsKey(np)) return new ServiceDelivery(false, "processor quarantined after callback failure");
        // each service once (mongoose#60 item 1): a register the processor already has - it joined after the service
        // was in the registry - is skipped, and a deregister reaches only a processor that was handed the service
        if (register ? !servicesDeliveredTo(eventProcessor).add(svc) : !servicesDeliveredTo(eventProcessor).remove(svc)) {
            return new ServiceDelivery(false, null);
        }
        com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(eventProcessor);
        try {
            // not framed: registerService/deRegisterService are exported service calls, already one audited cycle each
            // in the processor; a runInEventCycle around them nested a second record header (review of PR 6, 2)
            if (register) {
                eventProcessor.registerService(serviceFor(eventProcessor, svc));
            } else {
                eventProcessor.deRegisterService(serviceFor(eventProcessor, svc));
            }
            return new ServiceDelivery(true, null);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            if (ProcessorDispatchException.requiresIsolation(eventProcessor)) {
                isolateFailedProcessor(eventProcessor, new ProcessorDispatchException(eventProcessor,
                        register ? "service registration" : "service withdrawal", t));
                return new ServiceDelivery(true, String.valueOf(t));
            }
            reportProcessorFailure(np.name(), (register ? "failed to register" : "failed to deregister")
                    + " service '" + svc.serviceName() + "'", t);
            return new ServiceDelivery(true, String.valueOf(t));
        } finally {
            com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
        }
    }

    private java.util.Set<Service<?>> servicesDeliveredTo(DataFlow eventProcessor) {
        return servicesDelivered.computeIfAbsent(eventProcessor,
                k -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    public boolean isProcessorRegistered(String processorName) {
        NamedEventProcessor named = registeredEventProcessors.get(processorName);
        return named != null && !quarantined.containsKey(named);
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
