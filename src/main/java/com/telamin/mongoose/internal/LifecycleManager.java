/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.fluxtion.agrona.concurrent.AgentRunner;
import com.fluxtion.agrona.concurrent.DynamicCompositeAgent;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.LifeCycleEventSource;
import lombok.extern.java.Log;

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

    public LifecycleManager(MongooseServer server) {
        this.server = server;
    }

    public void init(Map<String, Service<?>> registeredServices,
                     Set<Service<?>> registeredAgentServices,
                     EventFlowManager flowManager,
                     com.fluxtion.runtime.service.ServiceRegistryNode serviceRegistry) {
        log.info("init");
        // Init non-LifeCycleEventSource services
        registeredServices.values().forEach(svc -> {
            if (!(svc.instance() instanceof LifeCycleEventSource)) {
                svc.init();
            }
        });
        // Init event sources via flow manager
        flowManager.init();
        // Register non-agent services with registry and inject dependencies
        registeredServices.values().forEach(svc -> {
            if (!(registeredAgentServices.contains(svc))) {
                serviceRegistry.nodeRegistered(svc.instance(), svc.serviceName());
                server.servicesRegistered().forEach(serviceRegistry::registerService);
            }
        });
    }

    public void start(Map<String, Service<?>> registeredServices,
                      ConcurrentHashMap<String, ? extends GroupRunner> composingServiceAgents,
                      ConcurrentHashMap<String, ? extends GroupRunner> composingEventProcessorAgents,
                      EventFlowManager flowManager,
                      Set<Service<?>> registeredAgentServices) throws InterruptedException {
        log.info("start");
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
        boolean waiting = true;
        log.info("waiting for agent hosted services to start");
        while (waiting) {
            waiting = composingServiceAgents.values().stream()
                    .anyMatch(f -> f.getGroup().status() != DynamicCompositeAgent.Status.ACTIVE);
            Thread.sleep(10);
            log.finer("checking all service agents are started");
        }
        // Start event processor agents
        log.info("start event processor agent workers");
        composingEventProcessorAgents.forEach((k, v) -> {
            log.info("starting composing event processor agent " + k);
            AgentRunner.startOnThread(v.getGroupRunner());
        });
        // Wait for processor agents ACTIVE
        waiting = true;
        log.info("waiting for event processor agents to start");
        while (waiting) {
            waiting = composingEventProcessorAgents.values().stream()
                    .anyMatch(f -> f.getGroup().status() != DynamicCompositeAgent.Status.ACTIVE);
            Thread.sleep(10);
            log.finer("checking all processor agents are started");
        }
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

    public void stop(boolean started,
                     ConcurrentHashMap<String, ? extends GroupRunner> composingEventProcessorAgents,
                     ConcurrentHashMap<String, ? extends GroupRunner> composingServiceAgents,
                     Map<String, Service<?>> registeredServices) {
        log.info("stopping server");
        if (!started) {
            log.info("server not started, nothing to stop");
            return;
        }
        log.info("stopping event processor agents");
        composingEventProcessorAgents.forEach((k, v) -> {
            log.info("stopping composing event processor agent " + k);
            AgentRunner groupRunner = v.getGroupRunner();
            if (groupRunner.thread() != null) {
                groupRunner.close();
            }
        });
        log.info("stopping agent hosted services");
        composingServiceAgents.forEach((k, v) -> {
            log.info("stopping composing service agent " + k);
            AgentRunner groupRunner = v.getGroupRunner();
            if (groupRunner.thread() != null) {
                groupRunner.close();
            }
        });
        log.info("stopping registered services");
        for (Service<?> service : registeredServices.values()) {
            if (!(service.instance() instanceof LifeCycleEventSource)) {
                service.stop();
            }
        }
    }

    /**
     * Simple view wrapper to avoid depending on concrete runner classes.
     */
    public interface GroupRunner {
        com.fluxtion.agrona.concurrent.AgentRunner getGroupRunner();

        com.fluxtion.agrona.concurrent.DynamicCompositeAgent getGroup();

        default void startCompleteIfSupported() { /* no-op by default */ }
    }
}
