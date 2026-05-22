/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.internal.AgronaCountersService;
import com.telamin.mongoose.internal.NoOpCountersService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPI-level tests for {@link PerformanceMonitorAudit}. Drives the
 * Fluxtion {@code Auditor} callbacks directly so the assertions are
 * deterministic without a full Fluxtion-compiled processor in the loop.
 *
 * <p>A separate end-to-end test compiles a real Fluxtion processor with
 * the auditor bound to verify the integration shape.
 */
class PerformanceMonitorAuditTest {

    @Test
    void event_and_node_callbacks_increment_their_counters() {
        AgronaCountersService counters = new AgronaCountersService(64);
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("priceCalc");
        audit.registerService(new Service<>(counters, MongooseCountersService.class, "counters"));
        audit.init();
        audit.nodeRegistered(new Object(), "fxLineHandler");
        audit.nodeRegistered(new Object(), "pnlAggregator");

        for (int i = 0; i < 5; i++) {
            audit.eventReceived(new Object());
            audit.nodeInvoked(null, "fxLineHandler", "onEvent", null);
            audit.nodeInvoked(null, "pnlAggregator", "onEvent", null);
        }
        // Just the handler fires (not the aggregator) on one more event
        audit.eventReceived(new Object());
        audit.nodeInvoked(null, "fxLineHandler", "onEvent", null);

        Map<String, Long> snap = snapshot(counters);
        assertEquals(6L, snap.get("processor.priceCalc.events"));
        assertEquals(6L, snap.get("node.priceCalc.fxLineHandler.invocations"));
        assertEquals(5L, snap.get("node.priceCalc.pnlAggregator.invocations"));
    }

    @Test
    void setWriteEnabled_false_freezes_counters_even_under_continued_callbacks() {
        AgronaCountersService counters = new AgronaCountersService(64);
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("priceCalc");
        audit.registerService(new Service<>(counters, MongooseCountersService.class, "counters"));
        audit.init();
        audit.nodeRegistered(new Object(), "fxLineHandler");

        for (int i = 0; i < 100; i++) {
            audit.eventReceived(new Object());
            audit.nodeInvoked(null, "fxLineHandler", "onEvent", null);
        }
        long eventsBefore = counters.processorEventsCounter("priceCalc").get();
        long nodesBefore  = counters.nodeInvocationCounter("priceCalc", "fxLineHandler").get();
        assertEquals(100L, eventsBefore);
        assertEquals(100L, nodesBefore);

        audit.setWriteEnabled(false);
        for (int i = 0; i < 100; i++) {
            audit.eventReceived(new Object());
            audit.nodeInvoked(null, "fxLineHandler", "onEvent", null);
        }

        assertEquals(eventsBefore, counters.processorEventsCounter("priceCalc").get(),
                "writeEnabled=false should freeze the processor counter");
        assertEquals(nodesBefore, counters.nodeInvocationCounter("priceCalc", "fxLineHandler").get(),
                "writeEnabled=false should freeze the node counter");

        audit.setWriteEnabled(true);
        audit.eventReceived(new Object());
        assertEquals(101L, counters.processorEventsCounter("priceCalc").get(),
                "re-enabling should resume increments");
    }

    @Test
    void no_op_counters_service_means_no_counters_visible() {
        // Auditor wired against the no-op — its callbacks should be inert
        // and forEachCounter should still report nothing (the no-op tracks
        // no state, by contract).
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("priceCalc");
        audit.registerService(new Service<>(NoOpCountersService.INSTANCE, MongooseCountersService.class, "counters"));
        audit.init();
        audit.nodeRegistered(new Object(), "n1");

        for (int i = 0; i < 1_000; i++) {
            audit.eventReceived(new Object());
            audit.nodeInvoked(null, "n1", "onEvent", null);
        }
        Map<String, Long> snap = snapshot(NoOpCountersService.INSTANCE);
        assertTrue(snap.isEmpty(), "no-op should never expose any counters; got " + snap);
    }

    @Test
    void auditor_survives_callbacks_before_service_injection() {
        // Defensive: even if Fluxtion delivers a callback before our
        // ServiceListener.registerService fires (shouldn't happen but the
        // contract is worth pinning), the auditor uses the default no-op
        // counter and doesn't NPE.
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("priceCalc");
        audit.eventReceived(new Object());
        audit.nodeInvoked(null, "missingNode", "onEvent", null);
        // No assertion — the assertion is "we didn't throw".
    }

    @Test
    void auditInvocations_is_true() {
        assertTrue(new PerformanceMonitorAudit("x").auditInvocations(),
                "must opt in to nodeInvoked callbacks — otherwise per-node counters never bump");
    }

    private static Map<String, Long> snapshot(MongooseCountersService counters) {
        Map<String, Long> out = new HashMap<>();
        counters.forEachCounter((id, label, value) -> out.put(label, value));
        return out;
    }
}
