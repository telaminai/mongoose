/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.pool;

import com.telamin.mongoose.service.pool.impl.PoolTracker;


/**
 * Interface for objects that can be managed by an {@link ObjectPool}.
 * Implementing classes must maintain a reference to their {@link PoolTracker} which manages the
 * object's lifecycle within the pool through reference counting.
 * <p>
 * Objects implementing this interface can be pooled and reused, reducing memory allocation overhead
 * in high-performance scenarios. The pool tracks references to ensure objects are only returned to
 * the pool when all references have been released.
 *
 * @see ObjectPool
 * @see PoolTracker
 */
public interface PoolAware {
    /**
     * Returns the {@link PoolTracker} associated with this pooled object.
     * <p>
     * The tracker maintains reference counting and lifecycle management for the pooled object.
     * It is initialized by the creating pool when the object is acquired and manages the object's
     * return to the pool when all references are released.
     *
     * @return the PoolTracker managing this object's lifecycle
     */
    PoolTracker<?> getPoolTracker();
}
