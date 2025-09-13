/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MongooseServerConfigFluentServiceApiTest {

    interface FooService { String foo(); }
    static class FooServiceImpl implements FooService { public String foo() { return "bar"; } }

    static class AgentLike {}

    @Test
    void addService_inferred_populatesServicesList() {
        MongooseServerConfig cfg = new MongooseServerConfig();
        FooServiceImpl svc = new FooServiceImpl();
        cfg.addService(svc, "fooSvc");

        List<ServiceConfig<?>> services = cfg.getServices();
        assertNotNull(services, "services list should be initialized");
        assertEquals(1, services.size(), "one service expected");

        ServiceConfig<?> sc = services.get(0);
        assertSame(svc, sc.getService(), "service instance should match");
        assertEquals("fooSvc", sc.getName(), "service name should match");
        assertEquals(FooServiceImpl.class.getCanonicalName(), sc.getServiceClass(), "service class should be inferred");
    }

    @Test
    void addService_explicitClass_populatesServicesList() {
        MongooseServerConfig cfg = new MongooseServerConfig();
        FooServiceImpl svc = new FooServiceImpl();
        cfg.addService(svc, FooService.class, "fooSvcExplicit");

        List<ServiceConfig<?>> services = cfg.getServices();
        assertNotNull(services);
        assertEquals(1, services.size());
        ServiceConfig<?> sc = services.get(0);
        assertSame(svc, sc.getService());
        assertEquals("fooSvcExplicit", sc.getName());
        assertEquals(FooService.class.getCanonicalName(), sc.getServiceClass(), "service class should be explicit interface");
    }

    @Test
    void addWorkerService_inferred_setsAgentDetails() {
        MongooseServerConfig cfg = new MongooseServerConfig();
        // For this unit test we don't require a real Agent instance; we only verify config values
        AgentLike agentService = new AgentLike();
        cfg.addWorkerService(agentService, "agentSvc", "groupA", new BusySpinIdleStrategy());

        List<ServiceConfig<?>> services = cfg.getServices();
        assertNotNull(services);
        assertEquals(1, services.size());
        ServiceConfig<?> sc = services.get(0);
        assertSame(agentService, sc.getService());
        assertEquals("agentSvc", sc.getName());
        assertEquals("groupA", sc.getAgentGroup());
        assertTrue(sc.getIdleStrategy() instanceof BusySpinIdleStrategy, "idle strategy should be set");
    }

    @Test
    void addWorkerService_explicit_setsAgentDetails() {
        MongooseServerConfig cfg = new MongooseServerConfig();
        FooServiceImpl svc = new FooServiceImpl();
        cfg.addWorkerService(svc, FooService.class, "svcName", "workers", new BusySpinIdleStrategy());

        List<ServiceConfig<?>> services = cfg.getServices();
        assertEquals(1, services.size());
        ServiceConfig<?> sc = services.get(0);
        assertSame(svc, sc.getService());
        assertEquals("svcName", sc.getName());
        assertEquals("workers", sc.getAgentGroup());
        assertTrue(sc.getIdleStrategy() instanceof BusySpinIdleStrategy);
        assertEquals(FooService.class.getCanonicalName(), sc.getServiceClass(), "explicit service class expected");
    }
}
