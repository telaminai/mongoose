/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.memory;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
}
