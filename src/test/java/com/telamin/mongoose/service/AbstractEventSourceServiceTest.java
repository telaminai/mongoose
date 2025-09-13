/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.input.SubscriptionManager;
import com.fluxtion.runtime.node.EventSubscription;
import com.telamin.mongoose.dispatch.*;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class AbstractEventSourceServiceTest {

    private TestEventSourceService testService;
    private TestEventFlowManager testEventFlowManager;
    private TestEventToQueuePublisher testEventToQueuePublisher;
    private TestSchedulerService testSchedulerService;
    private TestEventProcessor testEventProcessor;
    private TestSubscriptionManager testSubscriptionManager;

    @BeforeEach
    void setUp() {
        testEventToQueuePublisher = new TestEventToQueuePublisher();
        testEventFlowManager = new TestEventFlowManager(testEventToQueuePublisher);
        testSchedulerService = new TestSchedulerService();
        testEventProcessor = new TestEventProcessor();
        testSubscriptionManager = new TestSubscriptionManager();
        testEventProcessor.setSubscriptionManager(testSubscriptionManager);

        // Create the service with default settings
        testService = new TestEventSourceService("testService");
    }

    @Test
    void testSetEventFlowManager() {
        // Arrange
        String serviceName = "testServiceName";

        // Act
        testService.setEventFlowManager(testEventFlowManager, serviceName);

        // Assert
        assertEquals(serviceName, testService.getServiceName(), "Service name should be set");
        assertSame(testEventToQueuePublisher, testService.getOutput(), "Output should be set");
        assertNotNull(testService.getSubscriptionKey(), "Subscription key should be created");
        assertEquals(serviceName, testService.getSubscriptionKey().eventSourceKey().sourceName(),
                "Subscription key should have the correct source name");
        assertEquals(CallBackType.ON_EVENT_CALL_BACK, testService.getSubscriptionKey().callBackType(),
                "Subscription key should have the correct callback type");
    }

    @Test
    void testSetEventFlowManagerWithCustomStrategy() {
        // Arrange
        String serviceName = "testServiceName";
        TestEventToInvokeStrategy testStrategy = new TestEventToInvokeStrategy();
        TestEventSourceService serviceWithStrategy = new TestEventSourceService(
                "testService",
                CallBackType.forClass(String.class),
                () -> testStrategy);

        // Act
        serviceWithStrategy.setEventFlowManager(testEventFlowManager, serviceName);

        // Assert
        assertTrue(testEventFlowManager.getRegisteredStrategies().containsKey(CallBackType.forClass(String.class)),
                "Strategy should be registered");
        assertSame(testStrategy, testEventFlowManager.getRegisteredStrategies().get(CallBackType.forClass(String.class)).get(),
                "Registered strategy should be the same instance");
    }

    @Test
    void testSchedulerRegistration() {
        // Act
        testService.scheduler(testSchedulerService);

        // Assert
        assertSame(testSchedulerService, testService.getScheduler(), "Scheduler should be set");
    }

    @Test
    void testSubscribe() {
        // Arrange
        testService.setEventFlowManager(testEventFlowManager, "testServiceName");
        ProcessorContext.setCurrentProcessor(testEventProcessor);

        // Act
        testService.subscribe();

        // Assert
        assertTrue(testSubscriptionManager.getSubscriptions().contains(testService.getSubscriptionKey()),
                "Subscription should be added to the subscription manager");
    }

    @Test
    void testRegisterSubscriberWithBroadcastStrategy() {
        // Arrange
        testService.setEventFlowManager(testEventFlowManager, "testServiceName");
        testService.setEventWrapStrategy(EventSource.EventWrapStrategy.BROADCAST_NOWRAP);
        ProcessorContext.setCurrentProcessor(testEventProcessor);

        // Act
        testService.registerSubscriber(testEventProcessor);

        // Assert
        assertTrue(testSubscriptionManager.getSubscriptions().contains(testService.getSubscriptionKey()),
                "Subscription should be added to the subscription manager");
    }

    @Test
    void testRegisterSubscriberWithSubscriptionStrategy() {
        // Arrange
        testService.setEventFlowManager(testEventFlowManager, "testServiceName");
        testService.setEventWrapStrategy(EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP);
        ProcessorContext.setCurrentProcessor(testEventProcessor);

        // Act
        testService.registerSubscriber(testEventProcessor);

        // Assert
        assertTrue(testSubscriptionManager.getSubscriptions().isEmpty(),
                "Subscription should not be added to the subscription manager");
    }

    @Test
    void testSubscribeWithEventSubscription() {
        // Arrange
        testService.setEventFlowManager(testEventFlowManager, "testServiceName");
        ProcessorContext.setCurrentProcessor(testEventProcessor);

        // Act
        testService.subscribe();

        // Assert
        assertTrue(testSubscriptionManager.getSubscriptions().contains(testService.getSubscriptionKey()),
                "Subscription should be added to the subscription manager");
    }

    @Test
    void testUnSubscribeWithEventSubscription() {
        // Arrange
        testService.setEventFlowManager(testEventFlowManager, "testServiceName");
        ProcessorContext.setCurrentProcessor(testEventProcessor);
        testSubscriptionManager.subscribe(testService.getSubscriptionKey());

        // Act
        testSubscriptionManager.unSubscribe(testService.getSubscriptionKey());

        // Assert
        assertFalse(testSubscriptionManager.getSubscriptions().contains(testService.getSubscriptionKey()),
                "Subscription should be removed from the subscription manager");
    }

    @Test
    void testSetEventWrapStrategy() {
        // Arrange
        testService.setEventFlowManager(testEventFlowManager, "testServiceName");

        // Act
        testService.setEventWrapStrategy(EventSource.EventWrapStrategy.BROADCAST_NAMED_EVENT);

        // Assert
        assertEquals(EventSource.EventWrapStrategy.BROADCAST_NAMED_EVENT, testEventToQueuePublisher.getEventWrapStrategy(),
                "Event wrap strategy should be set on the queue publisher");
    }

    @Test
    void testSetDataMapper() {
        // Arrange
        testService.setEventFlowManager(testEventFlowManager, "testServiceName");
        Function<String, Integer> dataMapper = String::length;

        // Act
        testService.setDataMapper(dataMapper);

        // Assert
        assertSame(dataMapper, testEventToQueuePublisher.getDataMapper(),
                "Data mapper should be set on the queue publisher");
    }

    // Test implementations

    private static class TestEventSourceService extends AbstractEventSourceService<String> {
        public TestEventSourceService(String name) {
            super(name);
        }

        public TestEventSourceService(String name, CallBackType eventToInvokeType, Supplier<EventToInvokeStrategy> eventToInokeStrategySupplier) {
            super(name, eventToInvokeType, eventToInokeStrategySupplier);
        }

        public String getServiceName() {
            return serviceName;
        }

        public EventToQueuePublisher<String> getOutput() {
            return output;
        }

        public EventSubscriptionKey<String> getSubscriptionKey() {
            return subscriptionKey;
        }

        public SchedulerService getScheduler() {
            return scheduler;
        }
    }

    private static class TestEventFlowManager extends EventFlowManager {
        private final TestEventToQueuePublisher eventToQueuePublisher;
        private final List<EventSourceKey<?>> registeredSources = new ArrayList<>();
        private final List<EventSubscriptionKey<?>> subscriptions = new ArrayList<>();
        private final List<EventSubscriptionKey<?>> unsubscriptions = new ArrayList<>();
        private final java.util.Map<CallBackType, Supplier<EventToInvokeStrategy>> registeredStrategies = new java.util.HashMap<>();

        public TestEventFlowManager(TestEventToQueuePublisher eventToQueuePublisher) {
            this.eventToQueuePublisher = eventToQueuePublisher;
        }

        @Override
        public <T> EventToQueuePublisher<T> registerEventSource(String sourceName, EventSource<T> eventSource) {
            registeredSources.add(new EventSourceKey<>(sourceName));
            return (EventToQueuePublisher<T>) eventToQueuePublisher;
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
        public void registerEventMapperFactory(Supplier<EventToInvokeStrategy> eventMapper, CallBackType type) {
            registeredStrategies.put(type, eventMapper);
        }

        public List<EventSourceKey<?>> getRegisteredSources() {
            return registeredSources;
        }

        public List<EventSubscriptionKey<?>> getSubscriptions() {
            return subscriptions;
        }

        public List<EventSubscriptionKey<?>> getUnsubscriptions() {
            return unsubscriptions;
        }

        public java.util.Map<CallBackType, Supplier<EventToInvokeStrategy>> getRegisteredStrategies() {
            return registeredStrategies;
        }
    }

    private static class TestEventToQueuePublisher extends EventToQueuePublisher<String> {
        private EventSource.EventWrapStrategy eventWrapStrategy;
        private Function<String, ?> dataMapper;

        public TestEventToQueuePublisher() {
            super("test");
        }

        @Override
        public void setEventWrapStrategy(EventSource.EventWrapStrategy eventWrapStrategy) {
            this.eventWrapStrategy = eventWrapStrategy;
        }

        @Override
        public void setDataMapper(Function<String, ?> dataMapper) {
            this.dataMapper = dataMapper;
        }

        public EventSource.EventWrapStrategy getEventWrapStrategy() {
            return eventWrapStrategy;
        }

        public Function<String, ?> getDataMapper() {
            return dataMapper;
        }
    }

    private static class TestSchedulerService implements SchedulerService {

        @Override
        public long scheduleAtTime(long expireTIme, Runnable expiryAction) {
            return 0;
        }

        @Override
        public long scheduleAfterDelay(long waitTime, Runnable expiryAction) {
            return 0;
        }

        @Override
        public long milliTime() {
            return 0;
        }

        @Override
        public long microTime() {
            return 0;
        }

        @Override
        public long nanoTime() {
            return 0;
        }
    }

    private static class TestEventProcessor implements StaticEventProcessor {
        private SubscriptionManager subscriptionManager;

        @Override
        public void onEvent(Object event) {
        }

        public void setSubscriptionManager(SubscriptionManager subscriptionManager) {
            this.subscriptionManager = subscriptionManager;
        }

        @Override
        public SubscriptionManager getSubscriptionManager() {
            return subscriptionManager;
        }
    }

    private static class TestSubscriptionManager implements SubscriptionManager {
        private final List<Object> subscriptions = new ArrayList<>();

        @Override
        public void subscribe(Object subscription) {
            subscriptions.add(subscription);
        }

        @Override
        public void unSubscribe(Object subscription) {
            subscriptions.remove(subscription);
        }

        @Override
        public void subscribeToNamedFeed(EventSubscription<?> subscription) {

        }

        @Override
        public void subscribeToNamedFeed(String feedName) {

        }

        @Override
        public void unSubscribeToNamedFeed(EventSubscription<?> subscription) {

        }

        @Override
        public void unSubscribeToNamedFeed(String feedName) {

        }

        public List<Object> getSubscriptions() {
            return subscriptions;
        }
    }


    private static class TestEventToInvokeStrategy implements EventToInvokeStrategy {
        @Override
        public void processEvent(Object event) {
        }

        @Override
        public void processEvent(Object event, long time) {
        }

        @Override
        public void registerProcessor(StaticEventProcessor eventProcessor) {
        }

        @Override
        public void deregisterProcessor(StaticEventProcessor eventProcessor) {
        }

        @Override
        public int listenerCount() {
            return 0;
        }
    }
}
