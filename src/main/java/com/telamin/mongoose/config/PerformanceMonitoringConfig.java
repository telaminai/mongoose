/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.config;

import lombok.Data;

/**
 * YAML-driven configuration for the {@link com.telamin.mongoose.service.counters.MongooseCountersService}.
 *
 * <p>Top-level shape under {@code MongooseServerConfig.performanceMonitoring}:
 * <pre>
 * performanceMonitoring:
 *   enabled: true        # default: false — registers the no-op service
 *   counterBufferKb: 256 # default: 256  — values-buffer size; ~2K counters
 * </pre>
 *
 * <p>When {@code enabled} is {@code false} (the default) the server installs
 * the no-op counters service. Hot-path call sites stay monomorphic and the
 * JIT inlines the no-op increment to nothing — no measurable overhead.
 *
 * <p>{@code counterBufferKb} is the size of the on-heap values buffer in
 * kilobytes; each counter occupies 128 bytes, so 256 KB yields ~2048
 * counters. Clamped to a minimum of 16 KB by the impl.
 */
@Data
public class PerformanceMonitoringConfig {
    /** Whether to install the Agrona-backed counters service. Default: {@code false}. */
    private boolean enabled = false;

    /** Values-buffer size in KB; ~2048 counters at the default 256 KB. */
    private int counterBufferKb = 256;

    /**
     * Whether to install the HdrHistogram-backed {@link
     * com.telamin.mongoose.service.counters.MongooseLatencyService}. Default:
     * {@code false}. Has no effect unless {@link #enabled} is also true —
     * counters are the prerequisite (the auditor that drives latency
     * sampling lives behind the same flag).
     *
     * <p>When off, the {@code NoOpLatencyService} is installed and the
     * auditor's latency hot path is a JIT-elided no-op.
     */
    private boolean latencyHistograms = false;

    /**
     * Audit-log capture plugin configuration. Sibling subsystem to the
     * counters + latency services, sharing the same enabled-by-default-
     * false philosophy. Nested config so the YAML stays grouped under
     * {@code performanceMonitoring}.
     *
     * <p>See {@link AuditCaptureConfig} for the field-by-field details.
     * When {@link AuditCaptureConfig#isEnabled()} is false (default),
     * the NoOp capture + introspection services are installed and the
     * audit path is JIT-elided to nothing.
     */
    private AuditCaptureConfig auditCapture = new AuditCaptureConfig();
}
