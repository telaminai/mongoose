/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2: verify {@link ComposingEventProcessorAgent#doWork()} updates
 * the two agent-level counters via the EFM-owned counters service —
 * {@code group.{name}.processed} bumps when work was done, and
 * {@code group.{name}.idleCycles} bumps when the duty cycle had nothing
 * to do.
 *
 * <p>Drives {@code doWork} directly so the assertions are deterministic
 * — no agent thread, no idle-strategy sleeps to flake on.
 */
class AgentLoopCountersTest {

    @Test
    void idle_doWork_increments_idleCycles_only() throws Exception {
        AgronaCountersService counters = new AgronaCountersService(64);
        EventFlowManager efm = new EventFlowManager();
        efm.setCountersService(counters);

        ComposingEventProcessorAgent agent = new ComposingEventProcessorAgent(
                "idleAgent", efm, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());

        // No processors registered → super.doWork() returns 0 each tick.
        for (int i = 0; i < 10; i++) {
            agent.doWork();
        }

        Map<String, Long> snapshot = snapshot(counters);
        assertEquals(10L, snapshot.get("group.idleAgent.idleCycles"),
                "every idle tick should bump idleCycles");
        // processed counter was allocated at construction (so it'll appear in
        // forEach) but value should be 0 — nothing has been dispatched.
        assertEquals(0L, snapshot.getOrDefault("group.idleAgent.processed", -1L),
                "processed counter should not have moved for idle ticks");
    }

    @Test
    void no_op_mode_keeps_agent_counters_invisible() {
        EventFlowManager efm = new EventFlowManager(); // default no-op counters service
        ComposingEventProcessorAgent agent = new ComposingEventProcessorAgent(
                "noOpAgent", efm, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());

        try {
            for (int i = 0; i < 5; i++) {
                agent.doWork();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Map<String, Long> snapshot = snapshot(efm.getCountersService());
        assertTrue(snapshot.isEmpty(),
                "no-op counters service should report no counters even after work; got " + snapshot);
    }

    private static Map<String, Long> snapshot(MongooseCountersService counters) {
        Map<String, Long> out = new HashMap<>();
        counters.forEachCounter((id, label, value) -> out.put(label, value));
        return out;
    }
}
