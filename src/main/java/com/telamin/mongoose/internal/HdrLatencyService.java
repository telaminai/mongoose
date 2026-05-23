/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.counters.MongooseLatencyService;
import com.telamin.mongoose.service.counters.NodeLatencySnapshot;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.concurrent.ConcurrentHashMap;

/**
 * HdrHistogram-backed implementation. One {@link Recorder} per
 * (processor, node) key — Recorder is the lock-free single-writer
 * variant of {@link Histogram}, safe under the auditor's single-writer
 * usage from the processor's agent thread.
 *
 * <p>Sampling ({@link #forEachNode}) calls
 * {@link Recorder#getIntervalHistogram()} which atomically rotates the
 * active recording buffer, so the snapshot represents observations
 * since the last sample call and the writer's hot path is not disrupted.
 *
 * <p>Configured for millisecond-resolution wall-clock measurements
 * (max = 1 hour, 3 significant digits). When a future high-resolution
 * clock source is plugged in, the Recorder ctor args become a
 * configurable tradeoff.
 */
public final class HdrLatencyService implements MongooseLatencyService {

    // 1 hour in millis as the upper tracking bound — anything past this
    // is unambiguous "the pipeline is dead." 3 significant digits gives
    // ~0.1% resolution which is plenty for a percentile view.
    private static final long HIGHEST_TRACKABLE = 60L * 60L * 1000L;
    private static final int SIGNIFICANT_DIGITS = 3;

    private final ConcurrentHashMap<Key, Recorder> recorders = new ConcurrentHashMap<>();
    // Reusable interval histogram per recorder — Recorder.getIntervalHistogram(prev)
    // avoids reallocating on every sample tick.
    private final ConcurrentHashMap<Key, Histogram> intervalBuffers = new ConcurrentHashMap<>();
    // Runtime recording toggle — set via the latency.* admin commands.
    // Default true so when the Hdr backend is installed via YAML, recording
    // starts immediately; flip via admin/UI to halt accumulation without
    // uninstalling the service.
    private volatile boolean enabled = true;

    @Override
    public void recordNodeLatency(String processor, String node, long elapsed) {
        if (!enabled || elapsed < 0) {
            return;
        }
        long clamped = Math.min(elapsed, HIGHEST_TRACKABLE);
        Key k = new Key(processor, node);
        Recorder r = recorders.computeIfAbsent(k, key -> new Recorder(HIGHEST_TRACKABLE, SIGNIFICANT_DIGITS));
        r.recordValue(clamped);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void forEachNode(LatencyVisitor visitor) {
        recorders.forEach((k, recorder) -> {
            // Atomic interval rotation — gets observations since the last
            // call to getIntervalHistogram and resets the active buffer.
            Histogram prev = intervalBuffers.get(k);
            Histogram h = recorder.getIntervalHistogram(prev);
            intervalBuffers.put(k, h);
            if (h.getTotalCount() == 0) {
                return;
            }
            NodeLatencySnapshot snap = new NodeLatencySnapshot(
                    h.getTotalCount(),
                    h.getValueAtPercentile(50.0),
                    h.getValueAtPercentile(90.0),
                    h.getValueAtPercentile(99.0),
                    h.getValueAtPercentile(99.9),
                    h.getMaxValue()
            );
            visitor.visit(k.processor, k.node, snap);
        });
    }

    @Override
    public void reset() {
        recorders.values().forEach(Recorder::reset);
        intervalBuffers.clear();
    }

    @Override
    public boolean isOperational() {
        return true;
    }

    private record Key(String processor, String node) {
    }
}
