/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.pool;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
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

        // Not automatically returned; explicit returnToPool required
        assertEquals(0, pool.availableCount());
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
        //initial 1 (acquire) is released on publish: 0 + 1 for queue + 1 for cache
        assertEquals(2, msg.getPoolTracker().currentRefCount());

        // drain queue -> NamedFeedEvent wrapper
        Object e = q.remove();
        assertTrue(e instanceof com.fluxtion.runtime.event.NamedFeedEvent);
        com.fluxtion.runtime.event.NamedFeedEvent<?> nfe = (com.fluxtion.runtime.event.NamedFeedEvent<?>) e;
        assertSame(msg, nfe.data());
        // consumer done
        msg.getPoolTracker().releaseReference();
        // still one held by cache
        assertEquals(1, msg.getPoolTracker().currentRefCount());

        // not back in pool yet
        assertEquals(0, pool.availableCount());
    }
}
