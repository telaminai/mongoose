/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import lombok.extern.java.Log;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Real {@link MongooseCountersService} backed by Agrona's
 * {@link CountersManager} over an on-heap {@link UnsafeBuffer}. Selected at
 * boot when {@code mongoose.performanceMonitoring.enabled = true}.
 *
 * <p><b>Sizing.</b> The buffer is sized in KB via the constructor; default
 * 256 KB yields ~2048 counters. The metadata buffer is sized to match
 * (counters × {@code CountersManager.METADATA_LENGTH}).
 *
 * <p><b>Threading.</b> {@code CountersManager.allocate(label)} is
 * <i>not</i> thread-safe (its javadoc: "This class in not threadsafe.
 * Counters should be centrally managed."). Phase 1 confines registration
 * to a single thread — the boot thread that constructs the service and the
 * EFM-driven wiring that allocates counter handles before any agent starts.
 * The fast path ({@code increment} / {@code get}) is contention-free in the
 * single-writer-per-counter case, which is how the service is wired (one
 * writer per feed / per agent group / per processor / per node).
 *
 * <p>Handles are cached in a {@link ConcurrentHashMap} keyed by label so
 * repeat calls return the same {@code MongooseCounter} reference. The
 * {@code computeIfAbsent} callback synchronises on the {@code CountersManager}
 * as a belt-and-braces guard for any future registration path that escapes
 * the single-threaded convention.
 */
@Log
public final class AgronaCountersService implements MongooseCountersService {

    /** Default values-buffer size; ~2048 counters at 128 bytes / counter. */
    public static final int DEFAULT_BUFFER_KB = 256;

    private final UnsafeBuffer valuesBuffer;
    private final CountersManager countersManager;
    private final ConcurrentMap<String, MongooseCounter> cache = new ConcurrentHashMap<>();

    /**
     * @param bufferKb size of the values buffer in KB (clamped to a minimum
     *                 of 16 KB — anything smaller yields too few counters
     *                 to be useful)
     */
    public AgronaCountersService(int bufferKb) {
        int valuesBytes = Math.max(16, bufferKb) * 1024;
        int maxCounters = valuesBytes / CountersManager.COUNTER_LENGTH;
        int metaDataBytes = maxCounters * CountersManager.METADATA_LENGTH;
        // Direct buffers — Agrona 2.x requires 8-byte aligned backing, which
        // byte[]s don't guarantee under the JVM object layout. allocateDirect
        // returns an 8-aligned native buffer.
        ByteBuffer values   = ByteBuffer.allocateDirect(valuesBytes).order(ByteOrder.nativeOrder());
        ByteBuffer metaData = ByteBuffer.allocateDirect(metaDataBytes).order(ByteOrder.nativeOrder());
        this.valuesBuffer = new UnsafeBuffer(values);
        UnsafeBuffer metaDataBuffer = new UnsafeBuffer(metaData);
        this.countersManager = new CountersManager(metaDataBuffer, valuesBuffer);
        log.info("AgronaCountersService initialised: bufferKb=" + bufferKb
                + ", maxCounters=" + maxCounters);
    }

    public AgronaCountersService() {
        this(DEFAULT_BUFFER_KB);
    }

    @Override
    public MongooseCounter feedPublishCounter(String feed) {
        return counter("feed." + feed + ".published");
    }

    @Override
    public MongooseCounter agentEventsCounter(String group) {
        return counter("group." + group + ".processed");
    }

    @Override
    public MongooseCounter agentIdleCyclesCounter(String group) {
        return counter("group." + group + ".idleCycles");
    }

    @Override
    public MongooseCounter queueDepthGauge(String path) {
        return counter("queue." + path + ".depth");
    }

    @Override
    public MongooseCounter processorEventsCounter(String processor) {
        return counter("processor." + processor + ".events");
    }

    @Override
    public MongooseCounter nodeInvocationCounter(String processor, String node) {
        return counter("node." + processor + "." + node + ".invocations");
    }


    @Override
    public void forEachCounter(CounterVisitor visitor) {
        // Iterate the local cache rather than the CountersManager — the cache
        // already holds (label, AgronaCounter) and AgronaCounter exposes its
        // Agrona AtomicCounter's id, which is all the visitor signature needs.
        cache.forEach((label, counter) -> {
            AgronaCounter ac = (AgronaCounter) counter;
            visitor.visit(ac.id(), label, ac.get());
        });
    }

    @Override
    public boolean isOperational() {
        return true;
    }

    @Override
    public MongooseCounter counter(String label) {
        return cache.computeIfAbsent(label, this::allocate);
    }

    private MongooseCounter allocate(String label) {
        synchronized (countersManager) {
            int id = countersManager.allocate(label);
            AtomicCounter atomic = new AtomicCounter(valuesBuffer, id, countersManager);
            return new AgronaCounter(id, atomic);
        }
    }

    /**
     * @return the underlying counters manager — exposed for testing and
     * future close()/free() use. Not part of the public service API.
     */
    CountersManager countersManager() {
        return countersManager;
    }

    /**
     * Adapter from mongoose-owned {@link MongooseCounter} to Agrona's
     * {@link AtomicCounter}. Holds the counter id so {@code forEachCounter}
     * can report it without a metadata-buffer walk.
     */
    static final class AgronaCounter implements MongooseCounter {
        private final int id;
        private final AtomicCounter atomic;

        AgronaCounter(int id, AtomicCounter atomic) {
            this.id = id;
            this.atomic = atomic;
        }

        int id() { return id; }

        @Override public long increment()             { return atomic.increment(); }
        @Override public long incrementRelease()      { return atomic.incrementRelease(); }
        @Override public void setOrdered(long value)  { atomic.setRelease(value); }
        @Override public long get()                   { return atomic.get(); }
    }
}
