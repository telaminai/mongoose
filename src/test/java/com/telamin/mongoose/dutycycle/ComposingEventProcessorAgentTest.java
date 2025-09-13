/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.lifecycle.Lifecycle;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class ComposingEventProcessorAgentTest {

    private ComposingEventProcessorAgent composingEventProcessorAgent;
    private TestEventFlowManager testEventFlowManager;
    private TestMongooseServer testFluxtionServer;
    private TestDeadWheelScheduler testDeadWheelScheduler;
    private ConcurrentHashMap<String, Service<?>> registeredServices;
    private TestEventProcessor testEventProcessor;
    private NamedEventProcessor testNamedEventProcessor;
    private TestEventQueueToEventProcessor testEventQueueToEventProcessor;

    @BeforeEach
    void setUp() {
        testEventFlowManager = new TestEventFlowManager();
        testFluxtionServer = new TestMongooseServer();
        testDeadWheelScheduler = new TestDeadWheelScheduler();
        registeredServices = new ConcurrentHashMap<>();
        testEventProcessor = new TestEventProcessor();
        testNamedEventProcessor = new NamedEventProcessor("testProcessor", testEventProcessor);
        testEventQueueToEventProcessor = new TestEventQueueToEventProcessor();

        // Create the agent with test dependencies
        composingEventProcessorAgent = new ComposingEventProcessorAgent(
                "testAgent",
                testEventFlowManager,
                testFluxtionServer,
                testDeadWheelScheduler,
                registeredServices
        );
    }

    @Test
    void testAddNamedEventProcessor() throws Exception {
        // Arrange
        Supplier<NamedEventProcessor> processorSupplier = () -> testNamedEventProcessor;

        // Act
        composingEventProcessorAgent.addNamedEventProcessor(processorSupplier);
        composingEventProcessorAgent.onStart();

        // Assert
        Collection<NamedEventProcessor> processors = composingEventProcessorAgent.registeredEventProcessors();
        assertEquals(1, processors.size(), "Should have one registered processor");
        assertTrue(processors.contains(testNamedEventProcessor), "Should contain the test processor");
        assertTrue(testEventProcessor.isStarted(), "Processor should be started");
        assertTrue(testEventProcessor.isStartCompleted(), "Processor start should be completed");
    }

    @Test
    void testRemoveEventProcessorByName() throws Exception {
        // Arrange
        Supplier<NamedEventProcessor> processorSupplier = () -> testNamedEventProcessor;
        composingEventProcessorAgent.addNamedEventProcessor(processorSupplier);
        composingEventProcessorAgent.onStart();

        // Act
        composingEventProcessorAgent.removeEventProcessorByName("testProcessor");
        composingEventProcessorAgent.doWork(); // This triggers the check for stopped processors

        // Assert
        Collection<NamedEventProcessor> processors = composingEventProcessorAgent.registeredEventProcessors();
        assertEquals(0, processors.size(), "Should have no registered processors");
        assertTrue(testEventProcessor.isStopped(), "Processor should be stopped");
        assertTrue(testEventProcessor.isTornDown(), "Processor should be torn down");
    }

    @Test
    void testSubscribe() {
        // Arrange
        EventSourceKey<String> sourceKey = new EventSourceKey<>("testSource");
        EventSubscriptionKey<String> subscriptionKey = new EventSubscriptionKey<>(
                sourceKey, CallBackType.ON_EVENT_CALL_BACK);
        testEventFlowManager.setMappingAgent(testEventQueueToEventProcessor);

        // Act
        composingEventProcessorAgent.subscribe(testEventProcessor, subscriptionKey);

        // Assert
        assertTrue(testEventFlowManager.getSubscriptions().contains(subscriptionKey),
                "Subscription should be registered with EventFlowManager");
        assertTrue(testEventQueueToEventProcessor.getRegisteredProcessors().contains(testEventProcessor),
                "Processor should be registered with EventQueueToEventProcessor");
    }

    @Test
    void testUnSubscribe() {
        // Arrange
        EventSourceKey<String> sourceKey = new EventSourceKey<>("testSource");
        EventSubscriptionKey<String> subscriptionKey = new EventSubscriptionKey<>(
                sourceKey, CallBackType.ON_EVENT_CALL_BACK);
        testEventFlowManager.setMappingAgent(testEventQueueToEventProcessor);
        composingEventProcessorAgent.subscribe(testEventProcessor, subscriptionKey);

        // Act
        composingEventProcessorAgent.unSubscribe(testEventProcessor, subscriptionKey);

        // Assert
        assertTrue(testEventFlowManager.getUnsubscriptions().contains(subscriptionKey),
                "Unsubscription should be registered with EventFlowManager");
        assertFalse(testEventQueueToEventProcessor.getRegisteredProcessors().contains(testEventProcessor),
                "Processor should be deregistered from EventQueueToEventProcessor");
    }

    @Test
    void testRemoveAllSubscriptions() {
        // Arrange
        EventSourceKey<String> sourceKey1 = new EventSourceKey<>("testSource1");
        EventSubscriptionKey<String> subscriptionKey1 = new EventSubscriptionKey<>(
                sourceKey1, CallBackType.ON_EVENT_CALL_BACK);
        EventSourceKey<Integer> sourceKey2 = new EventSourceKey<>("testSource2");
        EventSubscriptionKey<Integer> subscriptionKey2 = new EventSubscriptionKey<>(
                sourceKey2, CallBackType.ON_EVENT_CALL_BACK);
        testEventFlowManager.setMappingAgent(testEventQueueToEventProcessor);
        composingEventProcessorAgent.subscribe(testEventProcessor, subscriptionKey1);
        composingEventProcessorAgent.subscribe(testEventProcessor, subscriptionKey2);

        // Act
        composingEventProcessorAgent.removeAllSubscriptions(testEventProcessor);

        // Assert
        assertFalse(testEventQueueToEventProcessor.getRegisteredProcessors().contains(testEventProcessor),
                "Processor should be deregistered from all EventQueueToEventProcessors");
    }

    @Test
    void testIsProcessorRegistered() throws Exception {
        // Arrange
        Supplier<NamedEventProcessor> processorSupplier = () -> testNamedEventProcessor;
        composingEventProcessorAgent.addNamedEventProcessor(processorSupplier);
        composingEventProcessorAgent.onStart();

        // Act & Assert
        assertTrue(composingEventProcessorAgent.isProcessorRegistered("testProcessor"),
                "Processor should be registered");
        assertFalse(composingEventProcessorAgent.isProcessorRegistered("nonExistentProcessor"),
                "Non-existent processor should not be registered");
    }

    // Test implementations

    private static class TestEventFlowManager extends EventFlowManager {
        private final List<EventSubscriptionKey<?>> subscriptions = new ArrayList<>();
        private final List<EventSubscriptionKey<?>> unsubscriptions = new ArrayList<>();
        private EventQueueToEventProcessor mappingAgent;

        public void setMappingAgent(EventQueueToEventProcessor mappingAgent) {
            this.mappingAgent = mappingAgent;
        }

        @Override
        public void subscribe(EventSubscriptionKey<?> subscriptionKey) {
            subscriptions.add(subscriptionKey);
        }

        @Override
        public void unSubscribe(EventSubscriptionKey<?> subscriptionKey) {
            unsubscriptions.add(subscriptionKey);
        }

        @Override
        public <T> EventQueueToEventProcessor getMappingAgent(EventSubscriptionKey<T> subscriptionKey, Agent subscriber) {
            return mappingAgent;
        }

        public List<EventSubscriptionKey<?>> getSubscriptions() {
            return subscriptions;
        }

        public List<EventSubscriptionKey<?>> getUnsubscriptions() {
            return unsubscriptions;
        }
    }

    private static class TestMongooseServer extends MongooseServer {
        public TestMongooseServer() {
            super(null);
        }
    }

    private static class TestDeadWheelScheduler extends DeadWheelScheduler {
        public TestDeadWheelScheduler() {
            super();
        }
    }

    private static class TestEventProcessor implements StaticEventProcessor, Lifecycle {
        private boolean initialized = false;
        private boolean started = false;
        private boolean stopped = false;
        private boolean tornDown = false;
        private boolean startCompleted = false;
        private final List<Service<?>> registeredServices = new ArrayList<>();
        private final List<Object> eventFeeds = new ArrayList<>();

        @Override
        public void onEvent(Object event) {
            // Do nothing
        }

        @Override
        public void init() {
            initialized = true;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void tearDown() {
            tornDown = true;
        }

        @Override
        public void startComplete() {
            startCompleted = true;
        }

        @Override
        public void registerService(Service<?> service) {
            registeredServices.add(service);
        }

        @Override
        public void addEventFeed(com.fluxtion.runtime.input.EventFeed eventFeed) {
            eventFeeds.add(eventFeed);
        }

        public boolean isInitialized() {
            return initialized;
        }

        public boolean isStarted() {
            return started;
        }

        public boolean isStopped() {
            return stopped;
        }

        public boolean isTornDown() {
            return tornDown;
        }

        public boolean isStartCompleted() {
            return startCompleted;
        }

        public List<Service<?>> getRegisteredServices() {
            return registeredServices;
        }

        public List<Object> getEventFeeds() {
            return eventFeeds;
        }
    }

    // Using the actual NamedEventProcessor record instead of a custom implementation

    private static class TestEventQueueToEventProcessor implements EventQueueToEventProcessor {
        private final List<StaticEventProcessor> registeredProcessors = new ArrayList<>();
        private final String name = "testEventQueueProcessor";

        @Override
        public int registerProcessor(StaticEventProcessor processor) {
            registeredProcessors.add(processor);
            return registeredProcessors.size();
        }

        @Override
        public int deregisterProcessor(StaticEventProcessor processor) {
            registeredProcessors.remove(processor);
            return registeredProcessors.size();
        }

        @Override
        public int doWork() throws Exception {
            return 0;
        }

        @Override
        public int listenerCount() {
            return registeredProcessors.size();
        }

        @Override
        public String roleName() {
            return name;
        }

        public List<StaticEventProcessor> getRegisteredProcessors() {
            return registeredProcessors;
        }
    }
}
