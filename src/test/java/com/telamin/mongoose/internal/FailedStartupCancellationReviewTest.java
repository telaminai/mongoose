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

    @Test void successfulCancellationStillNeedsFinalStopAfterStartupReturns() throws Exception {
        checkCancellation(false);
    }

    @Test void replacedServiceReacquiredDuringStartupStillGetsFinalCleanup() throws Exception {
        checkReplacement(false);
    }

    @Test void failedRetirementStillAllowsDistinctFinalCleanupAfterStartup() throws Exception {
        checkReplacement(true);
    }

    private void checkReplacement(boolean failRetirement) throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(100);
        var server = new MongooseServer(config);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger oldStops = new AtomicInteger();
        AtomicInteger newStops = new AtomicInteger();
        var oldLive = new java.util.concurrent.atomic.AtomicBoolean();
        server.registerService(new Service<>(new Lifecycle() {
            public void init() { }
            public void start() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                oldLive.set(true);
            }
            public void stop() {
                int attempt = oldStops.incrementAndGet();
                if (failRetirement && attempt == 1) throw new IllegalStateException("retirement failed");
                oldLive.set(false);
            }
            public void tearDown() { }
        }, Lifecycle.class, "replace-during-start"));
        server.init();
        ExecutorService thread = Executors.newSingleThreadExecutor();
        Future<?> start = thread.submit(server::start);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            if (failRetirement) assertThrows(IllegalStateException.class, () -> server.removeService("replace-during-start"));
            else server.removeService("replace-during-start");
            server.registerService(new Service<>(new Lifecycle() {
                public void init() { }
                public void start() { }
                public void stop() { newStops.incrementAndGet(); }
                public void tearDown() { }
            }, Lifecycle.class, "replace-during-start"));
            assertFalse(server.shutdown().complete());
            assertEquals(1, oldStops.get(), "the retired incarnation was already stopped");
            assertEquals(0, newStops.get(), "cancellation must not address a replacement by name");
            release.countDown();
            assertThrows(ExecutionException.class, () -> start.get(2, TimeUnit.SECONDS));
            assertTrue(server.shutdown().complete());
            assertEquals(1, newStops.get());
            assertEquals(2, oldStops.get(), "the old start reacquired resources after retirement completed");
            assertFalse(oldLive.get());
        } finally {
            release.countDown();
            thread.shutdownNow();
        }
    }

    private void checkCancellation(boolean failCancellation) throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(100);
        var server = new MongooseServer(config);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger otherStops = new AtomicInteger();
        AtomicInteger cancellationStops = new AtomicInteger();
        var live = new java.util.concurrent.atomic.AtomicBoolean();
        server.registerService(new Service<>(new Lifecycle() {
            public void init() { }
            public void start() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                live.set(true);
            }
            public void stop() {
                int attempt = cancellationStops.incrementAndGet();
                if (failCancellation && attempt == 1) throw new IllegalStateException("cancel failed");
                live.set(false);
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
            assertTrue(result.complete(), "final cleanup succeeds even if the cancellation request failed");
            assertFalse(live.get(), "cleanup must follow the last resource acquisition in start");
            server.shutdown();
            assertEquals(2, cancellationStops.get(), "cancellation and post-fence cleanup are distinct attempts");
        } finally {
            release.countDown();
            thread.shutdownNow();
        }
    }
}
