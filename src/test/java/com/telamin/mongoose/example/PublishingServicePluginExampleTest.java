/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.fluxtion.runtime.audit.LogRecordListener;
import com.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Example that demonstrates a service plugin that can publish events.
 * The processor injects the service via @ServiceRegistered, calls subscribe(),
 * and receives events published by the service.
 */
public class PublishingServicePluginExampleTest {

    @Test
    public void service_publishes_and_processor_receives() throws Exception {
        // In-memory sink for verification
        InMemoryMessageSink memSink = new InMemoryMessageSink();

        // Our publishing service
        PublishingService pubService = new PublishingService("pubService");

        // Processor that subscribes to publishing service and forwards to sink
        PublishingServiceSubscriberHandler handler = new PublishingServiceSubscriberHandler();

        // Wire processor group
        EventProcessorGroupConfig processorGroup = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .put("subscriber-processor", new EventProcessorConfig(handler))
                .build();

        // Register the service and the sink
        ServiceConfig<PublishingService> svcCfg = ServiceConfig.<PublishingService>builder()
                .service(pubService)
                .serviceClass(PublishingService.class)
                .name("pubService")
                .build();

        EventSinkConfig<MessageSink<?>> sinkCfg = EventSinkConfig.<MessageSink<?>>builder()
                .instance(memSink)
                .name("memSink")
                .build();

        MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(processorGroup)
                .addService(svcCfg)
                .addEventSink(sinkCfg)
                .build();

        LogRecordListener logListener = rec -> {};
        MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, logListener);
        try {
            // Publish some events via the service
            pubService.publish("e1");
            pubService.publish("e2");
            pubService.publish("e3");

            List<Object> lines = waitForMessages(memSink, 3, 5, TimeUnit.SECONDS);
            Assertions.assertTrue(lines.containsAll(List.of("e1", "e2", "e3")),
                    () -> "Sink missing expected items: " + lines);
        } finally {
            server.stop();
        }
    }

    private static List<Object> waitForMessages(InMemoryMessageSink sink, int minCount, long timeout, TimeUnit unit) throws Exception {
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < end) {
            List<Object> lines = sink.getMessages();
            if (lines.size() >= minCount) {
                return lines;
            }
            Thread.sleep(50);
        }
        return sink.getMessages();
    }
}
