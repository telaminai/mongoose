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
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review of #67 (mongoose#58): the two ways a control queue item cannot be queued fail clearly, and a service
 * registration that one stalled group cannot take still reaches the other groups and is reported.
 */
class AgentControlQueueFullTest {

    private final List<ErrorEvent> reported = new CopyOnWriteArrayList<>();
    private final ErrorListener listener = reported::add;

    @BeforeEach
    void listen() {
        ErrorReporting.getReporter().addListener(listener);
    }

    @AfterEach
    void stopListening() {
        ErrorReporting.getReporter().removeListener(listener);
    }

    @Test
    void fullQueueOnTheGroupsOwnThreadFailsAtOnce() {
        ComposingEventProcessorAgent group = newGroup();
        group.onStart();   // this thread is now the group's thread
        Service<Object> svc = new Service<>(new Object(), Object.class, "s");
        for (int i = 0; i < ComposingEventProcessorAgent.CONTROL_QUEUE_CAPACITY; i++) group.broadcastServiceRegistered(svc);

        long t0 = System.nanoTime();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> group.broadcastServiceRegistered(svc));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms < 1_000, "fails at once, not after a timeout: " + ms + " ms");
        assertTrue(e.getMessage().contains("group g") && e.getMessage().contains("own thread"), e.getMessage());
        group.onClose();
    }

    @Test
    void fullQueueOfAGroupNotRunningFailsAfterTheTimeout() {
        ComposingEventProcessorAgent group = newGroup();   // never started: nothing drains its queues
        group.setControlQueueFullTimeout(Duration.ofMillis(200));
        for (int i = 0; i < ComposingEventProcessorAgent.CONTROL_QUEUE_CAPACITY; i++) group.removeEventProcessorByName("x" + i);

        long t0 = System.nanoTime();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> group.removeEventProcessorByName("y"));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms >= 200 && ms < 5_000, "waits for the timeout, then fails: " + ms + " ms");
        assertTrue(e.getMessage().contains("group g") && e.getMessage().contains("processor-stop")
                && e.getMessage().contains("200 ms"), e.getMessage());
    }

    @Test
    void fullQueueDrainedWithinTheTimeoutQueuesTheItem() throws Exception {
        ComposingEventProcessorAgent group = newGroup();
        group.setControlQueueFullTimeout(Duration.ofSeconds(5));
        group.onStart();
        Service<Object> svc = new Service<>(new Object(), Object.class, "s");
        for (int i = 0; i < ComposingEventProcessorAgent.CONTROL_QUEUE_CAPACITY; i++) group.broadcastServiceRegistered(svc);
        Thread producer = new Thread(() -> group.broadcastServiceRegistered(svc));
        producer.start();
        Thread.sleep(50);
        assertTrue(producer.isAlive(), "the producer waits on the full queue");
        group.doWork();   // the group's thread drains it
        producer.join(5_000);
        assertFalse(producer.isAlive(), "the producer queued its item once the group drained");
        group.onClose();
    }

    /** Blocks its group's thread on the "stall" service, and hands the test its group. */
    public static class Staller implements DataFlow {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stalled = new CountDownLatch(1);
        final List<String> services = new CopyOnWriteArrayList<>();
        volatile ComposingEventProcessorAgent group;
        volatile boolean started;
        final boolean stalls;

        Staller(boolean stalls) { this.stalls = stalls; }

        @Override public void init() {}
        @Override public void start() { started = true; }
        @Override public void stop() {}
        @Override public void tearDown() {}
        @Override public void onEvent(Object e) {}
        @Override public void addEventFeed(EventFeed f) { group = (ComposingEventProcessorAgent) f; }
        @Override public void registerService(Service<?> s) {
            services.add(s.serviceName());
            if (stalls && s.serviceName().equals("stall")) {
                stalled.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void serviceRegistrationAStalledGroupCannotTakeReachesTheOthersAndIsReported() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        server.setDefaultErrorHandler(t -> {});
        Staller stalling = new Staller(true);
        Staller fine = new Staller(false);
        server.addEventProcessor("stalling", "a-stalled", null, () -> stalling);
        server.addEventProcessor("fine", "z-fine", null, () -> fine);
        server.init();
        server.start();
        try {
            await(() -> stalling.started && fine.started, "both started");
            server.registerService(new Service<>(new Object(), Object.class, "stall"));
            assertTrue(stalling.stalled.await(5, TimeUnit.SECONDS), "the stalled group's thread is blocked");
            ComposingEventProcessorAgent stalledGroup = stalling.group;
            stalledGroup.setControlQueueFullTimeout(Duration.ofMillis(200));
            Service<Object> filler = new Service<>(new Object(), Object.class, "filler");
            for (int i = 0; i < ComposingEventProcessorAgent.CONTROL_QUEUE_CAPACITY; i++) {
                stalledGroup.broadcastServiceRegistered(filler);
            }

            assertDoesNotThrow(() -> server.registerService(new Service<>(new Object(), Object.class, "late")),
                    "a stalled group does not fail the registration");

            assertTrue(server.registeredServices().containsKey("late"), "the service stays registered");
            await(() -> fine.services.contains("late"), "the other group is given the service");
            assertTrue(reported.stream().anyMatch(e -> e.getMessage().contains("'late'")
                    && e.getMessage().contains("[a-stalled]")), "the stalled group is named: " + reported);
        } finally {
            stalling.release.countDown();
            server.stop();
        }
    }

    private static ComposingEventProcessorAgent newGroup() {
        return new ComposingEventProcessorAgent("g", new EventFlowManager(), null, new DeadWheelScheduler(),
                new ConcurrentHashMap<>());
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !condition.getAsBoolean()) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }
}
