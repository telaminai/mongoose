/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.LifeCycleEventSource;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BLOCK_WITH_DEADLINE on real threads (telaminai/mongoose#79): deadline expiry, room freed mid-wait, unsubscribe and
 * shutdown while a publish waits, agent-thread downgrade, all-or-nothing fan-out, pooled-item refusal, and a slow
 * subscriber under a per-group override. Every publish that may wait runs on its own non-agent thread.
 */
@Timeout(60)
class BlockWithDeadlineTest {

    @AfterEach
    void cleanup() {
        Pools.SHARED.remove(Msg.class);
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void deadlineExpiryRefusesAndNoQueueHoldsTheItem() throws Exception {
        var publisher = new EventToQueuePublisher<Object>("orders");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(150))));
        var roomy = new OneToOneConcurrentArrayQueue<Object>(8);
        var full = full(2);
        bind(publisher, roomy, "roomy", "a");
        bind(publisher, full, "full", "b");
        long seqBefore = publisher.getSequenceNumber();

        Outcome out = publishOnThread(publisher, "order-1");

        assertTrue(out.await(5_000), "the publish returns at its deadline");
        PublishRefusedException refused = assertInstanceOf(PublishRefusedException.class, out.error.get());
        assertEquals(PublishRefusedException.Reason.DEADLINE, refused.reason());
        assertEquals("full", refused.queue());
        assertEquals("orders", refused.feed());
        assertTrue(out.elapsedMillis() >= 140, "waited for the deadline: " + out.elapsedMillis() + "ms");
        assertTrue(refused.waitedNanos() >= TimeUnit.MILLISECONDS.toNanos(140));
        assertNull(roomy.poll(), "all-or-nothing: the queue with room did not get the refused item");
        assertEquals(seqBefore, publisher.getSequenceNumber(), "a refusal consumes no sequence number");
        assertEquals(1, publisher.refusedCount());
        assertEquals(1, publisher.refusedCount("full"));
        assertEquals(0, publisher.refusedCount("roomy"));
        assertEquals(0, publisher.droppedCount(), "a refusal is not a drop");
    }

    @Test
    void roomFreedMidWaitDeliversWithoutRefusal() throws Exception {
        var publisher = new EventToQueuePublisher<Object>("orders");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(20))));
        var full = full(2);
        bind(publisher, full, "q", "g");

        Outcome out = publishOnThread(publisher, "order-1");
        assertFalse(out.await(100), "waits while the queue is full");
        assertEquals("filler-0", full.poll(), "the consumer frees one slot");

        assertTrue(out.await(2_000), "the waiting publish notices the room well before its 20 s deadline");
        assertNull(out.error.get());
        assertEquals("filler-1", full.poll());
        assertEquals("order-1", full.poll(), "delivered after the older items, exactly once");
        assertNull(full.poll());
        assertEquals(0, publisher.refusedCount());
        assertEquals(1, publisher.getSequenceNumber());
    }

    /**
     * The review's deadlock: the subscriber's own group thread unsubscribes (EventFlowManager unsubscribe action ->
     * removeTargetQueueByName) while a publisher waits for room in that group's queue, which the thread is therefore
     * not draining. A waiting publish holds no lock, so the remove completes at once, and the publish re-evaluates
     * against the remaining subscribers.
     */
    @Test
    void unsubscribeWhileWaitingCompletesPromptlyAndPublishGoesToTheRemainingSubscribers() throws Exception {
        var manager = new EventFlowManager();
        manager.setFeedOverload("orders",
                FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(30))));
        var publisher = manager.registerEventSource("orders", new Source());
        var slow = new ComposingEventProcessorAgent("slow", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var other = new ComposingEventProcessorAgent("other", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var key = new EventSourceKey<Object>("orders");
        var slowReader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, slow);
        var otherReader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, other);
        List<Object> slowEvents = new ArrayList<>();
        List<Object> otherEvents = new ArrayList<>();
        DataFlow slowFlow = slowEvents::add;
        DataFlow otherFlow = otherEvents::add;
        slowReader.registerProcessor(slowFlow);
        otherReader.registerProcessor(otherFlow);
        try {
            for (int i = 0; i < 1024; i++) {
                publisher.publish(i);
                otherReader.doWork();
            }
            Outcome out = publishOnThread(publisher, "order-x");
            assertFalse(out.await(100), "waits: the slow group's queue is full");

            long start = System.nanoTime();
            Thread groupThread = new Thread(() -> slowReader.deregisterProcessor(slowFlow), "slow-group-unsubscribe");
            groupThread.start();
            groupThread.join(2_000);
            long removeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertFalse(groupThread.isAlive(), "unsubscribe never waits for a waiting publish (no deadlock)");
            assertTrue(removeMillis < 1_000, "unsubscribe completed in " + removeMillis + "ms");

            assertTrue(out.await(2_000), "the waiting publish re-evaluates at once, not at its 30 s deadline");
            assertNull(out.error.get(), "the remaining subscriber has room, so the publish is delivered");
            while (otherReader.doWork() > 0) {
            }
            assertEquals("order-x", otherEvents.getLast());
            assertEquals(1025, otherEvents.size(), "the other subscriber has every item exactly once");
            assertEquals(0, publisher.refusedCount());
        } finally {
            otherReader.deregisterProcessor(otherFlow);
            slowReader.onClose();
            otherReader.onClose();
        }
    }

    /**
     * The review's shutdown hang: a source whose stop() joins its own publishing thread, which waits for room in a
     * BLOCK_WITH_DEADLINE queue. The flow manager's stop (phase 2 of the server's ordered stop) refuses the waiting
     * publish first, so the source's stop returns at once rather than at the 60 s deadline.
     */
    @Test
    void shutdownWhileWaitingRefusesAndStopCompletes() throws Exception {
        var manager = new EventFlowManager();
        manager.setFeedOverload("orders",
                FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(60))));
        var source = new ThreadedSource();
        var publisher = manager.registerEventSource("orders", source);
        var full = full(2);
        bind(publisher, full, "q", "g");

        source.startPublishing("order-1");
        assertTrue(source.waiting(2_000), "the source thread is waiting for room");

        long start = System.nanoTime();
        Thread stopper = new Thread(manager::stop, "stop-event-sources");
        stopper.start();
        stopper.join(5_000);
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertFalse(stopper.isAlive(), "the flow manager's stop returned");
        assertTrue(stopMillis < 2_000, "stop completed in " + stopMillis + "ms, well inside a shutdown budget");
        PublishRefusedException refused = assertInstanceOf(PublishRefusedException.class, source.failure.get());
        assertEquals(PublishRefusedException.Reason.SHUTDOWN, refused.reason());
        assertEquals("filler-0", full.poll());
        assertEquals("filler-1", full.poll());
        assertNull(full.poll(), "the refused item reached no queue");
        assertEquals(1, publisher.refusedCount());

        // after close, a publish meeting a full blocking queue is refused at once rather than waiting
        full.offer("a");
        full.offer("b");
        Outcome late = publishOnThread(publisher, "order-2");
        assertTrue(late.await(1_000));
        assertEquals(PublishRefusedException.Reason.SHUTDOWN,
                assertInstanceOf(PublishRefusedException.class, late.error.get()).reason());
    }

    @Test
    void agentThreadNeverWaitsAndIsDowngradedCountedAndWarnedOnce() throws Exception {
        var publisher = new EventToQueuePublisher<Object>("orders");
        publisher.setOverload(FeedOverloadSettings.of(new OverloadSettings(OverloadPolicy.BLOCK_WITH_DEADLINE,
                TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.SECONDS.toNanos(30))));
        var full = full(2);
        bind(publisher, full, "q", "g");
        var warnings = captureWarnings();
        try {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            long[] elapsed = new long[1];
            Thread agent = new Thread(() -> {
                AgentThreads.enter(this);
                try {
                    long start = System.nanoTime();
                    publisher.publish("a");
                    publisher.publish("b");
                    elapsed[0] = System.nanoTime() - start;
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    AgentThreads.exit();
                }
            }, "fake-agent-thread");
            agent.start();
            agent.join(10_000);
            assertFalse(agent.isAlive(), "an agent thread never parks for the 30 s deadline");
            assertNull(failure.get(), "a downgraded publish is never refused");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(elapsed[0]) < 1_000, "spun ~1 ms per publish, never waited");
            assertEquals(2, publisher.downgradedCount());
            assertEquals(2, publisher.droppedCount(), "the drop policy applied: counted drops");
            assertEquals(0, publisher.refusedCount());
            assertEquals(2, publisher.getSequenceNumber(), "a downgraded publish is committed as before #79");
            assertEquals(1, warnings.records.stream().filter(r -> r.getMessage().contains("downgraded")).count(),
                    "warned once");
            assertTrue(publisher.getDropAudit().records().getFirst().contains("agent thread fake-agent-thread"),
                    publisher.getDropAudit().records().getFirst());
        } finally {
            warnings.close();
        }
    }

    @Test
    void refusalAfterPartialRoomLeavesNoDuplicateOnRetry() throws Exception {
        var publisher = new EventToQueuePublisher<Object>("orders");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(50))));
        var a = new OneToOneConcurrentArrayQueue<Object>(8);
        var b = full(2);
        bind(publisher, a, "a", "ga");
        bind(publisher, b, "b", "gb");

        Outcome first = publishOnThread(publisher, "order-1");
        assertTrue(first.await(5_000));
        assertInstanceOf(PublishRefusedException.class, first.error.get());

        b.poll();
        b.poll();
        Outcome retry = publishOnThread(publisher, "order-1");
        assertTrue(retry.await(5_000));
        assertNull(retry.error.get());

        assertEquals("order-1", a.poll());
        assertNull(a.poll(), "queue a holds the order once: the refused attempt left nothing behind");
        assertEquals("order-1", b.poll());
        assertNull(b.poll());
        assertEquals(1, publisher.getSequenceNumber(), "only the delivered attempt has a sequence number");
    }

    @Test
    void refusedPooledItemIsReturnedOnceAndAFreshAcquireRetriesCleanly() throws Exception {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 1);
        var publisher = new EventToQueuePublisher<Object>("orders");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(50))));
        var q = full(2);
        bind(publisher, q, "q", "g");

        Msg first = pool.acquire();
        first.payload = 42;
        Outcome out = publishOnThread(publisher, first);
        assertTrue(out.await(5_000));
        PublishRefusedException refused = assertInstanceOf(PublishRefusedException.class, out.error.get());
        assertEquals("Msg{42}", refused.item(), "the exception describes the item, it does not hand it back");
        assertEquals(0, first.tracker.currentRefCount(), "the publisher released the reference it was handed");
        assertEquals(1, pool.availableCount(), "returned to the pool exactly once");

        // the documented rule: a retry acquires a fresh instance and copies the payload
        q.poll();
        q.poll();
        Msg retry = pool.acquire();
        retry.payload = 42;
        Outcome again = publishOnThread(publisher, retry);
        assertTrue(again.await(5_000));
        assertNull(again.error.get(), "no 'already returned', no ABA");
        Object delivered = q.poll();
        assertSame(retry, delivered);
        assertEquals(42, ((Msg) delivered).payload);
        ((Msg) delivered).tracker.releaseReference(); // the consumer's release
        assertEquals(1, pool.availableCount());
    }

    @Test
    void slowSubscriberUnderAPerGroupDropOverrideDoesNotHoldUpTheOthers() throws Exception {
        var publisher = new EventToQueuePublisher<Object>("fills");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(10)))
                .withSubscriberGroup("telemetry", OverloadSettings.dropAfterSpin(Duration.ZERO)));
        var telemetry = full(2);
        var risk = new OneToOneConcurrentArrayQueue<Object>(8);
        bind(publisher, telemetry, "telemetry-q", "telemetry");
        bind(publisher, risk, "risk-q", "risk");

        Outcome out = publishOnThread(publisher, "fill-1");
        assertTrue(out.await(1_000), "the full telemetry queue does not make the fill wait");
        assertNull(out.error.get());
        assertTrue(out.elapsedMillis() < 500, "published in " + out.elapsedMillis() + "ms");
        assertEquals("fill-1", risk.poll(), "the risk group got the fill");
        assertEquals(1, publisher.droppedCount("telemetry-q"), "telemetry dropped it, counted");
        assertEquals(0, publisher.refusedCount());

        // and the risk group is still protected: with its own queue full, the fill waits, then is refused
        var riskFull = full(2);
        var publisher2 = new EventToQueuePublisher<Object>("fills");
        publisher2.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(100)))
                .withSubscriberGroup("telemetry", OverloadSettings.dropAfterSpin(Duration.ZERO)));
        bind(publisher2, new OneToOneConcurrentArrayQueue<>(8), "telemetry-q", "telemetry");
        bind(publisher2, riskFull, "risk-q", "risk");
        Outcome blocked = publishOnThread(publisher2, "fill-2");
        assertTrue(blocked.await(5_000));
        assertEquals("risk-q", assertInstanceOf(PublishRefusedException.class, blocked.error.get()).queue());
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void bind(EventToQueuePublisher<?> publisher, OneToOneConcurrentArrayQueue<Object> queue,
                             String name, String group) {
        publisher.addTargetQueue(queue, name, NoOpCountersService.INSTANCE.queueDropCounter(name), null, group, name);
    }

    private static OneToOneConcurrentArrayQueue<Object> full(int capacity) {
        var q = new OneToOneConcurrentArrayQueue<Object>(capacity);
        for (int i = 0; q.offer("filler-" + i); i++) {
        }
        return q;
    }

    private static Outcome publishOnThread(EventToQueuePublisher<Object> publisher, Object item) {
        Outcome out = new Outcome();
        Thread t = new Thread(() -> {
            long start = System.nanoTime();
            try {
                publisher.publish(item);
            } catch (Throwable e) {
                out.error.set(e);
            } finally {
                out.elapsedNanos = System.nanoTime() - start;
                out.done.countDown();
            }
        }, "non-agent-publisher");
        t.setDaemon(true);
        t.start();
        return out;
    }

    static final class Outcome {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile long elapsedNanos;

        boolean await(long millis) throws InterruptedException {
            return done.await(millis, TimeUnit.MILLISECONDS);
        }

        long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        }
    }

    static final class Msg implements PoolAware {
        final PoolTracker<Msg> tracker = new PoolTracker<>();
        volatile long payload;

        @Override
        public PoolTracker<Msg> getPoolTracker() {
            return tracker;
        }

        @Override
        public String toString() {
            return "Msg{" + payload + "}";
        }
    }

    static final class Source implements com.telamin.mongoose.service.EventSource<Object> {
        public void subscribe(EventSubscriptionKey<Object> key) {
        }

        public void unSubscribe(EventSubscriptionKey<Object> key) {
        }

        public void setEventToQueuePublisher(EventToQueuePublisher<Object> publisher) {
        }
    }

    /** A source that publishes from its own (non-agent) thread and joins it on stop, as many connectors do. */
    static final class ThreadedSource implements LifeCycleEventSource<Object> {
        private volatile EventToQueuePublisher<Object> output;
        private volatile Thread thread;
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void setEventToQueuePublisher(EventToQueuePublisher<Object> publisher) {
            this.output = publisher;
        }

        void startPublishing(Object item) {
            thread = new Thread(() -> {
                try {
                    output.publish(item);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "source-publisher");
            thread.setDaemon(true);
            thread.start();
        }

        boolean waiting(long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                Thread.State state = thread.getState();
                if (state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING) {
                    return true;
                }
                Thread.sleep(1);
            }
            return false;
        }

        @Override
        public void stop() {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(90));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class Captured extends Handler implements AutoCloseable {
        final List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Logger logger = Logger.getLogger(EventToQueuePublisher.class.getName());

        Captured() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }

    private static Captured captureWarnings() {
        return new Captured();
    }
}
