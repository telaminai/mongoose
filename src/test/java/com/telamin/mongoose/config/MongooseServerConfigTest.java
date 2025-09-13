/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.config;

import com.fluxtion.agrona.concurrent.BackoffIdleStrategy;
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.agrona.concurrent.IdleStrategy;
import com.fluxtion.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MongooseServerConfigTest {

    private MongooseServerConfig mongooseServerConfig;
    private IdleStrategy defaultIdleStrategy;
    private IdleStrategy customIdleStrategy;
    private IdleStrategy threadConfigIdleStrategy;

    @BeforeEach
    void setUp() {
        mongooseServerConfig = new MongooseServerConfig();
        defaultIdleStrategy = new YieldingIdleStrategy();
        customIdleStrategy = new BusySpinIdleStrategy();
        threadConfigIdleStrategy = new BackoffIdleStrategy(1, 1, 1, 1);
    }

    @Test
    void testLookupIdleStrategyWhenNull_NullPreferredAndNullAgentThreads() {
        // Arrange
        mongooseServerConfig.setIdleStrategy(defaultIdleStrategy);

        // Act
        IdleStrategy result = mongooseServerConfig.lookupIdleStrategyWhenNull(null, "testAgent");

        // Assert
        assertSame(defaultIdleStrategy, result, "Should return the default idle strategy");
    }

    @Test
    void testLookupIdleStrategyWhenNull_NullPreferredWithAgentThreads_Matching() {
        // Arrange
        List<ThreadConfig> threadConfigs = new ArrayList<>();
        ThreadConfig threadConfig = new ThreadConfig();
        threadConfig.setAgentName("testAgent");
        threadConfig.setIdleStrategy(threadConfigIdleStrategy);
        threadConfigs.add(threadConfig);
        mongooseServerConfig.setAgentThreads(threadConfigs);

        // Act
        IdleStrategy result = mongooseServerConfig.lookupIdleStrategyWhenNull(null, "testAgent");

        // Assert
        assertSame(threadConfigIdleStrategy, result, "Should return the thread config idle strategy");
    }

    @Test
    void testLookupIdleStrategyWhenNull_NullPreferredWithAgentThreads_NoMatching() {
        // Arrange
        List<ThreadConfig> threadConfigs = new ArrayList<>();
        ThreadConfig threadConfig = new ThreadConfig();
        threadConfig.setAgentName("otherAgent");
        threadConfig.setIdleStrategy(threadConfigIdleStrategy);
        threadConfigs.add(threadConfig);
        mongooseServerConfig.setAgentThreads(threadConfigs);

        // Act
        IdleStrategy result = mongooseServerConfig.lookupIdleStrategyWhenNull(null, "testAgent");

        // Assert
        assertTrue(result instanceof YieldingIdleStrategy, "Should return a new YieldingIdleStrategy");
    }

    @Test
    void testLookupIdleStrategyWhenNull_WithPreferred() {
        // Arrange
        List<ThreadConfig> threadConfigs = new ArrayList<>();
        ThreadConfig threadConfig = new ThreadConfig();
        threadConfig.setAgentName("testAgent");
        threadConfig.setIdleStrategy(threadConfigIdleStrategy);
        threadConfigs.add(threadConfig);
        mongooseServerConfig.setAgentThreads(threadConfigs);

        // Act
        IdleStrategy result = mongooseServerConfig.lookupIdleStrategyWhenNull(customIdleStrategy, "testAgent");

        // Assert
        assertSame(customIdleStrategy, result, "Should return the preferred idle strategy");
    }

    @Test
    void testGetIdleStrategyOrDefault_NullAgentThreads() {
        // Arrange
        mongooseServerConfig.setAgentThreads(null);

        // Act
        IdleStrategy result = mongooseServerConfig.getIdleStrategyOrDefault("testAgent", defaultIdleStrategy);

        // Assert
        assertSame(defaultIdleStrategy, result, "Should return the default idle strategy");
    }

    @Test
    void testGetIdleStrategyOrDefault_WithAgentThreads_Matching() {
        // Arrange
        List<ThreadConfig> threadConfigs = new ArrayList<>();
        ThreadConfig threadConfig = new ThreadConfig();
        threadConfig.setAgentName("testAgent");
        threadConfig.setIdleStrategy(threadConfigIdleStrategy);
        threadConfigs.add(threadConfig);
        mongooseServerConfig.setAgentThreads(threadConfigs);

        // Act
        IdleStrategy result = mongooseServerConfig.getIdleStrategyOrDefault("testAgent", defaultIdleStrategy);

        // Assert
        assertSame(threadConfigIdleStrategy, result, "Should return the thread config idle strategy");
    }

    @Test
    void testGetIdleStrategyOrDefault_WithAgentThreads_NoMatching() {
        // Arrange
        List<ThreadConfig> threadConfigs = new ArrayList<>();
        ThreadConfig threadConfig = new ThreadConfig();
        threadConfig.setAgentName("otherAgent");
        threadConfig.setIdleStrategy(threadConfigIdleStrategy);
        threadConfigs.add(threadConfig);
        mongooseServerConfig.setAgentThreads(threadConfigs);

        // Act
        IdleStrategy result = mongooseServerConfig.getIdleStrategyOrDefault("testAgent", defaultIdleStrategy);

        // Assert
        assertTrue(result instanceof YieldingIdleStrategy, "Should return a new YieldingIdleStrategy");
    }

    @Test
    void testGetIdleStrategyOrDefault_WithAgentThreads_MatchingButNullStrategy() {
        // Arrange
        List<ThreadConfig> threadConfigs = new ArrayList<>();
        ThreadConfig threadConfig = new ThreadConfig();
        threadConfig.setAgentName("testAgent");
        threadConfig.setIdleStrategy(null);
        threadConfigs.add(threadConfig);
        mongooseServerConfig.setAgentThreads(threadConfigs);

        // Act
        IdleStrategy result = mongooseServerConfig.getIdleStrategyOrDefault("testAgent", defaultIdleStrategy);

        // Assert
        assertSame(defaultIdleStrategy, result, "Should return the default idle strategy");
    }
}