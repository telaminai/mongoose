/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.connector.memory;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The event-log cache holds what was published before {@code startComplete}, to replay it to subscribers; after
 * {@code startComplete} it must stop growing, or a long-lived cached feed leaks memory in proportion to its traffic
 * (telaminai/mongoose#59).
 */
class EventLogAfterStartCompleteTest {

    @Test
    void logStopsGrowingAfterStartComplete_andPreStartEventsReplayOnce() throws Exception {
        InMemoryEventSource<String> src = new InMemoryEventSource<>();
        src.setCacheEventLog(true);
        EventToQueuePublisher<String> output = new EventToQueuePublisher<>("cachedFeed");
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(4096);
        output.addTargetQueue(queue, "subscriber");
        src.setOutput(output);

        src.start();
        src.offer("pre-1");
        src.offer("pre-2");
        src.doWork();
        assertEquals(List.of("pre-1", "pre-2"), logData(output));
        assertTrue(queue.isEmpty(), "nothing published before startComplete");

        src.startComplete();
        assertEquals(List.of("pre-1", "pre-2"), drain(queue), "pre-start events replayed at startComplete");

        final int n = 1_000;
        for (int i = 0; i < n; i++) {
            src.offer("post-" + i);
        }
        src.doWork();
        for (int i = 0; i < n; i++) {
            src.publishNow("now-" + i);
        }

        List<Object> delivered = drain(queue);
        assertEquals(2 * n, delivered.size(), "every post-start event delivered once");
        assertFalse(delivered.contains("pre-1") || delivered.contains("pre-2"), "pre-start events replayed only once");
        assertEquals(List.of("pre-1", "pre-2"), logData(output), "the log does not grow after startComplete");
        assertEquals(2, src.eventLog().length);
    }

    @Test
    void publisherStartCompleteStopsTheLog() {
        EventToQueuePublisher<String> output = new EventToQueuePublisher<>("cachedPublisher");
        output.setCacheEventLog(true);
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(256);
        output.cache("pre");
        output.addTargetQueue(queue, "subscriber");

        output.startComplete();
        assertEquals(List.of("pre"), drain(queue));
        for (int i = 0; i < 100; i++) {
            output.publish("post-" + i);
        }
        assertEquals(100, drain(queue).size());
        assertEquals(List.of("pre"), logData(output));

        // an explicit cache() after start is still kept, and replayed on the next publish
        output.cache("late");
        output.publish("next");
        assertEquals(List.of("late", "next"), drain(queue));
        assertEquals(List.of("pre", "late"), logData(output));
    }

    private static List<Object> logData(EventToQueuePublisher<?> publisher) {
        return publisher.getEventLog().stream().map(NamedFeedEvent::data).map(Object.class::cast).toList();
    }

    private static List<Object> drain(OneToOneConcurrentArrayQueue<Object> queue) {
        List<Object> drained = new ArrayList<>();
        queue.drainTo(drained, Integer.MAX_VALUE);
        return drained;
    }
}
