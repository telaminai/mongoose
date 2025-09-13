/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.memory;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class InMemoryEventSourceTest {

    @Test
    void testOffer_cacheThenPublish_andEventLog() throws Exception {
        // Arrange
        InMemoryEventSource<String> src = new InMemoryEventSource<>();
        src.setCacheEventLog(true);

        // Inject target queue via publisher
        EventToQueuePublisher<String> eventToQueue = new EventToQueuePublisher<>("inMemoryEventFeed");
        OneToOneConcurrentArrayQueue<Object> targetQueue = new OneToOneConcurrentArrayQueue<>(128);
        eventToQueue.addTargetQueue(targetQueue, "outputQueue");
        src.setOutput(eventToQueue);

        // Pre-start: offer items to be cached
        src.offer("item 1");
        src.offer("item 2");

        // Start lifecycle - before startComplete, doWork should cache items
        src.start();
        int cached = src.doWork();
        Assertions.assertEquals(2, cached);

        // startComplete should replay cached eventLog to queues
        src.startComplete();

        ArrayList<Object> drained = new ArrayList<>();
        targetQueue.drainTo(drained, 100);
        Assertions.assertIterableEquals(List.of("item 1", "item 2"), drained.stream().map(Object::toString).collect(Collectors.toList()));

        // Append more and publish via doWork
        src.offer("item 3");
        src.offer("item 4");

        // No new items until doWork
        drained.clear();
        targetQueue.drainTo(drained, 100);
        Assertions.assertTrue(drained.isEmpty());

        int published = src.doWork();
        Assertions.assertEquals(2, published);
        targetQueue.drainTo(drained, 100);
        Assertions.assertIterableEquals(List.of("item 3", "item 4"), drained.stream().map(Object::toString).collect(Collectors.toList()));

        // Verify event log contains all items when cache was enabled
        List<String> eventLogData = eventToQueue.getEventLog()
                .stream().map(NamedFeedEvent::data).map(Object::toString).collect(Collectors.toList());
        Assertions.assertIterableEquals(List.of("item 1", "item 2", "item 3", "item 4"), eventLogData);
    }
}
