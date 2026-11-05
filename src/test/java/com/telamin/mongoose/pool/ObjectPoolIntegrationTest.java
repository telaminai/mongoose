/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.pool;

import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ObjectPoolIntegrationTest {

    static class PooledMessage implements PoolAware {
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

    @Test
    public void testReferenceCountingAcrossQueuesNowrap() {
        Pools.SHARED.remove(PooledMessage.class);
        ObjectPool<PooledMessage> pool = Pools.SHARED.getOrCreate(PooledMessage.class, PooledMessage::new, pm -> pm.value = null);
        PooledMessage msg = pool.acquire();
        msg.value = "hello";

        EventToQueuePublisher<Object> pub = new EventToQueuePublisher<>("poolTest");
        OneToOneConcurrentArrayQueue<Object> q1 = new OneToOneConcurrentArrayQueue<>(8);
        OneToOneConcurrentArrayQueue<Object> q2 = new OneToOneConcurrentArrayQueue<>(8);
        pub.addTargetQueue(q1, "q1");
        pub.addTargetQueue(q2, "q2");
        pub.setEventWrapStrategy(EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP);

        pub.publish(msg);
        // initial 1 (acquire) is released on publish: 0 + 2 for two queues
        assertEquals(2, msg.getPoolTracker().currentRefCount());

        // drain first queue and release
        Object d1 = q1.remove();
        assertSame(msg, d1);
        msg.getPoolTracker().releaseReference();
        assertEquals(1, msg.getPoolTracker().currentRefCount());

        // drain second queue and release
        Object d2 = q2.remove();
        assertSame(msg, d2);
        msg.getPoolTracker().releaseReference();

        // the last release returns it to the pool (#54); a later returnToPool is a no-op
        assertEquals(1, pool.availableCount());
        msg.getPoolTracker().returnToPool();
        assertEquals(1, pool.availableCount());
    }

    @Test
    public void testReferenceCountingWithNamedEventWrapAndCache() {
        Pools.SHARED.remove(PooledMessage.class);
        ObjectPool<PooledMessage> pool = Pools.SHARED.getOrCreate(PooledMessage.class, PooledMessage::new, pm -> pm.value = null);
        PooledMessage msg = pool.acquire();
        msg.value = "helloWrap";

        EventToQueuePublisher<Object> pub = new EventToQueuePublisher<>("poolTestWrap");
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        pub.addTargetQueue(q, "q");
        pub.setEventWrapStrategy(EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT);
        pub.setCacheEventLog(true);

        pub.publish(msg);
        // initial 1 (acquire) is released after dispatch: 1 for the queue. The cache keeps a detached String
        // snapshot, not the pooled instance, so it holds no reference (#54: holding one kept the item out of the pool)
        assertEquals(1, msg.getPoolTracker().currentRefCount());

        // drain queue -> NamedFeedEvent wrapper
        Object e = q.remove();
        assertTrue(e instanceof com.telamin.fluxtion.runtime.event.NamedFeedEvent);
        com.telamin.fluxtion.runtime.event.NamedFeedEvent<?> nfe = (com.telamin.fluxtion.runtime.event.NamedFeedEvent<?>) e;
        assertSame(msg, nfe.data());
        // consumer done: the last reference, so it is back in the pool
        msg.getPoolTracker().releaseReference();
        assertEquals(0, msg.getPoolTracker().currentRefCount());
        assertEquals(1, pool.availableCount());
        assertEquals("PooledMessage{helloWrap}", pub.getEventLog().get(0).data(), "the cache keeps a snapshot");
    }
}
