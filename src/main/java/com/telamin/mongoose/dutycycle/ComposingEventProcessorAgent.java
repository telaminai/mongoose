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
 *   <li>Accepts registrations/removals of named event processors</li>
 *   <li>Wires processors to event queues via the EventFlowManager on first subscription</li>
 *   <li>Registers shared services and a SchedulerService into each processor</li>
 *   <li>Adds/removes queue reader agents dynamically as subscriptions change</li>
 * </ul>
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
    /** How long a producer waits for this group's thread to make room in a full control queue before it fails. */
    static final long CONTROL_QUEUE_FULL_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
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
     * and queued for broadcast; without this the processor got it from both. Agent thread only.
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
     * the item, only when that cannot happen: it is the group's own thread, or the queue stays full for
     * {@link #CONTROL_QUEUE_FULL_TIMEOUT_NANOS} (the group is not running).
     */
    private <T> void enqueue(ManyToOneConcurrentArrayQueue<T> queue, T item, String what) {
        if (queue.offer(item)) {
            return;
        }
        long deadline = System.nanoTime() + CONTROL_QUEUE_FULL_TIMEOUT_NANOS;
        while (!queue.offer(item)) {
            if (Thread.currentThread() == agentThread) {
                throw new IllegalStateException("group " + roleName() + ": " + what + " queue is full ("
                        + queue.capacity() + ") and cannot drain while its own thread waits on it");
            }
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("group " + roleName() + ": " + what + " queue stayed full ("
                        + queue.capacity() + ") for " + java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(
                        CONTROL_QUEUE_FULL_TIMEOUT_NANOS) + "s; is the group's thread running?");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(50_000);
        }
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
     * Run {@code work} on this group's thread (mongoose#46): directly when called on it, or when the group is not
     * running (then nothing else drives its processors); otherwise queued for the start of the next duty cycle. Best
     * effort: work queued as the group closes may be left unrun, so a caller that needs it applied awaits it with a
     * timeout ({@link AgentHandoff}), which then cancels it rather than leave it half applied.
     */
    public void runOnAgentThread(Runnable work) {
        Thread agent = agentThread;
        if (agent == null || agent == Thread.currentThread() || !agent.isAlive()) {
            work.run();
            return;
        }
        Runnable once = AgentHandoff.once(work);
        if (!onAgentThread.offer(once)) {
            throw new IllegalStateException("group " + roleName() + " cannot take more work from other threads");
        }
        // best effort, not a delivery guarantee: if the thread stopped between the check above and the offer, run it
        // here (the claim makes it run once, whichever gets to it). A window remains (a close that drains before this
        // offer and clears the thread after this re-read), in which the work is left unrun; a caller that awaits it
        // times out, and AgentHandoff then cancels it, so nothing is half applied
        Thread after = agentThread;
        if (after == null || !after.isAlive()) once.run();
    }

    @Override
    public void onClose() {
        for (Runnable r; (r = onAgentThread.poll()) != null; ) r.run();   // work handed over before the close still runs
        agentThread = null;
        log.info("onClose " + roleName());
        super.onClose();
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
     * source subscription is released; and the reader leaves the composite rather than poll a queue nobody fills.
     */
    private void deregister(DataFlow subscriber, EventSubscriptionKey<?> subscriptionKey, EventQueueToEventProcessor reader) {
        if (reader.deregisterProcessor(subscriber) > 0) {
            return;
        }
        if (!queueProcessorMap.remove(subscriptionKey, reader)) {
            return; // already released
        }
        log.info("EventQueueToEventProcessor listener count = 0, removing subscription:" + subscriptionKey);
        eventFlowManager.unSubscribe(subscriptionKey);
        // a reader not yet added to the composite is simply never added; one already in it is taken out
        if (!queueReadersToAdd.remove(reader)) {
            queueReadersToRemove.add(reader);
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

    private void checkForAdded() {
        if (!toStartList.isEmpty()) {
            toStartList.drain(this::startProcessor);
        }

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
     * Build, wire and start one processor. A failure here is the processor's, not the group's (mongoose#57): it is
     * reported and the processor dropped, and the other processors in the group run on. Left to escape, it would reach
     * the agent's error handler, which under bootServer exits the JVM.
     */
    private void startProcessor(Supplier<NamedEventProcessor> init) {
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
            reportProcessorFailure(name, "failed to start, dropped from the group", t);
            if (namedEventProcessor != null) {
                registeredEventProcessors.remove(name, namedEventProcessor);
                servicesDelivered.remove(namedEventProcessor.eventProcessor());
                releaseSubscriptions(name, namedEventProcessor.eventProcessor());
            }
        } finally {
            com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
        }
    }

    private void checkForStopped() {
        if (toStopList.isEmpty()) {
            return;
        }
        toStopList.drain(this::stopProcessor);
    }

    /** Stop and tear down one processor; it leaves the group even when either throws (mongoose#57). */
    private void stopProcessor(String name) {
        NamedEventProcessor named = registeredEventProcessors.remove(name);
        if (named == null) {
            return;
        }
        servicesDelivered.remove(named.eventProcessor());
        DataFlow eventProcessor = named.eventProcessor();
        try {
            if (eventProcessor instanceof Lifecycle) {
                ((Lifecycle) eventProcessor).stop();
                ((Lifecycle) eventProcessor).tearDown();
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            reportProcessorFailure(name, "failed to stop cleanly, removed from the group", t);
            releaseSubscriptions(name, eventProcessor);
        }
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
        String message = "processor '" + processorName + "' in group '" + roleName() + "' " + what + ": " + t;
        log.severe(message);
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
