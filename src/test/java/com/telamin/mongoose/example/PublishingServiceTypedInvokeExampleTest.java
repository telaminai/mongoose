/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.fluxtion.runtime.audit.LogRecordListener;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Extends the publishing service example by configuring the service to use a
 * typed invoke strategy that calls a strongly-typed interface on the processor.
 */
public class PublishingServiceTypedInvokeExampleTest {

    @Test
    public void service_publishes_and_processor_receives_via_typed_invoke() throws Exception {
        InMemoryMessageSink memSink = new InMemoryMessageSink();

        // typed publishing service
        PublishingServiceTyped pubService = new PublishingServiceTyped("pubServiceTyped");

        // processor implements PublishingServiceListener and forwards to sink
        PublishingServiceTypedSubscriberHandler handler = new PublishingServiceTypedSubscriberHandler(new PublishingServiceTypedSubscriberHandler.TypedHandler());

        EventProcessorGroupConfig processorGroup = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .put("typed-subscriber-processor", EventProcessorConfig.builder().handler(handler).build())
                .build();

        ServiceConfig<PublishingServiceTyped> svcCfg = ServiceConfig.<PublishingServiceTyped>builder()
                .service(pubService)
                .serviceClass(PublishingServiceTyped.class)
                .name("pubServiceTyped")
//                .agent("service-agent", new BusySpinIdleStrategy())
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
            pubService.publish("t1");
            pubService.publish("t2");
            pubService.publish("t3");

            List<Object> lines = waitForMessages(memSink, 3, 5, TimeUnit.SECONDS);
            Assertions.assertTrue(lines.containsAll(List.of("t1", "t2", "t3")),
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
