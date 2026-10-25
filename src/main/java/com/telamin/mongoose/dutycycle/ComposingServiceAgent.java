/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.service.ServiceRegistryNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.internal.ServiceInjector;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import lombok.extern.java.Log;
import org.agrona.concurrent.DynamicCompositeAgent;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Composite agent that manages a group of worker service agents for Fluxtion Server.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Accepts registrations of ServiceAgent instances destined to run on this agent group</li>
 *   <li>Initializes and starts their exported services, injecting server-registered services and the scheduler</li>
 *   <li>Adds their Agent delegates to the underlying DynamicCompositeAgent at the appropriate time</li>
 *   <li>Signals startComplete to services once the agent group is fully active</li>
 * </ul>
 */
@Experimental
@Log
public class ComposingServiceAgent extends DynamicCompositeAgent {

    private final EventFlowManager eventFlowManager;
    private final MongooseServer mongooseServer;
    private final DeadWheelScheduler scheduler;
    private final Service<SchedulerService> schedulerService;
    /**
     * Design PR 4: written by many threads (the config loader, admin, a service's own start), so many-to-one; a full
     * queue is waited on, bounded, never thrown at the first attempt.
     */
    private final org.agrona.concurrent.ManyToOneConcurrentArrayQueue<ServiceAgent<?>> toStartList =
            new org.agrona.concurrent.ManyToOneConcurrentArrayQueue<>(128);
    private final OneToOneConcurrentArrayQueue<ServiceAgent<?>> toAddList = new OneToOneConcurrentArrayQueue<>(128);
    private final org.agrona.concurrent.ManyToOneConcurrentArrayQueue<ServiceAgent<?>> toCallStartupCompleteList =
            new org.agrona.concurrent.ManyToOneConcurrentArrayQueue<>(128);
    private final ServiceRegistryNode serviceRegistry = new ServiceRegistryNode();
    private final AtomicBoolean startUpComplete = new AtomicBoolean(false);
    /** Every service agent registered on this group, in registration order. */
    private final java.util.List<ServiceAgent<?>> hostedAgents = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ComposingServiceAgent(String roleName,
                                 EventFlowManager eventFlowManager,
                                 MongooseServer mongooseServer,
                                 DeadWheelScheduler scheduler) {
        super(roleName, scheduler);
        this.eventFlowManager = eventFlowManager;
        this.mongooseServer = mongooseServer;
        this.scheduler = scheduler;
        this.schedulerService = new Service<>(scheduler, SchedulerService.class);
    }

    public <T> void registerServer(ServiceAgent<T> server) {
        hostedAgents.add(server);
        offerBounded(toStartList, server, "service start");
        offerBounded(toCallStartupCompleteList, server, "service start-complete");
        log.info("registerServer toCallStartupCompleteList size:" + toCallStartupCompleteList.size());
    }

    /** Offer with a bounded back-off (10 s); never on an agent thread, which is refused at once. */
    private void offerBounded(org.agrona.concurrent.ManyToOneConcurrentArrayQueue<ServiceAgent<?>> queue, ServiceAgent<?> item,
                              String what) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!queue.offer(item)) {
            if (com.telamin.mongoose.control.AgentThreads.isAgentThread() || System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("service group " + roleName() + ": " + what + " queue is full ("
                        + queue.capacity() + ")");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(50_000);
        }
    }

    @Override
    public void onStart() {
        com.telamin.mongoose.control.AgentThreads.enter(mongooseServer != null ? mongooseServer : this);
        // Best-effort core pinning if configured for this agent group (guard for null during unit tests)
        if (mongooseServer != null) {
            Integer coreId = mongooseServer.resolveCoreIdForAgentName(roleName());
            if (coreId != null) {
                com.telamin.mongoose.internal.CoreAffinity.pinCurrentThreadToCore(coreId);
            }
        }
        log.info("onStart toStartList size:" + toStartList.size());
        checkForAdded();
        super.onStart();
    }

    @Override
    public int doWork() throws Exception {
        checkForAdded();
        return super.doWork();
    }

    public void startComplete() {
        log.info("startComplete toCallStartupCompleteList size:" + toCallStartupCompleteList.size());
        startUpComplete.set(true);
    }

    /**
     * mongoose#56: take the hosted agents matching {@code which} out of this group while it keeps running the others.
     * Each removal is handed to the group's own thread, which closes the agent in its next duty cycle; this waits for
     * each, at most {@code timeoutMillis} in all. A group that is not active runs nothing, so there is nothing to remove.
     *
     * @return false when the wait ran out before every matching agent was removed
     */
    public boolean removeHostedAgents(java.util.function.Predicate<ServiceAgent<?>> which, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        for (ServiceAgent<?> serviceAgent : hostedAgents) {
            if (!which.test(serviceAgent)) {
                continue;
            }
            try {
                while (!tryRemove(serviceAgent.delegate())) {   // another removal is still pending
                    if (System.nanoTime() - deadline >= 0) return false;
                    Thread.sleep(1);
                }
                while (!hasRemoveAgentCompleted()) {
                    if (System.nanoTime() - deadline >= 0) return false;
                    Thread.sleep(1);
                }
            } catch (IllegalStateException notActive) {
                return true;   // the group is not (or no longer) running: none of its agents run
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        log.info("onClose");
        try {
            super.onClose();
        } finally {
            com.telamin.mongoose.control.AgentThreads.exit();
        }
    }

    private void checkForAdded() {
        if (!toStartList.isEmpty()) {
            toStartList.drain(serviceAgent -> {
                toAddList.add(serviceAgent);
                Service<?> exportedService = serviceAgent.exportedService();
                exportedService.init();
                serviceRegistry.init();
                serviceRegistry.nodeRegistered(exportedService.instance(), exportedService.serviceName());
                serviceRegistry.registerService(schedulerService);
                mongooseServer.servicesRegistered().forEach(serviceRegistry::registerService);
                // Inject dependencies into the agent-hosted service instance (scheduler + server services)
                java.util.List<com.telamin.fluxtion.runtime.service.Service<?>> inj = new java.util.ArrayList<>(mongooseServer.servicesRegistered());
                inj.add(schedulerService);
                ServiceInjector.inject(exportedService.instance(), inj);
                mongooseServer.registerAgentService(exportedService);
                exportedService.start();
            });
        }

        if (!toAddList.isEmpty() && status() == Status.ACTIVE && tryAdd(toAddList.peek().delegate())) {
            toAddList.poll();
        }

        if (startUpComplete.get() & !toCallStartupCompleteList.isEmpty()) {
            toCallStartupCompleteList.drain(s -> {
                s.exportedService().startComplete();
            });
        }
    }
}
