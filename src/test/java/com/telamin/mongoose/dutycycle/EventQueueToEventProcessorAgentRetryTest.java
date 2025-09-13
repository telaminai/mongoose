/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.fluxtion.runtime.StaticEventProcessor;
import com.telamin.mongoose.dispatch.RetryPolicy;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class EventQueueToEventProcessorAgentRetryTest {

    @Test
    void retriesOnFailureAndEventuallySucceeds() {
        TestQueue<Object> q = new TestQueue<>();
        FailingThenSucceedStrategy strategy = new FailingThenSucceedStrategy(2); // fail twice then succeed
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(q, strategy, "retryAgent")
                .withRetryPolicy(new RetryPolicy(5, 0, 0, 1.0, java.util.Set.of(RuntimeException.class)));
        agent.registerProcessor(new NoopProcessor());

        String event = "E1";
        q.offer(event);

        int processed = agent.doWork();
        assertEquals(1, processed, "one event should be processed in batch");
        assertEquals(3, strategy.attempts, "should attempt 3 times (2 failures + 1 success)");
        assertEquals(event, strategy.lastEvent, "event should be processed eventually");
    }

    @Test
    void dropsAfterMaxRetries() {
        TestQueue<Object> q = new TestQueue<>();
        FailingThenSucceedStrategy strategy = new FailingThenSucceedStrategy(Integer.MAX_VALUE); // always fail
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(q, strategy, "retryAgent")
                .withRetryPolicy(new RetryPolicy(3, 0, 0, 1.0, java.util.Set.of(RuntimeException.class)));
        agent.registerProcessor(new NoopProcessor());

        String event = "E2";
        q.offer(event);

        int processed = agent.doWork();
        assertEquals(1, processed, "agent should advance even when dropping event");
        assertNull(strategy.lastEvent, "event should not be recorded as processed");
        assertEquals(3, strategy.attempts, "should attempt exactly max attempts");
    }

    private static class TestQueue<T> extends OneToOneConcurrentArrayQueue<T> {
        private final List<T> items = new ArrayList<>();

        public TestQueue() {
            super(16);
        }

        @Override
        public boolean offer(T item) {
            items.add(item);
            return true;
        }

        @Override
        public T poll() {
            return items.isEmpty() ? null : items.remove(0);
        }
    }

    private static class NoopProcessor implements StaticEventProcessor {
        @Override
        public void onEvent(Object event) { /* no-op */ }
    }

    private static class FailingThenSucceedStrategy implements EventToInvokeStrategy {
        private final int failuresBeforeSuccess;
        private int failures;
        private final List<StaticEventProcessor> procs = new ArrayList<>();
        int attempts;
        Object lastEvent;

        FailingThenSucceedStrategy(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public void processEvent(Object event) {
            attempts++;
            if (failures < failuresBeforeSuccess) {
                failures++;
                throw new RuntimeException("boom" + failures);
            }
            lastEvent = event;
            for (StaticEventProcessor p : procs) p.onEvent(event);
        }

        @Override
        public void processEvent(Object event, long time) {
            processEvent(event);
        }

        @Override
        public void registerProcessor(StaticEventProcessor eventProcessor) {
            procs.add(eventProcessor);
        }

        @Override
        public void deregisterProcessor(StaticEventProcessor eventProcessor) {
            procs.remove(eventProcessor);
        }

        @Override
        public int listenerCount() {
            return procs.size();
        }
    }
}
