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
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Composite agent that manages named DataFlow instances within an agent group.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Accepts registrations/removals of named event processors; a removed (stopped) processor and one dropped after
 *       a failed start leave by the same path, which releases its subscriptions, timers, replay bindings and the
 *       server's bindings (mongoose#71)</li>
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
public class ComposingEventProcessorAgent extends DynamicCompositeAgent implements EventFeed<EventSubscriptionKey<?>> {

    private final EventFlowManager eventFlowManager;
    private final ConcurrentHashMap<String, Service<?>> registeredServices;
    private final ConcurrentHashMap<String, NamedEventProcessor> registeredEventProcessors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventSubscriptionKey<?>, EventQueueToEventProcessor> queueProcessorMap = new ConcurrentHashMap<>();
    /**
     * Control queues (mongoose#58): fed from several threads at once - admin, loaders, and every service-agent thread
     * registering its service at boot - and drained by this group's thread alone, so many-to-one. A full queue is
     * waited on (see {@link #enqueue}), never dropped.
     */
    static final int CONTROL_QUEUE_CAPACITY = 4096;
    /** Default for how long a producer waits for this group's thread to make room in a full control queue. */
    static final long CONTROL_QUEUE_FULL_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    private volatile long controlQueueFullTimeoutNanos = CONTROL_QUEUE_FULL_TIMEOUT_NANOS;
    private final ManyToOneConcurrentArrayQueue<Supplier<NamedEventProcessor>> toStartList = new ManyToOneConcurrentArrayQueue<>(CONTROL_QUEUE_CAPACITY);
    private final ManyToOneConcurrentArrayQueue<String> toStopList = new ManyToOneConcurrentArrayQueue<>(CONTROL_QUEUE_CAPACITY);
    // Dynamic-service broadcast queue. MongooseServer.registerService /
    // removeService publish onto it so the agent thread (and only the
    // agent thread) calls eventProcessor.registerService /
    // deRegisterService on every running DataFlow in this group — that's
    // what fires @ServiceRegistered / @ServiceDeregistered on processor-
    // internal nodes (SubscriptionManagerNode for feeds, SinkPublisher
    // for sinks, plus any operator-defined node carrying the annotation).
    // One queue for both, so a service's deregistration is never handled
    // before the registration queued ahead of it (mongoose#60 item 1).
    private final ManyToOneConcurrentArrayQueue<ServiceBroadcast> servicesToBroadcast = new ManyToOneConcurrentArrayQueue<>(CONTROL_QUEUE_CAPACITY);

    private record ServiceBroadcast(Service<?> service, boolean register) {
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
    /** mongoose#46: work other threads hand to this group's thread, drained in doWork. */
    private final org.agrona.concurrent.ManyToOneConcurrentArrayQueue<Runnable> onAgentThread =
            new org.agrona.concurrent.ManyToOneConcurrentArrayQueue<>(256);
    private volatile Thread agentThread;
    /** mongoose#46: set as the group closes; hand-offs are refused after it. */
    private volatile boolean closed;
    /** Without a server: isolate a start failure in onStart instead of failing the group's start (review of #66). */
    private volatile boolean isolateStartFailuresAtBoot;

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
    }

    public void addNamedEventProcessor(Supplier<NamedEventProcessor> initFunction) {
        enqueue(toStartList, initFunction, "processor-start");
    }

    public void removeEventProcessorByName(String name) {
        enqueue(toStopList, name, "processor-stop");
    }

    /**
     * Queue {@code item} for this group's thread. Safe from any number of threads at once (mongoose#58). When the queue
     * is full the caller backs off until the group's thread drains it; it fails with a clear error, rather than drop
     * the item, only when that cannot happen: it is the group's own thread (immediately), or the queue stays full for
     * the control-queue timeout, 10 s by default ({@link #setControlQueueFullTimeout}): the group is not running.
     *
     * @throws IllegalStateException naming the group and the queue when the item cannot be queued
     */
    private <T> void enqueue(ManyToOneConcurrentArrayQueue<T> queue, T item, String what) {
        if (queue.offer(item)) {
            return;
        }
        long timeoutNanos = controlQueueFullTimeoutNanos;
        long deadline = System.nanoTime() + timeoutNanos;
        while (!queue.offer(item)) {
            if (Thread.currentThread() == agentThread) {
                throw new IllegalStateException("group " + roleName() + ": " + what + " queue is full ("
                        + queue.capacity() + ") and cannot drain while its own thread waits on it");
            }
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("group " + roleName() + ": " + what + " queue stayed full ("
                        + queue.capacity() + ") for " + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(timeoutNanos)
                        + " ms; is the group's thread running?");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(50_000);
        }
    }

    /**
     * How long a producer waits on a full control queue for this group's thread to drain it before failing; 10 s by
     * default.
     */
    public void setControlQueueFullTimeout(java.time.Duration timeout) {
        this.controlQueueFullTimeoutNanos = Objects.requireNonNull(timeout, "timeout is null").toNanos();
    }

    /** Queue a service for broadcast {@code @ServiceRegistered} dispatch
     *  on every processor in this group. Called from arbitrary threads
     *  (typically the MongooseServer thread handling an admin call); the
     *  actual {@code eventProcessor.registerService(...)} happens on the
     *  agent thread via {@link #checkForBroadcasts()} so processor-
     *  internal state stays single-threaded. */
    public void broadcastServiceRegistered(Service<?> service) {
        enqueue(servicesToBroadcast, new ServiceBroadcast(service, true), "service broadcast");
    }

    /** Symmetric to {@link #broadcastServiceRegistered}. Fires
     *  {@code @ServiceDeregistered} callbacks on every processor in this
     *  group — that's how SubscriptionManagerNode unbinds a feed and how
     *  SinkPublisher nulls its consumer reference. */
    public void broadcastServiceDeregistered(Service<?> service) {
        enqueue(servicesToBroadcast, new ServiceBroadcast(service, false), "service broadcast");
    }

    @Override
    public void onStart() {
        // Best-effort core pinning if configured for this agent group (guard for null during unit tests)
        if (mongooseServer != null) {
            Integer coreId = mongooseServer.resolveCoreIdForAgentName(roleName());
            if (coreId != null) {
                com.telamin.mongoose.internal.CoreAffinity.pinCurrentThreadToCore(coreId);
            }
        }
        log.info("onStart " + roleName());
        agentThread = Thread.currentThread();
        // the processors queued before the group starts are its boot processors: by default a failed start among them
        // fails the group's start, so the server's boot fails, rather than boot on without it (review of #66)
        startQueued(failsBootOnStartFailure());
        checkForAdded();
        super.onStart();
    }

    @Override
    public int doWork() throws Exception {
        checkForStopped();
        checkForAdded();
        checkForBroadcasts();
        for (Runnable r; (r = onAgentThread.poll()) != null; ) r.run();
        int work = super.doWork();
        if (replayer != null) work += replayer.doWork();
        // Counter accounting:
        //   work > 0  → events were dispatched through the composed agents
        //   work == 0 → the duty cycle didn't find anything to do; idle strategy
        //               about to spin / yield / park.
        // The branch is predictable in steady state (a busy group sees work > 0
        // most ticks; an idle group sees work == 0 most ticks). Both counters
        // are JIT-inlined down to nothing in no-op mode because the call sites
        // are monomorphic.
        if (work > 0) {
            eventsProcessedCounter.increment();
        } else {
            idleCyclesCounter.increment();
        }
        return work;
    }

    /**
     * Run {@code work} on this group's thread (mongoose#46): directly when called on it, or when the group has not
     * started yet (then nothing else drives its processors); otherwise queued for the start of the next duty cycle. A
     * full queue backs off as {@link #enqueue} does, bounded by the control-queue timeout. Refused with an
     * {@link IllegalStateException} once the group has closed: its processors are gone, so nothing is applied. Best
     * effort: work queued as the group closes may be left unrun, so a caller that needs it applied awaits it with a
     * timeout ({@link AgentHandoff}), which then cancels it rather than leave it half applied.
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
        Runnable once = AgentHandoff.once(work);
        enqueue(onAgentThread, once, "agent hand-off");        // a full queue backs off, bounded (mongoose#58)
        // best effort, not a delivery guarantee: if the thread stopped between the check above and the offer, run it
        // here (the claim makes it run once, whichever gets to it). A window remains (a close that drains before this
        // offer and clears the thread after this re-read), in which the work is left unrun; a caller that awaits it
        // times out, and AgentHandoff then cancels it, so nothing is half applied
        Thread after = agentThread;
        if (after == null || !after.isAlive()) once.run();
    }

    @Override
    public void onClose() {
        closed = true;                                                    // no work is taken after this
        for (Runnable r; (r = onAgentThread.poll()) != null; ) r.run();   // work handed over before the close still runs
        stopAndTearDownProcessors();   // mongoose#56: on the group's thread, as every other processor callback
        agentThread = null;
        log.info("onClose " + roleName());
        super.onClose();
    }

    /**
     * mongoose#56: a closing group stops and tears down each processor it still hosts, once (it is removed first). A
     * processor that throws is reported at ERROR through {@link com.telamin.mongoose.service.error.ErrorReporting}
     * and the others are still stopped; a {@link VirtualMachineError} is rethrown (the mongoose#57 policy).
     */
    private void stopAndTearDownProcessors() {
        for (String name : new ArrayList<>(registeredEventProcessors.keySet())) {
            NamedEventProcessor named = registeredEventProcessors.remove(name);
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

    private void startQueued(boolean failFast) {
        if (!toStartList.isEmpty()) {
            toStartList.drain(init -> startProcessor(init, failFast));
        }
    }

    private void checkForAdded() {
        startQueued(false);

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
     * Build, wire and start one processor.
     * <p>
     * A failure here is the processor's, not the group's (mongoose#57): the processor is dropped
     * ({@link #processorDropped}) and the other processors in the group run on. With {@code failFast} (a boot processor,
     * by default) the failure is then rethrown as a {@link ProcessorStartException} naming the group and the processor,
     * so the group's {@code onStart} fails and the server's boot with it; otherwise it is reported through
     * {@code ErrorReporting} and the group carries on.
     * <p>
     * Caught: every {@code Throwable} except {@link VirtualMachineError} ({@code OutOfMemoryError},
     * {@code StackOverflowError}, ...), which propagates to the agent's error handler as before, and under bootServer
     * still exits the JVM.
     */
    private void startProcessor(Supplier<NamedEventProcessor> init, boolean failFast) {
        NamedEventProcessor namedEventProcessor = null;
        try {
            namedEventProcessor = init.get();
            DataFlow eventProcessor = namedEventProcessor.eventProcessor();
            registeredEventProcessors.put(namedEventProcessor.name(), namedEventProcessor);
            com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(eventProcessor);
            // replay: a recorded processor gets a live recording clock, a replayed one a pinned replay clock. Before
            // anything that can subscribe it (services, the feed): a replayed processor's live inputs are muted as
            // each subscription is made, so the replayer must already know it
            if (recorder != null) recorder.attach(namedEventProcessor.name(), eventProcessor);
            if (replayer != null) replayer.attach(namedEventProcessor.name(), eventProcessor);
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
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            String name = namedEventProcessor == null ? "<not built>" : namedEventProcessor.name();
            if (namedEventProcessor != null) {
                processorDropped(namedEventProcessor);
            }
            if (failFast) {
                throw new ProcessorStartException(roleName(), name, t);   // reported once, by the agent's error handler
            }
            reportProcessorFailure(name, "failed to start, dropped from the group", t);
        } finally {
            com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
        }
    }

    private void checkForStopped() {
        if (toStopList.isEmpty()) {
            return;
        }
        // start what was queued before the stop first: a stop made just after an add, while the add is still queued,
        // then finds its processor instead of missing it and leaving it to start and run on
        startQueued(false);
        toStopList.drain(this::stopProcessor);
    }

    /**
     * Stop one processor (mongoose#71): it leaves the group by {@link #release}, the same path a processor dropped after
     * a failed start takes, so its timers, replay bindings and server bindings go with it. It leaves the group even when
     * its {@code stop()} or {@code tearDown()} throws (mongoose#57).
     */
    private void stopProcessor(String name) {
        NamedEventProcessor named = registeredEventProcessors.get(name);
        if (named != null) {
            release(named, true);     // tells the server (processorStopped), once
        }
    }

    /** A processor whose start failed leaves the group (review of #66): {@link #release}, without a {@code stop()}. */
    private void processorDropped(NamedEventProcessor named) {
        release(named, false);
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
                    : () -> mongooseServer.processorDropped(roleName(), name, eventProcessor));
        }
    }

    private void guarded(String name, String what, Runnable step) {
        try {
            step.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
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

    /** Drains pending service-broadcast queues onto every running
     *  processor in this group. Runs on the agent thread so the
     *  per-processor {@code registerService} / {@code deRegisterService}
     *  call (which mutates the processor's internal
     *  {@code ServiceRegistryNode} bookkeeping) inherits the same
     *  threading guarantees as event dispatch. */
    private void checkForBroadcasts() {
        if (!servicesToBroadcast.isEmpty()) {
            servicesToBroadcast.drain(b -> registeredEventProcessors.values().forEach(np ->
                    deliverService(np, b.service(), b.register())));
        }
    }

    /**
     * Hand one processor a service (de)registration. A processor that throws is reported and keeps running; the
     * broadcast still reaches every other processor in the group (mongoose#57).
     * <p>
     * Delivery is recorded before the call (mongoose#60), so a {@code registerService} that throws still counts as
     * delivered: a later broadcast of the same service is not offered to that processor again, and a later deregister
     * of it does reach the processor. Likewise a
     * {@code deRegisterService} that throws counts as done. A processor that must not miss a service handles its own
     * failure inside its handler.
     */
    private void deliverService(NamedEventProcessor np, Service<?> svc, boolean register) {
        DataFlow eventProcessor = np.eventProcessor();
        // each service once (mongoose#60 item 1): a register the processor already has - it joined after the service
        // was in the registry - is skipped, and a deregister reaches only a processor that was handed the service
        if (register ? !servicesDeliveredTo(eventProcessor).add(svc) : !servicesDeliveredTo(eventProcessor).remove(svc)) {
            return;
        }
        com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(eventProcessor);
        try {
            if (register) {
                eventProcessor.registerService(serviceFor(eventProcessor, svc));
            } else {
                eventProcessor.deRegisterService(serviceFor(eventProcessor, svc));
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            reportProcessorFailure(np.name(), (register ? "failed to register" : "failed to deregister")
                    + " service '" + svc.serviceName() + "'", t);
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
