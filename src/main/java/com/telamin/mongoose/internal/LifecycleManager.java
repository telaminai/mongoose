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
    private volatile boolean startAttempted;

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
        startAttempted = true;   // a failed start leaves threads running: stop() must still close them
        // Start non-LifeCycleEventSource services
        registeredServices.values().forEach(svc -> {
            if (!(svc.instance() instanceof LifeCycleEventSource)) {
                svc.start();
            }
        });
        // Start flow manager
        log.info("start flowManager");
        flowManager.start();
        // Start agent hosted services
        log.info("start agent hosted services");
        composingServiceAgents.forEach((k, v) -> {
            log.info("starting composing service agent " + k);
            AgentRunner.startOnThread(v.getGroupRunner());
        });
        // Wait for service agents ACTIVE
        log.info("waiting for agent hosted services to start");
        awaitActive("agent hosted service", composingServiceAgents, startupTimeoutMillis);
        // Start event processor agents
        log.info("start event processor agent workers");
        composingEventProcessorAgents.forEach((k, v) -> {
            log.info("starting composing event processor agent " + k);
            AgentRunner.startOnThread(v.getGroupRunner());
        });
        // Wait for processor agents ACTIVE
        log.info("waiting for event processor agents to start");
        awaitActive("event processor", composingEventProcessorAgents, startupTimeoutMillis);
        // Notify start complete on non-agent services
        log.info("calling startup complete on services");
        for (Service<?> service : registeredServices.values()) {
            if (!registeredAgentServices.contains(service)) {
                service.startComplete();
            }
        }
        // Notify start complete on agent groups
        log.info("calling startup complete on agent hosted services");
        composingServiceAgents.values().forEach(GroupRunner::startCompleteIfSupported);
    }

    /**
     * mongoose#60: wait, at most {@code timeoutMillis}, for every group to become ACTIVE. Fails at once, naming them, if
     * any group closed (its thread ended, typically because onStart threw), and at the timeout naming every group not
     * yet active.
     */
    private static void awaitActive(String kind, Map<String, ? extends GroupRunner> groups, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (true) {
            java.util.List<String> notActive = groupsWithStatus(groups, s -> s != DynamicCompositeAgent.Status.ACTIVE);
            if (notActive.isEmpty()) {
                return;
            }
            java.util.List<String> closed = groupsWithStatus(groups, s -> s == DynamicCompositeAgent.Status.CLOSED);
            if (!closed.isEmpty()) {
                throw new IllegalStateException("server start failed: " + kind + " group(s) " + closed
                        + " closed before becoming active (did onStart throw? see the error handler)");
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("server start failed: " + kind + " group(s) " + notActive
                        + " not active after " + timeoutMillis + "ms (startupTimeoutMillis)");
            }
            Thread.sleep(10);
        }
    }

    private static java.util.List<String> groupsWithStatus(Map<String, ? extends GroupRunner> groups,
                                                           java.util.function.Predicate<DynamicCompositeAgent.Status> test) {
        return groups.entrySet().stream()
                .filter(e -> test.test(e.getValue().getGroup().status()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * The ordered shutdown (mongoose#56), run once: a second call, or one racing the first, is a no-op.
     * <ol>
     *   <li>stop the feeds: close the agent groups that host event sources, so they publish no more, then stop and tear
     *   down every event source;</li>
     *   <li>drain: wait, at most {@code drainTimeoutMillis}, for the events already queued to processors to be
     *   delivered (processor groups still run);</li>
     *   <li>close each processor group: on its own thread the group stops and tears down its processors;</li>
     *   <li>close the remaining service agent groups and stop the registered services.</li>
     * </ol>
     */
    public void stop(boolean started,
                     ConcurrentHashMap<String, ? extends GroupRunner> composingEventProcessorAgents,
                     ConcurrentHashMap<String, ? extends GroupRunner> composingServiceAgents,
                     Map<String, Service<?>> registeredServices,
                     EventFlowManager flowManager,
                     long drainTimeoutMillis) {
        log.info("stopping server");
        if (!started && !startAttempted) {
            log.info("server not started, nothing to stop");
            return;
        }
        if (!stopped.compareAndSet(false, true)) {
            log.info("server already stopped");
            return;
        }
        log.info("stopping feeds");
        composingServiceAgents.forEach((k, v) -> {
            if (v.hostsEventSource()) {
                closeGroup("event source agent", k, v);
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
            if (!v.hostsEventSource()) {
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
    }

    /**
     * Simple view wrapper to avoid depending on concrete runner classes.
     */
    public interface GroupRunner {
        AgentRunner getGroupRunner();

        DynamicCompositeAgent getGroup();

        default void startCompleteIfSupported() { /* no-op by default */ }

        /** True for a service agent group hosting an event source: the ordered shutdown closes it first. */
        default boolean hostsEventSource() { return false; }
    }
}
