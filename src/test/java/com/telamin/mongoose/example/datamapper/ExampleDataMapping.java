/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.example.datamapper;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Minimal example showing how to boot a MongooseServer using the fluent builder APIs.
 * It wires a simple EventSource to a processor and sends one event end-to-end.
 */
public class ExampleDataMapping {

    public static void main(String[] args) throws Exception {
        // Latch to verify we processed one event
        CountDownLatch latch = new CountDownLatch(1);

        // Create processor instance and wrap in builder config
        TestEventProcessor processor = new TestEventProcessor(latch);
        EventProcessorConfig<?> processorCfg = EventProcessorConfig
                .builder()
                .customHandler(processor)
                .build();

        // Group config with a named processor
        EventProcessorGroupConfig groupCfg = EventProcessorGroupConfig.builder()
                .agentName("exampleGroup")
                .put("exampleProcessor", processorCfg)
                .build();


        // Create an event source value mapper
        Function<TestEvent_In, ?> testDataMapper = new TestDataMapper();

        // Create an event source (feed) and config
        TestEventSource exampleSource = new TestEventSource("exampleSource");
        EventFeedConfig<TestEvent_In> feedCfg = EventFeedConfig
                .<TestEvent_In>builder()
                .instance(exampleSource)
                .name("exampleEventFeed")
                .valueMapper(testDataMapper)
                .broadcast(true)
                .wrapWithNamedEvent(false)
                .build();

        // Build app config and boot server
        MongooseServerConfig config = MongooseServerConfig.builder()
                .addProcessorGroup(groupCfg)
                .addEventFeed(feedCfg)
                .build();

        MongooseServer server = MongooseServer.bootServer(config, l ->{});
        try {
            // Publish one event and wait for processing
            TestEvent_In event = new TestEvent_In("hello-world");
            exampleSource.publishEvent(event);

            boolean received = latch.await(15, TimeUnit.SECONDS);
            System.out.println("Event delivered: " + received + ", last= " + processor.getLastProcessedEvent());
        } finally {
            server.stop();
        }
    }
}
