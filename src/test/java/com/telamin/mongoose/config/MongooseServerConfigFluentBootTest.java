/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.runtime.EventProcessor;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.audit.LogRecord;
import com.fluxtion.runtime.audit.LogRecordListener;
import com.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots MongooseServer using the new fluent MongooseServerConfig builder APIs and verifies an end-to-end event flow.
 */
public class MongooseServerConfigFluentBootTest {

    private MongooseServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void bootServerWithFluentAppConfig_andProcessOneEvent() throws Exception {
        // Arrange: latch and listener
        CountDownLatch eventProcessed = new CountDownLatch(1);
        TestLogRecordListener logListener = new TestLogRecordListener();

        // Processor and its config via builder
        TestEventProcessor processor = new TestEventProcessor(eventProcessed);
        EventProcessorConfig<TestEventProcessor> processorCfg = EventProcessorConfig
                .<TestEventProcessor>builder()
                .handler(processor)
                .build();

        // Group: put processor under a name
        EventProcessorGroupConfig groupCfg = EventProcessorGroupConfig.builder()
                .agentName("fluentGroup")
                .put("fluentProcessor", processorCfg)
                .build();

        // Event source and its feed config
        TestEventSource eventSource = new TestEventSource("fluentSource");
        EventFeedConfig<TestEventSource> feedCfg = EventFeedConfig
                .<TestEventSource>builder()
                .instance(eventSource)
                .name("fluentEventFeed")
                .broadcast(true) // make available to all subscribers
                .wrapWithNamedEvent(false)
                .build();

        // Build app config via fluent builder
        MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(groupCfg)
                .addEventFeed(feedCfg)
                .build();

        // Act: boot server
        server = MongooseServer.bootServer(mongooseServerConfig, logListener);

        // Publish an event
        TestEvent event = new TestEvent("hello");
        eventSource.publishEvent(event);

        // Assert: processor handled the event
        assertTrue(eventProcessed.await(30, TimeUnit.SECONDS), "Processor should receive event via fluent MongooseServerConfig");
        assertEquals(event, processor.getLastProcessedEvent());
    }

    // --- Helper types (mirroring EndToEndEventFlowIT minimal setup) ---
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
            return "TestEvent_In{" + message + '}';
        }
    }

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

    private static class TestEventProcessor implements StaticEventProcessor, EventProcessor<TestEventProcessor> {
        private final CountDownLatch latch;
        private volatile TestEvent last;
        private final List<EventFeed> feeds = new ArrayList<>();

        public TestEventProcessor(CountDownLatch latch) {
            this.latch = latch;
        }

        public TestEvent getLastProcessedEvent() {
            return last;
        }

        @Override
        public void addEventFeed(EventFeed eventFeed) {
            feeds.add(eventFeed);
        }

        @Override
        public void onEvent(Object event) {
            if (event instanceof TestEvent te) {
                last = te;
                latch.countDown();
            }
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
            // Subscribe via fluent EventSubscriptionKey
            EventSubscriptionKey<Object> key = EventSubscriptionKey.onEvent("fluentEventFeed");
            feeds.forEach(f -> f.subscribe(this, key));
        }

        @Override
        public void tearDown() {
        }
    }

    private static class TestLogRecordListener implements LogRecordListener {
        private final List<LogRecord> logRecords = new ArrayList<>();

        @Override
        public void processLogRecord(LogRecord logRecord) {
            if (logRecords.size() < 1000) logRecords.add(logRecord);
        }

        public List<LogRecord> getLogRecords() {
            return logRecords;
        }
    }
}
