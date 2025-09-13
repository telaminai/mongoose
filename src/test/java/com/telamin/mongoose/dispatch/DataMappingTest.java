/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.example.datamapper.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How-to/example test: boot a MongooseServer using the fluent builder APIs and process one event.
 */
public class DataMappingTest {

    private MongooseServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void mapWithInstance() throws Exception {
        // Create an event source value mapper
        TestDataMapper testDataMapper = new TestDataMapper();

        CountDownLatch latch = new CountDownLatch(1);
        TestLogRecordListener logListener = new TestLogRecordListener();

        TestEventProcessor processor = new TestEventProcessor(latch);
        EventProcessorConfig<?> processorCfg = EventProcessorConfig
                .builder()
                .customHandler(processor)
                .build();

        EventProcessorGroupConfig groupCfg = EventProcessorGroupConfig.builder()
                .agentName("exampleGroup")
                .put("exampleProcessor", processorCfg)
                .build();

        TestEventSource exampleSource = new TestEventSource("exampleSource");
        EventFeedConfig<TestEvent_In> feedCfg = EventFeedConfig
                .<TestEvent_In>builder()
                .instance(exampleSource)
                .name("exampleEventFeed")
                .valueMapper(testDataMapper)
                .broadcast(true)
                .wrapWithNamedEvent(false)
                .build();

        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(groupCfg)
                .addEventFeed(feedCfg)
                .build();

        server = MongooseServer.bootServer(config, logListener);

        assertNotNull(testDataMapper.getObjectPoolsRegistry(), "Object pools registry should be injected");
        exampleSource.publishEvent(new TestEvent_In("hello-from-test"));

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Processor should receive event from example feed");
    }


    @Test
    void mapWithStaticLambda() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        TestLogRecordListener logListener = new TestLogRecordListener();

        TestEventProcessor processor = new TestEventProcessor(latch);
        EventProcessorConfig<?> processorCfg = EventProcessorConfig
                .builder()
                .customHandler(processor)
                .build();

        EventProcessorGroupConfig groupCfg = EventProcessorGroupConfig.builder()
                .agentName("exampleGroup")
                .put("exampleProcessor", processorCfg)
                .build();

        TestEventSource exampleSource = new TestEventSource("exampleSource");
        EventFeedConfig<TestEvent_In> feedCfg = EventFeedConfig
                .<TestEvent_In>builder()
                .instance(exampleSource)
                .name("exampleEventFeed")
                .valueMapper(DataMappingTest::mapToOut)
                .broadcast(true)
                .wrapWithNamedEvent(false)
                .build();

        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(groupCfg)
                .addEventFeed(feedCfg)
                .build();

        server = MongooseServer.bootServer(config, logListener);

        exampleSource.publishEvent(new TestEvent_In("hello-from-test"));

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Processor should receive event from example feed");
    }


    public static TestEvent_Out mapToOut(TestEvent_In in) {
        return new TestEvent_Out(in.getMessage() + "-out");
    }
}
