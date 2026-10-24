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
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.DynamicCompositeAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review of #66 (mongoose#57). A processor whose start fails while the server boots fails the boot by default (it used
 * to, by exiting the JVM; #57 had silently dropped it instead); {@code isolateProcessorStartFailuresAtBoot} opts into
 * isolating it. A processor added at runtime is always isolated. Either way a dropped processor's resources are
 * released: its timers never fire into it, its tearDown runs, and the server is told.
 */
class ProcessorStartFailureTest {

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

    static EventSubscriptionKey<Object> key() {
        return new EventSubscriptionKey<>(new EventSourceKey<>("feed"), CallBackType.ON_EVENT_CALL_BACK);
    }

    public static class Proc implements DataFlow {
        final List<EventFeed> feeds = new ArrayList<>();
        final AtomicInteger timerFired = new AtomicInteger();
        volatile boolean started, tornDown;
        boolean failStart, timerInStart, throwingTimerInStart;
        SchedulerService scheduler;

        Proc failStart() { failStart = true; return this; }
        Proc timerInStart() { timerInStart = true; return this; }
        Proc throwingTimerInStart() { throwingTimerInStart = true; return this; }

        @Override public void init() {}
        @Override public void start() {
            for (EventFeed f : feeds) f.subscribe(this, key());
            if (timerInStart) scheduler.scheduleAfterDelay(5, timerFired::incrementAndGet);
            if (throwingTimerInStart) scheduler.scheduleAfterDelay(5, () -> { throw new IllegalStateException("boom in timer"); });
            if (failStart) throw new IllegalStateException("boom in start");
            started = true;
        }
        @Override public void stop() {}
        @Override public void tearDown() {
            tornDown = true;
            for (EventFeed f : feeds) f.removeAllSubscriptions(this);
        }
        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }
        @Override public void onEvent(Object e) {}
        @Override public void registerService(Service<?> s) {
            if (s.instance() instanceof SchedulerService ss) scheduler = ss;
        }
    }

    public static class Feed implements com.telamin.mongoose.service.EventFlowService<Object> {
        @Override public void setEventToQueuePublisher(com.telamin.mongoose.dispatch.EventToQueuePublisher<Object> p) {}
        @Override public void subscribe(EventSubscriptionKey<Object> k) {}
        @Override public void unSubscribe(EventSubscriptionKey<Object> k) {}
    }

    // ---- boot: fail fast by default ----

    @Test
    void startFailureAtBootFailsTheGroupStartByDefaultNamingGroupAndProcessor() {
        CountingFlowManager flowManager = new CountingFlowManager();
        ComposingEventProcessorAgent group = newGroup("boot-group", flowManager);
        Proc bad = new Proc().failStart();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("bad", bad));

        ProcessorStartException failure = assertThrows(ProcessorStartException.class, group::onStart,
                "a boot processor's failed start fails the group's start");
        assertTrue(failure.getMessage().contains("'bad'") && failure.getMessage().contains("'boot-group'"),
                failure.getMessage());
        assertEquals("boot-group", failure.groupName());
        assertEquals("bad", failure.processorName());
        assertEquals("boom in start", failure.getCause().getMessage());
        assertFalse(group.isProcessorRegistered("bad"), "the processor is dropped");
        assertTrue(bad.tornDown, "the dropped processor is torn down");
        assertEquals(1, flowManager.unSubscribed.get(), "its subscription is released");
        assertTrue(reported.isEmpty(), "reported once, by the agent's error handler, not also here: " + reported);
    }

    /** On a real AgentRunner: the failure reaches the agent's error handler and the group never becomes active. */
    @Test
    void startFailureAtBootReachesTheErrorHandlerAndTheGroupNeverBecomesActive() throws Exception {
        ComposingEventProcessorAgent group = newGroup("boot-group", new CountingFlowManager());
        Proc good = new Proc();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("good", good));
        group.addNamedEventProcessor(() -> new NamedEventProcessor("bad", new Proc().failStart()));
        List<Throwable> agentErrors = new CopyOnWriteArrayList<>();
        AgentRunner runner = new AgentRunner(new BusySpinIdleStrategy(), agentErrors::add, null, group);
        AgentRunner.startOnThread(runner);
        try {
            await(() -> group.status() == DynamicCompositeAgent.Status.CLOSED, "the group closed");
            assertEquals(1, agentErrors.size(), agentErrors.toString());
            ProcessorStartException failure = assertInstanceOf(ProcessorStartException.class, agentErrors.get(0));
            assertEquals("bad", failure.processorName());
            assertEquals("boot-group", failure.groupName());
        } finally {
            runner.close();
        }
    }

    @Test
    void serverWithBootIsolationOnBootsWithoutTheFailedProcessor() throws Exception {
        MongooseServerConfig cfg = MongooseServerConfig.builder().isolateProcessorStartFailuresAtBoot(true).build();
        MongooseServer server = new MongooseServer(cfg);
        List<Throwable> agentErrors = new CopyOnWriteArrayList<>();
        server.setDefaultErrorHandler(agentErrors::add);
        server.registerEventSource("feed", new Feed());
        Proc good = new Proc();
        server.addEventProcessor("bad", "g", null, () -> new Proc().failStart());
        server.addEventProcessor("good", "g", null, () -> good);
        server.init();
        server.start();
        try {
            assertTrue(good.started);
            assertFalse(server.registeredProcessors().get("g").stream().anyMatch(p -> p.name().equals("bad")));
            assertEquals(List.of(), agentErrors);
            assertTrue(reported.stream().anyMatch(e -> e.getMessage().contains("'bad'")), "reported: " + reported);
        } finally {
            server.stop();
        }
    }

    @Test
    void bootIsolationIsOffByDefaultAndYamlSettable() {
        assertFalse(new MongooseServerConfig().isIsolateProcessorStartFailuresAtBoot());
        assertFalse(MongooseServerConfig.builder().build().isIsolateProcessorStartFailuresAtBoot());
        MongooseServerConfig fromYaml = new Yaml().loadAs("isolateProcessorStartFailuresAtBoot: true",
                MongooseServerConfig.class);
        assertTrue(fromYaml.isIsolateProcessorStartFailuresAtBoot());
    }

    // ---- runtime: always isolated ----

    /**
     * Integration of #65 and #66: a processor dropped after a failed runtime start releases its name reservation, so a
     * processor of the same name can be added to that group again (it used to be refused as a duplicate for ever).
     */
    @Test
    void processorDroppedAfterAFailedRuntimeStartReleasesItsName() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        server.setDefaultErrorHandler(t -> {});
        server.registerEventSource("feed", new Feed());
        server.addEventProcessor("first", "g", null, Proc::new);
        server.init();
        server.start();
        try {
            server.addEventProcessor("p", "g", null, () -> new Proc().failStart());
            await(() -> reported.stream().anyMatch(e -> e.getMessage().contains("'p'")), "the failed start reported");
            Proc again = new Proc();
            java.util.concurrent.atomic.AtomicBoolean added = new java.util.concurrent.atomic.AtomicBoolean();
            await(() -> {
                if (added.get()) return true;   // await checks once more after it succeeds: add only once
                try {
                    server.addEventProcessor("p", "g", null, () -> again);
                    added.set(true);
                    return true;
                } catch (IllegalArgumentException stillReserved) {
                    return false;
                }
            }, "the name 'p' is free again in group 'g'");
            await(() -> again.started, "the re-added processor started");
            assertTrue(server.registeredProcessors().get("g").stream().anyMatch(n -> n.name().equals("p")));
        } finally {
            server.stop();
        }
    }

    /** A processor added at runtime to a group the running server starts for it is isolated, not a failed boot. */
    @Test
    void startFailureInANewGroupAddedAtRuntimeIsIsolated() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        List<Throwable> agentErrors = new CopyOnWriteArrayList<>();
        server.setDefaultErrorHandler(agentErrors::add);
        server.registerEventSource("feed", new Feed());
        Proc first = new Proc();
        server.addEventProcessor("first", "g1", null, () -> first);
        server.init();
        server.start();
        try {
            Proc good = new Proc();
            server.addEventProcessor("bad", "late-group", null, () -> new Proc().failStart());
            server.addEventProcessor("good", "late-group", null, () -> good);
            await(() -> good.started, "good started in the late group");
            assertEquals(List.of(), agentErrors, "a runtime add never fails like a boot");
            assertTrue(reported.stream().anyMatch(e -> e.getMessage().contains("'bad'")), "reported: " + reported);
        } finally {
            server.stop();
        }
    }

    @Test
    void droppedProcessorIsReleasedAndTheServerIsTold() throws Exception {
        List<String> dropped = new CopyOnWriteArrayList<>();
        MongooseServer server = new MongooseServer(new MongooseServerConfig()) {
            @Override
            public void processorDropped(String groupName, String processorName, DataFlow eventProcessor, Object registration) {
                assertNotNull(registration, "the drop carries the accepted start's registration identity");
                dropped.add(groupName + "/" + processorName);
                super.processorDropped(groupName, processorName, eventProcessor, registration);
            }
        };
        server.setDefaultErrorHandler(t -> {});
        server.registerEventSource("feed", new Feed());
        Proc first = new Proc();
        server.addEventProcessor("first", "g", null, () -> first);
        server.init();
        server.start();
        try {
            Proc bad = new Proc().failStart();
            server.addEventProcessor("bad", "g", null, () -> bad);
            await(() -> dropped.contains("g/bad"), "the server is told the processor was dropped");
            assertTrue(bad.tornDown, "tearDown runs after a failed start");
        } finally {
            server.stop();
        }
    }

    // ---- a dropped processor's timers ----

    @Test
    void timerScheduledInAFailedStartNeverFires() throws Exception {
        ComposingEventProcessorAgent group = newGroup("g", new CountingFlowManager());
        group.onStart();
        Proc bad = new Proc().timerInStart().failStart();
        Proc good = new Proc().timerInStart();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("bad", bad));
        group.addNamedEventProcessor(() -> new NamedEventProcessor("good", good));
        long end = System.nanoTime() + 300_000_000L;
        while (System.nanoTime() < end) group.doWork();
        assertEquals(1, good.timerFired.get(), "a running processor's timer fires");
        assertEquals(0, bad.timerFired.get(), "a dropped processor's timer never fires");
        group.onClose();
    }

    @Test
    void timerCallbackFailureNamesTheProcessor() throws Exception {
        ComposingEventProcessorAgent group = newGroup("g", new CountingFlowManager());
        group.onStart();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("ticker", new Proc().throwingTimerInStart()));
        long end = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < end && reported.isEmpty()) group.doWork();
        assertTrue(reported.stream().anyMatch(e -> e.getMessage().contains("processor 'ticker'")
                && e.getMessage().contains("boom in timer")), "the report names the processor: " + reported);
        group.onClose();
    }

    @Test
    void cancelTimersOfCancelsOnlyThatProcessorsTimers() {
        DeadWheelScheduler scheduler = new DeadWheelScheduler();
        Proc a = new Proc(), b = new Proc();
        AtomicInteger firedA = new AtomicInteger(), firedB = new AtomicInteger(), firedNone = new AtomicInteger();
        com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(a);
        scheduler.scheduleAfterDelay(1, firedA::incrementAndGet);
        scheduler.scheduleAfterDelay(1, firedA::incrementAndGet);
        com.telamin.mongoose.dispatch.ProcessorContext.setCurrentProcessor(b);
        scheduler.scheduleAfterDelay(1, firedB::incrementAndGet);
        com.telamin.mongoose.dispatch.ProcessorContext.removeCurrentProcessor();
        scheduler.scheduleAfterDelay(1, firedNone::incrementAndGet);

        assertEquals(2, scheduler.cancelTimersOf(a));
        long end = System.currentTimeMillis() + 2_000;
        while ((firedB.get() == 0 || firedNone.get() == 0) && System.currentTimeMillis() < end) scheduler.doWork();
        assertEquals(0, firedA.get());
        assertEquals(1, firedB.get());
        assertEquals(1, firedNone.get());
    }

    private static ComposingEventProcessorAgent newGroup(String name, EventFlowManager flowManager) {
        return new ComposingEventProcessorAgent(name, flowManager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !condition.getAsBoolean()) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    private static class CountingFlowManager extends EventFlowManager {
        final AtomicInteger unSubscribed = new AtomicInteger();

        @Override public void subscribe(EventSubscriptionKey<?> key) {}
        @Override public void unSubscribe(EventSubscriptionKey<?> key) { unSubscribed.incrementAndGet(); }
        @Override public <T> EventQueueToEventProcessor getMappingAgent(EventSubscriptionKey<T> key, Agent subscriber) {
            return new EventQueueToEventProcessor() {
                final List<DataFlow> processors = new ArrayList<>();
                @Override public int registerProcessor(DataFlow p) { processors.add(p); return processors.size(); }
                @Override public int deregisterProcessor(DataFlow p) { processors.remove(p); return processors.size(); }
                @Override public int listenerCount() { return processors.size(); }
                @Override public int doWork() { return 0; }
                @Override public String roleName() { return "fakeReader"; }
            };
        }
    }
}
