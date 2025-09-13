/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.pool.impl;

import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import lombok.ToString;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Tracks the lifecycle of a {@link PoolAware} instance using reference
 * counting. Only when the reference count drops to zero is the object
 * returned to its originating {@link ObjectPoolManager}.
 */
@ToString
public final class PoolTracker<T extends PoolAware> {

    private volatile ObjectPool<T> pool;
    private volatile T owner;
    private final AtomicInteger refCount = new AtomicInteger(0);
    // Guard to ensure returnToPool is performed at most once per cycle.
    private final AtomicBoolean returned = new AtomicBoolean(false);

    /**
     * Optional reset invoked when returning to pool.
     */
    private volatile Consumer<T> onReturn;

    public PoolTracker() {
    }

    /**
     * Initialises (or reactivates) the tracker when an instance is acquired from a pool.
     * If this is the first acquisition for this instance, binds the tracker permanently
     * to its owner and originating pool. For subsequent acquisitions, only the lifecycle
     * counters are reset.
     */
    void init(ObjectPool<T> pool, T owner, Consumer<T> onReturn) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(owner, "owner");
        // Bind pool/owner once: the owner never changes for the lifetime of this tracker.
        if (this.pool == null) {
            this.pool = pool;
            this.owner = owner;
        }
        // Always use the latest reset hook (optional), allows pool config changes to take effect.
        this.onReturn = onReturn;
        // Reactivate lifecycle
        refCount.set(1);
        returned.set(false);
    }

    /**
     * Acquire one additional reference to the owner.
     */
    public void acquireReference() {
        // If already returned, acquiring is invalid
        if (returned.get()) {
            throw new IllegalStateException("Cannot acquire reference: object already returned to pool");
        }
        ensureInitialised();
        refCount.incrementAndGet();
    }

    /**
     * Release a reference; does NOT return to pool even if it drops to zero.
     */
    public void releaseReference() {
        // Tolerate late releases after return-to-pool: treat as no-op
        if (returned.get()) {
            return;
        }
        ensureInitialised();
        int after = refCount.decrementAndGet();
        if (after < 0) {
            throw new IllegalStateException("PoolTracker underflow: release called more times than acquired");
        }
    }

    /**
     * Explicitly return to pool now if and only if the reference count is zero.
     * This method will not modify the reference count.
     * <p>
     * This method is idempotent and safe under concurrent calls:
     * only the first successful CAS on 'returned' will perform the release.
     */
    public void returnToPool() {
        // If already returned, nothing to do
        if (returned.get()) {
            return;
        }
        ensureInitialised();
        if (refCount.get() == 0) {
            doReturnOnce();
        } else {
            // noop: will be returned by the holder that releases the last reference\
        }
    }

    private void doReturnOnce() {
        final T toReturn = owner; // owner is stable
        if (toReturn != null && returned.compareAndSet(false, true)) {
            final Consumer<T> reset = onReturn;
            final ObjectPool<T> p = pool; // pool is stable
            p.release(toReturn, reset);
        }
    }

    /**
     * Detach the owner from the pool permanently. The pool will create and
     * stage a fresh replacement instance on its free list. After this call,
     * the owner will never be returned to the pool even when its reference
     * count reaches zero; late releases are tolerated.
     */
    public void removeFromPool() {
        ensureInitialised();
        // Mark returned to prevent any future return attempts
        returned.set(true);
        final ObjectPool<T> p = pool;
        final T toDetach = owner;
        if (p != null && toDetach != null) {
            p.removeFromPool(toDetach);
        }
    }

    private void ensureInitialised() {
        if (pool == null || owner == null) {
            throw new IllegalStateException("PoolTracker not initialised by ObjectPool");
        }
    }

    /**
     * For testing/metrics.
     */
    public int currentRefCount() {
        return refCount.get();
    }
}
