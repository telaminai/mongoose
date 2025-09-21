/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.concurrent.DynamicCompositeAgent;
import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.service.ServiceRegistryNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.internal.ServiceInjector;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import lombok.extern.java.Log;

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
    private final OneToOneConcurrentArrayQueue<ServiceAgent<?>> toStartList = new OneToOneConcurrentArrayQueue<>(128);
    private final OneToOneConcurrentArrayQueue<ServiceAgent<?>> toAddList = new OneToOneConcurrentArrayQueue<>(128);
    private final OneToOneConcurrentArrayQueue<ServiceAgent<?>> toCallStartupCompleteList = new OneToOneConcurrentArrayQueue<>(128);
    private final ServiceRegistryNode serviceRegistry = new ServiceRegistryNode();
    private final AtomicBoolean startUpComplete = new AtomicBoolean(false);

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
        toStartList.add(server);
        toCallStartupCompleteList.add(server);
        log.info("registerServer toCallStartupCompleteList size:" + toCallStartupCompleteList.size());
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

    @Override
    public void onClose() {
        log.info("onClose");
        super.onClose();
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
