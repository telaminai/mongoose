/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the canonical static-vs-dynamic processor registration
 * contract at the Mongoose dispatcher level.
 *
 * <p>A statically-registered processor (added to {@link MongooseServerConfig}
 * before boot) and a dynamically-registered processor (added by a service
 * that calls {@code MongooseServerController.addEventProcessor} from its
 * own {@code start()} hook — the path {@code svc-loader-yaml} and
 * {@code svc-loader-spring} use) should behave identically when both follow
 * the canonical pattern of calling {@code feed.subscribe(this, key)} from
 * the processor's own {@code start()} on the {@link EventFeed} handed to
 * them via {@link DataFlow#addEventFeed}.
 *
 * <p>This test wires both paths identically — same {@link RecordingProcessor}
 * implementation, same broadcast feed, same subscribe-in-start() pattern —
 * and asserts both receive the same events. It passes today.
 *
 * <h2>What this test pins down</h2>
 *
 * The dispatcher-level paths are symmetric:
 * <ol>
 *   <li>{@code MongooseServer.addEventProcessor} (called from a service's
 *       {@code start()}) queues the new processor on the group's agent.
 *       Since 1.0.9 the late-start pass in {@link MongooseServer#start()}
 *       starts the agent thread when the registration happens during
 *       service-start.</li>
 *   <li>The agent's {@code checkForAdded()} drains the queue, calls
 *       {@code addEventFeed(this)} on the processor, then invokes
 *       {@code start()} → {@code startComplete()}.</li>
 *   <li>The processor's {@code start()} calls {@code feed.subscribe(this, key)};
 *       the subscription routes events from then on.</li>
 * </ol>
 *
 * <h2>TODO — known seam one layer up</h2>
 *
 * The remaining seam, which this test does NOT exercise, is in the
 * Fluxtion-compile + dynamic-registration combination:
 * <ul>
 *   <li>A handler that extends {@code ObjectEventHandlerNode} and is wrapped
 *       into a {@code DataFlow} by {@code Fluxtion.compile(...)} relies on
 *       compile-time auto-subscription from the {@code @OnEventHandler}
 *       annotation. When that compiled processor is registered statically,
 *       auto-subscription fires during the EventFlowManager wiring phase.
 *       When it is registered dynamically via the loader plugins, the
 *       handler's {@code start()} does not re-subscribe, and events do
 *       not arrive.</li>
 *   <li>That seam is documented and fails noisily in
 *       {@code mongoose-plugins/test-support/mongoose-test-support/.../DynamicProcessorRegistrationTest}.
 *       The fix likely lives in fluxtion-builder's generated
 *       {@code addEventFeed(EventFeed)} implementation rather than in
 *       Mongoose; flag this comment if you move it.</li>
 * </ul>
 */
public class DynamicProcessorRegistrationTest {

    private static final String FEED_NAME = "feed";

    @Test
    void dynamic_processor_should_receive_same_events_as_static_processor() throws Exception {
        RecordingProcessor staticProc = new RecordingProcessor("static");
        DynamicRegistrarService registrar = new DynamicRegistrarService();
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();

        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor(staticProc, "static-processor");
        cfg.addEventSourceWorker(feed, FEED_NAME, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        cfg.addService(registrar, DynamicRegistrarService.class, "dynamic-registrar");

        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            // Wait for the registrar to install its dynamic processor.
            awaitTrue(() -> registrar.installed && registrar.dynamicProc != null
                    && registrar.dynamicProc.startCalled);

            feed.offer("a");
            feed.offer("b");
            feed.offer("c");

            // Static path: the documented contract. Sanity-check that the test wiring works.
            awaitTrue(() -> staticProc.received.get() >= 3);
            assertEquals(3, staticProc.received.get(),
                    "static processor should see all 3 events");

            // Sanity-check: the dynamic processor's lifecycle DID run.
            assertTrue(registrar.dynamicProc.initCalled, "dynamic processor init() should fire");
            assertTrue(registrar.dynamicProc.startCalled, "dynamic processor start() should fire");
            assertTrue(registrar.dynamicProc.subscribedTo(FEED_NAME),
                    "dynamic processor should have called feed.subscribe(...) in start()");

            // The dispatcher is symmetric: a dynamically-registered processor
            // that follows the canonical subscribe-in-start() pattern receives
            // every event a statically-registered processor with the same code
            // would. This passes today; it is here as a regression guard.
            assertEquals(3, registrar.dynamicProc.received.get(),
                    "dynamic processor should also see all 3 events through the same subscription");
        } finally {
            server.stop();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
        // not asserting here; the caller will assert on the underlying state and
        // produce a clearer failure message than "timed out waiting".
    }

    /**
     * Hand-rolled DataFlow that records lifecycle hits and counts events received.
     * Subscribes to {@link #FEED_NAME} on the EventFeed handed to it via
     * {@link #addEventFeed} — the canonical pattern from
     * {@code EndToEndEventFlowIT.TestEventProcessor}.
     */
    public static class RecordingProcessor implements DataFlow {
        public final String label;
        public final AtomicInteger received = new AtomicInteger();
        public volatile boolean initCalled;
        public volatile boolean startCalled;

        private final List<EventFeed> eventFeeds = new ArrayList<>();
        private final List<String> subscribedFeeds = new ArrayList<>();

        public RecordingProcessor(String label) {
            this.label = label;
        }

        @Override public void init() { initCalled = true; }

        @Override
        public void start() {
            startCalled = true;
            EventSubscriptionKey<Object> key = new EventSubscriptionKey<>(
                    new EventSourceKey<>(FEED_NAME),
                    CallBackType.ON_EVENT_CALL_BACK);
            for (EventFeed f : eventFeeds) {
                f.subscribe(this, key);
                subscribedFeeds.add(FEED_NAME);
            }
        }

        @Override public void tearDown() { }

        @Override
        public void addEventFeed(EventFeed eventFeed) {
            eventFeeds.add(eventFeed);
        }

        @Override
        public void onEvent(Object event) {
            if (event instanceof String) {
                received.incrementAndGet();
            }
        }

        public boolean subscribedTo(String feedName) {
            return subscribedFeeds.contains(feedName);
        }
    }
}
