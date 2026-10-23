/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.exception.ServiceRegistrationException;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mongoose#60 items 2–4: atomic name reservation for processors and services, a bounded startup wait that names the
 * group that never became active, and a shutdown that stays quick while a feed is busy publishing.
 */
class LifecycleRacesTest {

    static final String FEED = "feed";

    /** A processor whose start can block or throw, and which counts what it receives. */
    public static class Probe implements DataFlow {
        final AtomicInteger received = new AtomicInteger();
        final List<EventFeed> feeds = new ArrayList<>();
        final CountDownLatch blockStart;
        final boolean failStart;
        boolean subscribeToFeed;   // only where a feed named FEED is registered
        volatile boolean started;

        Probe() { this(null, false); }

        Probe(CountDownLatch blockStart, boolean failStart) {
            this.blockStart = blockStart;
            this.failStart = failStart;
        }

        @Override public void init() {}

        @Override
        public void start() {
            if (failStart) throw new IllegalStateException("probe start fails");
            if (blockStart != null) {
                try {
                    blockStart.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (subscribeToFeed) {
                for (EventFeed f : feeds) {
                    f.subscribe(this, new EventSubscriptionKey<>(new EventSourceKey<>(FEED), CallBackType.ON_EVENT_CALL_BACK));
                }
            }
            started = true;
        }

        volatile boolean stopped;

        @Override public void stop() { stopped = true; }

        @Override public void tearDown() { for (EventFeed f : feeds) f.removeAllSubscriptions(this); }

        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }

        @Override public void onEvent(Object e) { if (e instanceof String) received.incrementAndGet(); }
    }

    /** A feed that publishes a burst on every duty cycle, for as long as its agent runs. */
    public static class BusyFeed extends AbstractAgentHostedEventSourceService<String> {
        final AtomicInteger published = new AtomicInteger();

        BusyFeed() { super("busyFeed"); }

        @Override public void start() {}

        @Override
        public int doWork() {
            for (int i = 0; i < 500; i++) {
                output.publish("e");
                published.incrementAndGet();
            }
            return 500;
        }
    }

    static void await(BooleanSupplier c, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !c.getAsBoolean()) Thread.sleep(10);
        assertTrue(c.getAsBoolean(), "timed out waiting for " + what);
    }

    /** A server built by hand, with an error handler that records instead of exiting the JVM. */
    static MongooseServer serverWithoutExit(MongooseServerConfig cfg, AtomicReference<Throwable> errors) {
        MongooseServer server = new MongooseServer(cfg);
        server.setDefaultErrorHandler(errors::set);
        return server;
    }

    // ---- item 2: duplicate names ----

    @Test
    void sameProcessorNameAddedTwiceBeforeStartIsRefused() {
        MongooseServer server = serverWithoutExit(new MongooseServerConfig(), new AtomicReference<>());
        server.addEventProcessor("A", "g", null, Probe::new);
        assertThrows(IllegalArgumentException.class, () -> server.addEventProcessor("A", "g", null, Probe::new),
                "a second processor named A in group g is refused, even before the group's thread fills its map");
        server.addEventProcessor("A", "other-group", null, Probe::new);   // names are unique per group
    }

    @Test
    void concurrentSameNameAddsExactlyOneSucceeds() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", new Probe(), "seed");
        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            int threads = 8;
            for (int round = 0; round < 20; round++) {
                String name = "dup-" + round;
                AtomicInteger built = new AtomicInteger();
                AtomicInteger succeeded = new AtomicInteger();
                AtomicInteger refused = new AtomicInteger();
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Thread> adders = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    Thread adder = new Thread(() -> {
                        try {
                            barrier.await();
                            server.addEventProcessor(name, "g", null, () -> {
                                built.incrementAndGet();
                                return new Probe();
                            });
                            succeeded.incrementAndGet();
                        } catch (IllegalArgumentException e) {
                            refused.incrementAndGet();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    adders.add(adder);
                    adder.start();
                }
                for (Thread adder : adders) adder.join(5_000);
                assertEquals(1, succeeded.get(), "round " + round + ": exactly one concurrent add of " + name + " succeeds");
                assertEquals(threads - 1, refused.get(), "round " + round + ": the other adds are refused");
                await(() -> built.get() >= 1, "the accepted processor is built");
                Thread.sleep(20);
                assertEquals(1, built.get(), "round " + round + ": only one processor named " + name + " is built");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void stoppedProcessorNameCanBeReused() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        Probe first = new Probe();
        cfg.addProcessor("g", first, "A");
        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            await(() -> first.started, "first A starts");
            server.stopProcessor("g", "A");
            Probe second = new Probe();
            // the name is freed on the group's thread once it has taken the stop; until then a re-add is refused
            long end = System.nanoTime() + 5_000_000_000L;
            boolean added = false;
            while (!added && System.nanoTime() < end) {
                try {
                    server.addEventProcessor("A", "g", null, () -> second);
                    added = true;
                } catch (IllegalArgumentException stillTaken) {
                    Thread.sleep(5);
                }
            }
            assertTrue(added, "the name of a stopped processor is freed");
            await(() -> second.started, "a processor re-added under a stopped name starts");
            assertTrue(first.stopped, "the first A was stopped before the name was reused");
            Thread.sleep(50);
            assertFalse(second.stopped, "the stop meant for the first A does not stop the second");
        } finally {
            server.stop();
        }
    }

    @Test
    void concurrentSameServiceNameRegistersExactlyOnce() throws Exception {
        MongooseServer server = serverWithoutExit(new MongooseServerConfig(), new AtomicReference<>());
        int threads = 8;
        for (int round = 0; round < 3000; round++) {
            String name = "svc-" + round;
            AtomicInteger succeeded = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Thread> registrars = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Thread registrar = new Thread(() -> {
                    try {
                        barrier.await();
                        server.registerService(new Service<>(new Object(), Object.class, name));
                        succeeded.incrementAndGet();
                    } catch (ServiceRegistrationException e) {
                        refused.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                registrars.add(registrar);
                registrar.start();
            }
            for (Thread registrar : registrars) registrar.join(5_000);
            assertEquals(1, succeeded.get(), "round " + round + ": exactly one concurrent registration of " + name + " succeeds");
            assertEquals(threads - 1, refused.get(), "round " + round + ": the other registrations are refused");
        }
    }

    // ---- item 3: bounded startup wait ----

    @Test
    void startFailsWithinTimeoutNamingTheGroupThatNeverBecameActive() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setStartupTimeoutMillis(500);
        AtomicReference<Throwable> errors = new AtomicReference<>();
        MongooseServer server = serverWithoutExit(cfg, errors);
        CountDownLatch release = new CountDownLatch(1);
        server.addEventProcessor("A", "stuck-group", null, () -> new Probe(release, false));
        server.addEventProcessor("B", "fine-group", null, Probe::new);
        server.init();
        try {
            Throwable failure = startWithin(server, 5_000);
            assertNotNull(failure, "start fails rather than waits for ever");
            assertTrue(failure.getMessage().contains("stuck-group"), "the failure names the group: " + failure.getMessage());
            assertFalse(failure.getMessage().contains("fine-group"), "only groups not active are named: " + failure.getMessage());
        } finally {
            release.countDown();
            server.stop();
        }
    }

    @Test
    void startFailsFastWhenAGroupClosesDuringStart() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setStartupTimeoutMillis(30_000);   // a closed group fails start straight away, not at the timeout
        AtomicReference<Throwable> errors = new AtomicReference<>();
        MongooseServer server = serverWithoutExit(cfg, errors);
        server.addEventProcessor("A", "failing-group", null, () -> new Probe(null, true));
        server.init();
        try {
            long t0 = System.nanoTime();
            Throwable failure = startWithin(server, 5_000);
            assertNotNull(failure, "start fails rather than waits for ever");
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5), "start fails well before the timeout");
            assertTrue(failure.getMessage().contains("failing-group"), "the failure names the group: " + failure.getMessage());
            assertNotNull(errors.get(), "the start failure reached the error handler");
        } finally {
            server.stop();
        }
    }

    /** Runs start on another thread; returns what it threw, failing the test if it is still running after the bound. */
    private static Throwable startWithin(MongooseServer server, long millis) throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "starter");
        starter.setDaemon(true);
        starter.start();
        starter.join(millis);
        assertFalse(starter.isAlive(), "server.start() still waiting after " + millis + "ms");
        return thrown.get();
    }

    // ---- item 4: shutdown with a busy feed ----

    @Test
    void stopWithABusyFeedCompletesQuickly() throws Exception {
        BusyFeed feed = new BusyFeed();
        Probe processor = new Probe();
        processor.subscribeToFeed = true;
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor("g", processor, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        await(() -> processor.received.get() > 10_000, "the feed is publishing to the processor");

        long t0 = System.nanoTime();
        server.stop();
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(stopMillis < 1_500, "stop with a busy feed took " + stopMillis + "ms");
    }
}
