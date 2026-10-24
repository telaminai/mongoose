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
import com.telamin.mongoose.replay.JournalledItem;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

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
    void aStopForAFailedRegistrationCannotStopItsReplacement() throws Exception {
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("DEMO", new EventFlowManager(), null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart(); Object old = new Object(), replacement = new Object(); Proc second = new Proc();
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
}
