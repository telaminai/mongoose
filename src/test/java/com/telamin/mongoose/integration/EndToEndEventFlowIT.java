/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.integration;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.LogRecord;
import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for end-to-end event flow in Fluxtion Server.
 * This test verifies that events can flow from an event source to an event processor.
 */
public class EndToEndEventFlowIT {

    private MongooseServer server;
    private TestEventSource eventSource;
    private TestEventProcessor eventProcessor;
    private TestLogRecordListener logRecordListener;
    private CountDownLatch eventProcessedLatch;

    @BeforeEach
    void setUp() {
        // Create a latch to wait for event processing
        eventProcessedLatch = new CountDownLatch(1);

        // Create a log record listener to capture log events
        logRecordListener = new TestLogRecordListener();

        // Create a minimal app config
        MongooseServerConfig mongooseServerConfig = new MongooseServerConfig();

        // Create an event processor
        eventProcessor = new TestEventProcessor(eventProcessedLatch);
        mongooseServerConfig.addProcessor(eventProcessor, "testHandler");

        // Create an event source
        eventSource = new TestEventSource("testSource");
        mongooseServerConfig.addEventSource(eventSource, "testEventSourceFeed", true);

        // Create the server
        server = MongooseServer.bootServer(mongooseServerConfig, logRecordListener);
    }

    @AfterEach
    void tearDown() {
        // Clean up resources
        if (server != null) {
            // Stop the server and all its components
            server.stop();
        }
    }

    @Test
    void testEndToEndEventFlow() throws Exception {
        // Publish an event
        TestEvent testEvent = new TestEvent("Test message");
        eventSource.publishEvent(testEvent);

        // Wait for the event to be processed (with timeout)
        boolean processed = eventProcessedLatch.await(5, TimeUnit.MINUTES);

        // Verify that the event was processed
        assertTrue(processed, "Event should be processed within timeout");
        assertEquals(testEvent, eventProcessor.getLastProcessedEvent(), "Processor should receive the published event");

        server.stop();
    }

    /**
     * A simple event class for testing.
     */
    public static class TestEvent {
        private final String message;

        public TestEvent(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "TestEvent_In{message='" + message + "'}";
        }
    }

    /**
     * A test event source that can publish events.
     */
    private static class TestEventSource extends AbstractEventSourceService<TestEvent> {
        public TestEventSource(String name) {
            super(name);
        }

        public void publishEvent(TestEvent event) {
            if (output != null) {
                output.publish(event);
            }
        }
    }

    /**
     * A test event processor that processes TestEvents.
     */
    private static class TestEventProcessor implements DataFlow {
        private final CountDownLatch eventProcessedLatch;
        private TestEvent lastProcessedEvent;
        private final List<EventFeed> eventFeeds = new ArrayList<>();


        public TestEventProcessor(CountDownLatch eventProcessedLatch) {
            this.eventProcessedLatch = eventProcessedLatch;
        }

        public void handleTestEvent(TestEvent event) {
            lastProcessedEvent = event;
            System.out.println("[DEBUG_LOG] Processed event: " + event);
            eventProcessedLatch.countDown();
        }

        public TestEvent getLastProcessedEvent() {
            return lastProcessedEvent;
        }

        @Override
        public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed eventFeed) {
            eventFeeds.add(eventFeed);
        }

        @Override
        public void onEvent(Object event) {
            if (event instanceof TestEvent) {
                handleTestEvent((TestEvent) event);
            }
        }

        @Override
        public void init() {

        }

        @Override
        public void tearDown() {

        }

        @Override
        public void start() {
            EventSubscriptionKey<Object> subscriptionKey = new EventSubscriptionKey<>(
                    new EventSourceKey<>("testEventSourceFeed"),
                    CallBackType.ON_EVENT_CALL_BACK
            );

            eventFeeds.forEach(feed -> {
                feed.subscribe(this, subscriptionKey);
            });
        }
    }

    /**
     * A test log record listener that captures log records.
     */
    private static class TestLogRecordListener implements LogRecordListener {
        private final List<LogRecord> logRecords = new ArrayList<>();

        @Override
        public void processLogRecord(LogRecord logRecord) {
            logRecords.add(logRecord);
            System.out.println("[DEBUG_LOG] Log record: " + logRecord);
        }

        public List<LogRecord> getLogRecords() {
            return logRecords;
        }
    }
}
