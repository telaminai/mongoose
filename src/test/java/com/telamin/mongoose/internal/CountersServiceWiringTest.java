/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.config.ServiceConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link MongooseServer} registers a counters-service impl
 * picked by the {@code performanceMonitoring} YAML block, that the default
 * (absent config or {@code enabled = false}) is the no-op, that
 * {@code enabled = true} installs the Agrona-backed impl, and that consumers
 * can inject it via {@code @ServiceRegistered}.
 */
class CountersServiceWiringTest {

    @Test
    void default_config_installs_no_op_counters_service() {
        MongooseServerConfig cfg = new MongooseServerConfig();
        CountersProbe probe = new CountersProbe();
        cfg.setServices(List.of(probeConfig(probe)));

        MongooseServer server = MongooseServer.bootServer(cfg, r -> {});
        try {
            assertNotNull(probe.counters, "probe should have received the counters service");
            assertSame(NoOpCountersService.INSTANCE, probe.counters);
            assertFalse(probe.counters.isOperational());
        } finally {
            server.stop();
        }
    }

    @Test
    void enabled_config_installs_agrona_counters_service() {
        MongooseServerConfig cfg = new MongooseServerConfig();
        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setEnabled(true);
        perf.setCounterBufferKb(64);
        cfg.setPerformanceMonitoring(perf);

        CountersProbe probe = new CountersProbe();
        cfg.setServices(List.of(probeConfig(probe)));

        MongooseServer server = MongooseServer.bootServer(cfg, r -> {});
        try {
            assertNotNull(probe.counters);
            assertTrue(probe.counters.isOperational());
            assertTrue(probe.counters instanceof AgronaCountersService,
                    "expected AgronaCountersService, got " + probe.counters.getClass().getName());
        } finally {
            server.stop();
        }
    }

    private static ServiceConfig<CountersProbe> probeConfig(CountersProbe probe) {
        return new ServiceConfig<>(probe, CountersProbe.class, "countersProbe");
    }
}
