/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.hellomongoose;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;

/**
 * One-file getting-started example for Mongoose server.
 * <p>
 * It boots a server with:
 * - A simple business-logic handler (ObjectEventHandlerNode) that prints String events
 * - An in-memory feed that we publish a couple of messages to
 * <p>
 * Run this class' main() to see events flowing through your handler.
 */
public final class HelloMongoose {

    public static void main(String[] args) {
        // 1) Business logic handler
        var handler = new ObjectEventHandlerNode() {
            @Override
            protected boolean handleEvent(Object event) {
                if (event instanceof String s) {
                    System.out.println("Got event: " + s);
                }
                // true indicates the event was handled without requesting a stop
                return true;
            }
        };

        // 2) Create an in-memory event feed (String payloads)
        var feed = new InMemoryEventSource<String>();
        feed.setCacheEventLog(true); // allow publish-before-start, will replay on startComplete

        // 3) Wire processor group with our handler
        var processorGroup = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .put("hello-processor", new EventProcessorConfig<>(handler))
                .build();

        // 4) Wire the feed on its own agent with a busy-spin idle strategy (lowest latency)
        var feedCfg = EventFeedConfig.builder()
                .instance(feed)
                .name("hello-feed")
                .broadcast(true)
                .agent("feed-agent", new BusySpinIdleStrategy())
                .build();

        // 5) Build the application config and boot the mongooseServer
        var mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(processorGroup)
                .addEventFeed(feedCfg)
                .build();

        // boot with a no-op record consumer
        var mongooseServer = MongooseServer.bootServer(
                mongooseServerConfig, rec -> {/* no-op */});

        // 6) Publish a few events
        feed.publishNow("hi");
        feed.publishNow("mongoose");

        // 7) Stop the mongooseServer (in real apps, you keep it running)
        mongooseServer.stop();
    }
}
