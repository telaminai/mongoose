/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.pool;

public interface AutoCloseablPoolAware extends PoolAware, AutoCloseable{

    @Override
    default void close() throws Exception {
        getPoolTracker().releaseReference();
        getPoolTracker().returnToPool();
    }
}
