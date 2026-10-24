/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.AuditCaptureConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.PerformanceMonitoringConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.replay.GroupReplayer;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.audit.MongooseAuditCaptureService;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mongoose#71: a processor stopped normally ({@code stopProcessor}) leaves its group by the same path as one dropped
 * after a failed start, so everything held for it is released: its timers never fire into it, the replay forgets it,
 * the audit-capture service unbinds it, and a processor added again under its name starts clean.
 */
class ProcessorStopReleaseTest {

    private static final String FEED = "feed";

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

    /**
     * Subscribes to {@link #FEED} and schedules one timer on start; counts services, events and timer callbacks, and
     * notes whether a callback ran after its stop.
     */
    public static class Proc implements DataFlow {
        final List<EventFeed> feeds = new ArrayList<>();
        final Map<String, AtomicInteger> registered = new ConcurrentHashMap<>();
        final AtomicInteger received = new AtomicInteger();
        final AtomicInteger timerFired = new AtomicInteger();
        final AtomicInteger firedAfterStop = new AtomicInteger();
        final AtomicInteger schedulerRegistrations = new AtomicInteger();
        final long timerDelayMillis;
        volatile boolean started, stopped, tornDown;
        boolean failStop, subscribe = true;
        SchedulerService scheduler;

        Proc(long timerDelayMillis) { this.timerDelayMillis = timerDelayMillis; }

        Proc failStop() { failStop = true; return this; }
        Proc noSubscription() { subscribe = false; return this; }

        static EventSubscriptionKey<Object> key() {
            return new EventSubscriptionKey<>(new EventSourceKey<>(FEED), CallBackType.ON_EVENT_CALL_BACK);
        }

        @Override public void init() {}
        @Override public void start() {
            if (subscribe) for (EventFeed f : feeds) f.subscribe(this, key());
            if (timerDelayMillis >= 0) scheduler.scheduleAfterDelay(timerDelayMillis, () -> {
                timerFired.incrementAndGet();
                if (stopped) firedAfterStop.incrementAndGet();
            });
            started = true;
        }
        @Override public void stop() {
            stopped = true;
            if (failStop) throw new IllegalStateException("boom in stop");
        }
        @Override public void tearDown() {
            tornDown = true;
            for (EventFeed f : feeds) f.removeAllSubscriptions(this);
        }
        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }
        @Override public void onEvent(Object e) { if (e instanceof String) received.incrementAndGet(); }
        @Override public void registerService(Service<?> s) {
            if (s.instance() instanceof SchedulerService ss) {
                scheduler = ss;
                schedulerRegistrations.incrementAndGet();
            }
            registered.computeIfAbsent(s.serviceName(), k -> new AtomicInteger()).incrementAndGet();
        }

        int registrations(String name) {
            AtomicInteger c = registered.get(name);
            return c == null ? 0 : c.get();
        }
    }

    // ---- timers ----

    @Test
    void timerScheduledBeforeAStopNeverFiresIntoTheStoppedProcessor() throws Exception {
        ComposingEventProcessorAgent group = newGroup();
        group.onStart();
        Proc stopping = new Proc(150).noSubscription();
        Proc running = new Proc(150).noSubscription();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("stopping", stopping));
        group.addNamedEventProcessor(() -> new NamedEventProcessor("running", running));
        group.doWork();
        assertTrue(stopping.started && running.started);

        group.removeEventProcessorByName("stopping");
        long end = System.nanoTime() + 600_000_000L;
        while (System.nanoTime() < end) group.doWork();

        assertTrue(stopping.tornDown, "the stopped processor is torn down");
        assertEquals(1, running.timerFired.get(), "a running processor's timer fires");
        assertEquals(0, stopping.timerFired.get(), "a stopped processor's timer never fires");
        group.onClose();
    }

    /** A stop that throws still tears the processor down and releases the rest: each step is guarded. */
    @Test
    void processorWhoseStopThrowsIsStillTornDownAndItsTimersCancelled() throws Exception {
        ComposingEventProcessorAgent group = newGroup();
        group.onStart();
        Proc failing = new Proc(150).failStop().noSubscription();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", failing));
        group.doWork();
        assertTrue(failing.started);

        group.removeEventProcessorByName("p");
        long end = System.nanoTime() + 600_000_000L;
        while (System.nanoTime() < end) assertDoesNotThrow(group::doWork);

        assertFalse(group.isProcessorRegistered("p"));
        assertTrue(failing.tornDown, "tearDown runs though stop threw");
        assertEquals(0, failing.timerFired.get(), "its timer never fires");
        assertTrue(reported.stream().anyMatch(e -> e.getMessage().contains("processor 'p'")
                && e.getMessage().contains("boom in stop")), "the failed stop is reported: " + reported);
        group.onClose();
    }

    // ---- audit capture ----

    @Test
    void stoppedProcessorIsUnboundFromTheAuditCapture(@TempDir Path dir) throws Exception {
        AuditCaptureConfig capture = new AuditCaptureConfig();
        capture.setEnabled(true);
        capture.setDirectory(dir.toString());
        capture.setAutoStart(List.of("p"));
        PerformanceMonitoringConfig perf = new PerformanceMonitoringConfig();
        perf.setAuditCapture(capture);
        MongooseServerConfig cfg = new MongooseServerConfig();
        cfg.setPerformanceMonitoring(perf);
        Proc first = new Proc(-1).noSubscription();
        cfg.addProcessor("g", first, "p");
        MongooseServer server = MongooseServer.bootServer(cfg, rec -> { });
        try {
            MongooseAuditCaptureService audit = (MongooseAuditCaptureService)
                    server.registeredServices().get(MongooseAuditCaptureService.SERVICE_NAME).instance();
            await(() -> first.started, "p started");
            assertTrue(audit.isRecording("p"), "auto-started capture records p");

            server.stopProcessor("g", "p");
            await(() -> first.tornDown, "p torn down");
            await(() -> !audit.isRecording("p"), "the capture of the stopped processor is stopped");
            IllegalArgumentException unbound = assertThrows(IllegalArgumentException.class, () -> audit.start("p"),
                    "the capture service no longer holds the stopped processor");
            assertTrue(unbound.getMessage().contains("'p'"), unbound.getMessage());

            // the same name again: a fresh binding, recording the new processor
            Proc second = new Proc(-1).noSubscription();
            server.addEventProcessor("p", "g", null, () -> second);
            await(() -> second.started, "p added again");
            assertTrue(audit.isRecording("p"), "auto-start binds and records the processor added again");
        } finally {
            server.stop();
        }
    }

    // ---- replay ----

    @Test
    void stoppedProcessorLeavesTheReplay() throws Exception {
        MongooseServerConfig cfg = MongooseServerConfig.builder()
                .replay(ReplayConfig.replay(Set.of("p"), Map.of(), null, new InMemoryReplayStore()))
                .build();
        Proc replayed = new Proc(-1).noSubscription();
        cfg.addProcessor("g", replayed, "p");
        MongooseServer server = MongooseServer.bootServer(cfg, rec -> { });
        try {
            await(() -> replayed.started, "p started");
            GroupReplayer replayer = server.replayers().get("g");
            assertTrue(replayer.replays(replayed), "p is replayed");

            server.stopProcessor("g", "p");
            await(() -> replayed.tornDown, "p torn down");
            await(() -> !replayer.replays(replayed), "the replay forgets the stopped processor");
        } finally {
            server.stop();
        }
    }

    // ---- stop, then the same name again ----

    @Test
    void processorAddedAgainUnderAStoppedNameGetsServicesAndEventsOnceAndItsTimersWork() throws Exception {
        InMemoryEventSource<String> feed = new InMemoryEventSource<>();
        MongooseServerConfig cfg = new MongooseServerConfig();
        Proc first = new Proc(300);
        cfg.addProcessor("g", first, "p");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg, rec -> { });
        try {
            server.registerService(new Service<>(new Object(), Object.class, "svc"));
            await(() -> first.started && first.registrations("svc") == 1, "p started and given svc");
            feed.offer("x");
            await(() -> first.received.get() == 1, "p received its event");

            server.stopProcessor("g", "p");                 // well before its 300 ms timer is due
            await(() -> first.tornDown, "p torn down");
            await(() -> !server.registeredProcessors().get("g").stream().anyMatch(n -> n.name().equals("p")),
                    "p left the group");

            Proc second = new Proc(50);
            server.addEventProcessor("p", "g", null, () -> second);
            await(() -> second.started, "p added again");
            Thread.sleep(200);                              // let a duplicate delivery, if any, arrive
            feed.offer("y");
            await(() -> second.received.get() >= 1, "the new p received the event");
            await(() -> second.timerFired.get() >= 1, "the new p's timer fired");
            Thread.sleep(400);                              // past the old processor's timer

            assertEquals(1, second.registrations("svc"), "the new processor is given svc once: " + second.registered);
            assertEquals(1, second.schedulerRegistrations.get(), "and the scheduler once: " + second.registered);
            assertEquals(1, second.received.get(), "the new processor receives the event once");
            assertEquals(1, second.timerFired.get(), "its timer fires once");
            assertEquals(1, first.received.get(), "the stopped processor receives nothing after its stop");
            assertEquals(0, first.timerFired.get(), "the stopped processor's timer never fires");
        } finally {
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
