/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.config;

import com.telamin.mongoose.MongooseServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the server using YAML and verifies that an event processor receives
 * the initial configuration map via ConfigListener.initialConfig.
 */
public class InitialConfigYamlBootTest {

    private MongooseServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        // reset static for isolation between tests
        ConfigAwareYamlHandler.lastConfig = null;
    }

    @Test
    void yaml_injects_initial_config_into_processor() {
        String yaml = """
                # --------- EVENT HANDLERS BEGIN CONFIG ---------
                eventHandlers:
                  - agentName: processor-agent
                    idleStrategy: !!com.fluxtion.agrona.concurrent.BusySpinIdleStrategy { }
                    eventHandlers:
                      config-processor:
                        customHandler: !!com.telamin.mongoose.config.ConfigAwareYamlHandler { }
                        logLevel: INFO
                        configMap:
                          greeting: hello
                          answer: 42
                # --------- EVENT HANDLERS END CONFIG ---------
                """;

        server = MongooseServer.bootServer(new StringReader(yaml), rec -> {});

        assertNotNull(ConfigAwareYamlHandler.lastConfig, "Expected initial config to be injected from YAML");
        assertEquals("hello", ConfigAwareYamlHandler.lastConfig.getOrDefault(ConfigKey.of("greeting", String.class), null));
        assertEquals(42, ConfigAwareYamlHandler.lastConfig.getOrDefault(ConfigKey.of("answer", Integer.class), -1));
    }
}
