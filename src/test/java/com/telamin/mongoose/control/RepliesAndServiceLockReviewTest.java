package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.AddedProcessor;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.ReplyOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions from the review of control-plane PR 3 and 4: each was a reproduced defect. */
@Timeout(90)
class RepliesAndServiceLockReviewTest {

    @AfterEach
    void clearAgentMark() {
        AgentThreads.exit();
    }

    static class Proc implements DataFlow, Lifecycle {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final Set<String> held = ConcurrentHashMap.newKeySet();
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { starts.incrementAndGet(); }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() { }
        @Override public void registerService(Service<?> s) { held.add(s.serviceName()); }
        @Override public void deRegisterService(Service<?> s) { held.remove(s.serviceName()); }
    }

    static final class Svc implements Lifecycle {
        final Runnable onStop;
        Svc(Runnable onStop) { this.onStop = onStop; }
        Svc() { this(() -> { }); }
        @Override public void init() { }
        @Override public void start() { }
        @Override public void stop() { onStop.run(); }
        @Override public void tearDown() { }
    }

    private static MongooseServer started() {
        MongooseServer s = new MongooseServer(new MongooseServerConfig());
        s.init();
        s.start();
        return s;
    }

    /** Finding 1: one reply thread deadlocked when a dependent waited on another isolated stage. */
    @Test
    void aDependentMayWaitOnAnotherIsolatedStage() throws Exception {
        MongooseServer s = started();
        try {
            s.addEventProcessor("seed", "g", null, Proc::new);
            CompletableFuture<ControlReply> inner = new CompletableFuture<>();
            s.addProcessor("a", "g", null, Proc::new).reply().thenAccept(r -> {
                try {
                    inner.complete(s.addProcessor("b", "g", null, Proc::new).reply().toCompletableFuture().get(5, TimeUnit.SECONDS));
                } catch (Exception e) {
                    inner.completeExceptionally(e);
                }
            });
            assertEquals(ReplyOutcome.APPLIED, inner.get(10, TimeUnit.SECONDS).outcome());
        } finally {
            s.stop();
        }
    }

    /** Finding 2: registerService held the service lock while backing off on a full mailbox. */
    @Test
    void aFullMailboxNeverHoldsTheServiceLock() throws Exception {
        MongooseServer s = started();
        CountDownLatch release = new CountDownLatch(1);
        try {
            s.registerService(new Service<>(new Svc(), Svc.class, "unrelated"));
            s.addEventProcessor("seed", "g", null, Proc::new);
            CountDownLatch holding = new CountDownLatch(1);
            s.addProcessor("holder", "g", null, () -> new Proc() {
                @Override public void start() {
                    holding.countDown();
                    try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                }
            });
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 4096; i++) s.addProcessor("fill-" + i, "g", null, Proc::new);
            CountDownLatch registered = new CountDownLatch(1);
            Thread registering = new Thread(() -> {
                s.registerService(new Service<>(new Svc(), Svc.class, "late"));
                registered.countDown();
            });
            registering.setDaemon(true);
            registering.start();
            assertTrue(registered.await(5, TimeUnit.SECONDS), "a group that cannot take it is skipped, not waited on");
            long t0 = System.nanoTime();
            s.stopService("unrelated");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1_000, "nothing parked under the lock");
        } finally {
            release.countDown();
            s.stop();
        }
    }

    /**
     * Finding 3: a failed start must be observable. The void add keeps mongoose#57's contract (isolated, reported, no
     * throw); the outcome is in addProcessor's reply.
     */
    @Test
    void aFailedStartIsObservableInTheAddsReply() throws Exception {
        MongooseServer s = started();
        try {
            s.addEventProcessor("seed", "g", null, Proc::new);
            ControlReply reply = s.addProcessor("bad", "g", null, () -> new Proc() {
                @Override public void start() { throw new IllegalStateException("start failed"); }
            }).reply().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ReplyOutcome.FAILED, reply.outcome());
            assertTrue(reply.reason().contains("start failed"), reply.reason());
        } finally {
            s.stop();
        }
    }

    /** Finding 4: no handle came with an add, and looking one up by name could find a replacement. */
    @Test
    void anAddsOwnHandleNeverStopsAReplacement() throws Exception {
        MongooseServer s = started();
        try {
            s.addEventProcessor("seed", "g", null, Proc::new);
            AddedProcessor added = s.addProcessor("x", "g", null, Proc::new);
            assertNotNull(added.handle());
            ControlReply first = added.reply().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(first.generation(), added.handle().generation(), "the handle names this add's own registration");
            s.stopProcessor("g", "x");
            Proc replacement = new Proc();
            s.addEventProcessor("x", "g", null, () -> replacement);
            ControlReply stale = s.stopProcessor(added.handle()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(ReplyOutcome.APPLIED, stale.outcome());
            assertEquals(0, replacement.stops.get(), "the replacement is untouched");
        } finally {
            s.stop();
        }
    }

    /** Finding 5: a removeService that could not wait stopped the service beneath processors still holding it. */
    @Test
    void aRemoveThatCannotWaitStopsTheServiceOnlyOnceProcessorsUnbound() throws Exception {
        MongooseServer s = started();
        try {
            Proc holder = new Proc();
            s.addEventProcessor("holder", "g1", null, () -> holder);
            AtomicReference<Boolean> heldAtStop = new AtomicReference<>();
            CountDownLatch stopped = new CountDownLatch(1);
            s.registerService(new Service<>(new Svc(() -> {
                heldAtStop.set(holder.held.contains("svc-x"));
                stopped.countDown();
            }), Svc.class, "svc-x"));
            assertTrue(holder.held.contains("svc-x"));
            // removed from another group's agent thread, which never waits
            s.addEventProcessor("remover", "g2", null, () -> new Proc() {
                @Override public void start() {
                    super.start();
                    s.removeService("svc-x");
                }
            });
            assertTrue(stopped.await(10, TimeUnit.SECONDS), "the deferred stop ran");
            assertEquals(Boolean.FALSE, heldAtStop.get(), "the processor had unbound before the service stopped");
        } finally {
            s.stop();
        }
    }
}
