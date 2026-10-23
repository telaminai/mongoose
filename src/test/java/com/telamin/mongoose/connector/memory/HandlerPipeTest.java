/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.memory;

import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

class HandlerPipeTest {

    @Test
    void sinkPublishesIntoSourceLifecycle() throws Exception {
        HandlerPipe<String> pipe = HandlerPipe.<String>of("handlerPipeFeed").cacheEventLog(true);

        // Inject a queue to observe dispatches
        EventToQueuePublisher<String> eventToQueue = new EventToQueuePublisher<>("handlerPipeFeed");
        OneToOneConcurrentArrayQueue<Object> targetQueue = new OneToOneConcurrentArrayQueue<>(128);
        eventToQueue.addTargetQueue(targetQueue, "outputQueue");
        pipe.getSource().setOutput(eventToQueue);

        // start the source but not complete, so publish should be cached
        pipe.getSource().start();
        pipe.sink().accept("a");
        pipe.sink().accept("b");

        ArrayList<Object> drained = new ArrayList<>();
        targetQueue.drainTo(drained, 100);
        Assertions.assertTrue(drained.isEmpty(), "No items should be dispatched before startComplete when caching");

        // startComplete should replay cached
        pipe.getSource().startComplete();
        targetQueue.drainTo(drained, 100);
        Assertions.assertEquals(List.of("a", "b"), drained.stream().map(Object::toString).collect(Collectors.toList()));

        // Post startComplete publishes should dispatch immediately
        pipe.sink().accept("c");
        drained.clear();
        targetQueue.drainTo(drained, 100);
        Assertions.assertEquals(List.of("c"), drained.stream().map(Object::toString).collect(Collectors.toList()));
    }

    /**
     * Several processors publishing to one pipe: the sink is called on each publisher's thread, and every subscriber
     * queue is single-producer, so the publishes must be serialised (telaminai/mongoose#51). The queue never fills.
     */
    @RepeatedTest(5)
    void concurrentPublishersThroughTheSinkLoseNothing() throws Exception {
        HandlerPipe<String> pipe = HandlerPipe.of("concurrentPipe");
        EventToQueuePublisher<String> eventToQueue = new EventToQueuePublisher<>("concurrentPipe");
        OneToOneConcurrentArrayQueue<Object> targetQueue = new OneToOneConcurrentArrayQueue<>(1 << 17);
        eventToQueue.addTargetQueue(targetQueue, "subscriberQueue");
        pipe.getSource().setOutput(eventToQueue);
        pipe.getSource().start();
        pipe.getSource().startComplete();

        int producers = 4;
        int perProducer = 10_000;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int id = p;
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perProducer; i++) {
                    pipe.sink().accept(id + ":" + i);
                }
            }, "publisher-" + p);
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }

        List<Object> drained = new ArrayList<>();
        targetQueue.drainTo(drained, Integer.MAX_VALUE);
        Assertions.assertEquals(producers * perProducer, drained.size(), "events lost with concurrent publishers");
        Assertions.assertEquals(producers * perProducer, new HashSet<>(drained).size(), "events overwritten");
    }
}
