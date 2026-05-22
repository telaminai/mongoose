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
}
