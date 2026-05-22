/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.counters.MongooseCountersService;

/**
 * Default zero-overhead {@link MongooseCountersService} used when
 * {@code mongoose.performanceMonitoring.enabled} is {@code false} (the
 * default).
 *
 * <p>Every accessor returns the same singleton {@link MongooseCounter}
 * whose methods are empty / return zero. Because this class is uniquely
 * loaded across the JVM and the {@code MongooseCountersService} field on
 * consumers (publishers, agent loops) holds exactly one impl reference for
 * the JVM's lifetime, every {@code .increment()} call site is monomorphic
 * and C2 inlines the empty method body to nothing.
 *
 * <p>{@link #forEachCounter} visits nothing — there's nothing to visit;
 * the no-op tracks no state.
 */
public final class NoOpCountersService implements MongooseCountersService {

    public static final NoOpCountersService INSTANCE = new NoOpCountersService();
    private static final MongooseCounter NO_OP_COUNTER = new NoOpCounter();

    private NoOpCountersService() {
    }

    @Override
    public MongooseCounter feedPublishCounter(String feed) {
        return NO_OP_COUNTER;
    }

    @Override
    public MongooseCounter agentEventsCounter(String group) {
        return NO_OP_COUNTER;
    }

    @Override
    public MongooseCounter agentIdleCyclesCounter(String group) {
        return NO_OP_COUNTER;
    }

    @Override
    public MongooseCounter queueDepthGauge(String path) {
        return NO_OP_COUNTER;
    }

    @Override
    public MongooseCounter processorEventsCounter(String processor) {
        return NO_OP_COUNTER;
    }

    @Override
    public MongooseCounter nodeInvocationCounter(String processor, String node) {
        return NO_OP_COUNTER;
    }

    @Override
    public MongooseCounter counter(String label) {
        return NO_OP_COUNTER;
    }

    @Override
    public void forEachCounter(CounterVisitor visitor) {
        // intentional no-op — nothing to visit
    }

    @Override
    public boolean isOperational() {
        return false;
    }

    private static final class NoOpCounter implements MongooseCounter {
        @Override public long increment()             { return 0L; }
        @Override public long incrementRelease()      { return 0L; }
        @Override public void setOrdered(long value)  { /* no-op */ }
        @Override public long get()                   { return 0L; }
    }
}