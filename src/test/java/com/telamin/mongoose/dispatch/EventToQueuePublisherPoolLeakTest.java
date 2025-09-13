/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that when an offer to a target queue is abandoned (slow/full queue),
 * any per-attempt acquired references by the publisher are released, so the
 * pooled object can be returned to the pool when the origin releases its ref.
 */
public class EventToQueuePublisherPoolLeakTest {

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
    public void noLeak_whenAbandoningOfferToFullQueue() {
        // Create a tiny pool and acquire a pooled object
        ObjectPool<PooledMsg> pool = Pools.SHARED.getOrCreate(PooledMsg.class, PooledMsg::new, m -> m.payload = 0, 4);
        PooledMsg msg = pool.acquire();
        msg.payload = 42;

        // Prepare publisher with a single, very small queue and make it full
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("leakTest");
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(1);
        q.offer(new Object()); // fill to force offer() to fail and then abandon
        publisher.addTargetQueue(q, "q1");

        // Publish our pooled object; the publisher will try and then abandon
        publisher.publish(msg);

        msg.getPoolTracker().returnToPool();

        assertEquals(1, pool.availableCount(), "pooled object should be returned to pool (no leaked refs)");
    }
}
