package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.BroadcastEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.service.Service;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.internal.LifecycleManager;
import com.telamin.mongoose.replay.JournalledItem;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/** Integration review #69: cancellation, abandoned pooled references and accepted processor registrations. */
@Timeout(20)
class IntegrationReleaseRegressionTest {
    static class Proc implements DataFlow {
        volatile boolean started, stopped, tornDown;
        volatile ComposingEventProcessorAgent group;
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { group = (ComposingEventProcessorAgent) f; }
        @Override public void start() { started = true; }
        @Override public void stop() { stopped = true; }
        @Override public void tearDown() { tornDown = true; }
        @Override public void onEvent(Object e) { }
    }

    static class Msg implements PoolAware {
        final PoolTracker<Msg> tracker = new PoolTracker<>();
        @Override public PoolTracker<Msg> getPoolTracker() { return tracker; }
    }

    static MongooseServer server() {
        MongooseServer s = new MongooseServer(new MongooseServerConfig());
        s.setDefaultErrorHandler(t -> fail("unexpected agent failure", t));
        return s;
    }

    static void await(BooleanSupplier condition, String message) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), message);
    }

    static Stream<Arguments> readers() {
        return Stream.of(false, true).flatMap(active -> Stream.of(EventSource.EventWrapStrategy.values())
                .map(wrap -> Arguments.of(active, wrap)));
    }

    @ParameterizedTest
    @MethodSource("readers")
    void removingAReaderReleasesItsQueuedPooledEvents(boolean active, EventSource.EventWrapStrategy wrap) throws Exception {
        EventFlowManager flow = new EventFlowManager();
        EventToQueuePublisher<Object> publisher = flow.registerEventSource("DEMO", new EventSource<>() {
            @Override public void setEventToQueuePublisher(EventToQueuePublisher<Object> p) { }
            @Override public void subscribe(EventSubscriptionKey<Object> k) { }
            @Override public void unSubscribe(EventSubscriptionKey<Object> k) { }
        });
        publisher.setEventWrapStrategy(wrap);
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("DEMO", flow, null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        Proc p = new Proc();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("DEMO", p));
        group.onStart();
        group.subscribe(p, new EventSubscriptionKey<>(new EventSourceKey<>("DEMO"), CallBackType.ON_EVENT_CALL_BACK));
        if (active) { group.doWork(); group.doWork(); }
        var pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> { }, 2);
        Msg msg = pool.acquire();
        publisher.publish(msg);
        assertEquals(1, msg.tracker.currentRefCount(), "queue holds the only reference");
        try {
            group.removeAllSubscriptions(p);
            for (int i = 0; i < 5; i++) group.doWork();
            assertEquals(0, msg.tracker.currentRefCount(), "removed reader must release its backlog");
            assertEquals(1, pool.availableCount(), "queued item must return to the pool");
            assertEquals(0, publisher.getTargetQueues().size(), "producer detached before discard");
            assertEquals(0, flow.pendingQueuedEvents(), "no abandoned queue remains registered");
        } finally { group.onClose(); Pools.SHARED.remove(Msg.class); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "named", "journal", "replay", "broadcast"})
    void closingAReaderReleasesWrappedBacklogWithoutDispatch(String shape) {
        var pool = Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> { }, 2);
        Msg msg = pool.acquire();
        Object queued = switch (shape) {
            case "named" -> new NamedFeedEventImpl<>("DEMO").data(msg);
            case "journal" -> new JournalledItem(1, msg);
            case "replay" -> {
                ReplayRecord record = new ReplayRecord(); record.setEvent(msg); record.setWallClockTime(1);
                yield record;
            }
            case "broadcast" -> new BroadcastEvent(msg);
            default -> msg;
        };
        var q = new OneToOneConcurrentArrayQueue<Object>(8);
        var reader = new EventQueueToEventProcessorAgent(q, new EventToOnEventInvokeStrategy(), "DEMO");
        AtomicInteger dispatched = new AtomicInteger();
        reader.registerProcessor(new Proc() { @Override public void onEvent(Object e) { dispatched.incrementAndGet(); } });
        q.offer(queued);
        try {
            reader.onClose(); reader.onClose();
            assertEquals(0, msg.tracker.currentRefCount(), "close releases the wrapper's pooled payload exactly once");
            assertEquals(1, pool.availableCount());
            assertEquals(0, dispatched.get(), "discard must not invoke a stopped processor");
        } finally { Pools.SHARED.remove(Msg.class); }
    }

    @Test
    void rejectedEnqueueDoesNotReserveTheProcessorName() throws Exception {
        MongooseServer s = server(); Proc seed = new Proc();
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        s.addEventProcessor("seed", "DEMO", null, () -> seed); s.init(); s.start();
        try {
            await(() -> seed.started, "seed started");
            seed.group.setControlQueueFullTimeout(Duration.ofMillis(50));
            seed.group.runOnAgentThread(() -> {
                held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            assertTrue(held.await(5, TimeUnit.SECONDS));
            AtomicInteger built = new AtomicInteger();
            for (int i = 0; i < ComposingEventProcessorAgent.CONTROL_QUEUE_CAPACITY; i++) {
                s.addEventProcessor("DEMO-" + i, "DEMO", null, () -> { built.incrementAndGet(); return new Proc(); });
            }
            AtomicInteger rejectedSupplierCalls = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> s.addEventProcessor("RETRY", "DEMO", null, () -> {
                rejectedSupplierCalls.incrementAndGet(); return new Proc();
            }));
            assertEquals(0, rejectedSupplierCalls.get(), "a refused enqueue never runs its supplier");
            release.countDown();
            await(() -> built.get() == ComposingEventProcessorAgent.CONTROL_QUEUE_CAPACITY, "start queue drained");
            Proc retry = new Proc();
            assertDoesNotThrow(() -> s.addEventProcessor("RETRY", "DEMO", null, () -> retry),
                    "a rejected enqueue must not keep its name reserved");
            await(() -> retry.started, "retry accepted and started");
        } finally { release.countDown(); s.stop(); }
    }

    static class Gate implements Lifecycle {
        final CountDownLatch entered = new CountDownLatch(1), stopEntered = new CountDownLatch(1), finish = new CountDownLatch(1);
        volatile boolean live;
        @Override public void init() { }
        @Override public void start() {
            entered.countDown();
            try { assertTrue(finish.await(5, TimeUnit.SECONDS), "startup callback released"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            live = true; // models a resource acquired at the end of a slow startup
        }
        @Override public void stop() { live = false; stopEntered.countDown(); }
        @Override public void tearDown() { }
    }

    static class Source implements LifeCycleEventSource<Object> {
        final AtomicInteger starts = new AtomicInteger();
        volatile boolean live;
        @Override public void init() { }
        @Override public void start() { starts.incrementAndGet(); live = true; }
        @Override public void stop() { live = false; }
        @Override public void tearDown() { }
    }

    @Test
    void stoppedStartupCannotStartAnEventSourceLater() throws Exception {
        MongooseServer s = server(); Gate gate = new Gate(); Source source = new Source();
        s.registerService(new Service<>(gate, Gate.class, "DEMO-gate"));
        s.registerEventSource("DEMO-source", source); s.init();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> { try { s.start(); } catch (Throwable t) { failure.set(t); } });
        Thread stopper = new Thread(s::stop);
        starter.start();
        try {
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS)); stopper.start();
            assertTrue(gate.stopEntered.await(5, TimeUnit.SECONDS), "stop signals the in-progress callback");
            assertFalse(s.awaitStopped(30, TimeUnit.MILLISECONDS), "stop cannot finish while startup may still acquire resources");
            gate.finish.countDown(); starter.join(5000); stopper.join(5000);
            assertFalse(starter.isAlive()); assertFalse(stopper.isAlive());
            assertTrue(s.awaitStopped(1, TimeUnit.SECONDS));
            assertNotNull(failure.get()); assertTrue(failure.get().getMessage().contains("stopped during start"));
            assertEquals(0, source.starts.get(), "cancelled startup never starts the next source");
            assertFalse(source.live, "a completed stop must not be followed by a source start");
            assertFalse(gate.live, "final cleanup follows the last in-progress startup effect");
        } finally { gate.finish.countDown(); starter.join(5000); if (stopper.isAlive()) stopper.join(5000); s.stop(); }
    }

    @Test
    void aStartupCallbackCanRequestStopWithoutWaitingForItself() throws Exception {
        MongooseServer s = server();
        s.registerService(new Service<>(new Gate() {
            @Override public void start() { s.stop(); }
            @Override public void stop() { s.stop(); } // shutdown reentrancy is also harmless
        }, Gate.class, "DEMO"));
        s.init();
        assertThrows(IllegalStateException.class, s::start);
        assertTrue(s.awaitStopped(5, TimeUnit.SECONDS), "callback-owned stop completes without a self-wait");
    }

    @Test
    void stopBeforeGroupStartsKeepsItsNameUntilThatStopIsConsumed() throws Exception {
        MongooseServer s = server(); Proc first = new Proc(), second = new Proc();
        s.addEventProcessor("DEMO", "DEMO", null, () -> first);
        s.stopProcessor("DEMO", "DEMO");
        assertThrows(IllegalArgumentException.class, () -> s.addEventProcessor("DEMO", "DEMO", null, () -> second),
                "a pending stop must not free the original registration's name early");
        try {
            s.init(); s.start(); await(() -> first.tornDown, "original stopped and torn down");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                try { s.addEventProcessor("DEMO", "DEMO", null, () -> second); break; }
                catch (IllegalArgumentException pending) { if (System.nanoTime() >= deadline) throw pending; Thread.sleep(1); }
            }
            await(() -> second.started, "replacement started");
            CountDownLatch settled = new CountDownLatch(1); second.group.runOnAgentThread(settled::countDown);
            assertTrue(settled.await(5, TimeUnit.SECONDS));
            assertTrue(first.stopped && first.tornDown, "the original must not leak after its name is reused");
            assertFalse(second.stopped, "the original stop must not stop its replacement");
        } finally { s.stop(); }
    }

    @Test
    void aStopConsumedBeforeItsStartCancelsTheRegistration() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("DEMO", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart(); ProcessorRegistration registration = new ProcessorRegistration(); AtomicInteger built = new AtomicInteger();
        AtomicInteger acknowledged = new AtomicInteger();
        try {
            group.removeEventProcessorByName("DEMO", registration, acknowledged::incrementAndGet);
            group.doWork();
            assertEquals(1, acknowledged.get(), "stop consumed before the start is queued");
            group.addNamedEventProcessor(() -> {
                built.incrementAndGet(); return new NamedEventProcessor("DEMO", new Proc());
            }, registration);
            group.doWork();
            assertEquals(0, built.get(), "a consumed stop must cancel a start queued afterwards for that registration");
            assertFalse(group.isProcessorRegistered("DEMO"), "cancelled registration cannot run without a reservation");
            Proc replacement = new Proc(); ProcessorRegistration next = new ProcessorRegistration();
            group.addNamedEventProcessor(() -> new NamedEventProcessor("DEMO", replacement), next);
            group.doWork();
            assertTrue(replacement.started, "the name is free for a later registration");
            group.removeEventProcessorByName("DEMO", next, null); group.doWork();
            assertTrue(replacement.stopped && replacement.tornDown, "the later registration remains stoppable");
            assertFalse(group.isProcessorRegistered("DEMO"));
        } finally { group.onClose(); }
    }

    @Test
    void aStopAfterStartedDoesNotTurnTheLateStartPassIntoAStartupFailure() throws Exception {
        MongooseServer s = server(); AtomicInteger built = new AtomicInteger(); AtomicBoolean stopped = new AtomicBoolean();
        s.registerService(new Service<>(new Gate() {
            @Override public void start() {
                s.addEventProcessor("DEMO", "DEMO-late", null, () -> { built.incrementAndGet(); return new Proc(); });
            }
        }, Gate.class, "DEMO-loader"));
        Logger logger = Logger.getLogger(MongooseServer.class.getName());
        Handler stopAtLateStart = new Handler() {
            @Override public void publish(LogRecord record) {
                // The real logging call is after the STARTED check and before startAgent, so this forces precisely
                // the reviewer's interleaving without a sleep or a production-only test hook.
                if (record.getMessage().startsWith("late-starting event processor agent") && stopped.compareAndSet(false, true)) s.stop();
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(stopAtLateStart);
        try {
            s.init();
            assertDoesNotThrow(s::start, "a stop after STARTED must not be reported as a startup failure");
            assertTrue(stopped.get(), "stop landed between the late-start check and its effect");
            assertTrue(s.awaitStopped(5, TimeUnit.SECONDS));
            assertEquals(0, built.get(), "the stopped late group never builds its processor");
        } finally { logger.removeHandler(stopAtLateStart); s.stop(); }
    }

    @Test
    void aDropCannotFreeANewerReservationUnderTheSameName() {
        MongooseServer s = server();
        s.addEventProcessor("DEMO", "DEMO", null, Proc::new);
        s.processorDropped("DEMO", "DEMO", new Proc(), new ProcessorRegistration());
        assertThrows(IllegalArgumentException.class, () -> s.addEventProcessor("DEMO", "DEMO", null, Proc::new),
                "a stale drop must not free a newer registration's reservation");
        s.init(); s.start(); s.stop();
    }

    @Test
    void aStopForAFailedRegistrationCannotStopItsReplacement() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("DEMO", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart(); ProcessorRegistration old = new ProcessorRegistration(), replacement = new ProcessorRegistration(); Proc second = new Proc();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("DEMO", new Proc() {
            @Override public void start() {
                group.addNamedEventProcessor(() -> new NamedEventProcessor("DEMO", second), replacement);
                throw new IllegalStateException("DEMO start refused");
            }
        }), old);
        AtomicInteger completed = new AtomicInteger();
        group.removeEventProcessorByName("DEMO", old, completed::incrementAndGet);
        try {
            group.doWork();
            assertTrue(second.started);
            assertFalse(second.stopped, "a delayed old stop cannot address a newer registration by name");
            assertEquals(1, completed.get(), "old stop still acknowledges completion");
        } finally { group.onClose(); }
    }

    @Test
    void stopsThatFindNothingDoNotAffectFreshRegistrations() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("DEMO", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart();
        try {
            // a repeated stop: the first released the processor, the second finds nothing
            ProcessorRegistration released = new ProcessorRegistration();
            group.addNamedEventProcessor(() -> new NamedEventProcessor("DEMO-a", new Proc()), released);
            group.doWork();
            group.removeEventProcessorByName("DEMO-a", released, null);
            group.removeEventProcessorByName("DEMO-a", released, null);
            group.doWork();
            assertFalse(group.isProcessorRegistered("DEMO-a"), "a repeated stop leaves its processor removed");

            // a stop after a failed start: the processor was dropped, its start will not come again
            ProcessorRegistration failed = new ProcessorRegistration();
            group.addNamedEventProcessor(() -> new NamedEventProcessor("DEMO-b", new Proc() {
                @Override public void start() { throw new IllegalStateException("DEMO start refused"); }
            }), failed);
            group.doWork();
            group.removeEventProcessorByName("DEMO-b", failed, null);
            group.doWork();
            assertFalse(group.isProcessorRegistered("DEMO-b"), "a failed start remains removed after its stop");

            // a stale stop: the name now belongs to a replacement
            ProcessorRegistration old = new ProcessorRegistration(), current = new ProcessorRegistration();
            group.addNamedEventProcessor(() -> new NamedEventProcessor("DEMO-c", new Proc()), old);
            group.doWork();
            group.removeEventProcessorByName("DEMO-c", old, null);
            group.doWork();
            Proc replacement = new Proc();
            group.addNamedEventProcessor(() -> new NamedEventProcessor("DEMO-c", replacement), current);
            group.doWork();
            group.removeEventProcessorByName("DEMO-c", old, null);
            group.doWork();
            assertFalse(replacement.stopped, "the stale stop does not reach the replacement");
            assertTrue(group.isProcessorRegistered("DEMO-c"), "the fresh registration remains registered");

            // the race it exists for: the stop overtakes a start not yet queued, and is forgotten once that start comes
            ProcessorRegistration overtaken = new ProcessorRegistration(); AtomicInteger built = new AtomicInteger();
            group.removeEventProcessorByName("DEMO-d", overtaken, null);
            group.doWork();
            assertFalse(group.isProcessorRegistered("DEMO-d"), "the stop was consumed before its start arrived");
            group.addNamedEventProcessor(() -> { built.incrementAndGet(); return new NamedEventProcessor("DEMO-d", new Proc()); },
                    overtaken);
            group.doWork();
            assertEquals(0, built.get(), "the overtaken start is skipped");
            assertFalse(group.isProcessorRegistered("DEMO-d"), "the overtaken registration never becomes live");
        } finally { group.onClose(); }
    }

    @Test
    void aRejectedEnqueueDoesNotRetainItsCancelledRegistration() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("DEMO", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart();
        try {
            WeakReference<ProcessorRegistration> rejected = rejectStartAndConsumeItsStop(group);
            WeakReference<Object> gcControl = new WeakReference<>(new Object());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((rejected.get() != null || gcControl.get() != null) && System.nanoTime() < deadline) {
                System.gc(); Thread.sleep(20);
            }
            assertNull(gcControl.get(), "GC control must collect an unowned object");
            assertNull(rejected.get(), "a live group must not retain a cancelled registration whose enqueue failed");
        } finally { group.onClose(); Reference.reachabilityFence(group); }
    }

    private static WeakReference<ProcessorRegistration> rejectStartAndConsumeItsStop(ComposingEventProcessorAgent group)
            throws Exception {
        for (int i = 0; i < ComposingEventProcessorAgent.CONTROL_QUEUE_CAPACITY; i++) {
            String name = "fill-" + i;
            group.addNamedEventProcessor(() -> new NamedEventProcessor(name, new Proc()));
        }
        ProcessorRegistration rejected = new ProcessorRegistration(); AtomicInteger built = new AtomicInteger();
        AtomicInteger acknowledged = new AtomicInteger();
        group.removeEventProcessorByName("DEMO-rejected", rejected, acknowledged::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> group.addNamedEventProcessor(() -> {
            built.incrementAndGet(); return new NamedEventProcessor("DEMO-rejected", new Proc());
        }, rejected), "the full start queue rejects this enqueue on its own thread");
        group.doWork();
        assertEquals(1, acknowledged.get(), "the rejected add's stop was consumed");
        assertEquals(0, built.get(), "no supplier ran for the rejected add");
        return new WeakReference<>(rejected);
    }

    @Test
    void aSecondLaunchIsRefusedBeforeTheFirstThreadHasStarted() throws Exception {
        LifecycleManager manager = new LifecycleManager(null);
        CountDownLatch atLaunch = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Agent agent = new Agent() {
            @Override public int doWork() { return 0; }
            @Override public String roleName() { return "DEMO-held-launch"; }
        };
        AgentRunner runner = new AgentRunner(new SleepingMillisIdleStrategy(1), failure::set, null, agent) {
            @Override public Agent agent() {
                if (launches.incrementAndGet() == 1) {
                    atLaunch.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("launch not released"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
                return super.agent();
            }
        };
        Thread first = new Thread(() -> { try { manager.startOnce(runner); } catch (Throwable t) { failure.set(t); } });
        first.start();
        try {
            assertTrue(atLaunch.await(5, TimeUnit.SECONDS));
            assertNull(runner.thread(), "first launch is held before the runner can claim its thread");
            manager.startOnce(runner);
            assertEquals(1, launches.get(), "a competing caller must not attempt a second thread launch");
        } finally {
            release.countDown(); first.join(5000); runner.close();
        }
        assertFalse(first.isAlive(), "launch caller terminated");
        assertNull(failure.get(), "launch and runner callbacks succeeded");
    }

    @Test
    void aRuntimeAddLosingItsLaunchToStopNeverRunsItsSupplier() throws Exception {
        MongooseServer s = server(); s.init(); s.start();
        AtomicInteger built = new AtomicInteger(); AtomicBoolean stopped = new AtomicBoolean();
        Logger logger = Logger.getLogger(MongooseServer.class.getName()); Level prior = logger.getLevel();
        Handler stopBeforeLaunch = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().startsWith("staring event processor group:'DEMO-stop-race'")
                        && stopped.compareAndSet(false, true)) s.stop();
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.setLevel(Level.INFO); logger.addHandler(stopBeforeLaunch);
        try {
            assertDoesNotThrow(() -> s.addEventProcessor("DEMO", "DEMO-stop-race", null,
                    () -> { built.incrementAndGet(); return new Proc(); }));
            assertTrue(stopped.get(), "stop won after the runtime admission check and before the launch");
            assertTrue(s.awaitStopped(5, TimeUnit.SECONDS));
            AgentRunner runner = groupRunner(s, "DEMO-stop-race");
            assertSame(AgentRunner.TOMBSTONE, runner.thread(), "the stopped runner cannot claim a thread afterwards");
            assertTrue(runner.isClosed());
            assertEquals(0, built.get(), "an add whose launch loses to stop never runs its supplier");
        } finally { logger.removeHandler(stopBeforeLaunch); logger.setLevel(prior); s.stop(); }
    }

    @Test
    void concurrentFirstAddsToANewGroupStartItsThreadOnce() throws Exception {
        MongooseServer s = server();
        s.init(); s.start();
        try {
            for (int i = 0; i < 50; i++) {
                String group = "DEMO-" + i;
                Proc first = new Proc(), second = new Proc();
                CyclicBarrier together = new CyclicBarrier(2);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread a = new Thread(() -> addAfter(together, s, "DEMO-a", group, first, failure));
                Thread b = new Thread(() -> addAfter(together, s, "DEMO-b", group, second, failure));
                a.start(); b.start(); a.join(5_000); b.join(5_000);
                assertFalse(a.isAlive(), "first add caller terminated");
                assertFalse(b.isAlive(), "second add caller terminated");
                assertNull(failure.get(), "both concurrent adds returned successfully");
                await(() -> first.started && second.started, "both processors start in " + group);
                assertFalse(groupRunner(s, group).isClosed(),
                        "a second start of the group's runner must not mark it closed while its thread runs: " + group);
            }
        } finally { s.stop(); }
    }

    private static void addAfter(CyclicBarrier together, MongooseServer s, String name, String group, Proc proc,
                                 AtomicReference<Throwable> failure) {
        try {
            together.await(5, TimeUnit.SECONDS);
            s.addEventProcessor(name, group, null, () -> proc);
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }

    private static org.agrona.concurrent.AgentRunner groupRunner(MongooseServer s, String group) throws Exception {
        java.lang.reflect.Field f = MongooseServer.class.getDeclaredField("composingEventProcessorAgents");
        f.setAccessible(true);
        return ((java.util.Map<String, com.telamin.mongoose.internal.ComposingEventProcessorAgentRunner>) f.get(s))
                .get(group).groupRunner();
    }
}
