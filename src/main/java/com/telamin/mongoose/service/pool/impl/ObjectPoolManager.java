/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.pool.impl;

import com.fluxtion.agrona.concurrent.ManyToManyConcurrentArrayQueue;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lock-free object pool for {@link PoolAware} instances. The pool is
 * expected to be used via {@link GlobalObjectPool} global registry.
 * <p>
 * This implementation uses a bounded MPMC free-list ({@link ManyToManyConcurrentArrayQueue})
 * with a fixed capacity. Instances are created on demand up to the configured capacity.
 * When the capacity is reached and no free instances are available, acquire() will spin-wait
 * briefly for a previously checked-in instance to become available.
 */
final class ObjectPoolManager<T extends PoolAware> implements ObjectPool<T> {

    public static final int DEFAULT_CAPACITY = 256;

    private final Supplier<T> factory;
    private final Consumer<T> resetHook;
    private final ManyToManyConcurrentArrayQueue<T>[] freePartitions;
    private final int capacity;
    private final int partitions;
    private final int mask; // if partitions is power of two
    private final AtomicInteger created = new AtomicInteger();

    public ObjectPoolManager(Supplier<T> factory, Consumer<T> resetHook) {
        this(factory, resetHook, DEFAULT_CAPACITY);
    }

    public ObjectPoolManager(Supplier<T> factory, Consumer<T> resetHook, int capacity) {
        this(factory, resetHook, capacity, defaultPartitions());
    }

    public ObjectPoolManager(Supplier<T> factory, Consumer<T> resetHook, int capacity, int partitions) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.resetHook = resetHook;
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (partitions <= 0) throw new IllegalArgumentException("partitions must be > 0");
        // normalize partitions to power of two for fast mod via mask
        int p2 = 1;
        while (p2 < partitions) p2 <<= 1;
        this.partitions = p2;
        this.mask = p2 - 1;
        this.capacity = capacity;
        // Distribute capacity across partitions (first few get +1 if remainder)
        @SuppressWarnings("unchecked")
        ManyToManyConcurrentArrayQueue<T>[] arr = new ManyToManyConcurrentArrayQueue[this.partitions];
        int baseCap = Math.max(1, capacity / this.partitions);
        int remainder = Math.max(0, capacity - baseCap * this.partitions);
        for (int i = 0; i < this.partitions; i++) {
            int cap = baseCap + (i < remainder ? 1 : 0);
            // Ensure at least 1 to keep queue operational
            arr[i] = new ManyToManyConcurrentArrayQueue<>(Math.max(2, cap));
        }
        this.freePartitions = arr;
    }

    /**
     * Acquire an instance from the pool, creating a new one up to capacity.
     */
    public T acquire() {
        int home = homePartitionForCurrentThread();
        T t = pollFromPartition(home);
        if (t == null) {
            // try to create within capacity
            while (true) {
                int current = created.get();
                if (current < capacity) {
                    if (created.compareAndSet(current, current + 1)) {
                        t = factory.get();
                        break;
                    }
                } else {
                    // capacity reached, try to steal from other partitions with brief spin/yield
                    long start = System.nanoTime();
                    do {
                        // attempt to steal round-robin
                        for (int i = 0; i < partitions; i++) {
                            int idx = (home + i) & mask;
                            t = freePartitions[idx].poll();
                            if (t != null) break;
                        }
                        if (t != null) break;
                        Thread.onSpinWait();
                    } while (System.nanoTime() - start < TimeUnit.MICROSECONDS.toNanos(200));
                    if (t == null) {
                        Thread.yield();
                        for (int i = 0; i < partitions; i++) {
                            int idx = (home + i) & mask;
                            t = freePartitions[idx].poll();
                            if (t != null) break;
                        }
                    }
                    if (t == null) {
                        // continue loop until an item appears
                        continue;
                    }
                    break;
                }
            }
        }
        @SuppressWarnings("unchecked")
        PoolTracker<T> tracker = (PoolTracker<T>) t.getPoolTracker();
        tracker.init(this, t, resetHook);
        return t;
    }

    /**
     * Returns an instance to the pool, calling optional reset hook first.
     */
    public void release(T t, Consumer<T> reset) {
        if (reset != null) {
            try {
                reset.accept(t);
            } catch (Throwable ignored) {
            }
        }
        int idx = partitionForObject(t);
        // offer should succeed as we never exceed capacity (sum across partitions)
        while (!freePartitions[idx].offer(t)) {
            // in unlikely full partition case, try next partition to avoid stall
            idx = (idx + 1) & mask;
            Thread.onSpinWait();
        }
    }

    /**
     * Returns the number of currently free instances (for testing/metrics).
     */
    public int availableCount() {
        int sum = 0;
        for (int i = 0; i < partitions; i++) {
            sum += freePartitions[i].size();
        }
        return sum;
    }

    /**
     * Permanently remove an instance from pool management and replace it with a
     * freshly created instance that is placed on the free list. The 'created'
     * counter is not adjusted here because we are replacing one managed instance
     * with another, keeping the effective pool size constant.
     */
    public void removeFromPool(T t) {
        // Create a replacement instance and offer to a partition free list
        T replacement = factory.get();
        int idx = partitionForObject(replacement);
        while (!freePartitions[idx].offer(replacement)) {
            idx = (idx + 1) & mask;
            Thread.onSpinWait();
        }
        // Do not attempt to reset or return the removed instance; it is now
        // outside pool management and may be retained elsewhere (e.g., cache).
    }

    private int homePartitionForCurrentThread() {
        long tid = Thread.currentThread().getId();
        int h = (int) (tid ^ (tid >>> 21) ^ (tid >>> 7));
        return h & mask;
    }

    private int partitionForObject(Object o) {
        int h = System.identityHashCode(o);
        // spread bits
        h ^= (h >>> 16);
        return h & mask;
    }

    private T pollFromPartition(int idx) {
        return freePartitions[idx].poll();
    }

    private static int defaultPartitions() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        // limit to 8 by default to balance memory and contention
        int target = Math.min(8, cores);
        // normalize to power of two
        int p2 = 1;
        while (p2 < target) p2 <<= 1;
        return Math.max(1, p2);
    }
}
