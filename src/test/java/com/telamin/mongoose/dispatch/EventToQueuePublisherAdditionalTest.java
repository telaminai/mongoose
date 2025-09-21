/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorReporter;
import com.telamin.mongoose.service.error.ErrorReporting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class EventToQueuePublisherAdditionalTest {

    static class CapturingReporter implements ErrorReporter {
        final List<ErrorEvent> events = new CopyOnWriteArrayList<>();
        @Override public void addListener(com.telamin.mongoose.service.error.ErrorListener listener) { }
        @Override public void removeListener(com.telamin.mongoose.service.error.ErrorListener listener) { }
        @Override public void report(ErrorEvent event) { events.add(event); }
        @Override public List<ErrorEvent> recent(int limit) { return events; }
    }

    private CapturingReporter reporter;

    @BeforeEach
    void installReporter() {
        reporter = new CapturingReporter();
        ErrorReporting.setReporter(reporter);
    }

    @AfterEach
    void clearReporter() {
        // reset to default to avoid test interference
        ErrorReporting.setReporter(new com.telamin.mongoose.service.error.DefaultErrorReporter());
    }

    @Test
    public void dataMapperException_reportsErrorAndSkipsDispatch() {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("mapperErr");
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(q, "q1");

        // mapper that throws
        publisher.setDataMapper(new Function<>() {
            @Override public Object apply(String s) { throw new RuntimeException("boom"); }
        });

        publisher.publish("x");

        // nothing dispatched
        ArrayList<Object> drained = new ArrayList<>();
        q.drainTo(drained, 10);
        assertTrue(drained.isEmpty(), "queue should remain empty when mapper fails");

        // error captured
        assertFalse(reporter.events.isEmpty(), "error event should be reported");
        ErrorEvent evt = reporter.events.get(0);
        assertEquals(ErrorEvent.Severity.ERROR, evt.getSeverity());
        assertTrue(evt.getMessage().contains("data mapping failed"));
    }

    @Test
    public void removeTargetQueueByName_onlyRemainingGetsEvent() {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("rm");
        OneToOneConcurrentArrayQueue<Object> q1 = new OneToOneConcurrentArrayQueue<>(8);
        OneToOneConcurrentArrayQueue<Object> q2 = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(q1, "a");
        publisher.addTargetQueue(q2, "b");

        publisher.removeTargetQueueByName("a");
        publisher.publish("hello");

        ArrayList<Object> d1 = new ArrayList<>();
        ArrayList<Object> d2 = new ArrayList<>();
        q1.drainTo(d1, 10);
        q2.drainTo(d2, 10);

        assertTrue(d1.isEmpty(), "removed queue should not receive events");
        assertEquals(List.of("hello"), d2);
    }

    @Test
    public void namedEventWrapping_emitsNamedFeedEvent() {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("wrap");
        publisher.setEventWrapStrategy(EventSource.EventWrapStrategy.BROADCAST_NAMED_EVENT);
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        publisher.addTargetQueue(q, "q1");

        publisher.publish("hi");

        ArrayList<Object> drained = new ArrayList<>();
        q.drainTo(drained, 10);
        assertEquals(1, drained.size());
        Object o = drained.get(0);
        assertTrue(o instanceof NamedFeedEvent, "should be wrapped in NamedFeedEvent");
        NamedFeedEvent<?> nfe = (NamedFeedEvent<?>) o;
        assertEquals("hi", nfe.data());
    }
}
