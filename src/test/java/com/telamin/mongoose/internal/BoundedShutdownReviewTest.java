package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions from the review of control-plane PR 2 (bounded shutdown): each was a reproduced defect. */
@Timeout(120)
class BoundedShutdownReviewTest {

    static class Flow implements DataFlow, Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger tearDowns = new AtomicInteger();
        volatile boolean started;
        volatile Thread agent;
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { agent = Thread.currentThread(); started = true; }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() { tearDowns.incrementAndGet(); }
    }

    static class SlowStop extends Flow {
        final CountDownLatch release = new CountDownLatch(1);
        @Override public void stop() {
            super.stop();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** A worker service whose agent thread runs submitted tasks. */
    public static class TaskWorker implements Agent {
        final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public int doWork() { Runnable r = tasks.poll(); if (r != null) { r.run(); return 1; } return 0; }
        @Override public String roleName() { return "task-worker"; }
    }

    static MongooseServerConfig cfg(long shutdownTimeoutMillis) {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setShutdownTimeoutMillis(shutdownTimeoutMillis);
        cfg.setShutdownDrainTimeoutMillis(50);
        return cfg;
    }

    static void awaitTrue(java.util.function.BooleanSupplier c, String what) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!c.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(c.getAsBoolean(), what);
    }

    static long ms(long t0) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0); }

    // P1: a processor's own stop() (run on the agent thread inside the group's close) calls shutdown()
    @Test
    void p1_shutdownFromAProcessorStopDuringCloseIsHandedOff() throws Exception {
        MongooseServer s = new MongooseServer(cfg(500));
        AtomicReference<StopResult> inner = new AtomicReference<>();
        AtomicLong innerMs = new AtomicLong(-1);
        Flow p = new Flow() {
            @Override public void stop() {
                super.stop();
                long t0 = System.nanoTime();
                inner.set(s.shutdown());
                innerMs.set(ms(t0));
            }
        };
        s.addEventProcessor("p", "g", null, () -> p);
        s.init();
        s.start();
        awaitTrue(() -> p.started, "started");
        long t0 = System.nanoTime();
        StopResult first = s.shutdown();
        long firstMs = ms(t0);
        awaitTrue(() -> inner.get() != null, "inner stop returned");
        assertTrue(inner.get().handedOff(), "a stop on the agent thread is handed off, got " + inner.get() + " after " + innerMs.get() + "ms");
        assertTrue(first.complete(), "nothing blocks: the first stop completes: " + first);
    }

    // P2: an agent-thread stop on a server whose previous attempt ran out is lost
    @Test
    void p2_agentThreadStopOnAnIncompleteStoppingServerIsNotLost() throws Exception {
        MongooseServerConfig cfg = cfg(500);
        SlowStop slow = new SlowStop();
        TaskWorker worker = new TaskWorker();
        cfg.addProcessor("slow-group", slow, "slow");
        cfg.addWorkerService(worker, Agent.class, "worker", "worker-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer s = MongooseServer.bootServer(cfg);
        awaitTrue(() -> slow.started, "started");
        StopResult first = s.shutdown();
        assertFalse(first.complete(), "first attempt runs out: " + first);
        AtomicReference<StopResult> inner = new AtomicReference<>();
        worker.tasks.add(() -> inner.set(s.shutdown()));
        awaitTrue(() -> inner.get() != null, "worker ran the stop");
        slow.release.countDown();
        boolean stopped = s.awaitStopped(3, TimeUnit.SECONDS);
        assertTrue(inner.get().handedOff());
        assertTrue(stopped, "the handed-off stop resumes and completes once nothing blocks");
    }

    public static class BlockingStopFeed extends AbstractAgentHostedEventSourceService<String> {
        final CountDownLatch release = new CountDownLatch(1);
        BlockingStopFeed() { super("blockingFeed"); }
        @Override public void start() { }
        @Override public int doWork() { return 0; }
        @Override public void stop() {
            try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static class UninterruptibleFeed extends AbstractAgentHostedEventSourceService<String> {
        final CountDownLatch release = new CountDownLatch(1);
        UninterruptibleFeed() { super("stuckFeed"); }
        @Override public void start() { }
        @Override public int doWork() {
            while (true) {
                try { release.await(); return 0; } catch (InterruptedException ignored) { /* a blocking driver that ignores interrupts */ }
            }
        }
    }

    // P3a: phase 2 runs event source stop() inline on the stop thread, outside the deadline
    @Test
    void p3a_feedStopIsBoundedByTheDeadline() throws Exception {
        MongooseServerConfig cfg = cfg(500);
        BlockingStopFeed feed = new BlockingStopFeed();
        Flow p = new Flow();
        cfg.addProcessor("g", p, "p");
        cfg.addEventSourceWorker(feed, "blockingFeed", true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer s = MongooseServer.bootServer(cfg);
        awaitTrue(() -> p.started, "started");
        Thread watchdog = new Thread(() -> { try { Thread.sleep(3_000); } catch (InterruptedException ignored) { } feed.release.countDown(); });
        watchdog.start();
        long t0 = System.nanoTime();
        StopResult r = s.shutdown();
        long took = ms(t0);
        assertTrue(took < 2_000, "a 500 ms deadline bounds the stop, took " + took + "ms: " + r);
    }

    // P3b: phase 2 closes an event-source-only group inline: AgentRunner.close() retries for ever
    @Test
    void p3b_feedGroupCloseIsBoundedByTheDeadline() throws Exception {
        MongooseServerConfig cfg = cfg(500);
        UninterruptibleFeed feed = new UninterruptibleFeed();
        Flow p = new Flow();
        cfg.addProcessor("g", p, "p");
        cfg.addEventSourceWorker(feed, "stuckFeed", true, "stuck-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer s = MongooseServer.bootServer(cfg);
        awaitTrue(() -> p.started, "started");
        Thread watchdog = new Thread(() -> { try { Thread.sleep(7_000); } catch (InterruptedException ignored) { } feed.release.countDown(); });
        watchdog.start();
        long t0 = System.nanoTime();
        StopResult r = s.shutdown();
        long took = ms(t0);
        assertTrue(took < 2_000, "a 500 ms deadline bounds the stop, took " + took + "ms: " + r);
    }

    static IdleStrategy trippableIdle(AtomicBoolean trip) {
        return new IdleStrategy() {
            @Override public void idle(int workCount) { if (trip.get()) throw new RuntimeException("idle blew up"); java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); }
            @Override public void idle() { }
            @Override public void reset() { }
            @Override public String alias() { return "trippable"; }
        };
    }

    private static MongooseServer serverWithDeadGroup(Flow p, AtomicBoolean trip) throws Exception {
        MongooseServer s = new MongooseServer(cfg(5_000));
        s.setDefaultErrorHandler(t -> { throw new IllegalStateException("fail fast", t); });   // Agrona: thread exits, no onClose
        s.addEventProcessor("p", "g", trippableIdle(trip), () -> p);
        s.init();
        s.start();
        awaitTrue(() -> p.started, "started");
        trip.set(true);
        awaitTrue(() -> !p.agent.isAlive(), "the group's thread died");
        return s;
    }

    // P4a: a group whose thread died undetected: the stop's beginClosing() hides the death from cleanupAfterDeath
    @Test
    void p4a_stopReleasesTheProcessorsOfAGroupWhoseThreadDied() throws Exception {
        Flow p = new Flow();
        MongooseServer s = serverWithDeadGroup(p, new AtomicBoolean());
        StopResult r = s.shutdown();
        assertTrue(r.complete(), r.toString());
        assertEquals(1, p.stops.get(), "the dead group's processor is stopped by the server stop");
        assertEquals(1, p.tearDowns.get(), "and torn down");
    }

    // P4b: same, but a sender detects the death first (control: ended()), so the stop's cleanup runs
    @Test
    void p4b_contrast_senderDetectsDeathThenStopCleansUp() throws Exception {
        Flow p = new Flow();
        MongooseServer s = serverWithDeadGroup(p, new AtomicBoolean());
        s.stopProcessor("g", "p");   // send() notices the dead thread and marks the group DEAD
        StopResult r = s.shutdown();
        assertTrue(r.complete(), r.toString());
        assertEquals(1, p.stops.get());
        assertEquals(1, p.tearDowns.get());
    }

    static final class SlowService implements Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        @Override public void init() { }
        @Override public void start() { }
        @Override public void stop() { stops.incrementAndGet(); entered.countDown(); try { Thread.sleep(300); } catch (InterruptedException ignored) { } }
        @Override public void tearDown() { }
    }

    // P5: removeService during the stop's service phase stops the service a second time, concurrently
    @Test
    void p5_removeServiceDuringStopDoesNotDoubleStop() throws Exception {
        MongooseServer s = new MongooseServer(cfg(5_000));
        SlowService svc = new SlowService();
        s.registerService(new Service<>(svc, SlowService.class, "slow-svc"));
        Flow p = new Flow();
        s.addEventProcessor("p", "g", null, () -> p);
        s.init();
        s.start();
        awaitTrue(() -> p.started, "started");
        Thread stopper = new Thread(s::shutdown);
        stopper.start();
        assertTrue(svc.entered.await(5, TimeUnit.SECONDS));
        s.removeService("slow-svc");
        stopper.join(10_000);
        assertEquals(1, svc.stops.get(), "a service is stopped once");
    }
}
