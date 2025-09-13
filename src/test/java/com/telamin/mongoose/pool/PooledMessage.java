/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.pool;

import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;

public class PooledMessage implements PoolAware {
    final PoolTracker<PooledMessage> tracker = new PoolTracker<>();
    String value;

    @Override
    public PoolTracker<PooledMessage> getPoolTracker() {
        return tracker;
    }

    @Override
    public String toString() {
        return "PooledMessage{" + value + '}';
    }
}
