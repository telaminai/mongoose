/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.event.BroadcastEvent;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mock class for testing purposes only
 */
class ReplayRecord {
    private final Object event;
    private final long wallClockTime;

    public ReplayRecord(Object event, long wallClockTime) {
        this.event = event;
        this.wallClockTime = wallClockTime;
    }

    public Object getEvent() {
        return event;
    }

    public long getWallClockTime() {
        return wallClockTime;
    }
}

public class EventQueueToEventProcessorAgentTest {

    private EventQueueToEventProcessorAgent eventQueueToEventProcessorAgent;
    private TestOneToOneConcurrentArrayQueue<Object> testInputQueue;
    private TestEventToInvokeStrategy testEventToInvokeStrategy;
    private TestEventProcessor testEventProcessor;

    @BeforeEach
    void setUp() {
        testInputQueue = new TestOneToOneConcurrentArrayQueue<>();
        testEventToInvokeStrategy = new TestEventToInvokeStrategy();
        testEventProcessor = new TestEventProcessor();

        // Create the agent with test dependencies
        eventQueueToEventProcessorAgent = new EventQueueToEventProcessorAgent(
                testInputQueue,
                testEventToInvokeStrategy,
                "testAgent"
        );
    }

    @Test
    void testRegisterProcessor() {
        // Act
        int result = eventQueueToEventProcessorAgent.registerProcessor(testEventProcessor);

        // Assert
        assertEquals(1, result, "Should return the listener count");
        assertTrue(testEventToInvokeStrategy.getRegisteredProcessors().contains(testEventProcessor),
                "Processor should be registered with the strategy");
    }

    @Test
    void testDeregisterProcessor() {
        // Arrange
        eventQueueToEventProcessorAgent.registerProcessor(testEventProcessor);

        // Act
        int result = eventQueueToEventProcessorAgent.deregisterProcessor(testEventProcessor);

        // Assert
        assertEquals(0, result, "Should return the listener count");
        assertFalse(testEventToInvokeStrategy.getRegisteredProcessors().contains(testEventProcessor),
                "Processor should be deregistered from the strategy");
    }

    @Test
    void testDoWorkWithRegularEvent() {
        // Arrange
        String testEvent = "testEvent";
        testInputQueue.offer(testEvent);
        eventQueueToEventProcessorAgent.registerProcessor(testEventProcessor);

        // Act
        int result = eventQueueToEventProcessorAgent.doWork();

        // Assert
        assertEquals(1, result, "Should return 1 for processed event");
        assertEquals(testEvent, testEventToInvokeStrategy.getLastProcessedEvent(),
                "Event should be processed by the strategy");
    }

    @Test
    void testDoWorkWithBroadcastEvent() {
        // Arrange
        String testEvent = "testEvent";
        BroadcastEvent broadcastEvent = new BroadcastEvent(testEvent);
        testInputQueue.offer(broadcastEvent);
        eventQueueToEventProcessorAgent.registerProcessor(testEventProcessor);

        // Act
        int result = eventQueueToEventProcessorAgent.doWork();

        // Assert
        assertEquals(1, result, "Should return 1 for processed event");
        assertEquals(testEvent, testEventToInvokeStrategy.getLastProcessedEvent(),
                "Event should be processed by the strategy");
    }

    @Test
    void testDoWorkWithReplayRecord() {
        // Arrange
        String testEvent = "testEvent";
        long testTime = 123456789L;
        ReplayRecord replayRecord = new ReplayRecord(testEvent, testTime);
        testInputQueue.offer(replayRecord);
        eventQueueToEventProcessorAgent.registerProcessor(testEventProcessor);

        // Act
        int result = eventQueueToEventProcessorAgent.doWork();

        // Assert
        assertEquals(1, result, "Should return 1 for processed event");
        assertEquals(testEvent, testEventToInvokeStrategy.getLastProcessedEvent(),
                "Event should be processed by the strategy");
        assertEquals(testTime, testEventToInvokeStrategy.getLastProcessedTime(),
                "Time should be passed to the strategy");
    }

    @Test
    void testDoWorkWithNoEvent() {
        // Act
        int result = eventQueueToEventProcessorAgent.doWork();

        // Assert
        assertEquals(0, result, "Should return 0 for no processed event");
        assertNull(testEventToInvokeStrategy.getLastProcessedEvent(),
                "No event should be processed");
    }

    @Test
    void testListenerCount() {
        // Arrange
        eventQueueToEventProcessorAgent.registerProcessor(testEventProcessor);
        TestEventProcessor anotherProcessor = new TestEventProcessor();
        eventQueueToEventProcessorAgent.registerProcessor(anotherProcessor);

        // Act
        int result = eventQueueToEventProcessorAgent.listenerCount();

        // Assert
        assertEquals(2, result, "Should return the correct listener count");
    }

    @Test
    void testRoleName() {
        // Act
        String result = eventQueueToEventProcessorAgent.roleName();

        // Assert
        assertEquals("testAgent", result, "Should return the agent name");
    }

    // Test implementations

    private static class TestOneToOneConcurrentArrayQueue<T> extends OneToOneConcurrentArrayQueue<T> {
        private final List<T> items = new ArrayList<>();

        public TestOneToOneConcurrentArrayQueue() {
            super(100);
        }

        @Override
        public boolean offer(T item) {
            items.add(item);
            return true;
        }

        @Override
        public T poll() {
            if (items.isEmpty()) {
                return null;
            }
            return items.remove(0);
        }
    }

    private static class TestEventToInvokeStrategy implements EventToInvokeStrategy {
        private final List<StaticEventProcessor> registeredProcessors = new ArrayList<>();
        private Object lastProcessedEvent;
        private long lastProcessedTime;

        @Override
        public void processEvent(Object event) {
            // Special handling for our mock ReplayRecord
            if (event instanceof ReplayRecord replayRecord) {
                this.lastProcessedEvent = replayRecord.getEvent();
                this.lastProcessedTime = replayRecord.getWallClockTime();
                for (StaticEventProcessor processor : registeredProcessors) {
                    processor.onEvent(replayRecord.getEvent());
                }
                return;
            }

            this.lastProcessedEvent = event;
            for (StaticEventProcessor processor : registeredProcessors) {
                processor.onEvent(event);
            }
        }

        @Override
        public void processEvent(Object event, long time) {
            this.lastProcessedEvent = event;
            this.lastProcessedTime = time;
            for (StaticEventProcessor processor : registeredProcessors) {
                processor.onEvent(event);
            }
        }

        @Override
        public void registerProcessor(StaticEventProcessor eventProcessor) {
            registeredProcessors.add(eventProcessor);
        }

        @Override
        public void deregisterProcessor(StaticEventProcessor eventProcessor) {
            registeredProcessors.remove(eventProcessor);
        }

        @Override
        public int listenerCount() {
            return registeredProcessors.size();
        }

        public List<StaticEventProcessor> getRegisteredProcessors() {
            return registeredProcessors;
        }

        public Object getLastProcessedEvent() {
            return lastProcessedEvent;
        }

        public long getLastProcessedTime() {
            return lastProcessedTime;
        }
    }

    private static class TestEventProcessor implements StaticEventProcessor {
        private final List<Object> receivedEvents = new ArrayList<>();

        @Override
        public void onEvent(Object event) {
            receivedEvents.add(event);
        }

        public List<Object> getReceivedEvents() {
            return receivedEvents;
        }
    }
}
