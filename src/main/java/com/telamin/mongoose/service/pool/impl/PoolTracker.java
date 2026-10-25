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
 * counting. The object is returned to its originating {@link ObjectPoolManager}
 * by the release that takes the count to zero, and only by that release: every
 * holder (the publisher, each queue, each consumer) releases its own reference
 * once it no longer reads the object, and whoever releases last returns it
 * (telaminai/mongoose#54).
 */
@ToString
public final class PoolTracker<T extends PoolAware> {

    private volatile ObjectPool<T> pool;
    private volatile T owner;
    private final AtomicInteger refCount = new AtomicInteger(0);
    // Guard to ensure returnToPool is performed at most once per cycle.
    private final AtomicBoolean returned = new AtomicBoolean(false);
    /** Set by {@link #removeFromPool()}: the owner is outside pool management for good, and its references are not counted. */
    private volatile boolean detached;

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
        if (detached) {
            return;
        }
        // If already returned, acquiring is invalid
        if (returned.get()) {
            throw new IllegalStateException("Cannot acquire reference: object already returned to pool");
        }
        ensureInitialised();
        refCount.incrementAndGet();
    }

    /**
     * Release a reference. The release that takes the count to zero returns the owner to its pool; no other call
     * does, so an object is never recycled while a holder that has not yet released still reads it.
     * <p>
     * The count never goes below zero: a release with no reference held, while the object is still out of the pool,
     * is a double release and is refused with an {@link IllegalStateException}. A late release after the object has
     * gone back to the pool is tolerated as a no-op.
     * <p>
     * <b>The late-release no-op holds only until the object is acquired again.</b> Once the pool hands the object out
     * again its tracker is re-initialised (count 1, not returned), so a late release from a previous holder then
     * counts against the NEW holder's reference (an ABA): it can take the count to zero and return the object while
     * the new holder still uses it, or make the new holder's own release fail as a double release. A holder must
     * therefore release exactly once per reference it acquired, and never after it has given its reference up.
     */
    public void releaseReference() {
        if (detached || returned.get()) {
            return;
        }
        ensureInitialised();
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                if (returned.get()) {
                    return; // raced with the release that returned it: a late release
                }
                throw new IllegalStateException("PoolTracker underflow: release called more times than acquired: " + owner);
            }
            if (refCount.compareAndSet(current, current - 1)) {
                if (current == 1) {
                    doReturnOnce();
                }
                return;
            }
        }
    }

    /**
     * Compatibility no-op. The release that removes the last reference already returns the object to its pool.
     * Callers may still pair this method with {@link #releaseReference()}, including after the object has been
     * acquired again, but this call must never claim ownership of that later acquisition.
     *
     * <p>In particular, checking for a zero count here and then claiming a return with a separate CAS is unsafe:
     * another release may return and reactivate the object between the check and the CAS. Only the release that
     * actually changes the count to zero owns the return operation.
     */
    public void returnToPool() {
        ensureInitialised();
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
        detached = true;
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
