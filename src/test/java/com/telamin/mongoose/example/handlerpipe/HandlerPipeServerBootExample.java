/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example.handlerpipe;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.audit.LogRecordListener;
import com.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.EventSinkConfig;
import com.telamin.mongoose.connector.memory.HandlerPipe;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;
import com.telamin.mongoose.example.NamedFeedsFilterHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots a Fluxtion server and demonstrates wiring a HandlerPipe into the event flow.
 * A processor subscribes to the pipe's source by name and forwards received messages
 * into an InMemoryMessageSink for verification.
 */
public class HandlerPipeServerBootExample {

    @Test
    public void boot_server_and_use_handler_pipe() throws Exception {
        // Create a pipe and give it a descriptive feed name
        HandlerPipe<String> pipe = HandlerPipe.<String>of("examplePipe").cacheEventLog(true);

        // Processor subscribes to the pipe's feed name and forwards messages to the sink
        NamedFeedsFilterHandler handler = new NamedFeedsFilterHandler(java.util.Set.of(pipe.getSource().getName()));

        // In-memory sink to capture outputs
        InMemoryMessageSink sink = new InMemoryMessageSink();

        // Build server configuration: register processor group, event feed (the pipe's source), and sink
        EventProcessorGroupConfig processors = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .put("pipe-listener", new EventProcessorConfig(handler))
                .build();

        EventFeedConfig<?> pipeFeed = EventFeedConfig.builder()
                .instance(pipe.getSource())
                .name(pipe.getSource().getName())
                .broadcast(true) // allow broadcast subscription style in this example
                .agent("pipe-agent", new BusySpinIdleStrategy())
                .build();

        EventSinkConfig<MessageSink<?>> sinkCfg = EventSinkConfig.<MessageSink<?>>builder()
                .instance(sink)
                .name("memSink")
                .build();

        MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(processors)
                .addEventFeed(pipeFeed)
                .addEventSink(sinkCfg)
                .build();

        // Boot the server
        LogRecordListener logs = rec -> { };
        MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, logs);
        try {
            // Publish some messages through the pipe
            pipe.sink().accept("hello");
            pipe.sink().accept("world");

            // Wait for messages to arrive in the sink
            List<Object> out = waitForMessages(sink, 2, 5, TimeUnit.SECONDS);

            assertTrue(out.stream().anyMatch(s -> s.toString().equals("hello")));
            assertTrue(out.stream().anyMatch(s -> s.toString().equals("world")));
        } finally {
            server.stop();
        }
    }

    private static List<Object> waitForMessages(InMemoryMessageSink sink, int minCount, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<Object> snap;
        do {
            snap = sink.getMessages();
            if (snap.size() >= minCount) {
                return snap;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return snap;
    }
}
