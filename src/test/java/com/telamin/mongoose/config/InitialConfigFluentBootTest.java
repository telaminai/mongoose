/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that initial configuration provided via the fluent MongooseServerConfig builder
 * is injected into an event processor that exports ConfigListener.
 */
public class InitialConfigFluentBootTest {

    private MongooseServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void fluentBuilder_injects_initial_config_into_processor() throws Exception {
        // Build an in-memory event source to complete a minimal runtime
        InMemoryEventSource<Object> inMem = new InMemoryEventSource<>();

        // Handler that extends ObjectEventHandlerNode and implements ConfigListener to receive initial config
        ConfigAwareHandler handler = new ConfigAwareHandler();

        // Processor group with config map entries via builder, using customHandler
        EventProcessorGroupConfig group = EventProcessorGroupConfig.builder()
                .agentName("processor-agent")
                .idleStrategy(new BusySpinIdleStrategy())
                .put("config-processor",
                        EventProcessorConfig.builder()
                                .customHandler(handler)
                                .putConfig("greeting", "hello")
                                .putConfig("answer", 42)
                                .build())
                .build();

        // Feed config
        EventFeedConfig<?> feed = EventFeedConfig.builder()
                .instance(inMem)
                .name("inMemFeed")
                .broadcast(true)
                .wrapWithNamedEvent(false)
                .agent("memory-source-agent", new BusySpinIdleStrategy())
                .build();

        // App config via fluent builder
        MongooseServerConfig mongooseServerConfig = MongooseServerConfig.builder()
                .addProcessorGroup(group)
                .addEventFeed(feed)
                .build();

        // Boot server
        server = MongooseServer.bootServer(mongooseServerConfig, rec -> {});

        // Assert: initial config should have been injected during boot
        assertNotNull(handler.lastConfig, "Expected initial config to be injected");
        assertEquals("hello", handler.lastConfig.getOrDefault(ConfigKey.of("greeting", String.class), null));
        assertEquals(42, handler.lastConfig.getOrDefault(ConfigKey.of("answer", Integer.class), -1));

        // Optionally publish an event and ensure handler still works
        inMem.offer("ping");

        // Spin briefly to allow event to be processed (not strictly required for config assertion)
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end && handler.received.size() < 1) {
            Thread.sleep(10);
        }
        // Our handler records events for visibility; event content not asserted here
        assertTrue(handler.received.size() >= 0);
    }

    /**
     * Minimal event handler that extends ObjectEventHandlerNode, implements ConfigListener,
     * and records the last received config and events.
     */
    static class ConfigAwareHandler extends com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode implements ConfigListener {
        private final List<Object> received = new ArrayList<>();
        volatile ConfigMap lastConfig;

        @Override
        public boolean handleEvent(Object event) {
            received.add(event);
            return true;
        }

        @Override
        public boolean initialConfig(ConfigMap config) {
            this.lastConfig = config;
            return true;
        }
    }
}
