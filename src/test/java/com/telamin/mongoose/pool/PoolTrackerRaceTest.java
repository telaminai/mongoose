/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.pool;

import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces and validates that when the last reference is released AFTER an early returnToPool() call,
 * the pooled object is still returned to the pool (auto-return on last release).
 */
public class PoolTrackerRaceTest {

    static class PooledMsg implements PoolAware {
        final PoolTracker<PooledMsg> tracker = new PoolTracker<>();
        int payload;

        @Override
        public PoolTracker<PooledMsg> getPoolTracker() {
            return tracker;
        }
    }

    @AfterEach
    void cleanup() {
        Pools.SHARED.remove(PooledMsg.class);
    }

    @Test
    public void autoReturn_onLastRelease_afterEarlyReturnCall() {
        ObjectPool<PooledMsg> pool = Pools.SHARED.getOrCreate(PooledMsg.class, PooledMsg::new, m -> m.payload = 0, 8, 2);
        // Start empty
        assertEquals(0, pool.availableCount());

        PooledMsg m = pool.acquire();
        // Simulate two downstream holders (e.g., two queues)
        m.getPoolTracker().acquireReference();
        m.getPoolTracker().acquireReference();

        // Origin done
        m.getPoolTracker().releaseReference();
        // Early attempt to return (e.g., end-of-cycle) should be a no-op for now
        m.getPoolTracker().returnToPool();
        assertEquals(0, pool.availableCount());

        // First downstream releases -> still 1 ref left
        m.getPoolTracker().releaseReference();
        m.getPoolTracker().returnToPool();
        assertEquals(0, pool.availableCount());

        // Last downstream releases -> should auto-return now
        m.getPoolTracker().releaseReference();
        m.getPoolTracker().returnToPool();
        assertEquals(1, pool.availableCount(), "Object should be returned automatically when last reference is released");
    }
}
