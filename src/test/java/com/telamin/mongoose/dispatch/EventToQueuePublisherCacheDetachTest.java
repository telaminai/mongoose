/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that when cacheEventLog == true, the publisher detaches the pooled
 * instance from the pool (removeFromPool) and caches the original object while
 * replacing it in the underlying pool with a fresh instance.
 */
public class EventToQueuePublisherCacheDetachTest {

    static class PooledMsg implements PoolAware {
        private final PoolTracker<PooledMsg> tracker = new PoolTracker<>();
        int payload;

        @Override
        public PoolTracker<PooledMsg> getPoolTracker() {
            return tracker;
        }

        @Override
        public String toString() {
            return "PooledMsg{" + payload + '}';
        }
    }

    @AfterEach
    void cleanup() {
        Pools.SHARED.remove(PooledMsg.class);
    }

    @Test
    public void cache_detaches_from_pool_and_replaces_instance() {
        // Small pool
        ObjectPool<PooledMsg> pool = Pools.SHARED.getOrCreate(PooledMsg.class, PooledMsg::new, m -> m.payload = 0, 2);

        // Prepare publisher with caching enabled and one drainable queue
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("cacheDetachTest");
        publisher.setCacheEventLog(true);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(q, "q1");

        // Acquire pooled object and publish
        PooledMsg msg = pool.acquire();
        msg.payload = 7;
        publisher.cache(msg);

        // The event will have been dispatched; cached event should hold the same instance
        List<NamedFeedEvent<?>> log = publisher.getEventLog();
        assertEquals(1, log.size(), "one cached event expected");
        Object cached = log.get(0).data();
        assertSame(msg, cached, "cached data should be the original pooled instance");

        // After detach, returning the original instance should be a no-op; pool should still have a replacement available
        msg.getPoolTracker().returnToPool();
        assertTrue(pool.availableCount() >= 1, "pool should have at least one available instance from replacement");

        // A subsequent acquire should not return the detached instance
        PooledMsg next = pool.acquire();
        assertNotSame(msg, next, "pool should provide a fresh instance, not the detached one");
    }
}
