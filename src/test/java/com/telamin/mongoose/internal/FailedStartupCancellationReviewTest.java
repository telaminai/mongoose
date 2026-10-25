package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class FailedStartupCancellationReviewTest {
    @Test void failedCancellationMustNotStrandOtherServicesAfterStartupReturns() throws Exception {
        checkCancellation(true);
    }

    @Test void successfulCancellationIsNotStoppedAgainAfterStartupReturns() throws Exception {
        checkCancellation(false);
    }

    private void checkCancellation(boolean failCancellation) throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(100);
        var server = new MongooseServer(config);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger otherStops = new AtomicInteger();
        AtomicInteger cancellationStops = new AtomicInteger();
        server.registerService(new Service<>(new Lifecycle() {
            public void init() { }
            public void start() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            public void stop() {
                cancellationStops.incrementAndGet();
                if (failCancellation) throw new IllegalStateException("cancel failed");
            }
            public void tearDown() { }
        }, Lifecycle.class, "blocking-start"));
        server.registerService(new Service<>(new Lifecycle() {
            public void init() { }
            public void start() { }
            public void stop() { otherStops.incrementAndGet(); }
            public void tearDown() { }
        }, Lifecycle.class, "other"));
        server.init();
        ExecutorService thread = Executors.newSingleThreadExecutor();
        Future<?> start = thread.submit(server::start);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(server.shutdown().complete());
            release.countDown();
            assertThrows(ExecutionException.class, () -> start.get(2, TimeUnit.SECONDS));
            var result = server.shutdown();
            assertEquals(1, otherStops.get(), "startup returned, so unrelated services can now be cleaned up");
            assertEquals(!failCancellation, result.complete());
            if (failCancellation) assertTrue(result.servicesLeftRunning().contains("blocking-start"));
            server.shutdown();
            assertEquals(1, cancellationStops.get(), "cancellation already attempted this service's stop");
        } finally {
            release.countDown();
            thread.shutdownNow();
        }
    }
}
