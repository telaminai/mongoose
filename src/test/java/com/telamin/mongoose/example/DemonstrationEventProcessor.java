/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import com.telamin.mongoose.MongooseServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

public class DemonstrationEventProcessor {

    @Test
    @Disabled
    public void customHandlerTest() throws InterruptedException {
        MongooseServer.bootServer(new StringReader(configWrapHandler), System.out::println);
        Thread.sleep(1_000_000);
    }

    @Test
    @Disabled
    public void performanceTest() throws InterruptedException {
        MongooseServer.bootServer(new StringReader(configPerformance), System.out::println);
        Thread.sleep(1_000_000);
    }

    private final static String configPerformance = """            
            # --------- EVENT INPUT FEEDS BEGIN CONFIG ---------
            eventFeeds:
              - instance: !!com.fluxtion.server.example.HeartBeatEventFeed
                  publishIntervalNanos: 200
                name: heartBeater
                agentName: heartBeatPublisher-thread
                broadcast: true
            # --------- EVENT INPUT FEEDS END CONFIG ---------
            
            
            # --------- EVENT HANDLERS BEGIN CONFIG ---------
            eventHandlers:
              - agentName: heartBeatProcessor-thread
                eventHandlers:
                  heartBeatProcessor_1:
                    eventHandler: !!com.fluxtion.server.example.HeartBeatExampleProcessor { }
                    logLevel: DEBUG
                  heartBeatProcessor_2:
                    eventHandler: !!com.fluxtion.server.example.HeartBeatExampleProcessor { }
                    logLevel: DEBUG
            # --------- EVENT HANDLERS END CONFIG ---------
            
            # --------- AGENT THREAD BEGIN CONFIG ---------
            agentThreads:
              - agentName: heartBeatPublisher-thread
                idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
              - agentName: heartBeatProcessor-thread
                idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
            # --------- AGENT THREAD END CONFIG ---------
            """;

    private final static String configWrapHandler = """            
            # --------- EVENT INPUT FEEDS BEGIN CONFIG ---------
            eventFeeds:
              - instance: !!com.fluxtion.server.example.HeartBeatEventFeed
                  publishIntervalNanos: 200_000_000
                name: heartBeater
                agentName: heartBeatPublisher-thread
                broadcast: true
            # --------- EVENT INPUT FEEDS END CONFIG ---------
            
            
            # --------- EVENT HANDLERS BEGIN CONFIG ---------
            eventHandlers:
              - agentName: heartBeatProcessor-thread
                eventHandlers:
                  heartBeatProcessor_1:
                    customHandler: !!com.fluxtion.server.example.MyCustomEventHandler { }
                    logLevel: DEBUG
            # --------- EVENT HANDLERS END CONFIG ---------
            
            # --------- AGENT THREAD BEGIN CONFIG ---------
            agentThreads:
              - agentName: heartBeatPublisher-thread
                idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
              - agentName: heartBeatProcessor-thread
                idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
            # --------- AGENT THREAD END CONFIG ---------
            """;
}
