/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mongoose#60 item 1: each service is delivered to a processor once. A service registered while its group is starting
 * is both in the server's registry (read when a processor joins) and queued for broadcast; the processor must not be
 * handed it by both.
 */
class ServiceDeliveredOnceTest {

    /** Counts {@code registerService} / {@code deRegisterService} calls per service name. */
    public static class Counting implements DataFlow {
        final Map<String, AtomicInteger> registered = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> deregistered = new ConcurrentHashMap<>();
        volatile boolean started;

        @Override public void init() {}
        @Override public void start() { started = true; }
        @Override public void stop() {}
        @Override public void tearDown() {}
        @Override public void onEvent(Object e) {}
        @Override public void addEventFeed(EventFeed f) {}
        @Override public void registerService(Service<?> s) {
            registered.computeIfAbsent(s.serviceName(), k -> new AtomicInteger()).incrementAndGet();
        }
        @Override public void deRegisterService(Service<?> s) {
            deregistered.computeIfAbsent(s.serviceName(), k -> new AtomicInteger()).incrementAndGet();
        }

        int registrations(String name) {
            AtomicInteger c = registered.get(name);
            return c == null ? 0 : c.get();
        }

        Map<String, Integer> counts() {
            return registered.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
        }
    }

    @Test
    void agentHostedServicesAreDeliveredOnceAtBoot() throws Exception {
        MongooseServerConfig cfg = new MongooseServerConfig();
        Counting processor = new Counting();
        cfg.addProcessor("g", processor, "A");
        for (int i = 0; i < 4; i++) {
            cfg.addEventSourceWorker(new InMemoryEventSource<String>(), "feed" + i, true, "feed-agent" + i,
                    new SleepingMillisIdleStrategy(1));
        }
        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            await(() -> processor.started, "processor started");
            await(() -> processor.registrations("feed3") > 0, "the agent-hosted feeds delivered");
            Thread.sleep(300);
            Map<String, Integer> counts = processor.counts();
            assertTrue(counts.values().stream().allMatch(c -> c == 1), "every service delivered once: " + counts);
        } finally {
            server.stop();
        }
    }

    @Test
    void serviceRegisteredAndBroadcastBeforeTheGroupStartsIsDeliveredOnce() throws Exception {
        ConcurrentHashMap<String, Service<?>> registry = new ConcurrentHashMap<>();
        ComposingEventProcessorAgent group = newGroup(registry);
        Counting processor = new Counting();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", processor));

        // what MongooseServer.registerService does while the group is starting: registry first, then the broadcast
        Service<Object> svc = new Service<>(new Object(), Object.class, "svc");
        registry.put(svc.serviceName(), svc);
        group.broadcastServiceRegistered(svc);

        group.onStart();
        group.doWork();
        group.doWork();

        assertEquals(1, processor.registrations("svc"), "delivered once: " + processor.counts());
        group.onClose();
    }

    @Test
    void processorJoiningWhileABroadcastIsPendingGetsTheServiceOnce() throws Exception {
        ConcurrentHashMap<String, Service<?>> registry = new ConcurrentHashMap<>();
        ComposingEventProcessorAgent group = newGroup(registry);
        Counting running = new Counting();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("running", running));
        group.onStart();

        Service<Object> svc = new Service<>(new Object(), Object.class, "svc");
        registry.put(svc.serviceName(), svc);
        group.broadcastServiceRegistered(svc);
        Counting joining = new Counting();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("joining", joining));
        group.doWork();
        group.doWork();

        assertEquals(1, running.registrations("svc"), "the running processor gets the broadcast once");
        assertEquals(1, joining.registrations("svc"), "the joining processor gets it once: " + joining.counts());
        group.onClose();
    }

    @Test
    void aServiceRemovedAndRegisteredAgainIsDeliveredAgain() throws Exception {
        ConcurrentHashMap<String, Service<?>> registry = new ConcurrentHashMap<>();
        ComposingEventProcessorAgent group = newGroup(registry);
        Counting processor = new Counting();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", processor));
        group.onStart();

        Service<Object> svc = new Service<>(new Object(), Object.class, "svc");
        registry.put(svc.serviceName(), svc);
        group.broadcastServiceRegistered(svc);
        group.doWork();
        registry.remove(svc.serviceName());
        group.broadcastServiceDeregistered(svc);
        group.doWork();
        registry.put(svc.serviceName(), svc);
        group.broadcastServiceRegistered(svc);
        group.doWork();

        assertEquals(2, processor.registrations("svc"), "registered, removed, registered again");
        assertEquals(1, processor.deregistered.get("svc").get(), "deregistered once");
        group.onClose();
    }

    private static ComposingEventProcessorAgent newGroup(ConcurrentHashMap<String, Service<?>> registry) {
        return new ComposingEventProcessorAgent("g", new EventFlowManager(), null, new DeadWheelScheduler(), registry);
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !condition.getAsBoolean()) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }
}
