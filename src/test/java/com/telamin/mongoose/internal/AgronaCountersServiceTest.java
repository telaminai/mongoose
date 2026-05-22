/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgronaCountersServiceTest {

    @Test
    void counters_at_distinct_labels_are_distinct_handles() {
        MongooseCountersService svc = new AgronaCountersService();

        MongooseCounter a = svc.feedPublishCounter("fx");
        MongooseCounter b = svc.feedPublishCounter("rates");
        MongooseCounter c = svc.agentEventsCounter("priceCalc");

        assertNotSame(a, b);
        assertNotSame(b, c);
        assertNotSame(a, c);
    }

    @Test
    void counters_for_the_same_label_return_the_cached_handle() {
        MongooseCountersService svc = new AgronaCountersService();

        MongooseCounter first  = svc.feedPublishCounter("fx");
        MongooseCounter second = svc.feedPublishCounter("fx");
        MongooseCounter third  = svc.feedPublishCounter("fx");

        assertSame(first, second);
        assertSame(second, third);
    }

    @Test
    void increment_advances_the_counter_and_get_reads_it_back() {
        MongooseCountersService svc = new AgronaCountersService();
        MongooseCounter counter = svc.feedPublishCounter("fx");

        for (int i = 0; i < 1_000; i++) {
            counter.increment();
        }
        assertEquals(1_000L, counter.get());
    }

    @Test
    void setOrdered_writes_a_gauge_value() {
        MongooseCountersService svc = new AgronaCountersService();
        MongooseCounter gauge = svc.queueDepthGauge("/agent/priceCalc/q");

        gauge.setOrdered(42L);
        assertEquals(42L, gauge.get());

        gauge.setOrdered(0L);
        assertEquals(0L, gauge.get());

        gauge.setOrdered(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, gauge.get());
    }

    @Test
    void forEachCounter_visits_every_registered_label_with_current_value() {
        MongooseCountersService svc = new AgronaCountersService();

        MongooseCounter feed = svc.feedPublishCounter("fx");
        MongooseCounter group = svc.agentEventsCounter("priceCalc");
        MongooseCounter gauge = svc.queueDepthGauge("/agent/priceCalc/q");

        feed.increment();
        feed.increment();
        feed.increment();
        group.increment();
        gauge.setOrdered(7L);

        Map<String, Long> seen = new HashMap<>();
        svc.forEachCounter((id, label, value) -> seen.put(label, value));

        assertEquals(3, seen.size());
        assertEquals(3L, seen.get("feed.fx.published"));
        assertEquals(1L, seen.get("group.priceCalc.processed"));
        assertEquals(7L, seen.get("queue./agent/priceCalc/q.depth"));
    }

    @Test
    void forEachCounter_is_repeatable_and_reflects_live_value() {
        MongooseCountersService svc = new AgronaCountersService();
        MongooseCounter counter = svc.feedPublishCounter("fx");

        counter.increment();
        AtomicInteger visits = new AtomicInteger();
        long[] firstReading = {0};
        svc.forEachCounter((id, label, value) -> {
            visits.incrementAndGet();
            firstReading[0] = value;
        });
        assertEquals(1, visits.get());
        assertEquals(1L, firstReading[0]);

        counter.increment();
        counter.increment();
        long[] secondReading = {0};
        svc.forEachCounter((id, label, value) -> secondReading[0] = value);
        assertEquals(3L, secondReading[0]);
    }

    @Test
    void isOperational_is_true() {
        assertTrue(new AgronaCountersService().isOperational());
    }

    @Test
    void labels_follow_the_documented_prefix_convention() {
        MongooseCountersService svc = new AgronaCountersService();
        svc.feedPublishCounter("fx-market-data");
        svc.agentEventsCounter("priceCalculator");
        svc.agentIdleCyclesCounter("priceCalculator");
        svc.queueDepthGauge("/agent/priceCalculator/eventQueue");
        svc.processorEventsCounter("priceCalc");
        svc.nodeInvocationCounter("priceCalc", "FxLineHandler");

        Map<String, Long> seen = new HashMap<>();
        svc.forEachCounter((id, label, value) -> seen.put(label, value));

        assertTrue(seen.containsKey("feed.fx-market-data.published"));
        assertTrue(seen.containsKey("group.priceCalculator.processed"));
        assertTrue(seen.containsKey("group.priceCalculator.idleCycles"));
        assertTrue(seen.containsKey("queue./agent/priceCalculator/eventQueue.depth"));
        assertTrue(seen.containsKey("processor.priceCalc.events"));
        assertTrue(seen.containsKey("node.priceCalc.FxLineHandler.invocations"));
    }
}
