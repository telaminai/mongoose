/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.pool.impl;

import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import com.telamin.mongoose.service.pool.PoolAware;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared registry of {@link ObjectPoolManager} instances, one per class.
 * Instance-based to support dependency injection and testing.
 */
final class GlobalObjectPool implements ObjectPoolsRegistry {

    private final Map<Class<?>, ObjectPool<?>> pools = new ConcurrentHashMap<>();

    public GlobalObjectPool() {
    }

    /**
     * Get (or create) a pool for the specified type with default capacity and no reset callback.
     *
     * @param <T>     The type of objects managed by the pool
     * @param type    The class of objects to be pooled. Must not be null.
     * @param factory A supplier that creates new instances when needed. Must not be null.
     * @return An ObjectPool instance for managing objects of type T
     */
    @Override
    public <T extends PoolAware> ObjectPool<T> getOrCreate(Class<T> type, Supplier<T> factory) {
        return getOrCreate(type, factory, null, ObjectPoolManager.DEFAULT_CAPACITY);
    }

    /**
     * Get (or create) a pool for the specified type with default capacity and provided reset callback.
     *
     * @param <T>     The type of objects managed by the pool
     * @param type    The class of objects to be pooled. Must not be null.
     * @param factory A supplier that creates new instances when needed. Must not be null.
     * @param reset   Optional callback to reset object state when returning to pool. May be null.
     * @return An ObjectPool instance for managing objects of type T
     */
    @Override
    public <T extends PoolAware> ObjectPool<T> getOrCreate(Class<T> type, Supplier<T> factory, Consumer<T> reset) {
        return getOrCreate(type, factory, reset, ObjectPoolManager.DEFAULT_CAPACITY);
    }

    /**
     * Get (or create) a pool for the specified type with provided reset callback and capacity.
     *
     * @param <T>      The type of objects managed by the pool
     * @param type     The class of objects to be pooled. Must not be null.
     * @param factory  A supplier that creates new instances when needed. Must not be null.
     * @param reset    Optional callback to reset object state when returning to pool. May be null.
     * @param capacity Maximum number of objects to keep in the pool. Must be > 0.
     * @return An ObjectPool instance for managing objects of type T
     * @throws IllegalArgumentException if capacity is <= 0
     */
    @Override
    public <T extends PoolAware> ObjectPool<T> getOrCreate(Class<T> type, Supplier<T> factory, Consumer<T> reset, int capacity) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        @SuppressWarnings("unchecked")
        ObjectPool<T> pool = (ObjectPool<T>) pools.computeIfAbsent(type, k -> new ObjectPoolManager<>(factory, reset, capacity));
        return pool;
    }

    /**
     * Get (or create) a pool for the specified type with provided reset callback, capacity and partitions.
     *
     * @param <T>        The type of objects managed by the pool
     * @param type       The class of objects to be pooled. Must not be null.
     * @param factory    A supplier that creates new instances when needed. Must not be null.
     * @param reset      Optional callback to reset object state when returning to pool. May be null.
     * @param capacity   Maximum number of objects to keep in the pool. Must be > 0.
     * @param partitions Number of partitions for concurrent access. Must be > 0.
     * @return An ObjectPool instance for managing objects of type T
     * @throws IllegalArgumentException if capacity or partitions is <= 0
     */
    public <T extends PoolAware> ObjectPool<T> getOrCreate(Class<T> type, Supplier<T> factory, Consumer<T> reset, int capacity, int partitions) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (partitions <= 0) throw new IllegalArgumentException("partitions must be > 0");
        @SuppressWarnings("unchecked")
        ObjectPool<T> pool = (ObjectPool<T>) pools.computeIfAbsent(type, k -> new ObjectPoolManager<>(factory, reset, capacity, partitions));
        return pool;
    }

    /**
     * For tests/maintenance: remove a pool.
     */
    @Override
    public void remove(Class<?> type) {
        pools.remove(type);
    }
}
