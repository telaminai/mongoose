/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.test.util.EventFixtures;
import com.telamin.mongoose.test.util.TestNameUtil;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-style randomized tests for event handler behavior without adding
 * external property-testing libraries. Each repetition picks a random
 * sequence and asserts basic invariants:
 * - all published events are observed
 * - ordering is preserved for a single source
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EventHandlerRandomizedTest {

    private static final class IntEvent {
        final int v;

        IntEvent(int v) {
            this.v = v;
        }

        @Override
        public String toString() {
            return "IntEvent(" + v + ")";
        }
    }

    private static final class IntSource extends EventFixtures.EventSourceStub<IntEvent> {
        public IntSource(String name) {
            super(name);
        }
    }

    private static final class RecordingProcessor implements DataFlow {
        @Override
        public void init() {
        }

        @Override
        public void tearDown() {
        }

        private final CountDownLatch latch;
        private final List<EventFeed> feeds = new ArrayList<>();
        private final List<IntEvent> seen = new ArrayList<>();
        private final String feedName;

        RecordingProcessor(CountDownLatch latch, String feedName) {
            this.latch = latch;
            this.feedName = feedName;
        }

        @Override
        public void addEventFeed(EventFeed eventFeed) {
            feeds.add(eventFeed);
        }

        @Override
        public void onEvent(Object event) {
            if (event instanceof IntEvent ie) {
                seen.add(ie);
                latch.countDown();
            }
        }

        @Override
        public void start() {
            EventSubscriptionKey<Object> key = EventSubscriptionKey.onEvent(feedName);
            feeds.forEach(f -> f.subscribe(this, key));
        }
    }

    @RepeatedTest(5)
    void randomized_sequence_is_delivered_in_order() throws Exception {
        long seed = System.nanoTime();
        Random rnd = new Random(seed);
        String feedBase = "randFeed";
        String feedName = TestNameUtil.unique(feedBase);
        IntSource source = new IntSource(feedName);

        int count = 100 + rnd.nextInt(200);
        CountDownLatch latch = new CountDownLatch(count);
        RecordingProcessor proc = new RecordingProcessor(latch, feedName);

        EventFixtures.Harness<IntEvent, IntSource, RecordingProcessor> h = EventFixtures.bootOneSourceOneProcessor(
                source, feedBase, proc, "randProc");
        try {
            List<IntEvent> published = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                IntEvent e = new IntEvent(rnd.nextInt());
                published.add(e);
                source.publish(e);
            }
            boolean ok = latch.await(30, TimeUnit.SECONDS);
            assertTrue(ok, "Timeout waiting for events. seed=" + seed + ", count=" + count);

            // assert size
            assertEquals(published.size(), proc.seen.size(), "All events should be seen. seed=" + seed);
            // assert order
            for (int i = 0; i < count; i++) {
                assertSame(published.get(i), proc.seen.get(i), "Order mismatch at index " + i + ", seed=" + seed);
            }
        } finally {
            h.stop();
        }
    }
}
