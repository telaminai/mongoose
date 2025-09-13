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
     * Acquire an instance from the pool (creating up to capacity).
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
