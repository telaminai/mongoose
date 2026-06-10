/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.spike;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.connector.DataFlowConnector;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SPIKE — proves a Mongoose {@link InMemoryEventSource} can drive a fluxtion
 * {@code DataFlowConnector} via {@link EventSourceFeedAgent}. Publishing through the <em>Mongoose</em>
 * API ({@code source.offer(...)}) reaches a DataFlow hosted by the <em>fluxtion</em> connector.
 */
public class EventSourceFeedAgentSpikeTest {

    @Test
    void mongooseEventSource_hostedInside_dataFlowConnector() throws Exception {
        InMemoryEventSource<String> source = new InMemoryEventSource<>();
        EventSourceFeedAgent<String> feed = new EventSourceFeedAgent<>("mongoose-feed", source);

        CapturingDataFlow flow = new CapturingDataFlow();
        DataFlowConnector connector = new DataFlowConnector();
        connector.addFeed(feed).addDataFlow(flow).start();
        try {
            awaitTrue(feed::isStarted);
            source.offer("hello");          // publish via the MONGOOSE EventSource API
            source.offer("world");
            awaitTrue(() -> flow.payloads().size() >= 2);
        } finally {
            connector.stop();
        }
        assertEquals(List.of("hello", "world"), flow.payloads(),
                "events offered to the Mongoose EventSource should reach the fluxtion DataFlow");
    }

    /** Minimal hand-rolled DataFlow (mongoose has no fluxtion-builder for the DSL) that records
     *  what the connector delivers; unwraps the connector's NamedFeedEvent to the raw payload. */
    static class CapturingDataFlow implements DataFlow {
        private final List<Object> received = Collections.synchronizedList(new ArrayList<>());

        List<Object> payloads() {
            List<Object> out = new ArrayList<>();
            synchronized (received) {
                for (Object e : received) {
                    out.add(e instanceof NamedFeedEvent ? ((NamedFeedEvent<?>) e).data() : e);
                }
            }
            return out;
        }

        @Override public void init() { }
        @Override public void start() { }
        @Override public void tearDown() { }
        @Override public void addEventFeed(EventFeed eventFeed) { }
        @Override public void onEvent(Object e) { received.add(e); }
    }

    private static void awaitTrue(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(10);
        }
    }
}
