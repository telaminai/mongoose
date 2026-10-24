/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.Agent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review of mongoose#55: one source key shared by several groups. A source is unsubscribed only when no group reads
 * the key any more, and a source whose unsubscribe throws neither leaks the group's reader nor escapes the duty cycle.
 */
class SharedSourceSubscriptionTest {

    static EventSubscriptionKey<Object> key() {
        return new EventSubscriptionKey<>(new EventSourceKey<>("feed"), CallBackType.ON_EVENT_CALL_BACK);
    }

    /** Subscribes to {@code feed} on start and drops every subscription on tearDown, as SubscriptionManagerNode does. */
    public static class Proc implements DataFlow {
        final List<EventFeed> feeds = new ArrayList<>();
        volatile boolean started, tornDown;
        @Override public void init() {}
        @Override public void start() {
            for (EventFeed f : feeds) f.subscribe(this, key());
            started = true;
        }
        @Override public void stop() {}
        @Override public void tearDown() {
            for (EventFeed f : feeds) f.removeAllSubscriptions(this);
            tornDown = true;
        }
        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }
        @Override public void onEvent(Object e) {}
    }

    /** Records every subscribe and unsubscribe the source is told, in order. */
    public static class RecordingFeed implements EventFlowService<Object> {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger subs = new AtomicInteger(), unsubs = new AtomicInteger();
        @Override public void setEventToQueuePublisher(EventToQueuePublisher<Object> p) {}
        @Override public void subscribe(EventSubscriptionKey<Object> k) { subs.incrementAndGet(); calls.add("sub"); }
        @Override public void unSubscribe(EventSubscriptionKey<Object> k) { unsubs.incrementAndGet(); calls.add("unsub"); }
        String last() { return calls.isEmpty() ? "none" : calls.get(calls.size() - 1); }
    }

    @Test
    void sourceStaysSubscribedWhileAnotherGroupStillReadsTheKey() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        List<Throwable> agentErrors = new CopyOnWriteArrayList<>();
        server.setDefaultErrorHandler(agentErrors::add);
        RecordingFeed feed = new RecordingFeed();
        server.registerEventSource("feed", feed);
        Proc a = new Proc(), b = new Proc(), c = new Proc();
        server.addEventProcessor("a", "g1", null, () -> a);
        server.addEventProcessor("b", "g2", null, () -> b);
        server.addEventProcessor("c", "g2", null, () -> c);
        server.init();
        server.start();
        try {
            await(() -> a.started && b.started && c.started, "all started");
            assertEquals(3, feed.subs.get());

            server.stopProcessor("g1", "a");
            await(() -> a.tornDown, "a torn down");
            Thread.sleep(100);
            assertEquals(0, feed.unsubs.get(), "g2 still subscribes the key: the source must not be unsubscribed");

            server.stopProcessor("g2", "b");
            await(() -> b.tornDown, "b torn down");
            Thread.sleep(100);
            assertEquals(0, feed.unsubs.get(), "c in g2 still subscribes the key");

            server.stopProcessor("g2", "c");
            await(() -> c.tornDown, "c torn down");
            await(() -> feed.unsubs.get() == 1, "the source unsubscribed when the last group left");
            assertEquals("unsub", feed.last());

            // re-subscribe after the key was fully released, from a group that had left it
            Proc d = new Proc();
            server.addEventProcessor("d", "g1", null, () -> d);
            await(() -> d.started, "d started");
            assertEquals(4, feed.subs.get(), "the source is subscribed again");
            assertEquals("sub", feed.last());
            assertEquals(List.of(), agentErrors);
        } finally {
            server.stop();
        }
    }

    @Test
    void throwingSourceUnsubscribeIsReportedAndDoesNotLeakTheReader() throws Exception {
        List<FakeReader> readers = new ArrayList<>();
        EventFlowManager flowManager = new EventFlowManager() {
            @Override public void subscribe(EventSubscriptionKey<?> k) {}
            @Override public void unSubscribe(EventSubscriptionKey<?> k) { throw new IllegalStateException("venue down"); }
            @Override public <T> EventQueueToEventProcessor getMappingAgent(EventSubscriptionKey<T> k, Agent s) {
                FakeReader r = new FakeReader();
                readers.add(r);
                return r;
            }
        };
        List<ErrorEvent> reported = new CopyOnWriteArrayList<>();
        ErrorListener listener = reported::add;
        ErrorReporting.getReporter().addListener(listener);
        try {
            ComposingEventProcessorAgent group = new ComposingEventProcessorAgent("g", flowManager, null,
                    new DeadWheelScheduler(), new ConcurrentHashMap<>());
            group.onStart();
            Proc a = new Proc();
            group.subscribe(a, key());
            for (int i = 0; i < 10; i++) group.doWork();
            assertTrue(readers.get(0).started, "the reader joined the composite");

            assertDoesNotThrow(() -> group.removeAllSubscriptions(a), "a failing source must not escape the group");
            for (int i = 0; i < 10; i++) group.doWork();
            assertTrue(readers.get(0).closed, "the reader is removed from the composite although the source threw");
            assertTrue(reported.stream().anyMatch(e -> e.getMessage().contains("venue down")
                    && e.getMessage().contains("'g'")), "the source's failure is reported: " + reported);
            group.onClose();
        } finally {
            ErrorReporting.getReporter().removeListener(listener);
        }
    }

    /**
     * Group g2 releases its last listener on the key while group g1 subscribes it, on two threads at once. Whatever
     * the interleaving, the source must end up subscribed (g1 reads the key) - never with a trailing unsubscribe.
     */
    @Test
    void concurrentSubscribeAndLastListenerRemovalLeaveTheSourceSubscribed() throws Exception {
        EventFlowManager flowManager = new EventFlowManager();
        RecordingFeed feed = new RecordingFeed();
        flowManager.registerEventSource("feed", feed);
        ComposingEventProcessorAgent g1 = new ComposingEventProcessorAgent("g1", flowManager, null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        ComposingEventProcessorAgent g2 = new ComposingEventProcessorAgent("g2", flowManager, null,
                new DeadWheelScheduler(), new ConcurrentHashMap<>());
        Proc a = new Proc(), x = new Proc();
        EventSubscriptionKey<Object> key = key();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < 500; i++) {
            g2.subscribe(x, key);
            assertEquals(1, flowManager.readerCount(key));
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread t1 = new Thread(() -> run(barrier, failure, () -> g1.subscribe(a, key)));
            Thread t2 = new Thread(() -> run(barrier, failure, () -> g2.removeAllSubscriptions(x)));
            t1.start();
            t2.start();
            t1.join();
            t2.join();
            assertNull(failure.get());
            assertEquals(1, flowManager.readerCount(key), "iteration " + i + ": g1 reads the key");
            assertEquals("sub", feed.last(), "iteration " + i + ": the source must be left subscribed, calls " + feed.calls);
            g1.removeAllSubscriptions(a);
            assertEquals(0, flowManager.readerCount(key));
            assertEquals("unsub", feed.last(), "iteration " + i + ": released when g1, the last reader, left");
            feed.calls.clear();
        }
    }

    private static void run(CyclicBarrier barrier, AtomicReference<Throwable> failure, Runnable work) {
        try {
            barrier.await();
            work.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !condition.getAsBoolean()) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    static class FakeReader implements EventQueueToEventProcessor {
        final List<DataFlow> ps = new ArrayList<>();
        boolean started, closed;
        @Override public int registerProcessor(DataFlow p) { ps.add(p); return ps.size(); }
        @Override public int deregisterProcessor(DataFlow p) { ps.remove(p); return ps.size(); }
        @Override public int listenerCount() { return ps.size(); }
        @Override public void onStart() { started = true; }
        @Override public void onClose() { closed = true; }
        @Override public int doWork() { return 0; }
        @Override public String roleName() { return "fake"; }
    }
}
