/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

/**
 * Per-node latency histograms — the percentile counterpart to
 * {@link MongooseCountersService}. Sibling service in the same
 * {@code performanceMonitoring} block; opt-in via the
 * {@code latencyHistograms} YAML flag.
 *
 * <p>{@link PerformanceMonitorAudit} records a latency sample for each
 * node-invocation (interval since the previous {@code nodeInvoked} call
 * within the same event). When the no-op implementation is installed,
 * every record call is a JIT-elided no-op and the auditor's hot path
 * pays nothing.
 *
 * <p>The wall-clock source is {@code Clock.DEFAULT_CLOCK.getWallClockTime()}
 * by default — a deterministic, replay-safe clock that returns
 * millisecond-resolution wall-clock time. Sub-millisecond node latencies
 * therefore round to 0 with the default clock; meaningful sub-ms numbers
 * require swapping the clock strategy or injecting a custom
 * {@link java.util.function.LongSupplier} via
 * {@link PerformanceMonitorAudit#setTimeSource(java.util.function.LongSupplier)}.
 *
 * <p>Snapshots are sampled via {@link #forEachNode(LatencyVisitor)} once
 * per monitoring tick by {@code MonitoringSampler} and emitted as a
 * separate {@code latency} block in the admin-web WebSocket payload
 * (parallel to the existing {@code throughput} block).
 */
public interface MongooseLatencyService {

    String SERVICE_NAME = "com.telamin.mongoose.service.counters.MongooseLatencyService";

    /**
     * Record a single latency sample (in whatever time unit the auditor's
     * clock returns — millis with the default Fluxtion clock). Called
     * from {@link PerformanceMonitorAudit#nodeInvoked} on the processor's
     * agent thread; implementations must be safe for single-writer access
     * per (processor, node) key.
     */
    void recordNodeLatency(String processor, String node, long elapsed);

    /**
     * Walk every (processor, node) pair currently tracked and hand the
     * caller a snapshot of its percentile distribution. Called from the
     * monitoring sampler thread.
     */
    void forEachNode(LatencyVisitor visitor);

    /**
     * Reset every histogram to empty. Driven by the {@code counters.reset}
     * admin command. Implementations may finish in-flight observations
     * before clearing; the contract is "post-reset samples are clean."
     */
    default void reset() {
    }

    /**
     * True iff this is a live histogram backend (Hdr-backed). The no-op
     * service returns false; the sampler uses this to suppress the
     * {@code latency} block in the WS payload when the feature is off
     * rather than emitting a permanently-empty block.
     */
    default boolean isOperational() {
        return false;
    }

    /**
     * Runtime recording toggle, separate from {@link #isOperational()}.
     * When the Hdr-backed service is installed, this flag controls
     * whether {@link #recordNodeLatency} actually writes — flipping it
     * stops percentile accumulation without uninstalling the service
     * (so the UI keeps the toggle visible). Driven by the
     * {@code latency.enable / latency.disable / latency.toggle} admin
     * commands surfaced on the per-node stats table.
     *
     * <p>Default: {@code true} when the Hdr backend is installed,
     * {@code false} on the no-op (where the toggle has no meaning).
     */
    default boolean isEnabled() {
        return false;
    }

    default void setEnabled(boolean enabled) {
        // no-op default — the no-op service ignores
    }

    /**
     * Per-(processor, node) snapshot delivered by {@link #forEachNode}.
     * Values are in the auditor's clock units; the sampler tags the unit
     * on the wire so the front-end can render it correctly.
     */
    interface LatencyVisitor {
        void visit(String processor, String node, NodeLatencySnapshot snapshot);
    }
}
