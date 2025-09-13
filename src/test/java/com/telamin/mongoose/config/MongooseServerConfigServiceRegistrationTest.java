/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Disabled
public class MongooseServerConfigServiceRegistrationTest {

    private MongooseServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    interface GreetingService {
        String greet(String name);
    }

    static class GreetingServiceImpl implements GreetingService {
        @Override
        public String greet(String name) {
            return "Hello " + name;
        }
    }

    static class NormalPojoService {
        int calls;
        void touch() { calls++; }
    }

    static class AgentService implements Agent, GreetingService {
        private final String roleName;
        private volatile boolean started;
        private volatile boolean closed;

        AgentService(String roleName) {
            this.roleName = roleName;
        }

        @Override
        public int doWork() throws Exception {
            // minimal no-op work
            return 0;
        }

        @Override
        public void onStart() {
            started = true;
        }

        @Override
        public void onClose() {
            closed = true;
        }

        @Override
        public String roleName() {
            return roleName;
        }

        @Override
        public String greet(String name) {
            return "[agent] Hello " + name;
        }

        boolean isStarted() { return started; }
        boolean isClosed() { return closed; }
    }

    @Test
    void testAddService_InferredInterface() {
        // Arrange
        MongooseServerConfig cfg = new MongooseServerConfig();
        GreetingServiceImpl svc = new GreetingServiceImpl();
        String serviceName = "greetingSvc";
        cfg.addService(svc, serviceName);

        // Act
        server = MongooseServer.bootServer(cfg, null);
        Collection<Service<?>> services = server.servicesRegistered();

        // Assert
        Optional<Service<?>> found = services.stream()
                .filter(s -> serviceName.equals(s.serviceName()) && s.instance() == svc)
                .findFirst();
        assertTrue(found.isPresent(), "Service registered via addService(instance, name) should be present");
    }

    @Test
    void testAddService_ExplicitClass() {
        // Arrange
        MongooseServerConfig cfg = new MongooseServerConfig();
        GreetingServiceImpl svc = new GreetingServiceImpl();
        String serviceName = "greetingSvcExplicit";
        cfg.addService(svc, GreetingService.class, serviceName);

        // Act
        server = MongooseServer.bootServer(cfg, null);
        Collection<Service<?>> services = server.servicesRegistered();

        // Assert
        Optional<Service<?>> found = services.stream()
                .filter(s -> serviceName.equals(s.serviceName()) && s.instance() == svc)
                .findFirst();
        assertTrue(found.isPresent(), "Service registered via addService(instance, class, name) should be present");
    }

    @Test
    void testAddWorkerService_InferredInterface() {
        // Arrange
        MongooseServerConfig cfg = new MongooseServerConfig();
        AgentService agentSvc = new AgentService("agentGreetingSvc");
        String serviceName = "agentGreetingSvcName";
        cfg.addWorkerService(agentSvc, serviceName, "workerGroupA", new BusySpinIdleStrategy());

        // Act
        server = MongooseServer.bootServer(cfg, null);
        Collection<Service<?>> services = server.servicesRegistered();

        // Assert
        Optional<Service<?>> found = services.stream()
                .filter(s -> serviceName.equals(s.serviceName()) && s.instance() == agentSvc)
                .findFirst();
        assertTrue(found.isPresent(), "Agent-backed service registered via addWorkerService should be present");
    }
}
