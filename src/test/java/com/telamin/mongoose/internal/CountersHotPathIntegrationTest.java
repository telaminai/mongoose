/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 integration: verify the hot-path counter sites (feed publish +
 * agent processed/idle) move through the real {@link MongooseServer} boot
 * path when performance monitoring is enabled, and stay at zero when it's
 * disabled (no-op impl).
 *
 * <p>Stays below the agent-thread layer for determinism — drives publishes
 * directly through the {@link EventToQueuePublisher} that EFM allocates
 * for the registered source. Agent-loop processed/idle counters are
 * exercised separately by {@code ComposingEventProcessorAgentTest}'s
 * doWork() exercise, which already runs against the real agent class.
 */
class CountersHotPathIntegrationTest {

    @Test
    void feed_publish_counter_increments_through_real_server() {
        MongooseServerConfig cfg = new MongooseServerConfig();
        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setEnabled(true);
        cfg.setPerformanceMonitoring(perf);

        MongooseServer server = new MongooseServer(cfg);
        try {
            StringFeed feed = new StringFeed();
            server.registerEventSource("fx-market-data", feed);
            assertNotNull(feed.publisher, "EventFlowManager should hand the feed its publisher");

            for (int i = 0; i < 1_000; i++) {
                feed.publisher.publish("tick-" + i);
            }

            MongooseCountersService counters = server.registeredServices()
                    .get(MongooseCountersService.SERVICE_NAME)
                    .instance() instanceof MongooseCountersService c ? c : null;
            assertNotNull(counters, "counters service should be registered");

            Map<String, Long> snapshot = snapshot(counters);
            assertEquals(1_000L, snapshot.get("feed.fx-market-data.published"),
                    "publisher counter should track every publish call");
        } finally {
            server.stop();
        }
    }

    @Test
    void no_op_mode_keeps_every_counter_invisible() {
        // Default config — perfMon absent → no-op impl. The publisher still
        // exists and accepts publishes, but the no-op counter is not visited
        // by forEachCounter (it tracks nothing).
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        try {
            StringFeed feed = new StringFeed();
            server.registerEventSource("fx-market-data", feed);

            for (int i = 0; i < 100; i++) {
                feed.publisher.publish("tick-" + i);
            }

            MongooseCountersService counters = (MongooseCountersService) server.registeredServices()
                    .get(MongooseCountersService.SERVICE_NAME).instance();
            Map<String, Long> snapshot = snapshot(counters);
            assertTrue(snapshot.isEmpty(),
                    "no-op counters service should report no counters; got " + snapshot);
        } finally {
            server.stop();
        }
    }

    private static Map<String, Long> snapshot(MongooseCountersService counters) {
        Map<String, Long> out = new HashMap<>();
        counters.forEachCounter((id, label, value) -> out.put(label, value));
        return out;
    }

    /** Minimal EventFlowService that captures the publisher EFM hands it. */
    public static class StringFeed implements EventFlowService<String> {
        public EventToQueuePublisher<String> publisher;

        @Override
        public void setEventToQueuePublisher(EventToQueuePublisher<String> targetQueue) {
            this.publisher = targetQueue;
        }

        @Override public void subscribe(EventSubscriptionKey<String> k)   { /* no-op */ }
        @Override public void unSubscribe(EventSubscriptionKey<String> k) { /* no-op */ }
    }
}
