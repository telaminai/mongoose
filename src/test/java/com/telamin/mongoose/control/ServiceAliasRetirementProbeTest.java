package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ServiceAliasRetirementProbeTest {
    @Test void lastAliasWithdrawalMustCompleteBeforeOnePhysicalStop() throws Exception {
        var aEntered = new CountDownLatch(1);
        var aRelease = new CountDownLatch(1);
        var bEntered = new CountDownLatch(1);
        var bRelease = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var stopCount = new AtomicInteger();
        class Flow implements DataFlow, Lifecycle {
            public void init() { }
            public void start() { }
            public void stop() { }
            public void tearDown() { }
            public void onEvent(Object e) { }
            public void addEventFeed(EventFeed e) { }
            public void deRegisterService(Service<?> svc) {
                CountDownLatch entered, release;
                if (svc.serviceName().equals("A")) { entered=aEntered; release=aRelease; }
                else if (svc.serviceName().equals("B")) { entered=bEntered; release=bRelease; }
                else return;
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
        }
        Lifecycle instance = new Lifecycle() {
            public void init() { }
            public void start() { }
            public void tearDown() { }
            public void stop() { stopCount.incrementAndGet(); stopped.countDown(); }
        };
        var cfg = new MongooseServerConfig(); cfg.setControlWaitMillis(100);
        var server = new MongooseServer(cfg);
        server.init(); server.start();
        var removers = Executors.newFixedThreadPool(2);
        try {
            server.addEventProcessor("p", "g", null, Flow::new);
            server.registerService(new Service<>(instance, Lifecycle.class, "A"));
            server.registerService(new Service<>(instance, Lifecycle.class, "B"));
            Future<?> a = removers.submit(() -> server.removeService("A"));
            assertTrue(aEntered.await(5, TimeUnit.SECONDS));
            Future<?> b = removers.submit(() -> server.removeService("B"));
            a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
            aRelease.countDown();
            assertTrue(bEntered.await(5, TimeUnit.SECONDS));
            boolean stoppedWithBStillHeld = stopped.await(2, TimeUnit.SECONDS);
            bRelease.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(stopCount.get()<2 && System.nanoTime()<until) Thread.sleep(5);
            assertFalse(stoppedWithBStillHeld,
                    "A retirement stopped the shared instance while B withdrawal was still executing; total stops="+stopCount.get());
            assertEquals(1, stopCount.get(), "one physical instance must be stopped once after both withdrawals");
        } finally {
            aRelease.countDown(); bRelease.countDown(); removers.shutdownNow(); server.stop();
        }
    }

    @Test void shutdownStopsTwoLiveAliasesOnlyOnce() {
        var count = new AtomicInteger();
        Lifecycle instance = lifecycle(() -> count.incrementAndGet());
        var server = new MongooseServer(new MongooseServerConfig());
        server.registerService(new Service<>(instance, Lifecycle.class, "A"), new Service<>(instance, Lifecycle.class, "B"));
        server.init(); server.start();
        assertTrue(server.shutdown().complete());
        assertEquals(1, count.get());
    }

    @Test void unconfirmedSiblingRemainsOwnedUntilShutdown() throws Exception {
        var count = new AtomicInteger();
        Lifecycle instance = lifecycle(() -> count.incrementAndGet());
        var server = new MongooseServer(new MongooseServerConfig());
        class Flow implements DataFlow, Lifecycle {
            public void init() { }
            public void start() { }
            public void stop() { }
            public void tearDown() { }
            public void onEvent(Object event) { }
            public void addEventFeed(EventFeed e) { }
            public void deRegisterService(Service<?> svc) {
                if (svc.serviceName().equals("B")) throw new IllegalStateException("B remains held");
            }
        }
        server.init(); server.start();
        try {
            server.addEventProcessor("p", "g", null, Flow::new);
            server.registerService(new Service<>(instance, Lifecycle.class, "A"), new Service<>(instance, Lifecycle.class, "B"));
            // B's failed withdrawal must retain ownership even after A no longer appears in the registry.
            server.removeService("B");
            server.removeService("A");
            assertEquals(0, count.get());
            assertTrue(server.shutdown().complete());
            assertEquals(1, count.get());
        } finally { server.stop(); }
    }

    @Test void shutdownWaitsForSharedStopAndDoesNotRepeatIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        Lifecycle instance = lifecycle(() -> {
            count.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        var cfg = new MongooseServerConfig(); cfg.setShutdownTimeoutMillis(100);
        var server = new MongooseServer(cfg);
        server.registerService(new Service<>(instance, Lifecycle.class, "A"), new Service<>(instance, Lifecycle.class, "B"));
        server.init(); server.start();
        var caller = Executors.newSingleThreadExecutor();
        try {
            server.removeService("A");
            Future<?> removal = caller.submit(() -> server.removeService("B"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(server.shutdown().complete(), "the shared physical callback is still running");
            release.countDown(); removal.get(3, TimeUnit.SECONDS);
            assertTrue(server.shutdown().complete());
            assertEquals(1, count.get());
        } finally { release.countDown(); caller.shutdownNow(); server.stop(); }
    }

    @Test void failedPhysicalStopIsStillReportedByShutdown() {
        var count = new AtomicInteger();
        Lifecycle instance = lifecycle(() -> { count.incrementAndGet(); throw new IllegalStateException("cannot stop"); });
        var cfg = new MongooseServerConfig(); cfg.setShutdownTimeoutMillis(100);
        var server = new MongooseServer(cfg);
        server.registerService(new Service<>(instance, Lifecycle.class, "A"), new Service<>(instance, Lifecycle.class, "B"));
        server.init(); server.start();
        server.removeService("A");
        assertThrows(IllegalStateException.class, () -> server.removeService("B"));
        assertFalse(server.shutdown().complete());
        assertFalse(server.shutdown().complete());
        assertEquals(1, count.get(), "failure is retained, not retried through another alias");
    }

    private static Lifecycle lifecycle(Runnable stopping) {
        return new Lifecycle() {
            public void init() { }
            public void start() { }
            public void tearDown() { }
            public void stop() { stopping.run(); }
        };
    }
}
