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

        final CountDownLatch enteredStart = new CountDownLatch(1);

        public Probe() { this(null, false); }

        Probe(CountDownLatch blockStart, boolean failStart) {
            this.blockStart = blockStart;
            this.failStart = failStart;
        }

        @Override public void init() {}

        @Override
        public void start() {
            enteredStart.countDown();
            // a VirtualMachineError escapes onStart without onClose, so the group's thread ends while not ACTIVE; an
            // ordinary exception would be caught by the group's failure isolation (mongoose#57)
            if (failStart) throw new InternalError("probe start fails");
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
    void startFailsFastWhenAGroupThreadDiesDuringStart() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setStartupTimeoutMillis(30_000);   // a dead group fails start straight away, not at the timeout
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

    @Test
    void startTimeoutNamesEveryGroupNotActive() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setStartupTimeoutMillis(300);
        MongooseServer server = serverWithoutExit(cfg, new AtomicReference<>());
        CountDownLatch release = new CountDownLatch(1);
        server.addEventProcessor("A", "stuck-1", null, () -> new Probe(release, false));
        server.addEventProcessor("B", "stuck-2", null, () -> new Probe(release, false));
        server.addEventProcessor("C", "fine-group", null, Probe::new);
        server.init();
        try {
            Throwable failure = startWithin(server, 5_000);
            assertNotNull(failure, "start fails at the timeout");
            String message = failure.getMessage();
            assertTrue(message.contains("stuck-1") && message.contains("stuck-2"), "every inactive group is named: " + message);
            assertFalse(message.contains("fine-group"), "an active group is not named: " + message);
            assertTrue(message.contains("startupTimeoutMillis"), "the message names the setting: " + message);
        } finally {
            release.countDown();
            server.stop();
        }
    }

    /** A feed whose stop runs a hook: stop() reaches it in its first step, before any group a start waits on closes. */
    public static class HookedFeed extends AbstractAgentHostedEventSourceService<String> {
        final Runnable onStop;

        HookedFeed(Runnable onStop) {
            super("hookedFeed");
            this.onStop = onStop;
        }

        @Override public void start() {}

        @Override public void stop() { onStop.run(); }

        @Override public int doWork() { return 0; }
    }

    /**
     * A stop that lands while start waits for a group to become active fails start with "stopped during start", not
     * the misleading "closed before becoming active (did onStart throw?)".
     */
    @Test
    void stopWhileStartWaitsForAGroupReportsStoppedDuringStart() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Probe blocked = new Probe(release, false);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addEventSourceWorker(new HookedFeed(release::countDown), FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        AtomicReference<Throwable> errors = new AtomicReference<>();
        MongooseServer server = serverWithoutExit(cfg, errors);
        cfg.getEventFeeds().forEach(f -> server.registerEventFeedWorker(f.toServiceAgent(), f.getValueMapper()));
        server.addEventProcessor("A", "g", null, () -> blocked);
        server.init();
        MongooseServer s = server;
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                s.start();
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "starter");
        starter.start();
        assertTrue(blocked.enteredStart.await(5, TimeUnit.SECONDS), "the processor group is starting");

        server.stop();   // the feed's stop releases the processor, after the stop has begun
        starter.join(5_000);

        assertFalse(starter.isAlive(), "start returned");
        assertNotNull(thrown.get(), "start fails when the server is stopped during it");
        assertTrue(thrown.get().getMessage().contains("stopped during start"),
                "start reports the stop: " + thrown.get().getMessage());
        assertFalse(thrown.get().getMessage().contains("did onStart throw"), "not the onStart message: " + thrown.get().getMessage());
    }

    /** A service whose startComplete blocks until its stop runs. */
    public static class GateService implements com.telamin.fluxtion.runtime.lifecycle.Lifecycle {
        final CountDownLatch inStartComplete = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public void init() {}

        @Override public void start() {}

        @Override
        public void startComplete() {
            inStartComplete.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override public void stop() { release.countDown(); }

        @Override public void tearDown() {}
    }

    /**
     * A stop that lands after every group is active but before start has marked the server started: start fails, and
     * the server is not left marked started though it has stopped.
     */
    @Test
    void stopAfterGroupsAreActiveButBeforeStartFinishesLeavesTheServerStopped() throws Exception {
        GateService gate = new GateService();
        MongooseServer server = serverWithoutExit(new MongooseServerConfig(), new AtomicReference<>());
        server.registerService(new Service<>(gate, GateService.class, "gate"));
        server.addEventProcessor("A", "g", null, Probe::new);
        server.init();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "starter");
        starter.start();
        assertTrue(gate.inStartComplete.await(5, TimeUnit.SECONDS), "start reached startComplete: every group is active");

        server.stop();   // the gate service's stop lets startComplete, and so start, return
        starter.join(5_000);

        assertFalse(starter.isAlive(), "start returned");
        assertNotNull(thrown.get(), "start fails when the server is stopped before it finishes");
        assertTrue(thrown.get().getMessage().contains("stopped during start"), "start reports the stop: " + thrown.get().getMessage());
        assertFalse(server.isStarted(), "a server stopped during start is not marked started");
    }

    /** A processor whose wiring (after the supplier returns it) fails. */
    public static class FailingWiring extends Probe {
        @Override
        public void setAuditLogProcessor(com.telamin.fluxtion.runtime.audit.LogRecordListener listener) {
            throw new IllegalStateException("wiring fails");
        }
    }

    @Test
    void nameIsReleasedWhenWiringAfterTheSupplierFails() throws Exception {
        AtomicReference<Throwable> errors = new AtomicReference<>();
        MongooseServer server = serverWithoutExit(new MongooseServerConfig(), errors);
        Probe seed = new Probe();
        server.addEventProcessor("seed", "g", null, () -> seed);
        server.init();
        server.start();
        try {
            await(() -> seed.started, "the group runs");
            server.addEventProcessor("A", "g", null, FailingWiring::new);
            Probe replacement = new Probe();
            long end = System.nanoTime() + 5_000_000_000L;
            boolean added = false;
            while (!added && System.nanoTime() < end) {
                try {
                    server.addEventProcessor("A", "g", null, () -> replacement);
                    added = true;
                } catch (IllegalArgumentException stillTaken) {
                    Thread.sleep(5);
                }
            }
            assertTrue(added, "a name whose processor failed to wire is freed");
            await(() -> replacement.started, "the processor re-added under the freed name starts");
        } finally {
            server.stop();
        }
    }

    @Test
    void duplicateProcessorNamesInConfigFailBoot() {
        String yaml = """
                eventHandlers:
                  - agentName: dup-group
                    eventHandlers:
                      twin:
                        eventHandler: !!com.telamin.mongoose.internal.LifecycleRacesTest$Probe { }
                  - agentName: dup-group
                    eventHandlers:
                      twin:
                        eventHandler: !!com.telamin.mongoose.internal.LifecycleRacesTest$Probe { }
                """;
        Throwable failure = assertThrows(Throwable.class,
                () -> MongooseServer.bootServer(new java.io.StringReader(yaml), r -> {}),
                "a processor name configured twice in one group fails boot");
        assertTrue(failure.getMessage().contains("twin") && failure.getMessage().contains("dup-group"),
                "the failure names the processor and the group: " + failure.getMessage());
    }

    @Test
    void startAfterStopIsRefused() throws Exception {
        MongooseServer server = serverWithoutExit(new MongooseServerConfig(), new AtomicReference<>());
        server.addEventProcessor("A", "g", null, Probe::new);
        server.init();
        server.start();
        server.stop();
        assertThrows(IllegalStateException.class, server::start, "a stopped server does not start again");
    }

    @Test
    void startupTimeoutIsConfigurableFromYamlAndTheBuilder() {
        MongooseServerConfig fromYaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.LoaderOptions())
                .loadAs("startupTimeoutMillis: 4321\n", MongooseServerConfig.class);
        assertEquals(4321, fromYaml.getStartupTimeoutMillis(), "YAML key startupTimeoutMillis");
        assertEquals(MongooseServerConfig.DEFAULT_STARTUP_TIMEOUT_MILLIS,
                MongooseServerConfig.builder().build().getStartupTimeoutMillis(), "builder default");
        assertEquals(250, MongooseServerConfig.builder().startupTimeoutMillis(250).build().getStartupTimeoutMillis(),
                "builder setter");
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
