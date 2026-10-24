/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.replay.JournalledItem;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The paths on which a pooled event's reference must still be released (review of #63): a data mapper that throws,
 * a dispatch whose failure handling throws, a journalled feed drained by a group that does not record, an explicit
 * {@code cache()} with no event log, a NAMED_EVENT fan-out where one queue is full, and a replay of detached cached
 * items to late subscribers. A small pool makes a leak show: the next {@code acquire} waits for good.
 */
public class PooledEventReleasePathsTest {

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

    /** A pooled event whose toString throws: the agent's failure logging then throws out of its catch block. */
    static class BadToStringMsg implements PoolAware {
        final PoolTracker<BadToStringMsg> tracker = new PoolTracker<>();

        @Override
        public PoolTracker<BadToStringMsg> getPoolTracker() {
            return tracker;
        }

        @Override
        public String toString() {
            throw new IllegalStateException("toString failed");
        }
    }

    @AfterEach
    void cleanup() {
        Pools.SHARED.remove(Msg.class);
        Pools.SHARED.remove(BadToStringMsg.class);
    }

    @Test
    void aThrowingMapperReleasesThePooledInput() {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 2);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("throwingMapper");
        publisher.setDataMapper(in -> {
            throw new IllegalArgumentException("cannot map " + in);
        });
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(q, "q");

        Msg first = pool.acquire();
        publisher.publish(first);
        assertEquals(0, first.getPoolTracker().currentRefCount(), "the handed-over reference is released");
        assertEquals(1, pool.availableCount(), "the input is back in the pool");
        assertTrue(q.isEmpty());

        // a pool of 2 survives many failing publishes only if none leaks: a leak would block acquire for good
        List<Msg> published = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 20; i++) {
                Msg m = pool.acquire();
                published.add(m);
                publisher.publish(m);
            }
        });
        for (Msg m : published) {
            assertEquals(0, m.getPoolTracker().currentRefCount(), "no failed publish keeps a reference");
        }
        assertTrue(pool.availableCount() >= 1, "every object created is back in the pool");
    }

    @Test
    void aFailureThatEscapesTheRetryLoopStillReleasesTheQueuesReference() {
        ObjectPool<BadToStringMsg> pool = Pools.SHARED.getOrCreate(BadToStringMsg.class, BadToStringMsg::new, m -> { }, 2);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        CapturingStrategy strategy = new CapturingStrategy(e -> {
            throw new RuntimeException("handler failed");
        });
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(q, strategy, "failing")
                .withRetryPolicy(new RetryPolicy(1, 0, 0, 1.0, Set.of()));
        agent.registerProcessor(new NoopProcessor());

        BadToStringMsg msg = pool.acquire(); // the reference acquire hands over is the queue's
        q.offer(msg);
        // the warning built in the catch block calls event.toString(), which throws out of the retry loop
        assertThrows(IllegalStateException.class, agent::doWork);
        assertEquals(0, msg.getPoolTracker().currentRefCount(), "the queue's reference is released");
        assertEquals(1, pool.availableCount(), "the event is back in the pool");
    }

    @Test
    void aJournalledItemDrainedByAGroupThatDoesNotRecordIsReleasedAndUnwrapped() {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 2);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        CapturingStrategy strategy = new CapturingStrategy(e -> { });
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(q, strategy, "notRecording");
        agent.registerProcessor(new NoopProcessor());

        Msg msg = pool.acquire();
        msg.payload = 5;
        q.offer(new JournalledItem(1, msg)); // a journalled feed's item, as RECORD mode writes it to every queue
        agent.doWork();

        assertEquals(List.of(msg), strategy.received, "the processor receives the bare item, not the journal wrapper");
        assertEquals(0, msg.getPoolTracker().currentRefCount(), "the queue's reference is released");
        assertEquals(1, pool.availableCount(), "the event is back in the pool");
    }

    @Test
    void cacheWithoutAnEventLogReleasesThePooledInput() {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 2);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("cacheNoLog");
        Msg msg = pool.acquire();
        publisher.cache(msg);
        assertEquals(0, msg.getPoolTracker().currentRefCount(), "nothing holds it: the handed-over reference is released");
        assertEquals(1, pool.availableCount());
    }

    @Test
    void namedEventFanOutWithAFullQueueDropsThereAndReturnsOnceTheOtherConsumes() {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 4);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("namedFanOut");
        publisher.setEventWrapStrategy(EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT);
        OneToOneConcurrentArrayQueue<Object> live = new OneToOneConcurrentArrayQueue<>(8);
        OneToOneConcurrentArrayQueue<Object> full = new OneToOneConcurrentArrayQueue<>(2);
        full.offer("blocker-1");
        full.offer("blocker-2");
        publisher.addTargetQueue(live, "live");
        publisher.addTargetQueue(full, "full");

        Msg msg = pool.acquire();
        msg.payload = 9;
        publisher.publish(msg);

        assertEquals(1, publisher.droppedCount(), "the full queue dropped it");
        assertEquals(2, full.size(), "nothing written past capacity");
        assertEquals(1, msg.getPoolTracker().currentRefCount(), "held by the live queue alone");
        assertEquals(0, pool.availableCount());

        CapturingStrategy strategy = new CapturingStrategy(e -> { });
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(live, strategy, "liveConsumer");
        agent.registerProcessor(new NoopProcessor());
        agent.doWork();

        assertEquals(1, strategy.received.size());
        NamedFeedEvent<?> delivered = (NamedFeedEvent<?>) strategy.received.get(0);
        assertSame(msg, delivered.data());
        assertEquals(1, delivered.sequenceNumber());
        assertEquals(0, msg.getPoolTracker().currentRefCount());
        assertEquals(1, pool.availableCount(), "back in the pool once the only holder released it");
        assertEquals(-1, msg.payload, "reset on return");
    }

    @Test
    void detachedCachedItemsReplayToLateSubscribersThroughTheirAgents() {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 2);
        EventToQueuePublisher<Object> publisher = new EventToQueuePublisher<>("lateReplay");
        publisher.setCacheEventLog(true);
        publisher.setEventWrapStrategy(EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT);
        List<Msg> cached = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Msg m = pool.acquire(); // a pool of 2 still serves 3: cache() detaches each and stages a replacement
            m.payload = 100 + i;
            publisher.cache(m);
            cached.add(m);
        }

        List<CapturingStrategy> strategies = new ArrayList<>();
        List<EventQueueToEventProcessorAgent> agents = new ArrayList<>();
        for (int k = 0; k < 2; k++) {
            OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
            publisher.addTargetQueue(q, "late" + k);
            CapturingStrategy strategy = new CapturingStrategy(e -> { });
            EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(q, strategy, "late" + k);
            agent.registerProcessor(new NoopProcessor());
            strategies.add(strategy);
            agents.add(agent);
        }
        assertDoesNotThrow(publisher::dispatchCachedEventLog);
        for (EventQueueToEventProcessorAgent agent : agents) {
            assertDoesNotThrow(agent::doWork);
        }

        for (CapturingStrategy strategy : strategies) {
            assertEquals(3, strategy.received.size(), "every cached item replayed once to each late subscriber");
            for (int i = 0; i < 3; i++) {
                NamedFeedEvent<?> e = (NamedFeedEvent<?>) strategy.received.get(i);
                assertSame(cached.get(i), e.data());
                assertEquals(i + 1, e.sequenceNumber(), "each cached item keeps its own sequence number");
            }
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(101 + i, cached.get(i).payload, "a detached item is never reset or recycled");
        }
        Msg fresh = assertTimeoutPreemptively(Duration.ofSeconds(5), pool::acquire);
        assertFalse(cached.contains(fresh), "the pool hands out a replacement, never a detached item");
    }

    private static class NoopProcessor implements DataFlow {
        @Override
        public void onEvent(Object event) {
        }
    }

    private static class CapturingStrategy implements EventToInvokeStrategy {
        private final Consumer<Object> onEvent;
        private final List<DataFlow> processors = new ArrayList<>();
        final List<Object> received = new ArrayList<>();

        CapturingStrategy(Consumer<Object> onEvent) {
            this.onEvent = onEvent;
        }

        @Override
        public void processEvent(Object event) {
            onEvent.accept(event);
            received.add(event);
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
