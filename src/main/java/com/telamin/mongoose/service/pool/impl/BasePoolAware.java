/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.pool.impl;

import com.telamin.mongoose.service.pool.PoolAware;

/**
 * Convenience abstract base for PoolAware implementations.
 * <p>
 * Provides a ready-to-use PoolTracker instance and a default
 * {@link #getPoolTracker()} implementation. Subclasses can
 * simply extend this class instead of re-declaring a tracker field.
 */
public abstract class BasePoolAware implements PoolAware {

    /**
     * Pool lifecycle tracker for this instance. The creating
     * ObjectPool initialises this tracker via {@link PoolTracker#init}.
     */
    protected final PoolTracker<BasePoolAware> tracker = new PoolTracker<>();

    @Override
    public PoolTracker<BasePoolAware> getPoolTracker() {
        return tracker;
    }
}
