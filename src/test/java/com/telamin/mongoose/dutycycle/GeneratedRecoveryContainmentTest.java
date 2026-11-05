/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedRecoveryContainmentTest {
    /** Uses the committed AOT processor's real entry/exit and callback-dispatch code. */
    static class Probe extends GroupControlProcessor {
        final List<String> effects = new ArrayList<>();
        Probe() { init(); start(); }
        @Override public void onEventInternal(Object event) {
            if (event instanceof String value) {
                if (value.equals("boom")) {
                    effects.add("before-throw");
                    onEvent("stale");
                    throw new IllegalStateException("after a non-idempotent effect");
                }
                effects.add(value);
            } else {
                super.onEventInternal(event);
            }
        }
    }

    @Test void regeneratedCycleDiscardsStaleWorkButDoesNotRollBackEffects() {
        Probe graph = new Probe();
        assertThrows(IllegalStateException.class, () -> graph.onEvent("boom"));
        graph.onEvent("fresh");
        assertEquals(List.of("before-throw", "fresh"), graph.effects,
                "cleanup restores dispatch, discards queued work, and leaves the earlier effect intact");
        graph.stop(); graph.tearDown();
    }

    @Test void hostStillIsolatesRecoverableGraphWithoutRetryingItsSideEffect() {
        Probe graph = new Probe();
        var queue = new OneToOneConcurrentArrayQueue<Object>(16);
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "recovered-cycle");
        var healthy = new AtomicInteger();
        reader.registerProcessor(graph);
        reader.registerProcessor((DataFlow) event -> healthy.incrementAndGet());
        try {
            queue.offer("boom"); queue.offer("fresh");
            assertEquals(2, reader.doWork());
            assertEquals(List.of("before-throw"), graph.effects, "no retry or later event may repeat external work");
            assertEquals(2, healthy.get(), "healthy subscribers continue exactly once per event");
            assertEquals(1, reader.listenerCount());
        } finally {
            reader.onClose();
        }
    }
}
