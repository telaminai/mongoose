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
