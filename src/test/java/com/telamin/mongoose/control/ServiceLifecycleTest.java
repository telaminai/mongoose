package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.dutycycle.NamedEventProcessor;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design "Service lifecycle" (PR 4): one order for registry changes and broadcasts, versions, unbind before stop. */
@Timeout(60)
class ServiceLifecycleTest {

    /** A processor that records which named services it currently holds. */
    static class Holder implements DataFlow, Lifecycle {
        final Set<String> held = ConcurrentHashMap.newKeySet();

        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { }
        @Override public void onEvent(Object e) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void tearDown() { }

        @Override
        public void registerService(Service<?> s) {
            if (s.serviceName().startsWith("svc")) held.add(s.serviceName());
        }

        @Override
        public void deRegisterService(Service<?> s) {
            held.remove(s.serviceName());
        }
    }

    static final class Svc implements Lifecycle {
        final Runnable onStop;
        Svc(Runnable onStop) { this.onStop = onStop; }
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

    @Test
    void concurrentRegistersAndRemovesLeaveProcessorsHoldingExactlyTheRegistry() throws Exception {
        MongooseServer s = started();
        try {
            Holder holder = new Holder();
            s.addEventProcessor("holder", "g", null, () -> holder);
            int threads = 4;
            CyclicBarrier together = new CyclicBarrier(threads);
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                final int id = t;
                workers[t] = new Thread(() -> {
                    try {
                        together.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < 50; i++) {
                            String name = "svc-" + ((id + i) % 3);
                            if ((id + i) % 2 == 0) {
                                try {
                                    s.registerService(new Service<>(new Svc(() -> { }), Svc.class, name));
                                } catch (RuntimeException alreadyRegistered) {
                                    // the other thread won the name: fine
                                }
                            } else {
                                s.removeService(name);
                            }
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                workers[t].start();
            }
            for (Thread w : workers) w.join(30_000);
            Set<String> registry = ConcurrentHashMap.newKeySet();
            s.registeredServices().keySet().stream().filter(n -> n.startsWith("svc")).forEach(registry::add);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!holder.held.equals(registry) && System.nanoTime() < until) Thread.sleep(5);
            assertEquals(registry, holder.held, "the processor holds exactly the registry's services after the races");
        } finally {
            s.stop();
        }
    }

    @Test
    void removeServiceUnbindsProcessorsBeforeStoppingTheService() throws Exception {
        MongooseServer s = started();
        try {
            Holder holder = new Holder();
            s.addEventProcessor("holder", "g", null, () -> holder);
            AtomicReference<Boolean> stillHeldAtStop = new AtomicReference<>();
            s.registerService(new Service<>(new Svc(() -> stillHeldAtStop.set(holder.held.contains("svc-x"))),
                    Svc.class, "svc-x"));
            assertTrue(holder.held.contains("svc-x"), "delivered before registerService returned");
            s.removeService("svc-x");
            assertEquals(Boolean.FALSE, stillHeldAtStop.get(), "the processor was unbound before the service stopped");
        } finally {
            s.stop();
        }
    }

    @Test
    void theRegistryViewIsReadOnly() {
        MongooseServer s = new MongooseServer(new MongooseServerConfig());
        assertThrows(UnsupportedOperationException.class,
                () -> s.registeredServices().put("x", new Service<>(new Object(), Object.class, "x")));
    }

    @Test
    void aStaleVersionedBroadcastIsIgnored() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("v", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart();
        Holder holder = new Holder();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("holder", holder));
        group.doWork();
        Service<Svc> svc = new Service<>(new Svc(() -> { }), Svc.class, "svc-v");
        group.broadcastService(svc, true, 5);
        group.doWork();
        assertTrue(holder.held.contains("svc-v"));
        group.broadcastService(svc, false, 3);     // older than what was applied: arrives late, must not apply
        group.doWork();
        assertTrue(holder.held.contains("svc-v"), "a stale deregistration is ignored");
        group.broadcastService(svc, false, 6);
        group.doWork();
        assertFalse(holder.held.contains("svc-v"), "a newer one applies");
        group.onClose();
    }
}
