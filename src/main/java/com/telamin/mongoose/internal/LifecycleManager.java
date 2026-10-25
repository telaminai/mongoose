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
    /**
     * Serialises startup effects with shutdown, never used on an event-delivery path. A {@code Lock}, not a monitor, so
     * a stop can wait for an in-progress startup step with a deadline ({@code tryLock}) instead of forever.
     */
    private final java.util.concurrent.locks.ReentrantLock startupLock = new java.util.concurrent.locks.ReentrantLock();
    /** Progress of the ordered stop, so a stop that ran out of its deadline resumes where it left off. */
    private final StopProgress progress = new StopProgress();
    /** The helper threads a stop closes groups and stops services on: a stop called from one cannot wait for itself. */
    private final Set<Thread> stopHelpers = ConcurrentHashMap.newKeySet();
    private final Map<Thread, Throwable> helperFailures = new ConcurrentHashMap<>();
    /** A stop may first ask an in-progress synchronous startup callback to return. */
    private record StartupCancellation(Runnable action, Service<?> service) { }
    private volatile StartupCancellation cancelStartup;
    private Thread startupCancellation;
    private String cancelledStartupService;
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
                startStep(svc::start, svc::stop, svc);
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
                startStep(service::startComplete, service::stop, service);
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

    /** True once {@code runner}'s thread has been started, even before the thread itself has run and is visible. */
    public boolean isLaunched(AgentRunner runner) {
        return startedRunners.contains(runner);
    }

    /** Start {@code runner}'s thread unless it was already started; safe when several threads race to start it. */
    public void startOnce(AgentRunner runner) {
        if (startedRunners.add(runner)) {
            AgentRunner.startOnThread(runner);
        }
    }

    private void startStep(Runnable effect, Runnable cancel) {
        startStep(effect, cancel, null);
    }

    private void startStep(Runnable effect, Runnable cancel, Service<?> service) {
        startupLock.lock();
        try {
            requireStarting();
            cancelStartup = cancel == null ? null : new StartupCancellation(cancel, service);
            try {
                // A stop may have won between the first check and publishing the cancellation hook.
                requireStarting();
                effect.run();
                requireStarting();
            } finally {
                cancelStartup = null;
            }
        } finally {
            startupLock.unlock();
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

    /** Which phases of the ordered stop have finished, and the close helpers still running. Guarded by {@code this}. */
    private static final class StopProgress {
        boolean fenced;
        boolean feedsStopped;
        boolean drained;
        final java.util.Set<String> sourcesRemoved = new java.util.HashSet<>();
        final java.util.Set<String> closed = new java.util.HashSet<>();
        final java.util.Map<String, Thread> helpers = new java.util.LinkedHashMap<>();
        final java.util.Set<String> servicesStopped = new java.util.HashSet<>();
        boolean complete;
    }

    /**
     * The ordered shutdown (mongoose#56, design "Bounded shutdown"), bounded end to end by {@code shutdownTimeoutMillis}:
     * each phase gets what is left of it.
     * <ol>
     *   <li>fence startup: ask an in-progress startup step to return and wait for it, with the deadline;</li>
     *   <li>stop the feeds: close the agent groups that host only event sources, and take the event sources off the
     *   groups that also host other services (those keep running); then stop and tear down every event source;</li>
     *   <li>drain: wait for the events already queued to processors to be delivered (processor groups still run);</li>
     *   <li>close each processor group from its own helper thread, joining each with the deadline, because
     *   {@code AgentRunner.close()} itself retries until the runner closes;</li>
     *   <li>only when every processor group has closed: close the remaining service agent groups the same way, then
     *   stop the registered services, each on a helper thread with the deadline.</li>
     * </ol>
     * A stop that runs out returns an incomplete {@link com.telamin.mongoose.StopResult} naming what is still running;
     * calling it again resumes from the first unfinished phase and re-joins the helpers already started, never starting
     * a second close of the same group. Services are never stopped while a processor group still runs: broadcasts reach
     * every group, so any running processor may hold any service.
     */
    public synchronized com.telamin.mongoose.StopResult stop(boolean started,
                     ConcurrentHashMap<String, ? extends GroupRunner> composingEventProcessorAgents,
                     ConcurrentHashMap<String, ? extends GroupRunner> composingServiceAgents,
                     Map<String, Service<?>> registeredServices,
                     EventFlowManager flowManager,
                     long drainTimeoutMillis,
                     long shutdownTimeoutMillis) {
        log.info("stopping server");
        if (!started) {
            log.info("server not started, nothing to stop");
            return com.telamin.mongoose.StopResult.completed();
        }
        if (progress.complete) {
            log.info("server already stopped");
            return com.telamin.mongoose.StopResult.completed();
        }
        stopped.set(true);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, shutdownTimeoutMillis));
        // an interrupted stop thread would return from every wait at once and report everything as still running: the
        // interrupt is set aside for the attempt and restored after it (review of PR 2, finding 6)
        boolean interrupted = Thread.interrupted();
        try {
            return stopPhases(composingEventProcessorAgents, composingServiceAgents, registeredServices, flowManager,
                    drainTimeoutMillis, deadline);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Never-started groups still need terminal replies; close them on tracked helpers within the same deadline. */
    public synchronized com.telamin.mongoose.StopResult closeBeforeStart(
            ConcurrentHashMap<String, ? extends GroupRunner> groups, Map<String, Service<?>> removedServices, long deadline) {
        java.util.List<String> running = closeAll("unstarted processor agent", "processor:", groups, deadline);
        if (!running.isEmpty()) {
            return com.telamin.mongoose.StopResult.incomplete(null, running, servicesNotStopped(removedServices));
        }
        java.util.List<String> servicesLeft = stopServices(removedServices, deadline);
        return servicesLeft.isEmpty() ? com.telamin.mongoose.StopResult.completed()
                : com.telamin.mongoose.StopResult.incomplete(null, running, servicesLeft);
    }

    private com.telamin.mongoose.StopResult stopPhases(ConcurrentHashMap<String, ? extends GroupRunner> composingEventProcessorAgents,
                                                       ConcurrentHashMap<String, ? extends GroupRunner> composingServiceAgents,
                                                       Map<String, Service<?>> registeredServices,
                                                       EventFlowManager flowManager,
                                                       long drainTimeoutMillis,
                                                       long deadline) {

        // 1. fence startup. Signal outside the lock: a callback may need stop() to unblock. Once it returns, holding the
        // lock means the last synchronous startup effect has returned, and all later ones see stopped == true
        if (!progress.fenced) {
            StartupCancellation cancel = cancelStartup;
            if (cancel != null && startupCancellation == null) {
                Service<?> owner = cancel.service();
                // A replacement may reuse the name. A cancellation can stand for only this exact service's stop.
                cancelledStartupService = owner != null && registeredServices.get(owner.serviceName()) == owner
                        ? owner.serviceName() : null;
                startupCancellation = helper(cancel.action(), "mongoose-cancel-startup");
                if (cancelledStartupService != null) {
                    // Cancellation is this service's stop attempt, even when it fails. Reuse the same result in the
                    // final service phase instead of either stranding all other cleanup or invoking stop twice.
                    progress.helpers.put("service-stop:" + cancelledStartupService, startupCancellation);
                }
            }
            if (startupCancellation != null && !join(startupCancellation, deadline)) {
                if (startupCancellation.isAlive() || cancelledStartupService == null) {
                    String reason = startupCancellation.isAlive() ? "startup cancellation has not returned"
                            : "startup cancellation failed for an untracked source/service: " + helperFailures.get(startupCancellation);
                    return com.telamin.mongoose.StopResult.incomplete(reason,
                            new java.util.ArrayList<>(composingEventProcessorAgents.keySet()), servicesNotStopped(registeredServices));
                }
                // A failed service cancellation has ended. Once startup is fenced, other groups and services can
                // still be released; the failed helper remains the named service's incomplete stop outcome.
            }
            boolean fenced = false;
            try {
                fenced = startupLock.tryLock(remainingMillis(deadline), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!fenced) {
                String blocked = "a synchronous startup step did not return within the shutdown deadline";
                log.warning("stop incomplete: " + blocked);
                // nothing was closed: every group and service is still running
                java.util.List<String> groups = new java.util.ArrayList<>(composingEventProcessorAgents.keySet());
                groups.addAll(composingServiceAgents.keySet());
                return com.telamin.mongoose.StopResult.incomplete(blocked, groups, servicesNotStopped(registeredServices));
            }
            startupLock.unlock();
            progress.fenced = true;
        }

        // 2. stop the feeds, bounded and capped (review of PR 2, finding 3): event-source-only groups close on tracked
        // helpers and the sources are stopped on one, so a feed that does not stop cannot hold the whole stop. The stop
        // carries on without it, and reports it at the end if it is still running then
        java.util.List<String> feedsRunning = new java.util.ArrayList<>();
        if (!progress.feedsStopped) {
            log.info("stopping feeds");
            long feedDeadline = Math.min(deadline,
                    System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(EVENT_SOURCE_REMOVAL_TIMEOUT_MILLIS));
            ConcurrentHashMap<String, GroupRunner> feedOnly = new ConcurrentHashMap<>();
            composingServiceAgents.forEach((k, v) -> {
                if (!v.hostsEventSource()) {
                    return;
                }
                if (v.hostsOnlyEventSources()) {
                    feedOnly.put(k, v);
                } else if (progress.sourcesRemoved.add(k)) {
                    log.info("removing the event sources from shared service agent " + k);
                    try {
                        long bound = remainingMillis(feedDeadline);
                        if (!v.removeEventSources(bound)) {
                            log.warning("event sources in service agent " + k + " not removed after "
                                    + bound + "ms, stopping them anyway");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            feedsRunning.addAll(closeAll("event source agent", "service:", feedOnly, feedDeadline));
            Thread sources = progress.helpers.computeIfAbsent("feeds:stop", k -> helper(flowManager::stop, "mongoose-stop-event-sources"));
            if (join(sources, feedDeadline)) {
                progress.helpers.remove("feeds:stop");
                if (feedsRunning.isEmpty()) progress.feedsStopped = true;
            } else {
                feedsRunning.add("<event sources stopping>");
            }
            if (!feedsRunning.isEmpty()) log.warning("feeds still stopping after their bound, carrying on: " + feedsRunning);
        }

        // 3. drain
        if (!progress.drained) {
            long bound = Math.min(drainTimeoutMillis, remainingMillis(deadline));
            log.info("draining queued events, at most " + bound + "ms");
            try {
                if (!flowManager.awaitQueuesDrained(bound)) {
                    log.warning("shutdown drain timed out after " + bound + "ms, dropping "
                            + flowManager.pendingQueuedEvents() + " queued events");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warning("shutdown drain interrupted, dropping " + flowManager.pendingQueuedEvents() + " queued events");
            }
            progress.drained = true;
        }

        // 4. close processor groups, each from a tracked helper, joined with the deadline
        log.info("stopping event processor agents");
        java.util.List<String> running = closeAll("event processor agent", "processor:", composingEventProcessorAgents, deadline);
        if (!running.isEmpty()) {
            java.util.List<String> services = servicesNotStopped(registeredServices);
            log.warning("stop incomplete: processor group(s) " + running + " did not close within the shutdown deadline;"
                    + " services left running for them: " + services);
            running.addAll(feedsRunning);
            return com.telamin.mongoose.StopResult.incomplete(null, running, services);
        }

        // 5. service groups, then services: a worker may use a registered service, so none is stopped while a service
        // group is still closing (review of PR 2, finding 9)
        log.info("stopping agent hosted services");
        running = closeAll("service agent", "service:", composingServiceAgents, deadline);
        if (!progress.feedsStopped) {
            // re-join what was left of phase 2 with what is left of the whole deadline
            Thread sources = progress.helpers.get("feeds:stop");
            if (sources == null || join(sources, deadline)) {
                progress.helpers.remove("feeds:stop");
                if (running.isEmpty()) progress.feedsStopped = true;
            } else {
                running.add("<event sources stopping>");
            }
        }
        if (!running.isEmpty()) {
            java.util.List<String> services = servicesNotStopped(registeredServices);
            log.warning("stop incomplete: service group(s) " + running + " did not close within the shutdown deadline;"
                    + " services left running for them: " + services);
            return com.telamin.mongoose.StopResult.incomplete(null, running, services);
        }
        log.info("stopping registered services");
        java.util.List<String> servicesLeft = stopServices(registeredServices, deadline);
        if (!servicesLeft.isEmpty()) {
            log.warning("stop incomplete: services still stopping " + servicesLeft);
            return com.telamin.mongoose.StopResult.incomplete(null, running, servicesLeft);
        }
        progress.complete = true;
        return com.telamin.mongoose.StopResult.completed();
    }

    /** Stop owned resources on tracked helpers; both started and pre-start shutdown share failure/deadline handling. */
    private java.util.List<String> stopServices(Map<String, Service<?>> registeredServices, long deadline) {
        java.util.List<String> servicesLeft = new java.util.ArrayList<>();
        for (Service<?> service : registeredServices.values()) {
            if (service.instance() instanceof LifeCycleEventSource || progress.servicesStopped.contains(service.serviceName())) {
                continue;
            }
            String helperName = "service-stop:" + service.serviceName();
            Thread helper = progress.helpers.computeIfAbsent(helperName, k -> helper(service::stop,
                    "mongoose-stop-" + service.serviceName()));
            if (join(helper, deadline)) {
                progress.helpers.remove(helperName);
                progress.servicesStopped.add(service.serviceName());
            } else {
                servicesLeft.add(service.serviceName());
            }
        }
        return servicesLeft;
    }

    /** Close each group not yet closed from its own helper thread; @return the groups still closing at the deadline. */
    private java.util.List<String> closeAll(String kind, String prefix, ConcurrentHashMap<String, ? extends GroupRunner> groups,
                                            long deadline) {
        groups.forEach((k, v) -> {
            String key = prefix + k;
            Thread previous = progress.helpers.get(key);
            if (previous != null && !previous.isAlive() && helperFailures.remove(previous) != null) {
                progress.helpers.remove(key);
                stopHelpers.remove(previous);
            }
            if (progress.closed.contains(key) || progress.helpers.containsKey(key)) return;
            progress.helpers.put(key, helper(() -> closeGroup(kind, k, v), "mongoose-stop-" + k));
        });
        java.util.List<String> running = new java.util.ArrayList<>();
        for (String k : groups.keySet()) {
            String key = prefix + k;
            Thread helper = progress.helpers.get(key);
            if (helper == null) continue;
            if (join(helper, deadline)) {
                progress.helpers.remove(key);
                progress.closed.add(key);
            } else {
                running.add(k);
            }
        }
        return running;
    }

    /** A tracked daemon helper running {@code work}; a throw is logged. */
    private Thread helper(Runnable work, String name) {
        Thread t = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable failed) {
                helperFailures.put(Thread.currentThread(), failed);
                log.log(java.util.logging.Level.WARNING, name + " failed", failed);
            }
        }, name);
        t.setDaemon(true);
        stopHelpers.add(t);
        t.start();
        return t;
    }

    /** The registered services the stop has not stopped yet (event sources are stopped with the feeds). */
    private java.util.List<String> servicesNotStopped(Map<String, Service<?>> registeredServices) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Service<?> service : registeredServices.values()) {
            if (service.instance() instanceof LifeCycleEventSource || progress.servicesStopped.contains(service.serviceName())) {
                continue;
            }
            out.add(service.serviceName());
        }
        return out;
    }

    /** True for a thread a stop runs its closes and service stops on. */
    public boolean isStopHelper(Thread thread) {
        stopHelpers.removeIf(h -> !h.isAlive());     // helpers that ended after the last attempt (review of PR 2, finding 8)
        return stopHelpers.contains(thread);
    }

    private boolean join(Thread helper, long deadline) {
        try {
            long left = deadline - System.nanoTime();
            if (helper.isAlive() && left > 0) java.util.concurrent.TimeUnit.NANOSECONDS.timedJoin(helper, left);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (helper.isAlive() || helperFailures.containsKey(helper)) return false;
        stopHelpers.remove(helper);
        return true;
    }

    private static long remainingMillis(long deadline) {
        return Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static void closeGroup(String kind, String name, GroupRunner group) {
        log.info("stopping composing " + kind + " " + name);
        if (group.getGroup() instanceof com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent processors) {
            // a thread that died unnoticed is found dead first: closing would otherwise hide the death from the
            // cleanup below (review of PR 2, finding 4)
            processors.control().detectDeath();
            processors.control().beginClosing();   // senders are answered NOT_RUNNING from here on
        }
        try {
            // Agrona's retry messages go to the log, not stderr (review of PR 2, finding 8)
            group.getGroupRunner().close(AgentRunner.RETRY_CLOSE_TIMEOUT_MS,
                    t -> log.warning("composing " + kind + " " + name + " still closing, retrying: thread " + t.getName()));
        } catch (Throwable t) {
            log.log(java.util.logging.Level.WARNING, "composing " + kind + " " + name + " failed to close", t);
            throw t;
        }
        // a group whose thread died never ran onClose (Agrona's close() returns at once for it): release what it held
        // here, now that its thread has ended, so its processors are stopped and its requests answered
        if (group.getGroup() instanceof com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent processors) {
            processors.control().detectDeath();
            try {
                processors.cleanupAfterDeath();
            } catch (Throwable t) {
                log.log(java.util.logging.Level.WARNING, "composing " + kind + " " + name + " failed its post-death cleanup", t);
                throw new IllegalStateException("group cleanup failed: " + name, t);
            }
            if (processors.captureResourcesHeld() != 0) {
                throw new IllegalStateException("group " + name + " still holds " + processors.captureResourcesHeld() + " capture resources");
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
