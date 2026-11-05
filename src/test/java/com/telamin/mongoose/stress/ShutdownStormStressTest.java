/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.stress;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.AddedProcessor;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.EventProcessorGroupConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Item 4 of the stress campaign: a shutdown storm. Repeated boot, load, shutdown cycles, each with a short
 * {@code shutdownTimeoutMillis}, feeds publishing and admin threads adding and stopping processors through the stop, a
 * second concurrent shutdown, and hanging and throwing components: processors whose stop() hangs (ignoring interrupts)
 * or throws, a tearDown that throws, a service whose stop() hangs or throws. Each shutdown must return within its
 * deadline plus a margin with a truthful StopResult; a resumed stop must complete once the blocker is released, without
 * stopping anything twice; and no thread may leak across cycles. A separate service-only case verifies that a failed
 * service stop remains incomplete and is not retried; releasing a blocker does not erase a recorded stop failure.
 * <p>
 * Scale: {@code -Dmongoose.stress.shutdown.cycles} (default 20), {@code -Dmongoose.stress.seed}.
 */
@EnabledIfSystemProperty(named = "mongoose.stress", matches = "true")
class ShutdownStormStressTest {

    static final long MARGIN_MILLIS = Long.getLong("mongoose.stress.shutdown.marginMillis", 1_000);

    @BeforeAll
    static void quiet() {
        StressSupport.quietLogging();
    }

    /** A processor with the faults of its cycle. Stops and tearDowns are counted, so a double stop shows. */
    static final class StormProcessor implements DataFlow {
        final boolean hangStop, throwStop, throwTearDown, throwHandler;
        final CountDownLatch release;
        final CountDownLatch stopEntered = new CountDownLatch(1);
        final AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger(), tearDowns = new AtomicInteger();
        final List<EventFeed> feeds = new ArrayList<>();
        final AtomicInteger received = new AtomicInteger();

        StormProcessor(boolean hangStop, boolean throwStop, boolean throwTearDown, boolean throwHandler, CountDownLatch release) {
            this.hangStop = hangStop;
            this.throwStop = throwStop;
            this.throwTearDown = throwTearDown;
            this.throwHandler = throwHandler;
            this.release = release;
        }

        @Override public void init() { }

        @Override
        public void addEventFeed(EventFeed feed) {
            feeds.add(feed);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void start() {
            starts.incrementAndGet();
            EventSubscriptionKey<Object> key = new EventSubscriptionKey<>(new EventSourceKey<>("storm-feed"),
                    CallBackType.ON_EVENT_CALL_BACK);
            for (EventFeed f : feeds) f.subscribe(this, key);
        }

        @Override
        public void onEvent(Object event) {
            int count = received.incrementAndGet();
            if (throwHandler && count % 1000 == 0) throw new IllegalStateException("STORM handler");
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
            stopEntered.countDown();
            if (hangStop) awaitIgnoringInterrupts(release);
            if (throwStop) throw new IllegalStateException("STORM stop failure");
        }

        @Override
        public void tearDown() {
            tearDowns.incrementAndGet();
            if (throwTearDown) throw new IllegalStateException("STORM tearDown failure");
        }
    }

    /** A service whose stop() hangs (ignoring interrupts) or throws. */
    static final class StormService implements Lifecycle {
        final boolean hang, fail;
        final CountDownLatch release;
        final AtomicInteger stops = new AtomicInteger();

        StormService(boolean hang, boolean fail, CountDownLatch release) {
            this.hang = hang;
            this.fail = fail;
            this.release = release;
        }

        @Override public void init() { }
        @Override public void start() { }
        @Override public void tearDown() { }

        @Override
        public void stop() {
            stops.incrementAndGet();
            if (hang) awaitIgnoringInterrupts(release);
            if (fail) throw new IllegalStateException("STORM service stop failure");
        }
    }

    static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                if (latch.await(60, TimeUnit.SECONDS)) break;
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    record Cycle(int index, long timeoutMillis, boolean hangStop, boolean hangService, long firstMillis, StopResult first,
                 long resumedMillis, StopResult resumed, List<String> failures) { }

    @Test
    void repeatedBootLoadShutdown_isBoundedTruthfulResumableAndLeaksNoThreads() throws Exception {
        int cycles = Integer.getInteger("mongoose.stress.shutdown.cycles", 20);
        long seed = Long.getLong("mongoose.stress.seed", System.nanoTime());
        System.out.println("shutdown storm: cycles=" + cycles + " seed=" + seed);
        List<String> baselineNonDaemon = StressSupport.nonDaemonThreads();
        int baselineThreads = StressSupport.threadCount();
        List<Cycle> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cycles; i++) {
            Cycle c = cycle(i, new Random(seed * 1_000 + i));
            results.add(c);
            failures.addAll(c.failures());
            System.out.printf("cycle %2d timeout=%dms hangStop=%s hangService=%s first=%dms %s resumed=%dms %s%n", i,
                    c.timeoutMillis(), c.hangStop(), c.hangService(), c.firstMillis(), brief(c.first()),
                    c.resumedMillis(), c.resumed() == null ? "-" : brief(c.resumed()));
            c.failures().forEach(f -> System.out.println("   FAIL " + f));
        }
        // no thread leaks across cycles
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<String> leaked;
        List<String> nonDaemon;
        do {
            Thread.sleep(100);
            leaked = StressSupport.serverThreads(List.of("storm-", "mongoose-stop"));
            nonDaemon = StressSupport.nonDaemonThreads();
            nonDaemon.removeAll(baselineNonDaemon);
        } while ((!leaked.isEmpty() || !nonDaemon.isEmpty()) && System.nanoTime() < deadline);
        if (!leaked.isEmpty()) failures.add("server threads alive after every cycle ended: " + leaked);
        if (!nonDaemon.isEmpty()) failures.add("non-daemon threads leaked: " + nonDaemon);
        long maxFirst = results.stream().mapToLong(c -> c.firstMillis() - c.timeoutMillis()).max().orElse(0);
        System.out.println("threads baseline=" + baselineThreads + " after=" + StressSupport.threadCount()
                + "; worst overrun of a first shutdown past its deadline: " + maxFirst + " ms");
        assertEquals(List.of(), failures, "shutdown storm");
    }

    private static String brief(StopResult r) {
        return r.complete() ? "complete" : (r.handedOff() ? "handedOff" : "incomplete groups=" + r.groupsRunning()
                + " services=" + r.servicesLeftRunning().size() + (r.blockedStartup() == null ? "" : " blocked"));
    }

    private Cycle cycle(int index, Random random) throws Exception {
        List<String> failures = new ArrayList<>();
        long timeout = 300 + random.nextInt(500);
        boolean hangStop = random.nextInt(3) == 0;
        boolean hangService = random.nextInt(3) == 0;
        CountDownLatch release = new CountDownLatch(1);
        InMemoryEventSource<Object> feed = new InMemoryEventSource<>();
        feed.setName("storm-feed");
        MongooseServerConfig.Builder builder = MongooseServerConfig.builder()
                .shutdownTimeoutMillis(timeout)
                .shutdownDrainTimeoutMillis(50)
                .addEventFeed(EventFeedConfig.builder().instance(feed).name("storm-feed").broadcast(true)
                        .agent("storm-feed-agent", new BackoffIdleStrategy()).build());
        int groups = 2 + random.nextInt(2);
        List<StormProcessor> processors = new ArrayList<>();
        for (int g = 0; g < groups; g++) {
            EventProcessorGroupConfig.Builder group = EventProcessorGroupConfig.builder().agentName("storm-g" + g)
                    .idleStrategy(new BackoffIdleStrategy());
            for (int p = 0; p < 3; p++) {
                boolean hang = hangStop && g == 0 && p == 0;
                StormProcessor proc = new StormProcessor(hang, random.nextInt(4) == 0, random.nextInt(4) == 0,
                        random.nextInt(4) == 0, release);
                processors.add(proc);
                group.put("p" + p, EventProcessorConfig.builder().handler(proc).build());
            }
            builder.addProcessorGroup(group.build());
        }
        MongooseServer server = MongooseServer.bootServer(builder.build(), rec -> { });
        AtomicBoolean loading = new AtomicBoolean(true);
        List<Thread> actors = new ArrayList<>();
        try {
            StormService hanging = new StormService(hangService, false, release);
            StormService ordinary = new StormService(false, false, release);
            server.registerService(new Service<>(hanging, Lifecycle.class, "storm-hanging-service"));
            server.registerService(new Service<>(ordinary, Lifecycle.class, "storm-ordinary-service"));

            // load: a feed publishing and an admin thread adding and stopping processors, on through the stop
            Thread feeder = new Thread(() -> {
                long seq = 0;
                while (loading.get()) {
                    feed.offer("tick-" + seq++);
                    if (seq % 64 == 0) Thread.onSpinWait();
                    if (seq % 4096 == 0) ChaosWorkload.sleep(1);
                }
            }, "storm-feeder-" + index);
            List<StormProcessor> added = new java.util.concurrent.CopyOnWriteArrayList<>();
            AtomicInteger adminErrors = new AtomicInteger();
            AtomicReference<Throwable> adminFailure = new AtomicReference<>();
            Thread admin = new Thread(() -> {
                Random r = new Random(random.nextLong());
                int n = 0;
                while (loading.get()) {
                    try {
                        String group = "storm-g" + r.nextInt(groups);
                        String name = "dyn-" + (n++ % 8);
                        if (r.nextBoolean()) {
                            AddedProcessor a = server.addProcessor(name, group, null, () -> {
                                StormProcessor proc = new StormProcessor(false, r.nextInt(5) == 0, false, false, release);
                                added.add(proc);
                                return proc;
                            });
                            a.reply().toCompletableFuture().get(30, TimeUnit.SECONDS);
                        } else {
                            var h = server.processorHandle(group, name);
                            if (h != null) server.stopProcessor(h).toCompletableFuture().get(30, TimeUnit.SECONDS);
                        }
                    } catch (java.util.concurrent.TimeoutException hung) {
                        adminFailure.compareAndSet(null, hung);
                    } catch (Throwable t) {
                        adminErrors.incrementAndGet();
                        adminFailure.compareAndSet(null, t);
                    }
                    ChaosWorkload.sleep(r.nextInt(5));
                }
            }, "storm-admin-" + index);
            feeder.setDaemon(true);
            admin.setDaemon(true);
            actors.add(feeder);
            actors.add(admin);
            feeder.start();
            admin.start();
            Thread.sleep(100 + random.nextInt(300));

            // a second shutdown, racing the first
            AtomicReference<StopResult> raced = new AtomicReference<>();
            AtomicReference<Throwable> raceFailure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicLong raceMillis = new java.util.concurrent.atomic.AtomicLong();
            Thread racer = new Thread(() -> {
                long begin = System.nanoTime();
                try { raced.set(server.shutdown()); }
                catch (Throwable failure) { raceFailure.set(failure); }
                finally { raceMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)); }
            }, "storm-racer-" + index);
            racer.setDaemon(true);
            actors.add(racer);
            boolean race = random.nextBoolean();
            if (race) racer.start();
            long t0 = System.nanoTime();
            StopResult first = server.shutdown();
            long firstMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            loading.set(false);
            if (firstMillis > timeout + MARGIN_MILLIS) {
                failures.add("cycle " + index + ": shutdown took " + firstMillis + " ms, deadline " + timeout + " ms");
            }
            // truthful: a hung processor stop keeps its group running; a hung service stop keeps the service running
            if (hangStop) {
                if (first.complete() || first.groupsRunning().stream()
                        .noneMatch(name -> name.equals("storm-g0") || name.equals("processor:storm-g0"))) {
                    failures.add("cycle " + index + ": a hung processor stop was not reported: " + first);
                }
            } else if (hangService) {
                if (first.complete() || !first.servicesLeftRunning().contains("storm-hanging-service")) {
                    failures.add("cycle " + index + ": a hung service stop was not reported: " + first);
                }
            } else if (!first.complete()) {
                // A finite feed backlog may still consume this short deadline. It is truthful to report remaining work,
                // but not to return an unexplained early failure; after load ends the resumed attempt must finish.
                if (firstMillis + 25 < timeout || (first.groupsRunning().isEmpty() && first.servicesLeftRunning().isEmpty())) {
                    failures.add("cycle " + index + ": incomplete without exhausted deadline or pending work: " + first);
                }
            }
            if ((hangStop || hangService) && !first.complete() && server.awaitStopped(10, TimeUnit.MILLISECONDS)) {
                failures.add("cycle " + index + ": an incomplete stop reported the server stopped");
            }
            StopResult resumed = null;
            long resumedMillis = 0;
            if (!first.complete()) {
                release.countDown();
                long r0 = System.nanoTime();
                resumed = server.shutdown();
                resumedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - r0);
                if (!resumed.complete()) failures.add("cycle " + index + ": the resumed stop did not complete: " + resumed);
                if (resumedMillis > timeout + MARGIN_MILLIS) {
                    failures.add("cycle " + index + ": the resumed stop took " + resumedMillis + " ms, deadline " + timeout);
                }
            }
            release.countDown();
            if (race) {
                racer.join(timeout + 5_000);
                if (racer.isAlive()) failures.add("cycle " + index + ": the racing shutdown did not return");
                if (raced.get() == null || raceFailure.get() != null)
                    failures.add("cycle " + index + ": racing shutdown failed: " + raceFailure.get());
                if (raceMillis.get() > timeout + MARGIN_MILLIS)
                    failures.add("cycle " + index + ": racing shutdown exceeded its deadline: " + raceMillis.get());
            }
            feeder.join(5_000);
            admin.join(35_000);
            if (admin.isAlive()) failures.add("cycle " + index + ": the admin thread hung in a control call");
            if (adminFailure.get() != null) failures.add("cycle " + index + ": a control reply never came: " + adminFailure.get());
            if (!server.awaitStopped(10, TimeUnit.SECONDS)) failures.add("cycle " + index + ": the server never reported stopped");
            // nothing stopped twice, every started processor stopped once, every service stopped once
            List<StormProcessor> all = new ArrayList<>(processors);
            all.addAll(added);
            for (StormProcessor p : all) {
                if (p.starts.get() > 1 || p.stops.get() > 1 || p.tearDowns.get() > 1) {
                    failures.add("cycle " + index + ": lifecycle repeated: starts=" + p.starts + " stops=" + p.stops
                            + " tearDowns=" + p.tearDowns);
                }
                if (p.starts.get() == 1 && (p.stops.get() != 1 || p.tearDowns.get() != 1)) {
                    failures.add("cycle " + index + ": a started processor was not stopped and torn down once: stops="
                            + p.stops + " tearDowns=" + p.tearDowns);
                }
            }
            if (hanging.stops.get() != 1 || ordinary.stops.get() != 1) {
                failures.add("cycle " + index + ": services stopped " + hanging.stops + " and " + ordinary.stops + " times");
            }
            // this cycle's threads end
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<String> left;
            do {
                left = StressSupport.serverThreads(List.of("storm-g", "storm-feed-agent", "mongoose-stop"));
                if (left.isEmpty()) break;
                Thread.sleep(50);
            } while (System.nanoTime() < until);
            if (!left.isEmpty()) failures.add("cycle " + index + ": threads left after the stop: " + left);
            return new Cycle(index, timeout, hangStop, hangService, firstMillis, first, resumedMillis, resumed, failures);
        } finally {
            loading.set(false);
            release.countDown();
            boolean interrupted = Thread.interrupted();
            try {
                try {
                    server.shutdown();
                } finally {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    for (Thread actor : actors) {
                        long left = deadline - System.nanoTime();
                        if (left > 0) {
                            try { TimeUnit.NANOSECONDS.timedJoin(actor, left); }
                            catch (InterruptedException e) { interrupted = true; }
                        }
                        if (actor.isAlive()) failures.add("cycle " + index + ": harness actor remained alive: " + actor.getName());
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void aFailedServiceStopRemainsReportedAndIsNeverRetried() throws Exception {
        MongooseServerConfig config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(500);
        MongooseServer server = MongooseServer.bootServer(config, rec -> { });
        StormService failed = new StormService(false, true, new CountDownLatch(0));
        server.registerService(new Service<>(failed, Lifecycle.class, "failed-stop"));
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                long begin = System.nanoTime();
                StopResult result = server.shutdown();
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 500 + MARGIN_MILLIS);
                assertFalse(result.complete());
                assertEquals(List.of(), result.groupsRunning());
                assertEquals(List.of("failed-stop"), result.servicesLeftRunning());
                assertEquals(1, failed.stops.get(), "ambiguous stop failures must not be retried");
                assertFalse(server.awaitStopped(10, TimeUnit.MILLISECONDS));
            }
        } finally {
            server.shutdown();
        }
    }
}
