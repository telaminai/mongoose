/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.pool;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Service interface for accessing per-class {@link ObjectPool} instances.
 * Implementations provide a shared registry mapping types to their pools.
 */
public interface ObjectPoolsRegistry {

    String SERVICE_NAME = "com.telamin.mongoose.pool.ObjectPoolsRegistry";

    /**
     * Get (or create) the pool for the specified type.
     */
    <T extends PoolAware> ObjectPool<T> getOrCreate(Class<T> type, Supplier<T> factory);

    /**
     * Get (or create) the pool for the specified type with an optional reset hook.
     */
    <T extends PoolAware> ObjectPool<T> getOrCreate(Class<T> type, Supplier<T> factory, Consumer<T> reset);

    /**
     * Get (or create) the pool for the specified type with an optional reset hook and capacity.
     */
    <T extends PoolAware> ObjectPool<T> getOrCreate(Class<T> type, Supplier<T> factory, Consumer<T> reset, int capacity);

    /**
     * Get (or create) the pool for the specified type with an optional reset hook and capacity and partitions.
     */
    default <T extends PoolAware> ObjectPool<T> getOrCreate(Class<T> type, Supplier<T> factory, Consumer<T> reset, int capacity, int partitions) {
        // default interface method for backward compat; implementors can override
        return getOrCreate(type, factory, reset, capacity);
    }

    /**
     * Remove a pool for maintenance/testing.
     */
    void remove(Class<?> type);
}
