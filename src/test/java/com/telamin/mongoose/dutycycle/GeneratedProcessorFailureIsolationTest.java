/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.audit.EventLogControlEvent;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.AgentThreads;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GeneratedProcessorFailureIsolationTest {
    @AfterEach void clearContext() { ProcessorContext.removeCurrentProcessor(); AgentThreads.exit(); }

    static class Generated extends GroupControlProcessor {
        volatile boolean failAudit;
        volatile boolean failListenerChange;
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger tornDown = new AtomicInteger();
        EventFeed feed;
        boolean subscribe;
        Generated() {
            installThrowingListener();
            init();
            setAuditLogLevel(EventLogControlEvent.LogLevel.INFO);
        }
        void installThrowingListener() {
            setAuditLogProcessor(record -> {
                if (failAudit) { failures.incrementAndGet(); throw new IllegalStateException("injected audit failure"); }
            });
        }
        @Override public void onEvent(Object event) { calls.incrementAndGet(); super.onEvent(event); }
        @Override public void onEventInternal(Object event) {
            super.onEventInternal(event);
            if (failListenerChange && event instanceof EventLogControlEvent)
                throw new IllegalStateException("injected listener control failure inside generated cycle");
        }
        @Override public void addEventFeed(EventFeed feed) { super.addEventFeed(feed); this.feed = feed; }
        @Override public void startComplete() {
            super.startComplete();
            if (subscribe) {
                feed.subscribe(this, key("feed"));
                feed.subscribe(this, key("second"));
            }
        }
        @Override public void tearDown() { tornDown.incrementAndGet(); super.tearDown(); }
    }

    @Test void generatedAuditThrowIsQuarantinedWithoutRetryAndOtherTargetsContinue() {
        Generated flow = new Generated(); flow.start();
        var queue = new OneToOneConcurrentArrayQueue<Object>(16);
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "generated-failure");
        var good = new AtomicInteger();
        var first = new AtomicInteger();
        reader.registerProcessor((DataFlow) event -> first.incrementAndGet());
        reader.registerProcessor(flow);
        reader.registerProcessor((DataFlow) event -> good.incrementAndGet());
        int before = flow.calls.get();
        flow.failAudit = true;
        queue.offer(new RunIntent(1)); queue.offer(new RunIntent(2));
        assertEquals(2, reader.doWork());
        assertEquals(before + 1, flow.calls.get(), "no retry re-enters the failed graph");
        assertEquals(2, flow.failures.get(), "the failing auditor is called once on dispatch and once by failed-cycle cleanup");
        assertEquals(2, good.get(), "healthy subscriber receives both inputs exactly once");
        assertEquals(2, first.get(), "earlier subscriber must not receive a duplicate after the failure");
        assertEquals(2, reader.listenerCount());
        assertNull(ProcessorContext.currentProcessor());
        reader.onClose();
    }

    @Test void realAgentKeepsHealthyProcessorsRunningAndReportsQuarantinedOwnership() throws Exception {
        var errors = new CopyOnWriteArrayList<Throwable>();
        var config = new MongooseServerConfig(); config.setShutdownTimeoutMillis(200);
        var server = new MongooseServer(config);
        server.setDefaultErrorHandler(errors::add);
        class Feed implements EventFlowService<RunIntent> {
            volatile EventToQueuePublisher<RunIntent> publisher;
            public void setEventToQueuePublisher(EventToQueuePublisher<RunIntent> p) { publisher = p; }
            public void subscribe(EventSubscriptionKey<RunIntent> key) { }
            public void unSubscribe(EventSubscriptionKey<RunIntent> key) { }
        }
        Feed feed = new Feed();
        Feed second = new Feed();
        AtomicInteger healthyEvents = new AtomicInteger();
        AtomicInteger healthyStops = new AtomicInteger();
        class Healthy implements DataFlow {
            EventFeed subscription;
            public void init() { }
            public void onEvent(Object event) { if (event instanceof RunIntent) healthyEvents.incrementAndGet(); }
            public void addEventFeed(EventFeed f) { subscription = f; }
            public void start() { subscription.subscribe(this, key("feed")); subscription.subscribe(this, key("second")); }
            public void stop() { healthyStops.incrementAndGet(); }
        }
        Generated flow = new Generated(); flow.subscribe = true;
        server.registerEventSource("feed", feed);
        server.registerEventSource("second", second);
        server.addEventProcessor("generated", "g", null, () -> flow);
        server.addEventProcessor("healthy", "g", null, Healthy::new);
        server.init(); server.start();
        try {
            await(() -> feed.publisher != null && !feed.publisher.getTargetQueues().isEmpty());
            flow.installThrowingListener(); // the server installs its configured listener during registration
            int before = flow.calls.get();
            flow.failAudit = true;
            feed.publisher.publish(new RunIntent(9));
            await(() -> server.controlGroupStatus("g").quarantinedProcessors().containsKey("generated"));
            for (int i = 0; i < 10; i++) {
                feed.publisher.publish(new RunIntent(100 + i));
                second.publisher.publish(new RunIntent(200 + i));
            }
            await(() -> healthyEvents.get() == 21);
            assertEquals(before + 1, flow.calls.get(), "all queues fence the failed graph");
            assertEquals(ControlDriver.GroupState.RUNNING, server.controlGroupStatus("g").state());
            assertTrue(errors.isEmpty(), "the group stays live");
            flow.failAudit = false;
            var stopResult = server.shutdown();
            assertFalse(stopResult.complete(), "unknown graph-owned cleanup must remain explicit");
            // issue #77 M4: the typed result names what is still held, and why
            assertEquals(List.of("g/generated"), stopResult.heldNames().stream().map(h -> h.group() + "/" + h.name()).toList(),
                    stopResult.toString());
            assertTrue(stopResult.heldNames().get(0).reason().startsWith("quarantined: "), stopResult.toString());
            assertEquals(0, flow.tornDown.get(), "never re-enter damaged generated lifecycle methods");
            assertEquals(1, healthyStops.get(), "healthy lifecycle still closes");
        } finally { server.stop(); }
    }

    @Test void generatedServiceFailureQuarantinesTheProcessorBeforeItsNextDataCycle() throws Exception {
        var group = new ComposingEventProcessorAgent("service-failure", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        Generated flow = new Generated();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("generated", flow));
        group.onStart();
        try {
            flow.failAudit = true;
            var request = group.broadcastService(new Service<>(new Object(), Object.class, "extra"), true, 1);
            assertDoesNotThrow(group::doWork);
            assertTrue(request.reply().isDone());
            assertFalse(request.reply().join().applied());
            assertEquals(ControlDriver.GroupState.RUNNING, group.control().state());
            assertTrue(group.quarantinedProcessors().containsKey("generated"));
            flow.failAudit = false;
            assertDoesNotThrow(group::doWork);
            assertTrue(group.control().audit().exportText().contains("ProcessorQuarantined"));
            for (int i = 0; i < 2; i++) {
                var stop = group.removeEventProcessorByName("generated", null, null);
                group.doWork();
                assertFalse(stop.reply().join().applied(), "repeated stop cannot pretend damaged lifecycle was released");
                assertEquals(com.telamin.mongoose.control.event.FailureKind.QUARANTINED, stop.reply().join().kind(),
                        stop.reply().join().toString());
            }
        } finally { group.onClose(); }
        assertEquals(0, flow.tornDown.get());
    }

    @Test void standaloneSchedulerFencesFailedOwnerTimersWithoutBreakingItsReportingApi() {
        Generated flow = new Generated(); flow.start();
        DeadWheelScheduler scheduler = new DeadWheelScheduler();
        scheduler.setcurrentTickTime(System.currentTimeMillis() - 10_000);
        ProcessorContext.setCurrentProcessor(flow);
        AtomicInteger laterCalls = new AtomicInteger();
        try {
            scheduler.scheduleAtTime(System.currentTimeMillis() - 5, () -> flow.onEvent(new RunIntent(7)));
            scheduler.scheduleAtTime(System.currentTimeMillis() + 100, laterCalls::incrementAndGet);
        }
        finally { ProcessorContext.removeCurrentProcessor(); }
        int before = flow.calls.get();
        flow.failAudit = true;
        assertDoesNotThrow(() -> { for (int i = 0; i < 1000; i++) scheduler.doWork(); });
        assertEquals(before, flow.calls.get(), "the failing audit prologue prevents the timer's event handler from running");
        assertEquals(2, flow.failures.get(), "the failing auditor is called once on dispatch and once by failed-cycle cleanup");
        assertEquals(0, scheduler.cancelTimersOf(flow), "remaining owned timer was already cancelled");
        assertEquals(0, laterCalls.get());
        assertNull(ProcessorContext.currentProcessor());
    }

    @Test void generatedStartFailureRetainsOwnershipWithoutCallingTearDown() throws Exception {
        Generated flow = new Generated() {
            @Override public void start() { super.start(); throw new IllegalStateException("partial start"); }
        };
        var group = newGroup("start-failure");
        AtomicInteger namesReleased = new AtomicInteger();
        var registration = new ProcessorRegistration("generated");
        registration.nameReleaser(namesReleased::incrementAndGet);
        group.onStart();
        try {
            var add = group.addNamedEventProcessor(() -> new NamedEventProcessor("generated", flow), registration);
            group.doWork();
            assertFalse(add.reply().join().applied());
            assertTrue(group.quarantinedProcessors().containsKey("generated"));
            assertEquals(0, namesReleased.get());
        } finally { group.onClose(); }
        group.cleanupAfterDeath();
        assertEquals(0, namesReleased.get(), "failed start still owns unknown application resources");
        assertEquals(0, flow.tornDown.get());
    }

    @Test void generatedStopFailureCannotReenterTearDownOrReleaseItsName() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        Generated flow = new Generated() {
            @Override public void stop() { stops.incrementAndGet(); throw new IllegalStateException("partial stop"); }
        };
        var group = newGroup("stop-failure");
        AtomicInteger namesReleased = new AtomicInteger();
        var registration = new ProcessorRegistration("generated");
        registration.nameReleaser(namesReleased::incrementAndGet);
        group.addNamedEventProcessor(() -> new NamedEventProcessor("generated", flow), registration);
        group.onStart();
        try {
            for (int i = 0; i < 2; i++) {
                var stop = group.removeEventProcessorByName("generated", registration, null);
                group.doWork();
                assertFalse(stop.reply().join().applied());
                assertEquals(com.telamin.mongoose.control.event.FailureKind.QUARANTINED, stop.reply().join().kind(),
                        stop.reply().join().toString());
            }
        } finally { group.onClose(); }
        group.cleanupAfterDeath();
        assertEquals(1, stops.get());
        assertEquals(0, namesReleased.get());
        assertEquals(0, flow.tornDown.get());
    }

    @Test void replayDispatchFailureAlsoRemovesTheDamagedGeneratedTarget() {
        Generated flow = new Generated(); flow.start();
        var reader = new EventQueueToEventProcessorAgent(new OneToOneConcurrentArrayQueue<Object>(8),
                new EventToOnEventInvokeStrategy(), "replay-failure");
        reader.registerProcessor(flow);
        int before = flow.calls.get();
        flow.failAudit = true;
        assertThrows(IllegalStateException.class, () -> reader.replayTo(flow, new RunIntent(1)));
        assertEquals(0, reader.listenerCount());
        assertThrows(IllegalArgumentException.class, () -> reader.replayTo(flow, new RunIntent(2)));
        assertEquals(before + 1, flow.calls.get(), "replay cannot queue a second input in the failed graph");
        reader.onClose();
    }

    @Test void captureAfterQuarantineRefusesInstallAndDetachesWithoutCallingTheGraph() throws Exception {
        var group = newGroup("capture-failure");
        var tasks = new java.util.ArrayDeque<Runnable>();
        group.setCaptureIo(new com.telamin.mongoose.control.CaptureIo() {
            public void open(Runnable task) { tasks.add(task); }
            public void close(Runnable task) { tasks.add(task); }
            public Cancellable schedule(Runnable task, long millis) { return () -> { }; }
        });
        AtomicInteger opens = new AtomicInteger(), installs = new AtomicInteger(), closes = new AtomicInteger();
        var detaches = new java.util.ArrayList<DataFlow>();
        group.setAuditCaptureResources(new com.telamin.mongoose.service.audit.AuditCaptureResources() {
            public Object openCapture(String name) { opens.incrementAndGet(); return new Object(); }
            public void installCapture(Object handle, String name, DataFlow flow) { installs.incrementAndGet(); }
            public void uninstallCapture(Object handle, String name, DataFlow flow) { detaches.add(flow); }
            public void closeCapture(Object handle) { closes.incrementAndGet(); }
        });
        Generated flow = new Generated();
        var registration = new ProcessorRegistration("generated");
        group.addNamedEventProcessor(() -> new NamedEventProcessor("generated", flow), registration);
        group.onStart();
        try {
            var start = group.auditStart("generated", registration);
            pump(group, tasks);
            assertTrue(start.reply().join().applied());
            flow.failAudit = true;
            var service = group.broadcastService(new Service<>(new Object(), Object.class, "extra"), true, 1);
            group.doWork();
            assertFalse(service.reply().join().applied());
            var stop = group.auditStop("generated", registration);
            pump(group, tasks);
            assertTrue(stop.reply().join().applied());
            assertEquals(1, detaches.size());
            assertNull(detaches.get(0), "a quarantined graph must never be passed to the capture plugin");
            var restart = group.auditStart("generated", registration);
            pump(group, tasks);
            assertFalse(restart.reply().join().applied());
            assertEquals(1, installs.get(), "no second install can re-enter the quarantined graph");
            assertEquals(opens.get(), closes.get(), "refused installs still close their newly opened resources");
        } finally { group.onClose(); pump(group, tasks); }
        assertEquals(0, flow.tornDown.get());
    }

    @Test void routedChronicleInstallFailureQuarantinesGeneratedGraph(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        chronicleListenerFailure(dir, true);
    }

    @Test void routedChronicleRestoreFailureQuarantinesGeneratedGraph(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        chronicleListenerFailure(dir, false);
    }

    private void chronicleListenerFailure(java.nio.file.Path dir, boolean onInstall) throws Exception {
        var group = newGroup("chronicle-failure");
        var tasks = new java.util.ArrayDeque<Runnable>();
        group.setCaptureIo(new com.telamin.mongoose.control.CaptureIo() {
            public void open(Runnable task) { tasks.add(task); }
            public void close(Runnable task) { tasks.add(task); }
            public Cancellable schedule(Runnable task, long millis) { return () -> { }; }
        });
        var config = new com.telamin.mongoose.config.AuditCaptureConfig();
        config.setEnabled(true); config.setDirectory(dir.toString());
        var resources = new com.telamin.mongoose.internal.ChronicleAuditCaptureService(config,
                com.telamin.mongoose.internal.NoOpCountersService.INSTANCE);
        group.setAuditCaptureResources(resources);
        Generated flow = new Generated();
        var registration = new ProcessorRegistration("generated");
        group.addNamedEventProcessor(() -> new NamedEventProcessor("generated", flow), registration);
        group.onStart();
        resources.attachScoped(flow, new com.telamin.mongoose.service.audit.AuditCaptureIdentity(
                "chronicle-failure", "generated", registration.generation()), record -> { }, Runnable::run);
        try {
            flow.failListenerChange = onInstall;
            var start = group.auditStart("generated", registration);
            pump(group, tasks);
            if (onInstall) assertFalse(start.reply().join().applied());
            else {
                assertTrue(start.reply().join().applied());
                flow.failListenerChange = true;
                var stop = group.auditStop("generated", registration);
                pump(group, tasks);
                assertFalse(stop.reply().join().applied(), "failed restore must not be swallowed");
            }
            assertTrue(group.quarantinedProcessors().containsKey("generated"));
            assertFalse(resources.isRecording("chronicle-failure/generated"));
            assertEquals(ControlDriver.GroupState.RUNNING, group.control().state());
        } finally { group.onClose(); while (!tasks.isEmpty()) tasks.remove().run(); }
        assertEquals(0, flow.tornDown.get());
        assertEquals(0, group.captureResourceSlotsInUse(), "offline cleanup closes the real queue");
    }

    @Test void groupReplayFailureCannotReenterGeneratedLifecycleOnShutdown() throws Exception {
        class Manager extends EventFlowManager {
            EventQueueToEventProcessorAgent reader;
            @Override public <T> EventQueueToEventProcessor getMappingAgent(EventSubscriptionKey<T> key, org.agrona.concurrent.Agent agent) {
                reader = (EventQueueToEventProcessorAgent) super.getMappingAgent(key, agent);
                return reader;
            }
        }
        var manager = new Manager();
        var group = new ComposingEventProcessorAgent("group-replay", manager, null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        manager.registerEventSource("feed", new com.telamin.mongoose.connector.memory.InMemoryEventSource<>());
        Generated flow = new Generated();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("generated", flow));
        group.onStart();
        group.subscribe(flow, key("feed"));
        var reader = manager.reader;
        try {
            flow.failAudit = true;
            assertThrows(IllegalStateException.class, () -> reader.replayTo(flow, new RunIntent(1)));
            assertTrue(group.quarantinedProcessors().containsKey("generated"));
            assertEquals(0, reader.listenerCount());
        } finally { group.onClose(); }
        assertEquals(0, flow.tornDown.get());
    }

    /**
     * rc1 review N1: an opaque strategy (failedPosition() == -1) cannot name the target, but the processor captured at
     * throw time can. Each generated thrower is quarantined alone, as it fails; the group keeps running.
     */
    @Test void opaqueDispatcherQuarantinesEachIdentifiedGeneratedThrowerAndTheGroupRunsOn() throws Exception {
        var manager = new EventFlowManager();
        manager.registerEventMapperFactory(() -> new EventToOnEventInvokeStrategy() {
            @Override public int failedPosition() { return -1; }
            @Override public DataFlow failedProcessor() { return null; }
        }, CallBackType.ON_EVENT_CALL_BACK);
        var publisher = manager.registerEventSource("feed", new com.telamin.mongoose.connector.memory.InMemoryEventSource<>());
        var group = new ComposingEventProcessorAgent("opaque", manager, null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        Generated first = new Generated(), second = new Generated();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("first", first));
        group.addNamedEventProcessor(() -> new NamedEventProcessor("second", second));
        group.onStart();
        group.subscribe(first, key("feed")); group.subscribe(second, key("feed"));
        try {
            first.failAudit = true; second.failAudit = true;
            publisher.publish(new RunIntent(1));
            assertDoesNotThrow(() -> { for (int i = 0; i < 100; i++) group.doWork(); });
            assertEquals(java.util.Set.of("first", "second"), group.quarantinedProcessors().keySet(),
                    "each thrower is identified and quarantined alone");
            assertTrue(group.quarantinedProcessors().get("first").contains(Generated.class.getName()),
                    "the reason names the identified processor: " + group.quarantinedProcessors());
            assertEquals(ControlDriver.GroupState.RUNNING, group.control().state());
        } finally { group.onClose(); }
        assertEquals(0, first.tornDown.get()); assertEquals(0, second.tornDown.get());
    }

    /**
     * A strategy that neither names a position nor records the thrower leaves the target truly unidentifiable: the group
     * is contained by ending it, no peer is quarantined, and the failure says why.
     */
    @Test void aTrulyUnidentifiableOpaqueFailureEndsTheGroupWithoutBlamingAPeer() throws Exception {
        var manager = new EventFlowManager();
        manager.registerEventMapperFactory(() -> new EventToOnEventInvokeStrategy() {
            @Override public int failedPosition() { return -1; }
            @Override public DataFlow failedProcessor() { return null; }
            @Override public void processEvent(Object event) {       // dispatches without the base class's capture
                for (DataFlow target : eventProcessorSinks) target.onEvent(event);
            }
        }, CallBackType.ON_EVENT_CALL_BACK);
        var publisher = manager.registerEventSource("feed", new com.telamin.mongoose.connector.memory.InMemoryEventSource<>());
        var group = new ComposingEventProcessorAgent("unknown", manager, null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        Generated first = new Generated(), second = new Generated();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("first", first));
        group.addNamedEventProcessor(() -> new NamedEventProcessor("second", second));
        group.onStart();
        group.subscribe(first, key("feed")); group.subscribe(second, key("feed"));
        try {
            first.failAudit = true;
            publisher.publish(new RunIntent(1));
            var thrown = assertThrows(ProcessorDispatchException.class, () -> {
                for (int i = 0; i < 100; i++) group.doWork();
            });
            assertTrue(thrown.getMessage().contains("no subscriber is quarantined"), thrown.getMessage());
            assertTrue(group.quarantinedProcessors().isEmpty(), "no peer is blamed: " + group.quarantinedProcessors());
        } finally { group.onClose(); }
    }

    @Test void removingAReaderAnswersItsQueuedAdminCallerWithoutExecutingTheCommand() throws Exception {
        var queue = new OneToOneConcurrentArrayQueue<Object>(8);
        var publisher = new EventToQueuePublisher<com.telamin.mongoose.service.admin.impl.AdminCommand>("admin-failure");
        publisher.addTargetQueue(queue, "admin-failure");
        var calls = new AtomicInteger();
        var replies = new CopyOnWriteArrayList<Object>();
        var template = new com.telamin.mongoose.service.admin.impl.AdminCommand((args, out, err) -> calls.incrementAndGet(), publisher);
        var request = new com.telamin.mongoose.service.admin.AdminCommandRequest();
        request.setCommand("pending"); request.setArguments(List.of());
        request.setOutput(replies::add); request.setErrOutput(replies::add);
        var caller = java.util.concurrent.CompletableFuture.runAsync(() -> template.publishCommand(request));
        await(() -> !queue.isEmpty());
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "closing-admin");
        reader.onClose();
        caller.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0, calls.get());
        assertEquals(1, replies.size());
        assertTrue(replies.get(0).toString().contains("pending") && replies.get(0).toString().contains("did not run"));
        assertTrue(queue.isEmpty());
    }

    @Test void quarantineMidBatchLeavesTheSecondAdminInvocationForExplicitRefusal() throws Exception {
        var queue = new OneToOneConcurrentArrayQueue<Object>(8);
        var publisher = new EventToQueuePublisher<com.telamin.mongoose.service.admin.impl.AdminCommand>("admin-batch");
        publisher.addTargetQueue(queue, "admin-batch");
        var reader = new EventQueueToEventProcessorAgent(queue,
                new com.telamin.mongoose.service.admin.impl.AdminCommandInvoker(), "admin-batch");
        Generated flow = new Generated(); flow.start(); reader.registerProcessor(flow);
        var calls = new AtomicInteger();
        var firstReplies = new CopyOnWriteArrayList<Object>();
        var secondReplies = new CopyOnWriteArrayList<Object>();
        var template = new com.telamin.mongoose.service.admin.impl.AdminCommand((args, out, err) -> {
            calls.incrementAndGet(); out.accept("ran");
        }, publisher);
        var first = new com.telamin.mongoose.service.admin.AdminCommandRequest();
        first.setCommand("first"); first.setArguments(List.of()); first.setOutput(firstReplies::add); first.setErrOutput(firstReplies::add);
        var second = new com.telamin.mongoose.service.admin.AdminCommandRequest();
        second.setCommand("second"); second.setArguments(List.of()); second.setOutput(secondReplies::add); second.setErrOutput(secondReplies::add);
        var a = java.util.concurrent.CompletableFuture.runAsync(() -> template.publishCommand(first));
        await(() -> queue.size() == 1);
        var b = java.util.concurrent.CompletableFuture.runAsync(() -> template.publishCommand(second));
        await(() -> queue.size() == 2);
        flow.failAudit = true;
        assertEquals(1, reader.doWork());
        assertEquals(0, reader.doWork(), "a later duty cycle must also preserve the orphan backlog");
        assertEquals(1, queue.size(), "the unclaimed second request must not disappear into an empty strategy");
        reader.onClose();
        a.get(1, java.util.concurrent.TimeUnit.SECONDS); b.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, calls.get());
        assertEquals(List.of("ran"), firstReplies);
        assertEquals(1, secondReplies.size());
        assertTrue(secondReplies.get(0).toString().contains("second") && secondReplies.get(0).toString().contains("will not run"));
    }

    private static ComposingEventProcessorAgent newGroup(String name) {
        return new ComposingEventProcessorAgent(name, new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
    }

    private static void pump(ComposingEventProcessorAgent group, java.util.ArrayDeque<Runnable> tasks) throws Exception {
        for (int i = 0; i < 8; i++) {
            while (!tasks.isEmpty()) tasks.remove().run();
            group.doWork();
        }
    }

    private static EventSubscriptionKey<Object> key(String feed) {
        return new EventSubscriptionKey<>(new EventSourceKey<>(feed), CallBackType.ON_EVENT_CALL_BACK);
    }

    @Test void quarantineAndDiscardReleasePooledInputsExactlyOnce() {
        class Msg implements com.telamin.mongoose.service.pool.PoolAware {
            final com.telamin.mongoose.service.pool.impl.PoolTracker<Msg> tracker = new com.telamin.mongoose.service.pool.impl.PoolTracker<>();
            public com.telamin.mongoose.service.pool.impl.PoolTracker<Msg> getPoolTracker() { return tracker; }
        }
        var pool = com.telamin.mongoose.service.pool.impl.Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> { }, 2);
        try {
            Generated flow = new Generated() {
                @Override public void onEventInternal(Object event) {
                    // Map the pooled envelope inside the real generated processing bracket.
                    super.onEventInternal(event instanceof Msg ? new RunIntent(1) : event);
                }
            };
            flow.start(); flow.failAudit = true;
            int before = flow.calls.get();
            var queue = new OneToOneConcurrentArrayQueue<Object>(8);
            var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "pooled-failure");
            reader.registerProcessor(flow);
            Msg first = pool.acquire(), second = pool.acquire();
            queue.offer(first); queue.offer(second);
            reader.doWork(); reader.onClose();
            assertEquals(before + 1, flow.calls.get(), "the pooled input is not retried");
            assertEquals(2, flow.failures.get(), "the failing auditor is called once on dispatch and once by failed-cycle cleanup");
            assertEquals(0, first.tracker.currentRefCount());
            assertEquals(0, second.tracker.currentRefCount());
            assertEquals(2, pool.availableCount());
        } finally { com.telamin.mongoose.service.pool.impl.Pools.SHARED.remove(Msg.class); }
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(ready.getAsBoolean(), "condition did not settle");
    }
}
