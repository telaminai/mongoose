/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.counters;

/**
 * Hot-path counter handle returned by {@link MongooseCountersService}.
 *
 * <p>Callers cache the reference for the lifetime of the entity it represents
 * (a feed, an agent group, a processor, a node) and increment on each event.
 * Lookup happens once at registration; the increment path is allocation-free
 * and contention-free in the common single-writer case.
 *
 * <p>The interface is deliberately small and mongoose-owned rather than
 * exposing {@code org.agrona.concurrent.status.AtomicCounter} directly: the
 * JIT-monomorphism guarantee that lets the no-op implementation compile away
 * to nothing applies at this interface's callsite, and the abstraction keeps
 * the public contract decoupled from the backing store (Agrona today, could
 * be off-heap / JFR / OTLP later).
 */
public interface MongooseCounter {

    /**
     * Increment by one with volatile semantics. Pair with {@link #get()} for
     * reads that need the same ordering.
     *
     * @return the value <em>before</em> the increment
     */
    long increment();

    /**
     * Increment by one with release semantics. Cheaper than {@link #increment()};
     * pair with the readers's acquire load.
     *
     * @return the value <em>before</em> the increment
     */
    long incrementRelease();

    /**
     * Write a gauge value with release semantics. Used for sampled gauges
     * (queue depth, connection state, etc.) where the writer overwrites the
     * previous value rather than accumulating.
     *
     * @param value new value
     */
    void setOrdered(long value);

    /**
     * Read the current value with volatile semantics.
     *
     * @return the current value
     */
    long get();
}
