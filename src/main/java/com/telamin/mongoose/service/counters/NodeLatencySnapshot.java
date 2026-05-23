/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

/**
 * Percentile snapshot for one (processor, node) pair. Values are in the
 * auditor's clock units (millis with the default Fluxtion clock). The
 * sampler tags the unit on the WS wire so the front-end renders it
 * correctly without guessing.
 *
 * @param count   total samples recorded since the last reset
 * @param p50     median latency
 * @param p90     90th percentile
 * @param p99     99th percentile
 * @param p999    99.9th percentile
 * @param max     largest sample recorded
 */
public record NodeLatencySnapshot(
        long count,
        long p50,
        long p90,
        long p99,
        long p999,
        long max
) {
    public static final NodeLatencySnapshot EMPTY = new NodeLatencySnapshot(0, 0, 0, 0, 0, 0);
}
