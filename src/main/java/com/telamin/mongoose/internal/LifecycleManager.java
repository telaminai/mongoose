/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.LifeCycleEventSource;
import lombok.extern.java.Log;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.DynamicCompositeAgent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulates lifecycle orchestration for MongooseServer.
 * Keeps MongooseServer thin and focused on API while preserving behavior.
 */
@Log
public final class LifecycleManager {

    private final MongooseServer server;
    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
    /** Serialises startup effects with shutdown, never used on an event-delivery path. */
    private final Object startupLock = new Object();
    /** A stop may first ask an in-progress synchronous startup callback to return. */
    private volatile Runnable cancelStartup;
    /**
     * Runners whose thread has been started (review of #69). Agrona's losing {@code run()} still marks the runner closed,
     * so a second start of the same runner would report it closed while its real thread runs.
     */
    private final Set<AgentRunner> startedRunners = ConcurrentHashMap.newKeySet();
    /** Bound on taking the feeds off a service agent group shared with other services, during stop. */
    static final long EVENT_SOURCE_REMOVAL_TIMEOUT_MILLIS = 5_000;

    public LifecycleManager(MongooseServer server) {
        this.server = server;
    }

    public void init(Map<String, Service<?>> registeredServices,
                     Set<Service<?>> registeredAgentServices,
                     EventFlowManager flowManager,
                     com.telamin.fluxtion.runtime.service.ServiceRegistryNode serviceRegistry) {
        log.info("init");
        // Init non-LifeCycleEventSource services
        registeredServices.values().forEach(svc -> {
            if (!(svc.instance() instanceof LifeCycleEventSource)) {
                svc.init();
            }
        });
        // Init event sources via flow manager
        flowManager.init();
        // Warn loudly about any LifeCycleEventSource service that fell into the
        // contract trap: it was skipped above (deliberately — flow manager owns
        // its lifecycle) AND flow manager never heard of it (its
        // setEventFlowManager didn't call registerEventSource(name, this)).
        // The net effect is that its init() / start() never run, which
        // historically surfaced as silent "command not found" from the admin
        // surfaces. The standard AbstractEventSourceService superclass
        // self-registers, so any new direct LifeCycleEventSource implementor
        // must do the same — or implement Lifecycle directly and stop wearing
        // the marker.
        warnOnOrphanLifeCycleEventSources(registeredServices, flowManager);
        // Register non-agent services with registry and inject dependencies
        registeredServices.values().forEach(svc -> {
            if (!(registeredAgentServices.contains(svc))) {
                serviceRegistry.nodeRegistered(svc.instance(), svc.serviceName());
                server.servicesRegistered().forEach(serviceRegistry::registerService);
            }
        });
    }

    private static void warnOnOrphanLifeCycleEventSources(Map<String, Service<?>> registeredServices,
                                                          EventFlowManager flowManager) {
        for (Service<?> svc : registeredServices.values()) {
            Object instance = svc.instance();
            if (instance instanceof LifeCycleEventSource && !flowManager.knowsEventSource(instance)) {
                log.warning("Service '" + svc.serviceName() + "' (" + instance.getClass().getName()
                        + ") implements LifeCycleEventSource but was never registered with the"
                        + " EventFlowManager — its init() / start() / stop() will never run."
                        + " The implementation should call"
                        + " EventFlowManager.registerEventSource(name, this) during"
                        + " setEventFlowManager(...) (the AbstractEventSourceService"
                        + " superclass does this automatically) — otherwise drop the"
                        + " LifeCycleEventSource marker and implement Lifecycle directly so"
                        + " the LifecycleManager picks it up.");
            }
        }
    }

    public void start(Map<String, Service<?>> registeredServices,
                      ConcurrentHashMap<String, ? extends GroupRunner> composingServiceAgents,
                      ConcurrentHashMap<String, ? extends GroupRunner> composingEventProcessorAgents,
                      EventFlowManager flowManager,
                      Set<Service<?>> registeredAgentServices,
                      long startupTimeoutMillis) throws InterruptedException {
        log.info("start");
        // Start non-LifeCycleEventSource services
        registeredServices.values().forEach(svc -> {
            if (!(svc.instance() instanceof LifeCycleEventSource)) {
                startStep(svc::start, svc::stop);
            }
        });
        // Start flow manager
        log.info("start flowManager");
        flowManager.start(source -> startStep(source::start, source::stop));
        // Start agent hosted services
        log.info("start agent hosted services");
        composingServiceAgents.forEach((k, v) -> {
            log.info("starting composing service agent " + k);
            startAgent(v.getGroupRunner());
        });
        // Wait for service agents ACTIVE
        log.info("waiting for agent hosted services to start");
        awaitActive("agent hosted service", composingServiceAgents, startupTimeoutMillis);
        // Start event processor agents
        log.info("start event processor agent workers");
        composingEventProcessorAgents.forEach((k, v) -> {
            log.info("starting composing event processor agent " + k);
            startAgent(v.getGroupRunner());
        });
        // Wait for processor agents ACTIVE
        log.info("waiting for event processor agents to start");
        awaitActive("event processor", composingEventProcessorAgents, startupTimeoutMillis);
        // Notify start complete on non-agent services
        log.info("calling startup complete on services");
        for (Service<?> service : registeredServices.values()) {
            if (!registeredAgentServices.contains(service)) {
                startStep(service::startComplete, service::stop);
            }
        }
        // Notify start complete on agent groups
        log.info("calling startup complete on agent hosted services");
        composingServiceAgents.values().forEach(group -> startStep(group::startCompleteIfSupported, null));
    }

    /** Start a group only while startup is still admitted, including groups discovered during startup. */
    public void startAgent(AgentRunner runner) {
        startStep(() -> startOnce(runner), null);
    }

    /** Start {@code runner}'s thread unless it was already started; safe when several threads race to start it. */
    public void startOnce(AgentRunner runner) {
        if (startedRunners.add(runner)) {
            AgentRunner.startOnThread(runner);
        }
    }

    private void startStep(Runnable effect, Runnable cancel) {
        synchronized (startupLock) {
            requireStarting();
            cancelStartup = cancel;
            try {
                // A stop may have won between the first check and publishing the cancellation hook.
                requireStarting();
                effect.run();
                requireStarting();
            } finally {
                cancelStartup = null;
            }
        }
    }

    private void requireStarting() {
        if (stopped.get()) {
            throw new IllegalStateException(STOPPED_DURING_START);
        }
    }

    /**
     * mongoose#60: wait, at most {@code timeoutMillis}, for every group to become ACTIVE. Fails:
     * <ul>
     *   <li>at once with "stopped during start" when a stop has begun (a stop closes the groups this waits on);</li>
     *   <li>at once, naming them, when a group ended without becoming active: its status is CLOSED (onStart threw and
     *   onClose ran) or its agent thread is over (a VirtualMachineError escaped onStart, which skips onClose);</li>
     *   <li>at the timeout, naming every group not yet active.</li>
     * </ul>
     */
    private void awaitActive(String kind, Map<String, ? extends GroupRunner> groups, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (true) {
            requireStarting();
            java.util.List<String> notActive = groupsMatching(groups, g -> g.getGroup().status() != DynamicCompositeAgent.Status.ACTIVE);
            if (notActive.isEmpty()) {
                return;
            }
            java.util.List<String> ended = groupsMatching(groups, g -> g.getGroup().status() != DynamicCompositeAgent.Status.ACTIVE
                    && (g.getGroup().status() == DynamicCompositeAgent.Status.CLOSED || g.getGroupRunner().isClosed()));
            // read after the statuses: a stop sets the flag before it closes any group, so a group closed by a stop is
            // always reported as the stop
            if (stopped.get()) {
                throw new IllegalStateException(STOPPED_DURING_START + ": " + kind + " group(s) " + notActive
                        + " were not yet active");
            }
            if (!ended.isEmpty()) {
                throw new IllegalStateException("server start failed: " + kind + " group(s) " + ended
                        + " ended before becoming active (onStart failed? see the error handler)");
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("server start failed: " + kind + " group(s) " + notActive
                        + " not active after " + timeoutMillis + "ms (startupTimeoutMillis)");
            }
            Thread.sleep(10);
        }
    }

    /** The message prefix of a start that failed because the server was stopped while starting. */
    public static final String STOPPED_DURING_START = "server start failed: the server was stopped during start";

    private static java.util.List<String> groupsMatching(Map<String, ? extends GroupRunner> groups,
                                                         java.util.function.Predicate<GroupRunner> test) {
        return groups.entrySet().stream()
                .filter(e -> test.test(e.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * The ordered shutdown (mongoose#56), run once: a second call, or one racing the first, is a no-op.
     * <ol>
     *   <li>stop the feeds: close the agent groups that host only event sources, and take the event sources off the
     *   groups that also host other services (those keep running), so no feed publishes any more; then stop and tear
     *   down every event source;</li>
     *   <li>drain: wait, at most {@code drainTimeoutMillis}, for the events already queued to processors to be
     *   delivered (processor groups still run);</li>
     *   <li>close each processor group: on its own thread the group stops and tears down its processors;</li>
     *   <li>close every remaining service agent group, shared ones included, and stop the registered services.</li>
     * </ol>
     */
    public void stop(boolean started,
                     ConcurrentHashMap<String, ? extends GroupRunner> composingEventProcessorAgents,
                     ConcurrentHashMap<String, ? extends GroupRunner> composingServiceAgents,
                     Map<String, Service<?>> registeredServices,
                     EventFlowManager flowManager,
                     long drainTimeoutMillis) {
        log.info("stopping server");
        if (!started) {
            log.info("server not started, nothing to stop");
            return;
        }
        if (!stopped.compareAndSet(false, true)) {
            log.info("server already stopped");
            return;
        }
        // Signal outside startupLock: a callback may need stop() to unblock. Once it returns, take the fence before
        // the final cleanup, so a callback finishing late cannot reopen a resource after that resource was closed.
        // Only this in-progress callback may receive stop twice: cancellation, then the ordered final stop below.
        Runnable cancel = cancelStartup;
        if (cancel != null) {
            try {
                cancel.run();
            } catch (Throwable t) {
                log.log(java.util.logging.Level.WARNING, "in-progress startup could not be cancelled", t);
            }
        }
        synchronized (startupLock) {
            // The last synchronous startup effect has returned, and all later ones see stopped == true.
        }
        log.info("stopping feeds");
        // a group hosting only feeds closes; one that also hosts other services keeps running them, and only its feeds
        // are taken off its thread: processors may still use those services while they drain and stop
        Set<String> closedServiceGroups = new java.util.HashSet<>();
        composingServiceAgents.forEach((k, v) -> {
            if (!v.hostsEventSource()) {
                return;
            }
            if (v.hostsOnlyEventSources()) {
                closeGroup("event source agent", k, v);
                closedServiceGroups.add(k);
            } else {
                log.info("removing the event sources from shared service agent " + k);
                try {
                    if (!v.removeEventSources(EVENT_SOURCE_REMOVAL_TIMEOUT_MILLIS)) {
                        log.warning("event sources in service agent " + k + " not removed after "
                                + EVENT_SOURCE_REMOVAL_TIMEOUT_MILLIS + "ms, stopping them anyway");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        flowManager.stop();

        log.info("draining queued events, at most " + drainTimeoutMillis + "ms");
        try {
            if (!flowManager.awaitQueuesDrained(drainTimeoutMillis)) {
                log.warning("shutdown drain timed out after " + drainTimeoutMillis + "ms, dropping "
                        + flowManager.pendingQueuedEvents() + " queued events");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warning("shutdown drain interrupted, dropping " + flowManager.pendingQueuedEvents() + " queued events");
        }

        log.info("stopping event processor agents");
        composingEventProcessorAgents.forEach((k, v) -> closeGroup("event processor agent", k, v));

        log.info("stopping agent hosted services");
        composingServiceAgents.forEach((k, v) -> {
            if (!closedServiceGroups.contains(k)) {
                closeGroup("service agent", k, v);
            }
        });
        log.info("stopping registered services");
        for (Service<?> service : registeredServices.values()) {
            if (!(service.instance() instanceof LifeCycleEventSource)) {
                try {
                    service.stop();
                } catch (Throwable t) {
                    log.log(java.util.logging.Level.WARNING, "service " + service.serviceName() + " failed to stop", t);
                }
            }
        }
    }

    /** Close a group's runner, which waits for its thread to run onClose; an unstarted group closes on this thread. */
    private static void closeGroup(String kind, String name, GroupRunner group) {
        log.info("stopping composing " + kind + " " + name);
        try {
            group.getGroupRunner().close();
        } catch (Throwable t) {
            log.log(java.util.logging.Level.WARNING, "composing " + kind + " " + name + " failed to close", t);
        }
        // a group whose thread died never ran onClose (Agrona's close() returns at once for it): release what it held
        // here, now that its thread has ended, so its processors are stopped and its requests answered
        if (group.getGroup() instanceof com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent processors) {
            try {
                processors.cleanupAfterDeath();
            } catch (Throwable t) {
                log.log(java.util.logging.Level.WARNING, "composing " + kind + " " + name + " failed its post-death cleanup", t);
            }
        }
    }

    /**
     * Simple view wrapper to avoid depending on concrete runner classes.
     */
    public interface GroupRunner {
        AgentRunner getGroupRunner();

        DynamicCompositeAgent getGroup();

        default void startCompleteIfSupported() { /* no-op by default */ }

        /** True for a service agent group hosting an event source: the ordered shutdown stops its feeds first. */
        default boolean hostsEventSource() { return false; }

        /** True when every agent in the group is an event source: the ordered shutdown closes the whole group first. */
        default boolean hostsOnlyEventSources() { return hostsEventSource(); }

        /**
         * Take the event source agents off this running group, leaving its other services running; waits at most
         * {@code timeoutMillis}. Returns false if the wait ran out.
         */
        default boolean removeEventSources(long timeoutMillis) throws InterruptedException { return true; }
    }
}
