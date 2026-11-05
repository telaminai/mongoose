package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.exception.ServiceRegistrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RetirementReregistrationRaceTest {
    @Test void sameInstanceReregisteredDuringStartupGetsOnlyOnePostFenceStop() throws Exception {
        var config = new MongooseServerConfig();
        config.setShutdownTimeoutMillis(100);
        var server = new MongooseServer(config);
        var startEntered = new CountDownLatch(1);
        var releaseStart = new CountDownLatch(1);
        var stops = new AtomicInteger();
        Lifecycle instance = new Lifecycle() {
            public void init() { }
            public void start() {
                startEntered.countDown();
                try { releaseStart.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            public void stop() { stops.incrementAndGet(); }
            public void tearDown() { }
        };
        server.registerService(new Service<>(instance, Lifecycle.class, "same-instance"));
        server.init();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> starting = caller.submit(server::start);
        try {
            assertTrue(startEntered.await(2, TimeUnit.SECONDS));
            server.removeService("same-instance");
            assertEquals(1, stops.get(), "the old retirement completed");
            server.registerService(new Service<>(instance, Lifecycle.class, "same-instance"));
            assertFalse(server.shutdown().complete());
            releaseStart.countDown();
            assertThrows(ExecutionException.class, () -> starting.get(2, TimeUnit.SECONDS));
            assertTrue(server.shutdown().complete());
            assertEquals(2, stops.get(), "one retirement and one final cleanup, despite two wrappers for the same instance");
        } finally {
            releaseStart.countDown();
            caller.shutdown();
            caller.awaitTermination(2, TimeUnit.SECONDS);
            server.shutdown();
        }
    }

    @Test void reregistrationCannotRacePastAnAlreadyClaimedRetirementStop() throws Exception {
        var server = new MongooseServer(new MongooseServerConfig());
        var stopEntered = new CountDownLatch(1);
        var releaseStop = new CountDownLatch(1);
        var closedWhileRegistered = new AtomicBoolean();
        var stops = new AtomicInteger();
        Lifecycle instance = new Lifecycle() {
            public void init() { }
            public void start() { }
            public void tearDown() { }
            public void stop() {
                if (stops.incrementAndGet() != 1) return;
                stopEntered.countDown();
                try { releaseStop.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                closedWhileRegistered.set(server.registeredServices().values().stream().anyMatch(s -> s.instance() == this));
            }
        };
        server.registerService(new Service<>(instance, Lifecycle.class, "same-instance"));
        server.init(); server.start();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> removing = callers.submit(() -> server.removeService("same-instance"));
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS), "retirement passed its registry check and entered stop");
            Future<?> registering = callers.submit(() -> server.registerService(new Service<>(instance, Lifecycle.class, "same-instance")));
            try {
                registering.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waitingForRetirement) {
                // Serializing registration behind the physical stop would also be safe.
            } catch (ExecutionException rejected) {
                assertInstanceOf(ServiceRegistrationException.class, rejected.getCause());
            }
            releaseStop.countDown();
            removing.get(2, TimeUnit.SECONDS);
            try { registering.get(2, TimeUnit.SECONDS); }
            catch (ExecutionException rejected) { assertInstanceOf(ServiceRegistrationException.class, rejected.getCause()); }
            assertFalse(closedWhileRegistered.get(), "old retirement stopped an instance already admitted as live again");
        } finally {
            releaseStop.countDown();
            callers.shutdown();
            callers.awaitTermination(2, TimeUnit.SECONDS);
            server.shutdown();
        }
    }
}
