package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.StopResult;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ShutdownAdmissionReviewTest {
    public static class BlockingInjection {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @ServiceRegistered public void bind(String dependency) throws InterruptedException {
            entered.countDown();
            release.await();
        }
    }

    @Test void startedServerBoundsAdmissionBarrierAndCanResume() throws Exception { checkAdmission(true); }
    @Test void neverStartedServerBoundsAdmissionBarrierAndCanResume() throws Exception { checkAdmission(false); }

    @Test void blockingStartupCancellationIsBounded() throws Exception {
        MongooseServerConfig config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(150);
        MongooseServer server = new MongooseServer(config);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.registerService(new Service<>(new Lifecycle() {
            public void init() { }
            public void start() { entered.countDown(); waitForRelease(); }
            public void stop() { waitForRelease(); }
            public void tearDown() { }
            private void waitForRelease() {
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, Lifecycle.class, "blocking-start-and-stop"));
        server.init();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        Future<?> starting = threads.submit(server::start);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            StopResult result = threads.submit(server::shutdown).get(1, TimeUnit.SECONDS);
            assertFalse(result.complete());
            assertNotNull(result.blockedStartup());
            release.countDown();
            assertThrows(ExecutionException.class, () -> starting.get(3, TimeUnit.SECONDS));
            assertTrue(server.shutdown().complete());
        } finally {
            release.countDown();
            threads.shutdown();
            threads.awaitTermination(3, TimeUnit.SECONDS);
            server.shutdown();
        }
    }

    @Test void throwingServiceStopCannotBeReportedAsCompleteOrRetriedBlindly() {
        MongooseServerConfig config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(150);
        MongooseServer server = new MongooseServer(config);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server.registerService(new Service<>(new Lifecycle() {
            public void init() { }
            public void start() { }
            public void stop() { calls.incrementAndGet(); throw new IllegalStateException("resource still open"); }
            public void tearDown() { }
        }, Lifecycle.class, "failed-stop"));
        server.init(); server.start();
        assertTrue(server.shutdown().servicesLeftRunning().contains("failed-stop"));
        assertFalse(server.shutdown().complete());
        assertEquals(1, calls.get());
    }

    private void checkAdmission(boolean start) throws Exception {
        MongooseServerConfig config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(150);
        MongooseServer server = new MongooseServer(config);
        server.registerService(new Service<>("dependency", String.class, "dependency"));
        if (start) { server.init(); server.start(); }
        BlockingInjection service = new BlockingInjection();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        Future<?> registration = threads.submit(() -> server.registerService(new Service<>(service, "blocking")));
        try {
            assertTrue(service.entered.await(3, TimeUnit.SECONDS));
            Future<StopResult> stopping = threads.submit(server::shutdown);
            StopResult result = stopping.get(1, TimeUnit.SECONDS);
            assertFalse(result.complete(), "admitted callback is still running");
            assertFalse(server.awaitStopped(1, TimeUnit.MILLISECONDS));
            service.release.countDown();
            registration.get(3, TimeUnit.SECONDS);
            assertTrue(server.shutdown().complete(), "stop resumes after the admitted callback returns");
        } finally {
            service.release.countDown();
            threads.shutdown();
            threads.awaitTermination(3, TimeUnit.SECONDS);
            server.shutdown();
        }
    }
}
