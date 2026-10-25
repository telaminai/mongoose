/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.pool;

import java.util.function.Consumer;

/**
 * Public interface representing a per-type pool of PoolAware objects.
 * Implementations provide lock-free acquire and release semantics.
 */
public interface ObjectPool<T extends PoolAware> {

    /**
     * Acquire an instance from the pool (creating up to capacity). The instance carries one reference, the caller's;
     * publishing it hands that reference to the framework, which returns the instance when its last holder releases.
     * <p>
     * Never returns null: at capacity, with no free instance, it waits (spinning, then yielding) until one is
     * returned. That wait is the pool's back-pressure and is unbounded, so an instance that is never released (a
     * missing {@link com.telamin.mongoose.service.pool.impl.PoolTracker#releaseReference()}) shows as a caller that
     * waits here for good once the pool has run dry.
     */
    T acquire();

    /**
     * Number of currently available instances in the free list (for tests/metrics).
     */
    int availableCount();

    /**
     * Return an instance to the pool, optionally applying a reset hook.
     * Intended for internal use by PoolTracker.
     */
    void release(T t, Consumer<T> reset);

    /**
     * Remove the supplied instance from pool management permanently and immediately
     * replace it with a freshly created instance offered to the free list. The removed
     * instance will no longer be returned to the pool.
     */
    void removeFromPool(T t);
}
