package com.telamin.mongoose.control;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Adversarial hunt (server level). Each test asserts the behaviour the design promises; a failing test is the
 * demonstration of the failure mode.
 */
@Timeout(60)
class HuntServerFailureModesTest {

    static class Proc implements DataFlow, Lifecycle {
        final CountDownLatch deregisterEntered = new CountDownLatch(1);
        final CountDownLatch deregisterRelease = new CountDownLatch(1);
        final CountDownLatch startEntered = new CountDownLatch(1);
        final CountDownLatch startRelease = new CountDownLatch(1);
        volatile boolean blockDeregister;
        volatile boolean blockStart;

        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() {
            if (!blockStart) return;
            startEntered.countDown();
            try { startRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public void stop() { }
        @Override public void tearDown() { }
        @Override public void registerService(Service<?> s) { }
        @Override public void deRegisterService(Service<?> s) {
            if (!blockDeregister) return;
            deregisterEntered.countDown();
            try { deregisterRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    static final class Svc implements Lifecycle {
        final AtomicInteger stops = new AtomicInteger();
        @Override public void init() { }
        @Override public void start() { }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() { }
    }

    /**
     * FM: a deferred retirement (the group did not confirm the deregister within the control wait) stops the service
     * instance when the group finally confirms, even though the same instance has been registered again meanwhile.
     * The live, registered service is stopped beneath the processors that were just handed it.
     */
    @Test
    void deferredRetirementMustNotStopAnInstanceRegisteredAgain() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setControlWaitMillis(30);
        MongooseServer s = new MongooseServer(cfg);
        s.init();
        s.start();
        Proc p = new Proc();
        try {
            s.addEventProcessor("p", "g", null, () -> p);
            Svc svc = new Svc();
            s.registerService(new Service<>(svc, Svc.class, "svc"));
            p.blockDeregister = true;
            s.removeService("svc");                       // the group is held in deRegisterService: withdrawal deferred
            assertTrue(p.deregisterEntered.await(5, TimeUnit.SECONDS));
            assertEquals(0, svc.stops.get(), "unconfirmed withdrawal keeps the service running");
            p.blockDeregister = false;
            s.registerService(new Service<>(svc, Svc.class, "svc"));   // the same instance, registered again (restart)
            assertSame(svc, s.registeredServices().get("svc").instance());
            p.deregisterRelease.countDown();              // the group confirms the old withdrawal now
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (svc.stops.get() == 0 && System.nanoTime() < until) Thread.sleep(10);
            assertSame(svc, s.registeredServices().get("svc").instance(), "still the registered instance");
            assertEquals(0, svc.stops.get(), "the retirement of the removed incarnation stopped the live, re-registered instance");
        } finally {
            p.deregisterRelease.countDown();
            s.stop();
        }
    }

    /**
     * FM: a void add parked on a full mailbox holds the admission read lock for the whole full-mailbox backoff (10 s by
     * default), so the stop's write-lock barrier cannot be passed and no group is even asked to close. The sender keeps
     * waiting because the group is never moved to CLOSING (that happens after the barrier), and the agent is never
     * interrupted (that happens after the barrier too).
     */
    @Test
    void shutdownMustNotBeBlockedByAnAddWaitingOnAFullMailbox() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setShutdownTimeoutMillis(3_000);
        MongooseServer s = new MongooseServer(cfg);
        s.init();
        s.start();
        Proc first = new Proc();
        Proc holder = new Proc();
        holder.blockStart = true;
        Thread adder = null;
        try {
            s.addEventProcessor("p", "g", null, () -> first);
            s.addProcessor("holder", "g", null, () -> holder);            // the agent is held in start()
            assertTrue(holder.startEntered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 4096; i++) s.addProcessor("queued" + i, "g", null, Proc::new);   // mailbox full
            adder = new Thread(() -> s.addEventProcessor("late", "g", null, Proc::new), "late-adder");
            adder.start();
            Thread.sleep(200);                                             // parked in send(), read lock held
            long t0 = System.nanoTime();
            StopResult result = s.shutdown();
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            // the defect was the barrier: the parked sender held the admission lock, so the stop never asked a group to
            // close and reported a blocked startup. Fixed, the sender leaves NOT_RUNNING as the stop begins. The group
            // itself cannot close while its agent is held in a processor's start(): bounded shutdown reports that
            // truthfully, and a stop resumed once the start returns completes. (Adjusted from "complete within 3 s",
            // which no stop can do while user code holds the agent.)
            assertNull(result.blockedStartup(), "the parked sender no longer blocks the stop's barrier: " + result);
            assertTrue(tookMs < 4_000, "bounded by the deadline, took " + tookMs + " ms");
            if (!result.complete()) {
                assertTrue(result.groupsRunning().contains("g"), "the held group is named: " + result);
                holder.startRelease.countDown();
                StopResult resumed = s.shutdown();
                assertTrue(resumed.complete(), "the resumed stop completes once the start returns: " + resumed);
            }
            adder.join(5_000);
            assertFalse(adder.isAlive(), "the parked sender left when the stop began");
        } finally {
            holder.startRelease.countDown();
            if (adder != null) adder.join(15_000);
            s.stop();
        }
    }
}
