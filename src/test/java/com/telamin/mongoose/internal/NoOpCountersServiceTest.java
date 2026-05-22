/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.counters.MongooseCountersService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NoOpCountersServiceTest {

    @Test
    void all_accessors_return_the_same_shared_handle() {
        MongooseCountersService svc = NoOpCountersService.INSTANCE;

        MongooseCounter a = svc.feedPublishCounter("fx");
        MongooseCounter b = svc.agentEventsCounter("priceCalc");
        MongooseCounter c = svc.agentIdleCyclesCounter("priceCalc");
        MongooseCounter d = svc.queueDepthGauge("/agent/priceCalc/q");
        MongooseCounter e = svc.processorEventsCounter("priceCalc");
        MongooseCounter f = svc.nodeInvocationCounter("priceCalc", "FxLineHandler");

        // All six call sites must return the very same instance so call sites
        // stay monomorphic and the JIT can inline the empty methods away.
        assertSame(a, b);
        assertSame(b, c);
        assertSame(c, d);
        assertSame(d, e);
        assertSame(e, f);
    }

    @Test
    void counter_methods_are_inert_and_return_zero() {
        MongooseCounter counter = NoOpCountersService.INSTANCE.feedPublishCounter("fx");

        assertEquals(0L, counter.increment());
        assertEquals(0L, counter.incrementRelease());
        assertEquals(0L, counter.get());
        counter.setOrdered(123L);
        assertEquals(0L, counter.get());

        for (int i = 0; i < 1_000; i++) {
            counter.increment();
        }
        assertEquals(0L, counter.get());
    }

    @Test
    void forEachCounter_visits_nothing() {
        AtomicInteger visited = new AtomicInteger();
        NoOpCountersService.INSTANCE.forEachCounter((id, label, value) -> visited.incrementAndGet());

        // Register a few handles first to make sure forEach still visits nothing.
        NoOpCountersService.INSTANCE.feedPublishCounter("fx");
        NoOpCountersService.INSTANCE.agentEventsCounter("priceCalc");
        NoOpCountersService.INSTANCE.forEachCounter((id, label, value) -> visited.incrementAndGet());

        assertEquals(0, visited.get());
    }

    @Test
    void isOperational_is_false() {
        assertFalse(NoOpCountersService.INSTANCE.isOperational());
    }
}
