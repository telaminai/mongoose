/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.pool;

import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Multi-threaded stress tests for {@link ObjectPoolsRegistry}/{@link Pools} ensuring correctness
 * under contention and printing simple performance metrics.
 */
public class ObjectPoolStressTest {

    static class PooledMsg implements PoolAware {
        final PoolTracker<PooledMsg> tracker = new PoolTracker<>();
        int payload;

        @Override
        public PoolTracker<PooledMsg> getPoolTracker() {
            return tracker;
        }
    }

    @AfterEach
    void tearDown() {
        Pools.SHARED.remove(PooledMsg.class);
    }

    /**
     * Prime the pool by creating exactly capacity instances and returning them, so
     * availableCount() == capacity for post-conditions.
     */
    private void primePool(ObjectPool<PooledMsg> pool, int capacity) {
        List<PooledMsg> stash = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            PooledMsg m = pool.acquire();
            stash.add(m);
        }
        // Drop the acquire ref and return each to pool
        for (PooledMsg m : stash) {
            m.getPoolTracker().releaseReference();
            m.getPoolTracker().returnToPool();
        }
        assertEquals(capacity, pool.availableCount(), "pool should be fully populated after priming");
    }

    @Test
    public void stressMpmcAcquireRelease_withExtraRefs_printPerf() throws Exception {
        final int capacity = 1024; // recommended default for busy pipelines
        final int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        final long durationMs = 2000; // 2s run

        ObjectPool<PooledMsg> pool = Pools.SHARED.getOrCreate(PooledMsg.class, PooledMsg::new, m -> m.payload = 0, capacity);
        primePool(pool, capacity);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong ops = new AtomicLong();
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                Random rnd = new Random(Thread.currentThread().getId());
                try {
                    while (running.get()) {
                        PooledMsg m = pool.acquire();
                        // Simulate some extra references (e.g., queue + cache)
                        int extra = rnd.nextInt(3); // 0..2
                        for (int i = 0; i < extra; i++) m.getPoolTracker().acquireReference();

                        // Mutate payload
                        m.payload += 1;

                        // Release the extras, maybe call returnToPool prematurely (should be a no-op until zero)
                        for (int i = 0; i < extra; i++) {
                            if ((i & 1) == 0) {
                                // Attempt early return (should only return if count is zero)
                                m.getPoolTracker().returnToPool();
                            }
                            m.getPoolTracker().releaseReference();
                        }

                        // Release the original acquire ref
                        m.getPoolTracker().releaseReference();
                        // Now explicitly attempt return to pool
                        m.getPoolTracker().returnToPool();

                        ops.incrementAndGet();
                    }
                } catch (Throwable t1) {
                    error.compareAndSet(null, t1);
                }
            });
        }

        long start = System.nanoTime();
        Thread.sleep(durationMs);
        running.set(false);
        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);
        long elapsedNs = System.nanoTime() - start;

        if (error.get() != null) {
            error.get().printStackTrace();
        }
        assertNull(error.get(), "No exceptions should occur during stress");

        // After all threads stopped, pool should contain capacity instances
        assertEquals(capacity, pool.availableCount(), "pool should be full after stress");

        double secs = elapsedNs / 1_000_000_000.0;
        long totalOps = ops.get();
        double rate = totalOps / secs;
        System.out.println("[ObjectPoolStressTest] threads=" + threads + ", capacity=" + capacity +
                ", durationMs=" + durationMs + ", ops=" + totalOps + String.format(
                ", rate=%.1f ops/s", rate));
    }

    @Test
    public void highContentionSmallPool_correctness_printPerf() throws Exception {
        final int capacity = 64; // small to increase contention
        final int threads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        final long durationMs = 1500; // 1.5s

        ObjectPool<PooledMsg> pool = Pools.SHARED.getOrCreate(PooledMsg.class, PooledMsg::new, m -> m.payload = 0, capacity);
        primePool(pool, capacity);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong ops = new AtomicLong();
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    while (running.get()) {
                        PooledMsg m = pool.acquire();
                        // Randomly take/release an extra ref to emulate queue handoff
                        m.getPoolTracker().acquireReference();
                        m.getPoolTracker().releaseReference();
                        // Release primary
                        m.getPoolTracker().releaseReference();
                        m.getPoolTracker().returnToPool();
                        ops.incrementAndGet();
                    }
                } catch (Throwable thr) {
                    System.out.println("error idx:" + ops.get() + " message:" + thr.getMessage());
                    error.compareAndSet(null, thr);
                }
            });
        }

        long start = System.nanoTime();
        Thread.sleep(durationMs);
        running.set(false);
        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);
        long elapsedNs = System.nanoTime() - start;

        if (error.get() != null) {
            error.get().printStackTrace();
        }
        assertNull(error.get(), "No exceptions should occur during high-contention stress");
        assertEquals(capacity, pool.availableCount(), "pool should be full after high-contention stress");

        double secs = elapsedNs / 1_000_000_000.0;
        long totalOps = ops.get();
        double rate = totalOps / secs;
        System.out.println("[ObjectPoolStressTest-HighContention] threads=" + threads + ", capacity=" + capacity +
                ", durationMs=" + durationMs + ", ops=" + totalOps + String.format(
                ", rate=%.1f ops/s", rate));
    }
}
