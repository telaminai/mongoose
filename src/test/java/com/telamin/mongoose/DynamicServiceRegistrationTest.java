/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the dynamic-service broadcast contract. Whatever
 * MongooseServer.registerService(...) / removeService(...) receive after
 * boot must propagate to every already-running event processor — that's
 * the mechanism the runtime feed / sink loaders depend on, and the
 * general mechanism for any operator-installed service that needs to
 * reach existing processors.
 *
 * <p>Without the broadcast plumbing the global registeredServices map
 * is updated but no processor sees the change until a brand-new
 * processor is added (which walks the map at registration time). This
 * test would fail with "registerService was never called on the
 * running processor" before the patch.
 */
public class DynamicServiceRegistrationTest {

    interface FakeService {
        String ping();
    }

    @Test
    void register_then_remove_service_after_boot_propagates_to_running_processor() throws Exception {
        RecordingDataFlow processor = new RecordingDataFlow();

        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor(processor, "test-processor");
        // A do-nothing feed so the server has a real agent + duty cycle —
        // broadcast drains via the agent's doWork() pass.
        cfg.addEventSourceWorker(new InMemoryEventSource<String>(), "noop-feed",
                true, "noop-agent", new SleepingMillisIdleStrategy(1));

        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            awaitTrue(() -> processor.startCalled);

            FakeService impl = () -> "pong";
            Service<FakeService> svc = new Service<>(impl, FakeService.class, "fake-service");

            // ── REGISTER ─────────────────────────────────────────────
            server.registerService(svc);

            awaitTrue(() -> processor.registeredService.get() != null);
            assertEquals(svc, processor.registeredService.get(),
                    "the same Service<?> instance should reach the running processor");
            assertEquals(0, processor.deregisterCount,
                    "deregister should not fire on a register");

            // ── REMOVE ───────────────────────────────────────────────
            server.removeService("fake-service");

            awaitTrue(() -> processor.deregisteredService.get() != null);
            assertEquals(svc, processor.deregisteredService.get(),
                    "the same Service<?> instance should reach the running processor on remove");
            assertTrue(processor.registerCount >= 1, "registerService fired at least once");
            assertTrue(processor.deregisterCount >= 1, "deRegisterService fired at least once");

            // Service is gone from the global registry.
            assertEquals(null, server.registeredServices().get("fake-service"),
                    "removeService should clear the global registry entry");
        } finally {
            server.stop();
        }
    }

    @Test
    void remove_unknown_service_is_noop() throws Exception {
        RecordingDataFlow processor = new RecordingDataFlow();
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.addProcessor(processor, "test-processor");
        cfg.addEventSourceWorker(new InMemoryEventSource<String>(), "noop-feed",
                true, "noop-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            awaitTrue(() -> processor.startCalled);
            // Should not throw, should not broadcast anything.
            server.removeService("does-not-exist");
            Thread.sleep(50);
            assertEquals(0, processor.deregisterCount,
                    "removeService for unknown name should not broadcast");
        } finally {
            server.stop();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(10);
        }
    }

    /** Hand-rolled DataFlow whose {@code registerService} /
     *  {@code deRegisterService} record the inbound Service<?>. Mirrors
     *  the shape of generated processors' delegation into the
     *  ServiceRegistryNode but stays inline so the test owns the
     *  observation surface. */
    public static class RecordingDataFlow implements DataFlow {
        public volatile boolean startCalled;
        public final AtomicReference<Service<?>> registeredService = new AtomicReference<>();
        public final AtomicReference<Service<?>> deregisteredService = new AtomicReference<>();
        public volatile int registerCount = 0;
        public volatile int deregisterCount = 0;
        private final List<EventFeed> eventFeeds = new ArrayList<>();

        @Override public void init() { }
        @Override public void start() { startCalled = true; }
        @Override public void tearDown() { }
        @Override public void addEventFeed(EventFeed eventFeed) { eventFeeds.add(eventFeed); }
        @Override public void onEvent(Object event) { }

        @Override
        public void registerService(Service<?> service) {
            registerCount++;
            registeredService.set(service);
        }

        @Override
        public void deRegisterService(Service<?> service) {
            deregisterCount++;
            deregisteredService.set(service);
        }
    }
}
