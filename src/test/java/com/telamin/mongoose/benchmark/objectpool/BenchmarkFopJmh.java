/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.benchmark.objectpool;

import cn.danielw.fop.ObjectFactory;
import cn.danielw.fop.ObjectPool;
import cn.danielw.fop.PoolConfig;
import cn.danielw.fop.Poolable;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * A JMH micro-benchmark adapted from the FOP benchmark to exercise
 * borrow/return throughput on Fast-Object-Pool (FOP) only.
 *
 * Run via main():
 *   -Dthreads=8 -Dforks=1 -Dwarmups=1 -Dmeas=3
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
public class BenchmarkFopJmh {

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param({"64"})
        public int objectSize;

        @Param({"0"}) // 0 means no sleep; keep it purely contention/throughput
        public int workNanos;

        @Param({"1", "4", "16"})
        public int opsPerInvocation;

        @Param({"100000"})
        public int maxTotal;

        @Param({"100000"})
        public int maxIdle;

        ObjectPool<byte[]> pool;

        @Setup(Level.Trial)
        public void setup() {
            PoolConfig cfg = new PoolConfig();
            // Using default configuration to avoid depending on specific setter names.
            // The values are retained as @Param in case you want to wire them via cfg API.

            ObjectFactory<byte[]> factory = new ObjectFactory<>() {
                @Override
                public byte[] create() {
                    return new byte[objectSize];
                }

                @Override
                public void destroy(byte[] obj) {
                    // no-op
                }

                @Override
                public boolean validate(byte[] obj) {
                    return obj != null && obj.length == objectSize;
                }
            };

            pool = new ObjectPool<>(cfg, factory);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (pool != null) {
                try {
                    pool.shutdown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pool = null;
                }
            }
        }
    }

    @Benchmark
    @Threads(Threads.MAX)
    public void borrowAndReturn(final BenchmarkState state, final Blackhole bh) throws Exception {
        Poolable<byte[]> pooled = state.pool.borrowObject();
        byte[] obj = pooled.getObject();
        if (state.workNanos > 0) {
            // Simulate a small amount of work so JIT cannot optimize away completely
            long end = System.nanoTime() + state.workNanos;
            int sum = 0;
            while (System.nanoTime() < end) {
                sum += obj[0];
            }
            bh.consume(sum);
        } else {
            // Touch the object to prevent escape analysis from discarding it
            obj[0] = (byte) (obj[0] + 1);
            bh.consume(obj[0]);
        }
        state.pool.returnObject(pooled);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Threads(Threads.MAX)
    public void borrowAndReturn_latencyAvg(final BenchmarkState state, final Blackhole bh) throws Exception {
        for (int i = 0; i < state.opsPerInvocation; i++) {
            Poolable<byte[]> pooled = state.pool.borrowObject();
            byte[] obj = pooled.getObject();
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
            state.pool.returnObject(pooled);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Threads(Threads.MAX)
    public void borrowAndReturn_latencySample(final BenchmarkState state, final Blackhole bh) throws Exception {
        Poolable<byte[]> pooled = state.pool.borrowObject();
        byte[] obj = pooled.getObject();
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
        state.pool.returnObject(pooled);
    }

    /**
     * Launches JMH using its standard main. You can also run via IDE by executing this main method.
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
