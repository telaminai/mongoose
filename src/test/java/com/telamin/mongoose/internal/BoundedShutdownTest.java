package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design "Bounded shutdown": one deadline across the whole stop, a truthful result, nothing stopped beneath a running
 * processor group, a resumable STOPPING state, and admission serialised against the stop.
 */
@Timeout(60)
class BoundedShutdownTest {

    /** A processor whose stop() blocks until released: its group cannot close in time. */
    static class SlowStop implements DataFlow, Lifecycle {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stopEntered = new CountDownLatch(1);
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean started;

        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { started = true; }
        @Override public void tearDown() { }

        @Override
        public void stop() {
            stops.incrementAndGet();
            stopEntered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A service that counts its stops. */
    static final class CountingService implements Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        @Override public void init() { }
        @Override public void start() { }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() { }
    }

    private static MongooseServer server(long shutdownTimeoutMillis) {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setShutdownTimeoutMillis(shutdownTimeoutMillis);
        cfg.setShutdownDrainTimeoutMillis(50);
        return new MongooseServer(cfg);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    void aGroupThatCannotCloseInTimeIsReportedServicesKeepRunningAndAResumedStopCompletes() throws Exception {
        MongooseServer s = server(500);
        CountingService service = new CountingService();
        s.registerService(new Service<>(service, CountingService.class, "counting"));
        SlowStop slow = new SlowStop();
        s.addEventProcessor("slow", "slow-group", null, () -> slow);
        s.init();
        s.start();
        awaitTrue(() -> slow.started, "the slow processor started");

        long t0 = System.nanoTime();
        StopResult first = s.shutdown();
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertFalse(first.complete(), "the stop ran out of its deadline: " + first);
        assertEquals(com.telamin.mongoose.control.event.FailureKind.FAILED_SHUTDOWN, first.failureKind(), "issue #77: a real incomplete stop's kind");
        assertTrue(ms < 5_000, "bounded by the shutdown deadline, took " + ms + " ms");
        assertTrue(first.groupsRunning().contains("slow-group"), "names the group still closing: " + first);
        assertTrue(first.servicesLeftRunning().contains("counting"), "names the services left running: " + first);
        assertEquals(0, service.stops.get(), "no service is stopped beneath a running processor group");
        assertFalse(s.awaitStopped(10, TimeUnit.MILLISECONDS), "an incomplete stop does not report stopped");
        assertThrows(IllegalStateException.class, () -> s.addEventProcessor("late", "slow-group", null, SlowStop::new),
                "a server that is stopping admits nothing");

        slow.release.countDown();
        StopResult second = s.shutdown();
        assertTrue(second.complete(), "the resumed stop completes: " + second);
        assertEquals(com.telamin.mongoose.control.event.FailureKind.NONE, second.failureKind());
        assertEquals(1, slow.stops.get(), "the resumed stop re-joined the close in progress, not a second close");
        assertEquals(1, service.stops.get(), "services are stopped once the processor groups have closed");
        assertTrue(s.awaitStopped(1, TimeUnit.SECONDS));
    }

    @Test
    void aStartupStepThatDoesNotReturnIsReportedAndTheStopResumesOnceItDoes() throws Exception {
        MongooseServer s = server(300);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        s.registerService(new Service<>(new Lifecycle() {
            @Override public void init() { }
            @Override public void start() {
                entered.countDown();
                try {
                    finish.await(30, TimeUnit.SECONDS);     // ignores the cancellation stop()
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            @Override public void stop() { }
            @Override public void tearDown() { }
        }, Lifecycle.class, "blocking-start"));
        s.init();
        AtomicReference<Throwable> startOutcome = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                s.start();
            } catch (Throwable t) {
                startOutcome.set(t);
            }
        }, "starter");
        starter.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        StopResult first = s.shutdown();
        assertFalse(first.complete(), "a blocked startup step keeps the stop from completing: " + first);
        assertNotNull(first.blockedStartup(), "and the result says so");

        finish.countDown();
        starter.join(10_000);
        assertNotNull(startOutcome.get(), "the start reports that the server was stopped while starting");
        StopResult second = s.shutdown();
        assertTrue(second.complete(), "once the step returns, the stop resumes and completes: " + second);
        assertTrue(s.awaitStopped(1, TimeUnit.SECONDS));
    }

    @Test
    void aStopBeforeStartClosesTheGroupsCreatedAndRunsNoSupplier() throws Exception {
        MongooseServer s = server(5_000);
        AtomicInteger built = new AtomicInteger();
        s.addEventProcessor("never", "early-group", null, () -> {
            built.incrementAndGet();
            return new SlowStop();
        });
        StopResult result = s.shutdown();
        assertTrue(result.complete(), result.toString());
        assertEquals(0, built.get(), "a group closed before it started never builds its processors");
        assertTrue(s.awaitStopped(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, s::start, "a stopped server cannot start");
    }

    @Test
    void aStopCalledOnAnAgentThreadIsHandedToAStopThread() throws Exception {
        MongooseServer s = server(5_000);
        AtomicReference<StopResult> fromHandler = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        s.addEventProcessor("caller", "caller-group", null, () -> new SlowStop() {
            @Override public void start() {
                super.start();
                release.countDown();
                // the processor's own start runs on its group's agent thread
                fromHandler.set(s.shutdown());
                called.countDown();
            }
        });
        s.init();
        // the processor starts during the server's boot, so the server's start sees the stop it requested
        assertThrows(IllegalStateException.class, s::start);
        assertTrue(called.await(10, TimeUnit.SECONDS));
        assertTrue(fromHandler.get().handedOff(), "an agent thread cannot wait for the stop: " + fromHandler.get());
        assertEquals(com.telamin.mongoose.control.event.FailureKind.DETACHED, fromHandler.get().failureKind(), "a handed-off stop detaches its caller");
        assertTrue(s.awaitStopped(10, TimeUnit.SECONDS), "the stop thread completes it");
    }
}
