/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose;

import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.audit.LogRecord;
import com.fluxtion.runtime.audit.LogRecordListener;
import com.fluxtion.runtime.lifecycle.Lifecycle;
import com.fluxtion.runtime.service.Service;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSubscriptionKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class MongooseServerTest {

    private MongooseServerConfig mongooseServerConfig;
    private MongooseServer mongooseServer;
    private TestLogRecordListener logRecordListener;
    private TestEventSource testEventSource;
    private TestService testService;
    private TestEventProcessor testEventProcessor;
    private TestIdleStrategy testIdleStrategy;

    @BeforeEach
    void setUp() {
        mongooseServerConfig = new MongooseServerConfig();
        logRecordListener = new TestLogRecordListener();
        testEventSource = new TestEventSource();
        testService = new TestService();
        testEventProcessor = new TestEventProcessor();
        testIdleStrategy = new TestIdleStrategy();

        mongooseServer = new MongooseServer(mongooseServerConfig);
    }

    @Test
    void testRegisterEventSource() {
        // Arrange
        String sourceName = "testSource";

        // Act
        mongooseServer.registerEventSource(sourceName, testEventSource);

        // Assert
        assertNotNull(testEventSource.getEventToQueuePublisher(), "EventToQueuePublisher should be set");
        assertTrue(testEventSource.getEventToQueuePublisher().toString().contains(sourceName),
                "EventToQueuePublisher should contain the source name");
    }

    @Test
    void testRegisterService() {
        // Arrange
        Service<TestService> service = new Service<>(testService, TestService.class, "testService");

        // Act
        mongooseServer.registerService(service);

        // Assert
        Map<String, Service<?>> registeredServices = mongooseServer.registeredServices();
        assertTrue(registeredServices.containsKey("testService"), "Service should be registered");
        assertSame(service, registeredServices.get("testService"), "Registered service should be the same instance");
    }

    @Test
    void testRegisterEventFlowService() {
        // Arrange
        TestEventFlowService eventFlowService = new TestEventFlowService();
        Service<TestEventFlowService> service = new Service<>(eventFlowService, TestEventFlowService.class, "testEventFlowService");

        // Act
        mongooseServer.registerService(service);

        // Assert
        assertNotNull(eventFlowService.getEventFlowManager(), "EventFlowManager should be set");
        assertEquals("testEventFlowService", eventFlowService.getServiceName(), "Service name should be set");
    }

    @Test
    void testInitAndStart() {
        // Arrange
        Service<TestService> service = new Service<>(testService, TestService.class, "testService");
        mongooseServer.registerService(service);

        // Act - Init
        mongooseServer.init();

        // Assert
        assertTrue(testService.isInitialized(), "Service should be initialized");

        // Act - Start
        mongooseServer.start();

        // Assert
        assertTrue(testService.isStarted(), "Service should be started");
        assertTrue(testService.isStartCompleted(), "Service start should be completed");
    }

    @Test
    void testStartAndStopService() {
        // Arrange
        Service<TestService> service = new Service<>(testService, TestService.class, "testService");
        mongooseServer.registerService(service);

        // Act - Start service
        mongooseServer.startService("testService");

        // Assert
        assertTrue(testService.isStarted(), "Service should be started");

        // Act - Stop service
        mongooseServer.stopService("testService");

        // Assert
        assertTrue(testService.isStopped(), "Service should be stopped");
    }

    @Test
    void testAddEventProcessor() throws Exception {
        // Arrange
        String processorName = "testProcessor";
        String groupName = "testGroup";
        Supplier<StaticEventProcessor> processorSupplier = () -> testEventProcessor;

        // Act
        mongooseServer.addEventProcessor(processorName, groupName, testIdleStrategy, processorSupplier);

        // Assert
        Map<String, Collection<NamedEventProcessor>> registeredProcessors = mongooseServer.registeredProcessors();
        assertTrue(registeredProcessors.containsKey(groupName), "Processor group should be registered");

        // We can't directly check if the processor is registered because the ComposingEventProcessorAgent is not accessible
        // But we can verify that the group exists
    }

    @Test
    void testStopProcessor() throws Exception {
        // Arrange
        String processorName = "testProcessor";
        String groupName = "testGroup";
        Supplier<StaticEventProcessor> processorSupplier = () -> testEventProcessor;
        mongooseServer.addEventProcessor(processorName, groupName, testIdleStrategy, processorSupplier);

        // Act
        mongooseServer.stopProcessor(groupName, processorName);

        // Assert
        // We can't directly check if the processor is stopped because the ComposingEventProcessorAgent is not accessible
        // But we can verify that the method doesn't throw an exception
    }

    // Test implementations

    private static class TestLogRecordListener implements LogRecordListener {
        private final List<String> records = new ArrayList<>();

        @Override
        public void processLogRecord(LogRecord logRecord) {
            records.add(logRecord.toString());
        }

        public List<String> getRecords() {
            return records;
        }
    }

    private static class TestEventSource implements EventFlowService<String> {
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

    private static class TestService implements Lifecycle {
        private boolean initialized = false;
        private boolean started = false;
        private boolean stopped = false;
        private boolean tornDown = false;
        private boolean startCompleted = false;

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

        public void startComplete() {
            startCompleted = true;
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
    }

    private static class TestEventFlowService implements EventFlowService {
        private EventFlowManager eventFlowManager;
        private String serviceName;

        @Override
        public void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName) {
            this.eventFlowManager = eventFlowManager;
            this.serviceName = serviceName;
        }

        public EventFlowManager getEventFlowManager() {
            return eventFlowManager;
        }

        public String getServiceName() {
            return serviceName;
        }

        @Override
        public void subscribe(EventSubscriptionKey eventSourceKey) {

        }

        @Override
        public void unSubscribe(EventSubscriptionKey eventSourceKey) {

        }

        @Override
        public void setEventToQueuePublisher(EventToQueuePublisher targetQueue) {

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

    private static class TestIdleStrategy implements IdleStrategy {
        private int idleCount = 0;

        @Override
        public void idle() {
            idleCount++;
        }

        @Override
        public void idle(int workCount) {
            if (workCount <= 0) {
                idleCount++;
            }
        }

        @Override
        public void reset() {
            idleCount = 0;
        }

        public int getIdleCount() {
            return idleCount;
        }
    }
}
