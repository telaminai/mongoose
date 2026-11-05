/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mongoose#58: the group's control queues (processors to start / stop, services to broadcast) are fed from several
 * threads at once - admin, loaders, and every service-agent thread registering its service at boot. Every entry must
 * be delivered exactly once, and a burst beyond the old capacity of 128 must not throw.
 */
class AgentControlQueueConcurrencyTest {

    private static final int THREADS = 8;

    private final List<Throwable> agentErrors = new CopyOnWriteArrayList<>();
    private AgentRunner runner;

    @AfterEach
    void close() {
        if (runner != null) runner.close();
    }

    /** Counts every service registration and start it is given. */
    public static class Counting implements DataFlow {
        final Map<String, AtomicInteger> registered = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> deregistered = new ConcurrentHashMap<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();

        @Override public void init() {}
        @Override public void start() { starts.incrementAndGet(); }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public void tearDown() {}
        @Override public void onEvent(Object e) {}
        @Override public void addEventFeed(com.telamin.fluxtion.runtime.input.EventFeed f) {}
        @Override public void registerService(Service<?> s) {
            if (!s.serviceName().startsWith("svc-")) return;   // only the broadcasts under test, not the scheduler
            registered.computeIfAbsent(s.serviceName(), k -> new AtomicInteger()).incrementAndGet();
        }
        @Override public void deRegisterService(Service<?> s) {
            deregistered.computeIfAbsent(s.serviceName(), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    @Test
    void servicesBroadcastFromManyThreadsAreEachDeliveredOnce() throws Exception {
        ComposingEventProcessorAgent group = newGroup();
        Counting observer = new Counting();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("observer", observer));
        startOnThread(group);
        await(() -> observer.starts.get() == 1, "observer started");

        int perThread = 300;   // 2400 in all, far past the old 128-slot queue
        List<Throwable> producerErrors = runConcurrently(perThread, (t, i) -> {
            Service<Object> svc = new Service<>(new Object(), Object.class, "svc-" + t + "-" + i);
            group.broadcastServiceRegistered(svc);
            group.broadcastServiceDeregistered(svc);
        });

        assertEquals(List.of(), producerErrors, "no producer may fail to queue its broadcast");
        int total = THREADS * perThread;
        await(() -> observer.registered.size() == total && observer.deregistered.size() == total,
                "every broadcast delivered");
        Thread.sleep(50);
        assertEquals(total, observer.registered.size(), "every registration delivered");
        assertTrue(observer.registered.values().stream().allMatch(c -> c.get() == 1), "each registration delivered once");
        assertEquals(total, observer.deregistered.size(), "every deregistration delivered");
        assertTrue(observer.deregistered.values().stream().allMatch(c -> c.get() == 1), "each deregistration delivered once");
        assertEquals(List.of(), agentErrors);
    }

    @Test
    void processorsAddedAndStoppedFromManyThreadsAreEachHandledOnce() throws Exception {
        ComposingEventProcessorAgent group = newGroup();
        startOnThread(group);

        int perThread = 100;
        Map<String, Counting> processors = new ConcurrentHashMap<>();
        List<Throwable> addErrors = runConcurrently(perThread, (t, i) -> {
            String name = "p-" + t + "-" + i;
            Counting p = new Counting();
            processors.put(name, p);
            group.addNamedEventProcessor(() -> new NamedEventProcessor(name, p));
        });
        int total = THREADS * perThread;
        assertEquals(List.of(), addErrors, "no producer may fail to queue its processor");
        await(() -> group.registeredEventProcessors().size() == total, "every processor added");
        assertTrue(processors.values().stream().allMatch(p -> p.starts.get() == 1), "each processor started once");

        List<Throwable> stopErrors = runConcurrently(perThread, (t, i) -> group.removeEventProcessorByName("p-" + t + "-" + i));
        assertEquals(List.of(), stopErrors, "no producer may fail to queue its stop");
        await(() -> group.registeredEventProcessors().isEmpty(), "every processor stopped");
        Thread.sleep(50);
        assertTrue(processors.values().stream().allMatch(p -> p.stops.get() == 1), "each processor stopped once");
        assertEquals(List.of(), agentErrors);
    }

    @Test
    void aBurstBeyondTheOldCapacityQueuedBeforeTheGroupStartsIsAllStarted() throws Exception {
        ComposingEventProcessorAgent group = newGroup();
        int total = 1_000;
        List<Counting> processors = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Counting p = new Counting();
            processors.add(p);
            String name = "p-" + i;
            group.addNamedEventProcessor(() -> new NamedEventProcessor(name, p));
        }
        group.onStart();
        assertEquals(total, group.registeredEventProcessors().size());
        assertTrue(processors.stream().allMatch(p -> p.starts.get() == 1));
        group.onClose();
    }

    private interface Producer {
        void produce(int thread, int item) throws Exception;
    }

    private static List<Throwable> runConcurrently(int perThread, Producer producer) throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            Thread th = new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) producer.produce(thread, i);
                } catch (Throwable e) {
                    errors.add(e);
                }
            }, "producer-" + t);
            threads.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : threads) th.join(30_000);
        return errors;
    }

    private ComposingEventProcessorAgent newGroup() {
        return new ComposingEventProcessorAgent("g", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
    }

    private void startOnThread(ComposingEventProcessorAgent group) {
        runner = new AgentRunner(new YieldingIdleStrategy(), agentErrors::add, null, group);
        AgentRunner.startOnThread(runner);
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end && !condition.getAsBoolean()) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }
}
