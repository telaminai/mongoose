/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.counters.MongooseLatencyService;

/**
 * Default impl when {@code latencyHistograms} is off. Every call is a
 * trivial no-op so the hot path through {@link
 * com.telamin.mongoose.service.counters.PerformanceMonitorAudit} pays
 * nothing. The {@link #isOperational()} false return also gates the
 * sampler from emitting an empty {@code latency} block on the wire.
 */
public final class NoOpLatencyService implements MongooseLatencyService {

    public static final NoOpLatencyService INSTANCE = new NoOpLatencyService();

    private NoOpLatencyService() {
    }

    @Override
    public void recordNodeLatency(String processor, String node, long elapsed) {
        // intentionally empty
    }

    @Override
    public void forEachNode(LatencyVisitor visitor) {
        // intentionally empty
    }
}
