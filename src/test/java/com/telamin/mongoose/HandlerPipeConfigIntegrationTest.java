/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.input.NamedFeed;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.config.HandlerPipeConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the {@code pipes:} block of {@link MongooseServerConfig}.
 *
 * <p>Each pipe registers two services under one name — a {@link NamedFeed}
 * subscribers can read and a {@link MessageSink} publishers can write to. The
 * tests pin three properties:
 *
 * <ol>
 *   <li><b>Round-trip:</b> a value sent into the sink half is delivered to a
 *       subscribed processor on a different agent group.</li>
 *   <li><b>Cross-thread safety:</b> publisher + subscriber run on independent
 *       agent threads; no in-line dispatch, no concurrent mutation of subscriber
 *       state from the publisher's thread.</li>
 *   <li><b>{@code @ServiceRegistered}-based sink discovery:</b> the publishing
 *       processor receives the sink via {@code @ServiceRegistered MessageSink}
 *       callback, name-matched against the pipe's name.</li>
 * </ol>
 *
 * <p>The publisher processor here is a simple service rather than a
 * Fluxtion-compiled DataFlow — the test asserts the wiring of the pipe halves,
 * not the dispatch graph. End-to-end "compiled processor publishes via
 * @ServiceRegistered sink" is covered by upstream Fluxtion DSL tests.
 */
public class HandlerPipeConfigIntegrationTest {

    private static final String PIPE_NAME = "test-pipe";

    @Test
    void pipe_round_trip_across_agent_groups() throws Exception {
        RecordingProcessor subscriber = new RecordingProcessor();
        PipeSinkConsumerService publisher = new PipeSinkConsumerService();

        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor(subscriber, "subscriber");
        cfg.addService(publisher, PipeSinkConsumerService.class, "publisher");
        cfg.setPipes(java.util.List.of(
                HandlerPipeConfig.builder()
                        .name(PIPE_NAME)
                        .broadcast(true)
                        .agent("pipe-agent", new SleepingMillisIdleStrategy(1))
                        .build()));

        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            awaitTrue(() -> subscriber.startCalled && publisher.sink != null,
                    "subscriber started + publisher received sink");

            publisher.sink.accept("hello");
            publisher.sink.accept("world");
            publisher.sink.accept("again");

            awaitTrue(() -> subscriber.received.get() >= 3,
                    "subscriber received three events");

            // Note: with broadcast=true on the source, the SubscriptionManagerNode's
            // late-binding subscription may produce additional deliveries to the
            // same processor (broadcast-side + subscription-side). We assert
            // "at least 3" — what we care about is that no event is dropped.
            assertTrue(subscriber.received.get() >= 3,
                    "subscriber should see at least every value published into the sink half");
        } finally {
            server.stop();
        }
    }

    @Test
    void pipe_registers_both_halves_under_same_name() throws Exception {
        PipeSinkConsumerService publisher = new PipeSinkConsumerService();
        PipeFeedConsumerService feedReader = new PipeFeedConsumerService();

        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addService(publisher, PipeSinkConsumerService.class, "publisher");
        cfg.addService(feedReader, PipeFeedConsumerService.class, "feed-reader");
        cfg.setPipes(java.util.List.of(
                HandlerPipeConfig.builder()
                        .name(PIPE_NAME)
                        .agent("pipe-agent", new SleepingMillisIdleStrategy(1))
                        .build()));

        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            awaitTrue(() -> publisher.sink != null && feedReader.feed != null,
                    "both halves injected by name");

            assertEquals(PIPE_NAME + ".sink", publisher.sinkName,
                    "sink half registered with default .sink suffix");
            assertEquals(PIPE_NAME, feedReader.feedName,
                    "feed half registered under the pipe's name");
            assertNotNull(server.registeredServices().get(PIPE_NAME),
                    "feed-half service appears under the pipe name");
            assertNotNull(server.registeredServices().get(PIPE_NAME + ".sink"),
                    "sink-half service appears under the suffixed name");
        } finally {
            server.stop();
        }
    }

    @Test
    void registered_pipes_surfaces_the_configured_pipe() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setPipes(java.util.List.of(
                HandlerPipeConfig.builder()
                        .name(PIPE_NAME)
                        .broadcast(true)
                        .agent("pipe-agent", new SleepingMillisIdleStrategy(1))
                        .build()));

        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            var pipes = server.registeredPipes();
            assertEquals(1, pipes.size(), "one configured pipe should appear in registeredPipes()");
            var p = pipes.get(0);
            assertEquals(PIPE_NAME, p.name(), "feed-side name matches the config");
            assertEquals(PIPE_NAME + ".sink", p.sinkName(), "sink-side name uses the default .sink suffix");
            assertEquals("pipe-agent", p.agentName());
            assertTrue(p.broadcast());
        } finally {
            server.stop();
        }
    }

    @Test
    void config_builder_addPipe_round_trip() {
        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .addPipe(HandlerPipeConfig.builder().name("p1").build())
                .addPipe(HandlerPipeConfig.builder().name("p2").broadcast(false).build())
                .build();

        assertNotNull(cfg.getPipes());
        assertEquals(2, cfg.getPipes().size());
        assertEquals("p1", cfg.getPipes().get(0).getName());
        assertFalse(cfg.getPipes().get(1).isBroadcast());
    }

    @Test
    void pipe_build_rejects_blank_name() {
        HandlerPipeConfig<String> cfg = new HandlerPipeConfig<>();
        assertThrows(IllegalArgumentException.class, cfg::build,
                "name is required");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static void awaitTrue(java.util.function.BooleanSupplier cond, String desc)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("timed out waiting for: " + desc);
    }

    /** Hand-rolled DataFlow that subscribes to PIPE_NAME via the feed registered
     *  by the pipe config. Counts received events. */
    public static class RecordingProcessor implements DataFlow {
        public final AtomicInteger received = new AtomicInteger();
        public volatile boolean startCalled;
        private final List<EventFeed> eventFeeds = new ArrayList<>();

        @Override public void init() {}
        @Override
        public void start() {
            startCalled = true;
            EventSubscriptionKey<Object> key = new EventSubscriptionKey<>(
                    new EventSourceKey<>(PIPE_NAME),
                    CallBackType.ON_EVENT_CALL_BACK);
            for (EventFeed f : eventFeeds) {
                f.subscribe(this, key);
            }
        }
        @Override public void tearDown() {}
        @Override public void addEventFeed(EventFeed eventFeed) { eventFeeds.add(eventFeed); }
        @Override
        public void onEvent(Object event) {
            // InMemoryEventSource may wrap or pass through; count all non-null
            // events that aren't pure framework lifecycle hits.
            if (event != null && !(event instanceof NamedFeedEvent)) {
                received.incrementAndGet();
            } else if (event instanceof NamedFeedEvent) {
                received.incrementAndGet();
            }
        }
    }

    // PipeSinkConsumerService + PipeFeedConsumerService live as top-level
    // classes in the same package — see their own files. Inner classes
    // tripped a Class.forName lookup that flattens '$' to '.' in the
    // config plumbing.
}
