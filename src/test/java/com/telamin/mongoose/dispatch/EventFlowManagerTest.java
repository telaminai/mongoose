/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import com.fluxtion.runtime.StaticEventProcessor;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessor;
import com.telamin.mongoose.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class EventFlowManagerTest {

    private EventFlowManager eventFlowManager;
    private TestEventSource testEventSource;
    private TestAgent testAgent;
    private TestEventProcessor testEventProcessor;
    private TestEventToInvokeStrategy testEventToInvokeStrategy;
    private TestLifeCycleEventSource testLifeCycleEventSource;

    @BeforeEach
    void setUp() {
        eventFlowManager = new EventFlowManager();
        testEventSource = new TestEventSource();
        testAgent = new TestAgent();
        testEventProcessor = new TestEventProcessor();
        testEventToInvokeStrategy = new TestEventToInvokeStrategy();
        testLifeCycleEventSource = new TestLifeCycleEventSource();
    }

    // Test implementations of interfaces
    private static class TestEventSource implements EventSource<String> {
        private EventToQueuePublisher<String> eventToQueuePublisher;
        private final List<EventSubscriptionKey<String>> subscriptions = new ArrayList<>();

        @Override
        public void subscribe(EventSubscriptionKey<String> eventSourceKey) {
            subscriptions.add(eventSourceKey);
        }

        @Override
        public void unSubscribe(EventSubscriptionKey<String> eventSourceKey) {
            subscriptions.remove(eventSourceKey);
        }

        @Override
        public void setEventToQueuePublisher(EventToQueuePublisher<String> targetQueue) {
            this.eventToQueuePublisher = targetQueue;
        }

        public EventToQueuePublisher<String> getEventToQueuePublisher() {
            return eventToQueuePublisher;
        }

        public List<EventSubscriptionKey<String>> getSubscriptions() {
            return subscriptions;
        }
    }

    private static class TestLifeCycleEventSource extends TestEventSource implements LifeCycleEventSource<String> {
        private boolean initialized = false;
        private boolean started = false;
        private boolean tornDown = false;

        @Override
        public void init() {
            initialized = true;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void tearDown() {
            tornDown = true;
        }

        public boolean isInitialized() {
            return initialized;
        }

        public boolean isStarted() {
            return started;
        }

        public boolean isTornDown() {
            return tornDown;
        }

        @Override
        public void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName) {

        }
    }

    private static class TestAgent implements Agent {
        private String roleName = "testAgent";

        @Override
        public int doWork() {
            return 0;
        }

        @Override
        public String roleName() {
            return roleName;
        }

        public void setRoleName(String roleName) {
            this.roleName = roleName;
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

    private static class TestEventToInvokeStrategy implements EventToInvokeStrategy {
        private final List<StaticEventProcessor> processors = new ArrayList<>();
        private final List<Object> processedEvents = new ArrayList<>();

        @Override
        public void processEvent(Object event) {
            processedEvents.add(event);
            for (StaticEventProcessor processor : processors) {
                processor.onEvent(event);
            }
        }

        @Override
        public void processEvent(Object event, long time) {
            processedEvents.add(event);
            for (StaticEventProcessor processor : processors) {
                processor.onEvent(event);
            }
        }

        @Override
        public void registerProcessor(StaticEventProcessor eventProcessor) {
            processors.add(eventProcessor);
        }

        @Override
        public void deregisterProcessor(StaticEventProcessor eventProcessor) {
            processors.remove(eventProcessor);
        }

        @Override
        public int listenerCount() {
            return processors.size();
        }

        public List<Object> getProcessedEvents() {
            return processedEvents;
        }

        public List<StaticEventProcessor> getProcessors() {
            return processors;
        }
    }

    @Test
    void testRegisterEventSource() {
        // Arrange
        String sourceName = "testSource";

        // Act
        EventToQueuePublisher<String> result = eventFlowManager.registerEventSource(sourceName, testEventSource);

        // Assert
        assertNotNull(result, "EventToQueuePublisher should not be null");
        assertSame(result, testEventSource.getEventToQueuePublisher(), "EventToQueuePublisher should be set on the event source");
        // We can't directly check the source name as there's no getter, but we can verify it's working by using it
        assertTrue(result.toString().contains(sourceName), "Source name should be included in toString output");
    }

    @Test
    void testRegisterEventMapperFactory() {
        // Arrange
        CallBackType callBackType = CallBackType.ON_EVENT_CALL_BACK;
        Supplier<EventToInvokeStrategy> eventMapperSupplier = () -> testEventToInvokeStrategy;

        // Act
        eventFlowManager.registerEventMapperFactory(eventMapperSupplier, callBackType);

        // Act again with a different callback type
        CallBackType customCallBackType = CallBackType.forClass(String.class);
        eventFlowManager.registerEventMapperFactory(eventMapperSupplier, customCallBackType);

        // Assert - we can't directly verify the internal map, but we can test functionality
        // by using getMappingAgent which uses the registered factory
        EventSourceKey<String> eventSourceKey = new EventSourceKey<>("testSource");
        eventFlowManager.registerEventSource("testSource", testEventSource);

        // This should throw an exception if the factory wasn't registered
        assertDoesNotThrow(() -> {
            eventFlowManager.getMappingAgent(eventSourceKey, callBackType, testAgent);
        });

        assertDoesNotThrow(() -> {
            eventFlowManager.getMappingAgent(eventSourceKey, customCallBackType, testAgent);
        });
    }

    @Test
    void testSubscribeAndUnsubscribe() {
        // Arrange
        String sourceName = "testSource";
        EventSourceKey<String> eventSourceKey = new EventSourceKey<>(sourceName);
        EventSubscriptionKey<String> subscriptionKey = new EventSubscriptionKey<>(
                eventSourceKey, CallBackType.ON_EVENT_CALL_BACK);

        eventFlowManager.registerEventSource(sourceName, testEventSource);

        // Act - Subscribe
        eventFlowManager.subscribe(subscriptionKey);

        // Assert
        assertTrue(testEventSource.getSubscriptions().contains(subscriptionKey),
                "Subscription should be added to the event source");

        // Act - Unsubscribe
        eventFlowManager.unSubscribe(subscriptionKey);

        // Assert
        assertFalse(testEventSource.getSubscriptions().contains(subscriptionKey),
                "Subscription should be removed from the event source");
    }

    @Test
    void testGetMappingAgent() {
        // Arrange
        String sourceName = "testSource";
        EventSourceKey<String> eventSourceKey = new EventSourceKey<>(sourceName);
        CallBackType callBackType = CallBackType.ON_EVENT_CALL_BACK;

        eventFlowManager.registerEventSource(sourceName, testEventSource);

        // Act
        EventQueueToEventProcessor result = eventFlowManager.getMappingAgent(eventSourceKey, callBackType, testAgent);

        // Assert
        assertNotNull(result, "EventQueueToEventProcessor should not be null");
        assertEquals(testAgent.roleName() + "/" + sourceName + "/" + callBackType.name(), result.roleName(),
                "Role name should be constructed correctly");
    }

    @Test
    void testGetMappingAgentWithSubscriptionKey() {
        // Arrange
        String sourceName = "testSource";
        EventSourceKey<String> eventSourceKey = new EventSourceKey<>(sourceName);
        CallBackType callBackType = CallBackType.ON_EVENT_CALL_BACK;
        EventSubscriptionKey<String> subscriptionKey = new EventSubscriptionKey<>(eventSourceKey, callBackType);

        eventFlowManager.registerEventSource(sourceName, testEventSource);

        // Act
        EventQueueToEventProcessor result = eventFlowManager.getMappingAgent(subscriptionKey, testAgent);

        // Assert
        assertNotNull(result, "EventQueueToEventProcessor should not be null");
        assertEquals(testAgent.roleName() + "/" + sourceName + "/" + callBackType.name(), result.roleName(),
                "Role name should be constructed correctly");
    }

    @Test
    void testInitAndStart() {
        // Arrange
        String sourceName = "testSource";
        eventFlowManager.registerEventSource(sourceName, testLifeCycleEventSource);

        // Act - Init
        eventFlowManager.init();

        // Assert
        assertTrue(testLifeCycleEventSource.isInitialized(), "LifeCycleEventSource should be initialized");

        // Act - Start
        eventFlowManager.start();

        // Assert
        assertTrue(testLifeCycleEventSource.isStarted(), "LifeCycleEventSource should be started");
    }

    @Test
    void testRegisterEventSink() {
        // Arrange
        String sourceName = "testSource";
        EventSourceKey<String> eventSourceKey = new EventSourceKey<>(sourceName);
        Object sinkReader = new Object();

        // Act
        ManyToOneConcurrentArrayQueue<String> result = eventFlowManager.registerEventSink(eventSourceKey, sinkReader);

        // Assert
        assertNotNull(result, "ManyToOneConcurrentArrayQueue should not be null");
    }

    @Test
    void testCurrentProcessor() {
        // Act - Set current processor
        ProcessorContext.setCurrentProcessor(testEventProcessor);

        // Assert
        assertSame(testEventProcessor, ProcessorContext.currentProcessor(),
                "Current processor should be the one we set");

        // Act - Remove current processor
        ProcessorContext.removeCurrentProcessor();

        // Assert
        assertNull(ProcessorContext.currentProcessor(),
                "Current processor should be null after removal");
    }

    @Test
    void testExceptionHandling() {
        // Test null arguments
        assertThrows(NullPointerException.class, () -> {
            eventFlowManager.registerEventSource("testSource", null);
        });

        assertThrows(NullPointerException.class, () -> {
            eventFlowManager.subscribe(null);
        });

        assertThrows(NullPointerException.class, () -> {
            eventFlowManager.unSubscribe(null);
        });

        assertThrows(NullPointerException.class, () -> {
            eventFlowManager.registerEventMapperFactory(null, CallBackType.ON_EVENT_CALL_BACK);
        });

        assertThrows(NullPointerException.class, () -> {
            eventFlowManager.registerEventMapperFactory(() -> testEventToInvokeStrategy, (CallBackType) null);
        });
    }
}
