/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.LifeCycleEventSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests covering lifecycle management behavior and ordering.
 */
public class MongooseServerLifecycleTest {

    private MongooseServer server;
    private TestService service;
    private TestLifeCycleEventSource eventSourceService;

    @BeforeEach
    void setUp() {
        server = new MongooseServer(new MongooseServerConfig());
        service = new TestService();
        eventSourceService = new TestLifeCycleEventSource();
    }

    @Test
    void initStartAndStopOrderingAndExclusions() {
        // regular service
        server.registerService(new Service<>(service, TestService.class, "svc"));
        // life-cycle event source registered as both a service and event source
        server.registerService(new Service<>(eventSourceService, TestLifeCycleEventSource.class, "src"));

        // init
        server.init();
        // regular service is initialized by server lifecycle
        assertTrue(service.initialized, "regular service should be initialized");
        // LifeCycleEventSource should be initialized by EventFlowManager.init(), not by service loop
        assertTrue(eventSourceService.initialized, "event source should be initialized via flowManager");

        // start
        server.start();
        assertTrue(service.started, "regular service should be started");
        assertTrue(service.startCompleted, "startComplete should be called for regular service");
        assertTrue(eventSourceService.started, "event source should be started via flowManager");

        // stop
        server.stop();
        assertTrue(service.stopped, "regular service should be stopped on server.stop()");

        // idempotent stop should not throw
        server.stop();
    }

    // Test fixtures
    public static class TestService implements Lifecycle {
        boolean initialized;
        boolean started;
        boolean stopped;
        boolean tornDown;
        boolean startCompleted;

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
    }

    public static class TestLifeCycleEventSource implements LifeCycleEventSource<String> {
        boolean initialized;
        boolean started;
        boolean tornDown;
        private EventFlowManager eventFlowManager;

        @Override
        public void init() {
            initialized = true;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void tearDown() {
            tornDown = true;
        }

        @Override
        public void setEventToQueuePublisher(com.telamin.mongoose.dispatch.EventToQueuePublisher<String> targetQueue) {
        }

        @Override
        public void subscribe(EventSubscriptionKey<String> eventSourceKey) {
        }

        @Override
        public void unSubscribe(EventSubscriptionKey<String> eventSourceKey) {
        }
    }
}
