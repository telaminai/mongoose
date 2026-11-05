/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lifetime of a pooled event published to several queues, each drained by its own
 * {@link EventQueueToEventProcessorAgent} on its own thread (telaminai/mongoose#54).
 * <p>
 * A pooled event must stay out of the pool until every queue's consumer has finished dispatching it: the publisher
 * keeps its own reference until every queue holds one, each consumer drops its reference only after dispatch, and the
 * object goes back to the pool on the last release. A pool far smaller than the traffic makes any early return show:
 * the object is recycled and rewritten while a processor is still reading it.
 */
public class PooledEventLifetimeTest {

    static class Msg implements PoolAware {
        final PoolTracker<Msg> tracker = new PoolTracker<>();
        volatile long payload;

        @Override
        public PoolTracker<Msg> getPoolTracker() {
            return tracker;
        }

        @Override
        public String toString() {
            return "Msg{" + payload + '}';
        }
    }

    @AfterEach
    void cleanup() {
        Pools.SHARED.remove(Msg.class);
    }

    @Test
    void pooledEventsFanOutToSeveralConsumers_noneRecycledInUse() throws Exception {
        final int queues = 3;
        final int poolSize = 8;
        final long toPublish = 50_000;
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, poolSize);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("pooledFanOut");

        AtomicLong mutatedWhileRead = new AtomicLong();
        AtomicReference<Throwable> consumerError = new AtomicReference<>();
        AtomicBoolean consuming = new AtomicBoolean(true);
        List<OneToOneConcurrentArrayQueue<Object>> qs = new ArrayList<>();
        List<ReadingStrategy> strategies = new ArrayList<>();
        List<Thread> consumers = new ArrayList<>();
        for (int k = 0; k < queues; k++) {
            OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(1024);
            qs.add(q);
            publisher.addTargetQueue(q, "q" + k);
            ReadingStrategy strategy = new ReadingStrategy(mutatedWhileRead);
            strategies.add(strategy);
            EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(q, strategy, "consumer" + k);
            agent.registerProcessor(new NoopProcessor());
            Thread consumer = new Thread(() -> {
                try {
                    while (consuming.get() || !q.isEmpty()) {
                        if (agent.doWork() == 0) Thread.onSpinWait();
                    }
                } catch (Throwable t) {
                    consumerError.compareAndSet(null, t);
                }
            }, "consumer" + k);
            consumer.setDaemon(true);
            consumers.add(consumer);
            consumer.start();
        }

        Set<Msg> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        AtomicLong published = new AtomicLong();
        AtomicReference<Throwable> publishError = new AtomicReference<>();
        Thread publisherThread = new Thread(() -> {
            for (long n = 1; n <= toPublish; n++) {
                Msg m = pool.acquire();
                seen.add(m);
                m.payload = n;
                try {
                    publisher.publish(m);
                    published.incrementAndGet();
                } catch (Throwable t) {
                    publishError.compareAndSet(null, t);
                }
            }
        }, "publisher");
        publisherThread.setDaemon(true); // a publisher stuck in ObjectPool.acquire must not keep the JVM alive
        publisherThread.start();
        publisherThread.join(20_000);
        boolean publisherHung = publisherThread.isAlive();

        consuming.set(false);
        for (Thread consumer : consumers) consumer.join(5_000);

        assertFalse(publisherHung, "publisher hung in ObjectPool.acquire after " + published + " publishes"
                + " (trackers " + refCounts(seen) + ")");
        assertNull(publishError.get(), "publish failed: " + publishError.get());
        assertNull(consumerError.get(), "consumer failed: " + consumerError.get());
        assertEquals(0, mutatedWhileRead.get(), "pooled events recycled while a processor was reading them");
        for (int k = 0; k < queues; k++) {
            assertEquals(toPublish, strategies.get(k).received, "queue q" + k + " lost events");
            assertTrue(qs.get(k).isEmpty());
        }
        for (Msg m : seen) {
            assertEquals(0, m.getPoolTracker().currentRefCount(), "tracker not at 0: " + refCounts(seen));
        }
        assertEquals(seen.size(), pool.availableCount(), "every pooled event back in the pool");
    }

    @Test
    void publisherKeepsItsReferenceUntilEveryQueueHoldsOne() {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 4);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("twoQueues");
        OneToOneConcurrentArrayQueue<Object> q1 = new OneToOneConcurrentArrayQueue<>(8);
        OneToOneConcurrentArrayQueue<Object> q2 = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(q1, "q1");
        publisher.addTargetQueue(q2, "q2");

        Msg msg = pool.acquire();
        msg.payload = 7;
        publisher.publish(msg);
        assertEquals(2, msg.getPoolTracker().currentRefCount(), "one reference per queue, the publisher's released");

        assertSame(msg, q1.poll());
        msg.getPoolTracker().releaseReference();
        assertEquals(0, pool.availableCount(), "still held by q2");
        assertEquals(7, msg.payload);

        assertSame(msg, q2.poll());
        msg.getPoolTracker().releaseReference();
        assertEquals(1, pool.availableCount(), "back in the pool on the last release");
        assertEquals(-1, msg.payload, "reset on return");
    }

    @Test
    void publishWithNoSubscriberReturnsToPool() {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 4);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("noSubscriber");
        Msg msg = pool.acquire();
        publisher.publish(msg);
        assertEquals(0, msg.getPoolTracker().currentRefCount());
        assertEquals(1, pool.availableCount(), "nobody holds it, so it is back in the pool");
    }

    @Test
    void cachedPublishReturnsToPoolOnceConsumed() {
        // the cache keeps a detached snapshot (a String), not the pooled object, so it holds no reference to it
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 4);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("cached");
        publisher.setCacheEventLog(true);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(q, "q");

        Msg msg = pool.acquire();
        msg.payload = 3;
        publisher.publish(msg);
        assertEquals("Msg{3}", publisher.getEventLog().get(0).data());
        assertEquals(1, msg.getPoolTracker().currentRefCount(), "held by the queue alone");

        assertSame(msg, q.poll());
        msg.getPoolTracker().releaseReference();
        assertEquals(1, pool.availableCount(), "back in the pool once consumed");
    }

    @Test
    void detachedCachedItemReplaysToALateQueue() {
        // cache() detaches a pooled item from its pool: replaying it later must not count references on it
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 4);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("detachedReplay");
        publisher.setCacheEventLog(true);
        Msg cached = pool.acquire();
        cached.payload = 11;
        publisher.cache(cached);

        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(q, "late");
        assertDoesNotThrow(publisher::dispatchCachedEventLog);
        assertSame(cached, q.poll());
        cached.getPoolTracker().releaseReference();
        assertEquals(11, cached.payload, "a detached item is never reset or recycled");
    }

    @Test
    void lastReleaseReturnsOnce_andTheCountNeverGoesNegative() {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 4);
        Msg msg = pool.acquire();
        msg.getPoolTracker().acquireReference();
        msg.getPoolTracker().releaseReference();
        assertEquals(0, pool.availableCount(), "one reference still held");
        msg.getPoolTracker().releaseReference();
        assertEquals(1, pool.availableCount(), "returned on the last release, with no returnToPool call");
        // a late release after the return is tolerated: no underflow, and the object is not offered twice
        msg.getPoolTracker().releaseReference();
        msg.getPoolTracker().returnToPool();
        assertEquals(0, msg.getPoolTracker().currentRefCount());
        assertEquals(1, pool.availableCount());
    }

    private static String refCounts(Set<Msg> seen) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : seen) sb.append(m.getPoolTracker().currentRefCount()).append(' ');
        return sb.toString().trim();
    }

    private static class NoopProcessor implements DataFlow {
        @Override
        public void onEvent(Object event) {
        }
    }

    /** Reads the event as a processor would, and checks it was not rewritten (recycled) while being read. */
    private static class ReadingStrategy implements EventToInvokeStrategy {
        private final AtomicLong mutatedWhileRead;
        private final List<DataFlow> processors = new ArrayList<>();
        volatile long received;

        ReadingStrategy(AtomicLong mutatedWhileRead) {
            this.mutatedWhileRead = mutatedWhileRead;
        }

        @Override
        public void processEvent(Object event) {
            Msg m = (Msg) event;
            long before = m.payload;
            for (int i = 0; i < 200; i++) Thread.onSpinWait();
            if (before <= 0 || m.payload != before) {
                mutatedWhileRead.incrementAndGet();
            }
            received++;
            for (DataFlow p : processors) p.onEvent(event);
        }

        @Override
        public void processEvent(Object event, long time) {
            processEvent(event);
        }

        @Override
        public void registerProcessor(DataFlow eventProcessor) {
            processors.add(eventProcessor);
        }

        @Override
        public void deregisterProcessor(DataFlow eventProcessor) {
            processors.remove(eventProcessor);
        }

        @Override
        public int listenerCount() {
            return processors.size();
        }
    }
}
