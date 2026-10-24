package com.telamin.mongoose.dispatch;

import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A publisher called from several threads (telaminai/mongoose#51): every target queue is single-producer, so the
 * publisher must serialise its writes. The queues here are big enough never to fill, so no event may be lost.
 */
class EventToQueuePublisherConcurrencyTest {

    static final int PRODUCERS = 4;
    static final int PER_PRODUCER = 10_000;
    static final int TOTAL = PRODUCERS * PER_PRODUCER;

    @RepeatedTest(5)
    void concurrentPublishersLoseNothingInAnyQueue() throws Exception {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("concurrentFeed");
        OneToOneConcurrentArrayQueue<Object> first = new OneToOneConcurrentArrayQueue<>(1 << 17);
        OneToOneConcurrentArrayQueue<Object> second = new OneToOneConcurrentArrayQueue<>(1 << 17);
        publisher.addTargetQueue(first, "first");
        publisher.addTargetQueue(second, "second");

        runProducers(i -> publisher.publish(String.valueOf(i)));

        assertExactlyOnce(first);
        assertExactlyOnce(second);
        assertEquals(TOTAL, publisher.getSequenceNumber(), "one sequence number per publish");
    }

    @RepeatedTest(5)
    void concurrentPublishersWithACacheKeepEveryEventOnceInTheLog() throws Exception {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("cachedFeed");
        publisher.setCacheEventLog(true);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(1 << 17);
        publisher.addTargetQueue(queue, "queue");

        runProducers(i -> publisher.publish(String.valueOf(i)));

        assertExactlyOnce(queue);
        List<Object> logged = new ArrayList<>();
        publisher.getEventLog().forEach(e -> logged.add(e.data()));
        assertEquals(TOTAL, logged.size(), "every publish logged once");
        assertEquals(TOTAL, new HashSet<>(logged).size(), "no event logged twice or overwritten");
    }

    @Test
    void anItemDroppedOnAFullQueueIsCounted() {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("fullFeed");
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(4);
        publisher.addTargetQueue(queue, "full");

        for (int i = 0; i < 6; i++) {
            publisher.publish("item" + i);
        }

        assertEquals(4, queue.size(), "the queue holds its capacity");
        assertEquals(2, publisher.droppedCount(), "the two items that did not fit are counted");
    }

    /**
     * A processor unsubscribing (or being torn down) removes its queue on its own thread while a feed publishes. The
     * dispatch loop must never see the list shrink under it: an IndexOutOfBounds escaping publish reaches the agent's
     * error handler, which exits the process (C1 of the review of #51).
     */
    @RepeatedTest(3)
    void queuesAddedAndRemovedWhilePublishingNeverBreakAPublish() throws Exception {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("churnFeed");
        OneToOneConcurrentArrayQueue<Object> stable = new OneToOneConcurrentArrayQueue<>(1 << 17);
        publisher.addTargetQueue(stable, "stable");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread churn = new Thread(() -> {
            OneToOneConcurrentArrayQueue<Object> a = new OneToOneConcurrentArrayQueue<>(1 << 17);
            OneToOneConcurrentArrayQueue<Object> b = new OneToOneConcurrentArrayQueue<>(1 << 17);
            while (!Thread.currentThread().isInterrupted()) {
                publisher.addTargetQueue(a, "a");
                publisher.addTargetQueue(b, "b");
                publisher.removeTargetQueueByName("a");
                publisher.removeTargetQueueByName("b");
                a.clear();
                b.clear();
            }
        }, "churn");
        churn.start();
        try {
            for (int i = 0; i < TOTAL; i++) {
                publisher.publish(String.valueOf(i));
            }
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            churn.interrupt();
            churn.join();
        }
        assertNull(failure.get(), () -> "publish threw while queues were removed: " + failure.get());
        assertExactlyOnce(stable);
    }

    /**
     * The Lombok getters for the sequence number and the replay pointer read without the publish lock (taking it could
     * block a reader for the full-queue spin), so the fields they read must be volatile to be safe to read from another
     * thread (review of #52).
     */
    @Test
    void stateReadByLockFreeGettersIsVolatile() throws Exception {
        for (String field : new String[]{"sequenceNumber", "cacheReadPointer"}) {
            int modifiers = EventToQueuePublisher.class.getDeclaredField(field).getModifiers();
            assertTrue(java.lang.reflect.Modifier.isVolatile(modifiers), field + " is read without the lock: it must be volatile");
        }
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("getters");
        publisher.setCacheEventLog(true);
        publisher.publish("a");
        assertEquals(1, publisher.getSequenceNumber(), "getter still exists");
        assertEquals(1, publisher.getCacheReadPointer(), "getter still exists");
    }

    /**
     * Documents the cost the lock carries (review of #52): the publish lock is held through the bounded spin on a full
     * queue, so a second publisher on the same publisher, and add/removeTargetQueue, wait for that spin. The wait is
     * bounded (about 10 ms per full queue per publish), never indefinite: a remove completes once the publish in flight
     * has dropped its item.
     */
    @Test
    void aFullQueueHoldsOtherCallersForTheSpinBoundOnly() throws Exception {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("slowFeed");
        OneToOneConcurrentArrayQueue<Object> full = new OneToOneConcurrentArrayQueue<>(2);
        publisher.addTargetQueue(full, "full");
        publisher.publish("fill-1");
        publisher.publish("fill-2");

        Thread slow = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                publisher.publish("drop-" + i); // each spins ~10 ms under the lock, then drops
            }
        }, "slow-publisher");
        slow.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (publisher.droppedCount() < 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait(); // the slow publisher is now dropping, spin after spin
        }
        long start = System.nanoTime();
        publisher.removeTargetQueueByName("full");
        long removeMs = (System.nanoTime() - start) / 1_000_000;
        slow.join(5_000);

        assertTrue(removeMs < 2_000, "the remove waits only for the publish in flight, took " + removeMs + " ms");
        assertTrue(publisher.droppedCount() >= 1 && publisher.droppedCount() < 50,
                "drops stop once the queue is removed: " + publisher.droppedCount());
        assertEquals(2, full.size(), "nothing written past capacity");
    }

    static void runProducers(IntConsumer publish) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        for (int p = 0; p < PRODUCERS; p++) {
            final int base = p * PER_PRODUCER;
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < PER_PRODUCER; i++) {
                    publish.accept(base + i);
                }
            }, "producer-" + p);
            producers.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : producers) {
            t.join();
        }
    }

    static void assertExactlyOnce(OneToOneConcurrentArrayQueue<Object> queue) {
        List<Object> drained = new ArrayList<>();
        queue.drainTo(drained, Integer.MAX_VALUE);
        assertEquals(TOTAL, drained.size(), "events lost or duplicated");
        assertEquals(TOTAL, new HashSet<>(drained).size(), "events lost or overwritten");
    }
}
