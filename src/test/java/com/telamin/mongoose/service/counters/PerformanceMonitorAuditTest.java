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
    void node_counters_rebind_when_service_arrives_after_nodeRegistered() {
        // Regression: Fluxtion's initialiseAuditor() runs in the generated
        // processor CONSTRUCTOR — i.e. before the mongoose runtime dispatches
        // registerService(MongooseCountersService). The auditor must rebind
        // per-node counters once the real service shows up; without that the
        // cached handles stay wired to the no-op and per-node counters are
        // silent forever even though nodeInvoked fires.
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("priceCalc");
        audit.init();
        audit.nodeRegistered(new Object(), "fxLineHandler");
        audit.nodeRegistered(new Object(), "pnlAggregator");

        // Service arrives AFTER nodeRegistered (production order)
        AgronaCountersService counters = new AgronaCountersService(64);
        audit.registerService(new Service<>(counters, MongooseCountersService.class, "counters"));

        for (int i = 0; i < 3; i++) {
            audit.eventReceived(new Object());
            audit.nodeInvoked(null, "fxLineHandler", "onEvent", null);
            audit.nodeInvoked(null, "pnlAggregator", "onEvent", null);
        }

        Map<String, Long> snap = snapshot(counters);
        assertEquals(3L, snap.get("processor.priceCalc.events"));
        assertEquals(3L, snap.get("node.priceCalc.fxLineHandler.invocations"),
                "per-node counter must rebind to the real service when it arrives late");
        assertEquals(3L, snap.get("node.priceCalc.pnlAggregator.invocations"),
                "per-node counter must rebind to the real service when it arrives late");
    }

    @Test
    void setProcessorName_renames_counter_namespace_and_rebinds_existing_handles() {
        // The mongoose runtime calls setProcessorName after construction
        // when the YAML processor name differs from the auditor's ctor arg.
        // After the rename, both processor.{newName}.events and per-node
        // counters must accumulate under the NEW prefix.
        AgronaCountersService counters = new AgronaCountersService(64);
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("ctor-default");
        audit.init();
        audit.nodeRegistered(new Object(), "fxLineHandler");
        audit.registerService(new Service<>(counters, MongooseCountersService.class, "counters"));

        // Some traffic under the original name
        audit.eventReceived(new Object());
        audit.nodeInvoked(null, "fxLineHandler", "onEvent", null);

        // Rename — mimics what MongooseServer.addEventProcessor does
        audit.setProcessorName("pnl-processor");

        // Subsequent traffic must land under the new prefix
        for (int i = 0; i < 4; i++) {
            audit.eventReceived(new Object());
            audit.nodeInvoked(null, "fxLineHandler", "onEvent", null);
        }

        Map<String, Long> snap = snapshot(counters);
        assertEquals(1L, snap.getOrDefault("processor.ctor-default.events", 0L),
                "pre-rename traffic stays under the original prefix");
        assertEquals(4L, snap.get("processor.pnl-processor.events"),
                "post-rename traffic accumulates under the new prefix");
        assertEquals(4L, snap.get("node.pnl-processor.fxLineHandler.invocations"),
                "per-node handles must rebind under the new prefix after rename");
        assertEquals("pnl-processor", audit.getProcessorName());
    }

    @Test
    void setProcessorName_with_same_value_is_noop() {
        AgronaCountersService counters = new AgronaCountersService(64);
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("pnl-processor");
        audit.init();
        audit.nodeRegistered(new Object(), "h");
        audit.registerService(new Service<>(counters, MongooseCountersService.class, "counters"));

        audit.setProcessorName("pnl-processor");  // unchanged

        audit.eventReceived(new Object());
        audit.nodeInvoked(null, "h", "onEvent", null);

        Map<String, Long> snap = snapshot(counters);
        assertEquals(1L, snap.get("processor.pnl-processor.events"));
        assertEquals(1L, snap.get("node.pnl-processor.h.invocations"));
    }

    @Test
    void latency_state_machine_records_each_node_interval_against_the_previous_node() {
        // Validates the per-node latency capture: each nodeInvoked closes
        // the previous node's interval; processingComplete closes the tail.
        com.telamin.mongoose.internal.HdrLatencyService latency =
                new com.telamin.mongoose.internal.HdrLatencyService();
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("priceCalc");
        audit.registerService(new Service<>(latency,
                com.telamin.mongoose.service.counters.MongooseLatencyService.class, "latency"));
        // Deterministic time source: 100, 150, 350, 400 (so intervals 50, 200, 50)
        long[] ticks = {100L, 150L, 350L, 400L};
        int[] idx = {0};
        audit.setTimeSource(() -> ticks[idx[0]++]);
        audit.init();
        audit.nodeRegistered(new Object(), "a");
        audit.nodeRegistered(new Object(), "b");
        audit.nodeRegistered(new Object(), "c");

        audit.eventReceived(new Object());
        audit.nodeInvoked(null, "a", "m", null);  // t=100, lastNode=a
        audit.nodeInvoked(null, "b", "m", null);  // t=150, records (a, 50)
        audit.nodeInvoked(null, "c", "m", null);  // t=350, records (b, 200)
        audit.processingComplete();               // t=400, records (c, 50)

        java.util.Map<String, com.telamin.mongoose.service.counters.NodeLatencySnapshot> snaps = new java.util.HashMap<>();
        latency.forEachNode((proc, node, snap) -> snaps.put(node, snap));

        assertEquals(3, snaps.size(), "one snapshot per node, all with count=1");
        assertEquals(50L, snaps.get("a").max(), "node a's interval was 50");
        assertEquals(200L, snaps.get("b").max(), "node b's interval was 200");
        assertEquals(50L, snaps.get("c").max(), "node c's tail interval was 50");
        snaps.values().forEach(s -> assertEquals(1L, s.count(), "one sample each"));
    }

    @Test
    void latency_capture_is_no_op_when_NoOpLatencyService_is_installed() {
        // Sanity: with the no-op latency service, the auditor's
        // recordNodeLatency calls dispatch to nothing. eventReceived /
        // nodeInvoked / processingComplete still run their counter paths.
        AgronaCountersService counters = new AgronaCountersService(64);
        PerformanceMonitorAudit audit = new PerformanceMonitorAudit("priceCalc");
        audit.registerService(new Service<>(counters, MongooseCountersService.class, "counters"));
        // Latency service stays NoOp (default)
        audit.init();
        audit.nodeRegistered(new Object(), "a");

        audit.eventReceived(new Object());
        audit.nodeInvoked(null, "a", "m", null);
        audit.processingComplete();

        // Counters still work
        Map<String, Long> snap = snapshot(counters);
        assertEquals(1L, snap.get("processor.priceCalc.events"));
        assertEquals(1L, snap.get("node.priceCalc.a.invocations"));
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
