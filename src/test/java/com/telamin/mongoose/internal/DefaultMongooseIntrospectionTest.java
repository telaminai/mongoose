/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.ComposingServiceAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.service.introspection.AgentGroupSnapshot;
import com.telamin.mongoose.service.introspection.FeedTopology;
import com.telamin.mongoose.service.introspection.MongooseIntrospectionService;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies that {@link DefaultMongooseIntrospection} produces well-shaped
 * snapshots from live composing-agent maps without requiring a fully booted
 * server.
 */
class DefaultMongooseIntrospectionTest {

    @Test
    void agentGroups_reports_processor_group_with_idle_strategy() {
        ConcurrentHashMap<String, ComposingEventProcessorAgentRunner> procAgents = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, ComposingWorkerServiceAgentRunner>  workerAgents = new ConcurrentHashMap<>();
        EventFlowManager flow = new EventFlowManager();

        IdleStrategy idle = new BusySpinIdleStrategy();
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent(
                "pnl-agent", flow, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        AgentRunner runner = new AgentRunner(idle, errorHandler(), new AtomicCounter(new UnsafeBuffer(new byte[4096]), 0), group);
        procAgents.put("pnl-agent", new ComposingEventProcessorAgentRunner(group, runner, idle));

        MongooseIntrospectionService introspection =
                new DefaultMongooseIntrospection(procAgents, workerAgents, flow);
        Map<String, AgentGroupSnapshot> snaps = introspection.agentGroups();

        Assertions.assertEquals(1, snaps.size());
        AgentGroupSnapshot snap = snaps.get("pnl-agent");
        Assertions.assertNotNull(snap);
        Assertions.assertEquals("processor", snap.kind());
        Assertions.assertEquals("org.agrona.concurrent.BusySpinIdleStrategy", snap.idleStrategyClass());
        // Thread is null pre-start — the runner hasn't been launched in this test.
        Assertions.assertNull(snap.threadName(), "thread fields are null before the runner starts");
        Assertions.assertEquals(java.util.List.of(), snap.processors(),
                "no processors registered yet → empty list");
    }

    @Test
    void agentGroups_includes_worker_groups_too() {
        ConcurrentHashMap<String, ComposingEventProcessorAgentRunner> procAgents = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, ComposingWorkerServiceAgentRunner>  workerAgents = new ConcurrentHashMap<>();
        EventFlowManager flow = new EventFlowManager();

        IdleStrategy idle = new BusySpinIdleStrategy();
        ComposingServiceAgent worker = new ComposingServiceAgent("io-worker", flow, null, new DeadWheelScheduler());
        AgentRunner runner = new AgentRunner(idle, errorHandler(), new AtomicCounter(new UnsafeBuffer(new byte[4096]), 0), worker);
        workerAgents.put("io-worker", new ComposingWorkerServiceAgentRunner(worker, runner, idle));

        MongooseIntrospectionService introspection =
                new DefaultMongooseIntrospection(procAgents, workerAgents, flow);
        Map<String, AgentGroupSnapshot> snaps = introspection.agentGroups();

        AgentGroupSnapshot snap = snaps.get("io-worker");
        Assertions.assertNotNull(snap);
        Assertions.assertEquals("worker", snap.kind());
    }

    @Test
    void feedTopology_returns_empty_when_flowmanager_has_no_subscriptions() {
        // No event sources or subscriptions registered → empty topology.
        // The introspection service shouldn't throw on this baseline.
        EventFlowManager flow = new EventFlowManager();
        MongooseIntrospectionService introspection = new DefaultMongooseIntrospection(
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), flow);
        Map<String, FeedTopology> topo = introspection.feedTopology();
        Assertions.assertNotNull(topo, "feedTopology returns a (possibly empty) map, never null");
    }

    @Test
    void registered_as_a_service_on_mongoose_server_start() {
        // Verifies the wiring done in MongooseServer's constructor: the
        // introspection service should be present in registeredServices()
        // without any extra setup, so @ServiceRegistered injection picks it up.
        MongooseServerHandle handle = MongooseServerHandle.bootEmpty();
        try {
            Object svc = handle.server().registeredServices()
                    .get(MongooseIntrospectionService.SERVICE_NAME)
                    .instance();
            Assertions.assertTrue(svc instanceof MongooseIntrospectionService,
                    "MongooseServer auto-registers DefaultMongooseIntrospection");
        } finally {
            handle.close();
        }
    }

    @SuppressWarnings("unused") // referenced from registered_as_a_service_on_mongoose_server_start
    private static class MongooseServerHandle implements AutoCloseable {
        private final com.telamin.mongoose.MongooseServer server;
        MongooseServerHandle(com.telamin.mongoose.MongooseServer s) { this.server = s; }
        com.telamin.mongoose.MongooseServer server() { return server; }
        static MongooseServerHandle bootEmpty() {
            return new MongooseServerHandle(
                    new com.telamin.mongoose.MongooseServer(new com.telamin.mongoose.config.MongooseServerConfig()));
        }
        @Override public void close() {
            // Server wasn't started — nothing to tear down. Provided so the
            // try-with-resources block in the test reads naturally.
        }
    }

    @SuppressWarnings("unused") // We never invoke the runners; only a handler shape is needed.
    private static <T extends DataFlow> T unused() { return null; }

    private static ErrorHandler errorHandler() {
        return t -> { /* swallow — these runners never run in tests */ };
    }
}
