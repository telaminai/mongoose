/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.service.pool.ObjectPool;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests from the independent review of 35bdeb2 (telaminai/mongoose#79). The reviewer's tests are kept with
 * their exact assertions where they assert intended behaviour (interrupt, policy change while waiting, the pooled
 * reset contract, cache replay drops, waiter ordering). Two of them asserted the defects of findings 1 and 4; those
 * scenarios are kept with the assertions inverted to the fixed behaviour (the reviewer's assertions hold on 35bdeb2).
 * The rest cover findings 2, 5, 9 and 11.
 */
@Timeout(60)
class OverloadReviewRegressionTest {

    static OneToOneConcurrentArrayQueue<Object> full(int cap) {
        var q = new OneToOneConcurrentArrayQueue<Object>(cap);
        for (int i = 0; q.offer("f" + i); i++) { }
        return q;
    }

    static final class Out {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        volatile boolean interruptedAfter;
        volatile long nanos;
        Thread t;
    }

    static Out publishOn(EventToQueuePublisher<Object> p, Object item, String name) {
        Out o = new Out();
        o.t = new Thread(() -> {
            long s = System.nanoTime();
            try { p.publish(item); } catch (Throwable e) { o.err.set(e); }
            finally { o.interruptedAfter = Thread.currentThread().isInterrupted(); o.nanos = System.nanoTime() - s; o.done.countDown(); }
        }, name);
        o.t.setDaemon(true);
        o.t.start();
        return o;
    }

    static void awaitParked(Thread t) throws InterruptedException {
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            var s = t.getState();
            if (s == Thread.State.TIMED_WAITING || s == Thread.State.WAITING) return;
            Thread.sleep(1);
        }
        fail("never parked");
    }

    // ---- the reviewer's tests, exact assertions ----

    @Test
    void interruptRefusesAndKeepsFlag() throws Exception {
        var p = new EventToQueuePublisher<Object>("f");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(20))));
        p.addTargetQueue(full(2), "q");
        Out o = publishOn(p, "x", "pub");
        awaitParked(o.t);
        o.t.interrupt();
        assertTrue(o.done.await(2, TimeUnit.SECONDS));
        assertEquals(PublishRefusedException.Reason.INTERRUPTED, ((PublishRefusedException) o.err.get()).reason());
        assertTrue(o.interruptedAfter, "interrupt flag kept");
        assertEquals(0, p.getSequenceNumber());
    }

    @Test
    void policyChangedToDropWhileWaitingDeliversUnderDrop() throws Exception {
        var p = new EventToQueuePublisher<Object>("f");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(20))));
        p.addTargetQueue(full(2), "q");
        Out o = publishOn(p, "x", "pub");
        awaitParked(o.t);
        p.setOverload(FeedOverloadSettings.DEFAULT);
        assertTrue(o.done.await(2, TimeUnit.SECONDS));
        assertNull(o.err.get());
        assertEquals(1, p.droppedCount());
        assertEquals(1, p.getSequenceNumber());
    }

    static final class Msg implements PoolAware {
        final PoolTracker<Msg> tracker = new PoolTracker<>();
        volatile long payload;
        public PoolTracker<Msg> getPoolTracker() { return tracker; }
        public String toString() { return "Msg{" + payload + "}"; }
    }

    /** The documented contract (finding 3): the caller's instance is reset by the pool before it sees the refusal. */
    @Test
    void refusedPooledInstanceIsResetBeforeCallerSeesException() throws Exception {
        ObjectPool<Msg> pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> m.payload = -1, 1);
        try {
            var p = new EventToQueuePublisher<Object>("f");
            p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(20))));
            p.addTargetQueue(full(2), "q");
            Msg m = pool.acquire();
            m.payload = 42;
            Out o = publishOn(p, m, "pub");
            assertTrue(o.done.await(2, TimeUnit.SECONDS));
            assertInstanceOf(PublishRefusedException.class, o.err.get());
            assertEquals(-1, m.payload, "the caller's instance was reset by the pool");
        } finally {
            Pools.SHARED.remove(Msg.class);
        }
    }

    /** startComplete replays the pre-start cache with drop semantics even to a BLOCK_WITH_DEADLINE queue (finding 5,
     *  documented and warned). */
    @Test
    void startCompleteCacheReplayDropsOnBlockingFeed() {
        try (Captured log = new Captured(EventToQueuePublisher.class)) {
            var p = new EventToQueuePublisher<Object>("f");
            p.setCacheEventLog(true);
            p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(5))));
            var q = new OneToOneConcurrentArrayQueue<Object>(2);
            p.addTargetQueue(q, "q");
            for (int i = 0; i < 4; i++) p.publishOrCache("c" + i);
            p.startComplete();
            assertEquals(2, p.droppedCount());
            assertEquals(0, p.refusedCount());
            assertEquals(1, log.count("the cached event log is replayed with drop-after-spin semantics"),
                    log.messages.toString());
        }
    }

    /** Waiters are not FIFO (finding 8, documented): both complete; order across threads is undefined. */
    @Test
    void waitersAreNotFifo() throws Exception {
        int rounds = 30;
        for (int r = 0; r < rounds; r++) {
            var p = new EventToQueuePublisher<Object>("f");
            p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(5))));
            var q = full(2);
            p.addTargetQueue(q, "q");
            Out a = publishOn(p, "A", "a");
            awaitParked(a.t);
            Thread.sleep(2);
            Out b = publishOn(p, "B", "b");
            awaitParked(b.t);
            q.poll();
            long end = System.currentTimeMillis() + 2000;
            while (q.size() < 2 && System.currentTimeMillis() < end) Thread.onSpinWait();
            q.poll();
            q.poll();
            q.poll();
            assertTrue(a.done.await(3, TimeUnit.SECONDS) && b.done.await(3, TimeUnit.SECONDS));
        }
    }

    // ---- the reviewer's defect scenarios, inverted to the fixed behaviour ----

    /**
     * Finding 1. Reviewer's assertions on 35bdeb2: outer remove waited > 1000 ms, inner refused 1, downgraded 0. Fixed:
     * a publish nested in ANOTHER publisher's dispatch is downgraded, never waits, so the outer lock is not held.
     */
    @Test
    void crossPublisherNestedPublishDoesNotWaitHoldingTheOuterLock() throws Exception {
        var inner = new EventToQueuePublisher<Object>("inner");
        inner.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(1500))));
        inner.addTargetQueue(full(2), "iq");
        var outer = new EventToQueuePublisher<Object>("outer");
        outer.addTargetQueue(new OneToOneConcurrentArrayQueue<>(8), "oq");
        outer.setDataMapper(o -> { try { inner.publish(o); } catch (PublishRefusedException e) { } return o; });
        Out o = publishOn(outer, "x", "pub");
        assertTrue(o.done.await(1, TimeUnit.SECONDS), "the outer publish must not wait for the inner deadline");
        assertNull(o.err.get());
        long s = System.nanoTime();
        outer.removeTargetQueueByName("oq");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - s);
        assertTrue(ms < 500, "outer unsubscribe took " + ms + "ms");
        assertEquals(0, inner.refusedCount());
        assertEquals(1, inner.downgradedCount());
        assertEquals(1, inner.droppedCount());
    }

    /**
     * Finding 4. Reviewer's assertions on 35bdeb2: downgraded 10, dropped 0. Fixed: an agent-thread publish that
     * finds room is not a downgrade.
     */
    @Test
    void agentPublishWithRoomIsNotCountedAsDowngraded() throws Exception {
        var p = new EventToQueuePublisher<Object>("f");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(5))));
        var q = new OneToOneConcurrentArrayQueue<Object>(64);
        p.addTargetQueue(q, "q");
        try (Captured log = new Captured(EventToQueuePublisher.class)) {
            Thread t = new Thread(() -> {
                com.telamin.mongoose.control.AgentThreads.enter(this);
                try { for (int i = 0; i < 10; i++) p.publish("x" + i); } finally { com.telamin.mongoose.control.AgentThreads.exit(); }
            });
            t.start(); t.join();
            assertEquals(0, p.downgradedCount());
            assertEquals(0, p.droppedCount());
            assertEquals(10, q.size());
            assertEquals(0, log.count("downgraded"), "no warning for publishes that never had to wait");
        }
    }

    // ---- findings 2, 9, 11 ----

    /** Finding 2: a mapper publishing into its own full BLOCK feed is downgraded; it never waits holding the lock. */
    @Test
    void samePublisherNestedPublishIsDowngradedAndDoesNotWait() throws Exception {
        var p = new EventToQueuePublisher<Object>("f");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(1500))));
        p.addTargetQueue(full(2), "q");
        AtomicLong nestedNanos = new AtomicLong(-1);
        p.setDataMapper(item -> {
            if ("outer".equals(item)) {
                long s = System.nanoTime();
                p.publish("inner");
                nestedNanos.set(System.nanoTime() - s);
            }
            return item;
        });
        Out o = publishOn(p, "outer", "pub");
        assertTrue(o.done.await(5, TimeUnit.SECONDS));
        assertTrue(nestedNanos.get() >= 0, "the nested publish returned");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(nestedNanos.get()) < 500,
                "nested publish took " + TimeUnit.NANOSECONDS.toMillis(nestedNanos.get()) + "ms");
        assertEquals(1, p.downgradedCount());
        assertEquals(1, p.droppedCount(), "the nested item used the drop policy");
        assertEquals(PublishRefusedException.Reason.DEADLINE,
                assertInstanceOf(PublishRefusedException.class, o.err.get()).reason(), "the outer item still waited");
    }

    /** Finding 9: when the blocker moves to a group with a shorter deadline, that deadline applies. */
    @Test
    void deadlineIsReReadWhenTheBlockerChanges() throws Exception {
        var p = new EventToQueuePublisher<Object>("f");
        p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(20)))
                .withSubscriberGroup("b", OverloadSettings.blockWithDeadline(Duration.ofMillis(300))));
        var a = full(2);
        var b = full(2);
        p.addTargetQueue(a, "a", com.telamin.mongoose.internal.NoOpCountersService.INSTANCE.queueDropCounter("a"), null, "a", "a");
        p.addTargetQueue(b, "b", com.telamin.mongoose.internal.NoOpCountersService.INSTANCE.queueDropCounter("b"), null, "b", "b");
        Out o = publishOn(p, "x", "pub");
        awaitParked(o.t);
        Thread.sleep(100);
        a.poll(); // the first blocker (20 s deadline) gains room; b (300 ms) is now the blocker
        assertTrue(o.done.await(2, TimeUnit.SECONDS), "b's 300 ms deadline applied, not a's 20 s");
        PublishRefusedException refused = assertInstanceOf(PublishRefusedException.class, o.err.get());
        assertEquals("b", refused.queue());
        assertEquals(PublishRefusedException.Reason.DEADLINE, refused.reason());
    }

    /** Finding 11: a queue removal wakes a parked waiter explicitly (not only through the periodic poll). */
    @Test
    void queueRemovalWakesAParkedWaiter() throws Exception {
        long saved = EventToQueuePublisher.maxParkNanos;
        EventToQueuePublisher.maxParkNanos = TimeUnit.SECONDS.toNanos(10);
        try {
            var p = new EventToQueuePublisher<Object>("f");
            p.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofSeconds(30))));
            var gone = full(2);
            var stays = new OneToOneConcurrentArrayQueue<Object>(8);
            p.addTargetQueue(gone, "gone");
            p.addTargetQueue(stays, "stays");
            Out o = publishOn(p, "x", "pub");
            awaitParked(o.t);
            Thread.sleep(50); // past the 10 ms spin, into the 10 s park
            awaitParked(o.t);
            p.removeTargetQueueByName("gone");
            assertTrue(o.done.await(2, TimeUnit.SECONDS), "woken by the removal, not by the 10 s park timing out");
            assertNull(o.err.get());
            assertEquals("x", stays.poll());
        } finally {
            EventToQueuePublisher.maxParkNanos = saved;
        }
    }

    static final class Captured extends Handler implements AutoCloseable {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final Logger logger;

        Captured(Class<?> type) {
            logger = Logger.getLogger(type.getName());
            logger.addHandler(this);
        }

        long count(String text) {
            return messages.stream().filter(m -> m.contains(text)).count();
        }

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}
