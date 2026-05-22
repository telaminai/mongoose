/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.health.MongooseHealthService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Phase 4.5b integration: verify that MongooseServer auto-allocates the
 * per-service {@code .up} gauge at registerService, flips it 0→1 across
 * start, and that the built-in "up" health check reflects the state via
 * {@code aggregatedVerdict}.
 */
class AutoServiceHealthTest {

    /** Tiny lifecycle service — no event flow, just an "up" presence. */
    public static class TestService implements Lifecycle {
        @Override public void init()     {}
        @Override public void start()    {}
        @Override public void stop()     {}
        @Override public void tearDown() {}
    }

    @Test
    void up_gauge_starts_at_zero_and_flips_to_one_on_global_start() {
        MongooseServerConfig cfg = enabledPerfMonConfig();
        MongooseServer server = new MongooseServer(cfg);
        try {
            TestService svc = new TestService();
            server.registerService(new Service<>(svc, TestService.class, "test-svc"));

            MongooseCountersService counters = countersOf(server);
            long upBefore = counters.counter(DefaultMongooseHealth.upGaugeLabel("test-svc")).get();
            assertEquals(0L, upBefore, "up gauge auto-allocated at 0 — service not started yet");

            server.init();
            server.start();

            long upAfter = counters.counter(DefaultMongooseHealth.upGaugeLabel("test-svc")).get();
            assertEquals(1L, upAfter, "after global start, up gauge should flip to 1");
        } finally {
            server.stop();
        }
    }

    @Test
    void builtin_up_check_reflects_service_state_in_aggregated_verdict() {
        MongooseServerConfig cfg = enabledPerfMonConfig();
        MongooseServer server = new MongooseServer(cfg);
        try {
            TestService svc = new TestService();
            server.registerService(new Service<>(svc, TestService.class, "test-svc"));

            MongooseHealthService health = healthOf(server);

            // Pre-start: built-in check sees up=0 → DOWN.
            HealthStatus pre = health.aggregatedVerdict("test-svc");
            assertEquals(HealthStatus.Verdict.DOWN, pre.verdict(),
                    "before global start, the up gauge is 0 → built-in check is DOWN");

            server.init();
            server.start();

            // Drain the lazy 1s cache — the built-in check was evaluated
            // pre-start and cached DOWN. Direct re-evaluation via the check
            // entry happens after cacheMs elapses; until then, the verdict
            // sees the cached value. Disable the cache for this test by
            // sleeping past the window — keeps the test honest about the
            // cache behaviour while still exercising the gauge flip.
            try { Thread.sleep(1_100); } catch (InterruptedException ignored) {}

            HealthStatus post = health.aggregatedVerdict("test-svc");
            assertEquals(HealthStatus.Verdict.UP, post.verdict(),
                    "after start, the up gauge is 1 → built-in check is UP");
        } finally {
            server.stop();
        }
    }

    @Test
    void stopService_flips_up_back_to_zero() {
        MongooseServerConfig cfg = enabledPerfMonConfig();
        MongooseServer server = new MongooseServer(cfg);
        try {
            TestService svc = new TestService();
            server.registerService(new Service<>(svc, TestService.class, "test-svc"));
            server.init();
            server.start();

            MongooseCountersService counters = countersOf(server);
            assertEquals(1L, counters.counter(DefaultMongooseHealth.upGaugeLabel("test-svc")).get());

            server.stopService("test-svc");

            assertEquals(0L, counters.counter(DefaultMongooseHealth.upGaugeLabel("test-svc")).get(),
                    "stopService should drop the up gauge to 0");
        } finally {
            server.stop();
        }
    }

    @Test
    void up_check_emits_UNKNOWN_when_counters_disabled() {
        // No performanceMonitoring block → NoOpCountersService → the built-in
        // up check can't read a real gauge value. Per the contract it
        // returns UNKNOWN("counters disabled") rather than synthesising DOWN.
        MongooseServerConfig cfg = new MongooseServerConfig();
        MongooseServer server = new MongooseServer(cfg);
        try {
            TestService svc = new TestService();
            server.registerService(new Service<>(svc, TestService.class, "test-svc"));

            MongooseHealthService health = healthOf(server);
            HealthStatus verdict = health.aggregatedVerdict("test-svc");

            assertEquals(HealthStatus.Verdict.UNKNOWN, verdict.verdict());
            assertEquals("counters disabled", verdict.reason());
        } finally {
            server.stop();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static MongooseServerConfig enabledPerfMonConfig() {
        MongooseServerConfig cfg = new MongooseServerConfig();
        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setEnabled(true);
        perf.setCounterBufferKb(16);
        cfg.setPerformanceMonitoring(perf);
        return cfg;
    }

    private static MongooseCountersService countersOf(MongooseServer server) {
        Service<?> s = server.registeredServices().get(MongooseCountersService.SERVICE_NAME);
        assertNotNull(s, "counters service should be registered");
        return (MongooseCountersService) s.instance();
    }

    private static MongooseHealthService healthOf(MongooseServer server) {
        Service<?> s = server.registeredServices().get(MongooseHealthService.SERVICE_NAME);
        assertNotNull(s, "health service should be registered");
        return (MongooseHealthService) s.instance();
    }
}
