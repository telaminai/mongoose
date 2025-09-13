/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.objectpool;

import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.ObjectPoolsRegistry;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for Fluxtion's ObjectPoolService/ObjectPools, mirroring the FOP benchmark.
 * <p>
 * Measures throughput of acquire/use/return cycles under multi-threaded load.
 * <p>
 * Run via main(): for example
 * -Dthreads=8 -Dforks=1 -Dwarmups=1 -Dmeas=3
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
public class BenchmarkObjectPoolServiceJmh {

    /**
     * Simple pooled payload implementing PoolAware with an embedded PoolTracker.
     */
    public static class PooledBytes implements PoolAware {
        private final PoolTracker<PooledBytes> tracker = new PoolTracker<>();
        public byte[] bytes;

        public PooledBytes(int size) {
            this.bytes = new byte[size];
        }

        @Override
        public PoolTracker<PooledBytes> getPoolTracker() {
            return tracker;
        }
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param({"64"})
        public int objectSize;

        @Param({"0"}) // nanos of simulated work inside the critical section
        public int workNanos;

        @Param({"1", "4", "16"})
        public int opsPerInvocation;

        @Param({"1024"})
        public int capacity;

        @Param({"4"})
        public int partitions;

        ObjectPool<PooledBytes> pool;
        final ObjectPoolsRegistry poolService = Pools.SHARED; // use shared registry

        @Setup(Level.Trial)
        public void setup() {
            // Ensure a clean pool for this type before creating
            poolService.remove(PooledBytes.class);
            pool = poolService
                    .getOrCreate(PooledBytes.class,
                            () -> new PooledBytes(objectSize),
                            // reset hook: clear first byte
                            pb -> {
                                if (pb.bytes.length > 0) pb.bytes[0] = 0;
                            },
                            capacity,
                            partitions);
            // Optionally prime the pool to avoid first-iteration creation costs
            // by creating up to capacity and returning them.
            int create = Math.min(capacity, 128); // light priming to avoid long setup
            PooledBytes[] tmp = new PooledBytes[create];
            for (int i = 0; i < create; i++) {
                tmp[i] = pool.acquire();
            }
            for (int i = 0; i < create; i++) {
                tmp[i].getPoolTracker().releaseReference();
                tmp[i].getPoolTracker().returnToPool();
                tmp[i] = null;
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            poolService.remove(PooledBytes.class);
            pool = null;
        }
    }

    @Benchmark
    @Threads(Threads.MAX)
    public void acquireUseReturn(final BenchmarkState state, final Blackhole bh) {
        PooledBytes pb = state.pool.acquire();
        byte[] obj = pb.bytes;
        if (state.workNanos > 0) {
            long end = System.nanoTime() + state.workNanos;
            int sum = 0;
            while (System.nanoTime() < end) {
                sum += obj[0];
            }
            bh.consume(sum);
        } else {
            obj[0] = (byte) (obj[0] + 1);
            bh.consume(obj[0]);
        }
        // Drop the initial acquire ref and return the object to the pool
        pb.getPoolTracker().releaseReference();
        pb.getPoolTracker().returnToPool();
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Threads(Threads.MAX)
    public void acquireUseReturn_latencyAvg(final BenchmarkState state, final Blackhole bh) {
        for (int i = 0; i < state.opsPerInvocation; i++) {
            PooledBytes pb = state.pool.acquire();
            byte[] obj = pb.bytes;
            if (state.workNanos > 0) {
                long end = System.nanoTime() + state.workNanos;
                int sum = 0;
                while (System.nanoTime() < end) {
                    sum += obj[0];
                }
                bh.consume(sum);
            } else {
                obj[0] = (byte) (obj[0] + 1);
                bh.consume(obj[0]);
            }
            pb.getPoolTracker().releaseReference();
            pb.getPoolTracker().returnToPool();
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Threads(Threads.MAX)
    public void acquireUseReturn_latencySample(final BenchmarkState state, final Blackhole bh) {
        PooledBytes pb = state.pool.acquire();
        byte[] obj = pb.bytes;
        if (state.workNanos > 0) {
            long end = System.nanoTime() + state.workNanos;
            int sum = 0;
            while (System.nanoTime() < end) {
                sum += obj[0];
            }
            bh.consume(sum);
        } else {
            obj[0] = (byte) (obj[0] + 1);
            bh.consume(obj[0]);
        }
        pb.getPoolTracker().releaseReference();
        pb.getPoolTracker().returnToPool();
    }

    /**
     * Launches JMH using its standard main.
     */
    public static void main(String[] args) throws Exception {
        try {
            org.openjdk.jmh.Main.main(args);
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("META-INF/BenchmarkList")) {
                System.err.println("[INFO] JMH benchmark metadata not found. Ensure annotation processing for tests ran.\n" +
                        "Try: mvn -q test-compile (or enable Annotation Processing for test sources in your IDE) then run again.");
            }
            throw e;
        }
    }
}
