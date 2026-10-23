/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mongoose#55: a processor stopped and then re-added in the same group must receive events. Stopping the last
 * subscriber of a feed in a group must release that group's reader: the subscription map entry, the source
 * subscription, and the reader agent in the composite.
 */
class StoppedProcessorResubscribeTest {

    private static final String FEED = "feed";

    /** A minimal processor that subscribes on start and drops every subscription on tearDown, as Fluxtion's
     *  SubscriptionManagerNode does. */
    public static class SubscribingProcessor implements DataFlow {
        final AtomicInteger received = new AtomicInteger();
        volatile boolean started, tornDown;
        private final List<EventFeed> feeds = new ArrayList<>();

        static EventSubscriptionKey<Object> key() {
            return new EventSubscriptionKey<>(new EventSourceKey<>(FEED), CallBackType.ON_EVENT_CALL_BACK);
        }

        @Override public void init() {}
        @Override public void start() {
            started = true;
            for (EventFeed f : feeds) f.subscribe(this, key());
        }
        @Override public void stop() {}
        @Override public void tearDown() {
            tornDown = true;
            for (EventFeed f : feeds) f.removeAllSubscriptions(this);
        }
        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }
        @Override public void onEvent(Object e) { if (e instanceof String) received.incrementAndGet(); }
    }

    /** Counts source unsubscribes and exposes how many queues the source publishes to. */
    public static class CountingSource extends InMemoryEventSource<String> {
        final AtomicInteger unSubscribes = new AtomicInteger();
        @Override public void unSubscribe(EventSubscriptionKey<String> key) { unSubscribes.incrementAndGet(); }
        int targetQueues() { return output.getTargetQueues().size(); }
    }

    @Test
    void reAddedProcessorInSameGroupReceivesEveryEvent() throws Exception {
        CountingSource feed = new CountingSource();
        MongooseServerConfig cfg = new MongooseServerConfig();
        SubscribingProcessor a = new SubscribingProcessor();
        cfg.addProcessor("g", a, "A");
        cfg.addEventSourceWorker(feed, FEED, true, "feed-agent", new SleepingMillisIdleStrategy(1));
        MongooseServer server = MongooseServer.bootServer(cfg);
        try {
            await(() -> a.started, "A started");
            feed.offer("x");
            await(() -> a.received.get() >= 1, "A received its event");

            server.stopProcessor("g", "A");
            await(() -> a.tornDown, "A torn down");
            await(() -> feed.targetQueues() == 0, "the feed's queue for the group released");

            SubscribingProcessor b = new SubscribingProcessor();
            server.addEventProcessor("B", "g", null, () -> b);
            await(() -> b.started, "B started");
            Thread.sleep(200);

            feed.offer("y");
            feed.offer("z");
            await(() -> b.received.get() >= 2, "B received both events");
            assertEquals(2, b.received.get(), "B receives every event published after it subscribed");
            assertEquals(1, a.received.get(), "A receives nothing after it is stopped");
            assertEquals(1, feed.unSubscribes.get(), "the source is unsubscribed once A's subscription is gone");
            assertEquals(1, feed.targetQueues(), "the feed publishes to B's queue, and only to it");
        } finally {
            server.stop();
        }
    }

    @Test
    void lastSubscriberLeavingRemovesReaderAndNextSubscribeGetsAFreshOne() throws Exception {
        FakeFlowManager flowManager = new FakeFlowManager();
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent(
                "g", flowManager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart();
        SubscribingProcessor a = new SubscribingProcessor();
        SubscribingProcessor b = new SubscribingProcessor();
        EventSubscriptionKey<Object> key = SubscribingProcessor.key();

        group.subscribe(a, key);
        runCycles(group);
        FakeReader first = flowManager.readers.get(0);
        assertTrue(first.started, "the first reader joined the composite");

        group.removeAllSubscriptions(a);
        runCycles(group);
        assertEquals(List.of(key), flowManager.unSubscribed, "the source subscription is released");
        assertTrue(first.closed, "the dead reader is removed from the composite, not leaked");

        group.subscribe(b, key);
        runCycles(group);
        assertEquals(2, flowManager.readers.size(), "a new subscriber gets a fresh reader, not the dead one");
        FakeReader second = flowManager.readers.get(1);
        assertTrue(second.started, "the fresh reader joined the composite");
        assertEquals(List.of(b), second.processors);

        group.unSubscribe(b, key);
        runCycles(group);
        assertEquals(List.of(key, key), flowManager.unSubscribed);
        assertTrue(second.closed, "unSubscribe of the last listener removes the reader too");

        group.onClose();
    }

    @Test
    void readerReleasedBeforeItJoinedTheCompositeIsNeverAdded() throws Exception {
        FakeFlowManager flowManager = new FakeFlowManager();
        ComposingEventProcessorAgent group = new ComposingEventProcessorAgent(
                "g", flowManager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        group.onStart();
        SubscribingProcessor a = new SubscribingProcessor();

        group.subscribe(a, SubscribingProcessor.key());
        group.removeAllSubscriptions(a);   // before any duty cycle has added the reader
        runCycles(group);

        FakeReader reader = flowManager.readers.get(0);
        assertFalse(reader.started, "a reader released before it was added never joins the composite");
        group.onClose();
    }

    private static void runCycles(ComposingEventProcessorAgent group) throws Exception {
        for (int i = 0; i < 10; i++) group.doWork();
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !condition.getAsBoolean()) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    private static class FakeFlowManager extends EventFlowManager {
        final List<FakeReader> readers = new ArrayList<>();
        final List<EventSubscriptionKey<?>> unSubscribed = new ArrayList<>();

        @Override public void subscribe(EventSubscriptionKey<?> subscriptionKey) {}
        @Override public void unSubscribe(EventSubscriptionKey<?> subscriptionKey) { unSubscribed.add(subscriptionKey); }
        @Override public <T> EventQueueToEventProcessor getMappingAgent(EventSubscriptionKey<T> key, Agent subscriber) {
            FakeReader reader = new FakeReader();
            readers.add(reader);
            return reader;
        }
    }

    private static class FakeReader implements EventQueueToEventProcessor {
        final List<DataFlow> processors = new ArrayList<>();
        boolean started, closed;
        @Override public int registerProcessor(DataFlow p) { processors.add(p); return processors.size(); }
        @Override public int deregisterProcessor(DataFlow p) { processors.remove(p); return processors.size(); }
        @Override public int listenerCount() { return processors.size(); }
        @Override public void onStart() { started = true; }
        @Override public void onClose() { closed = true; }
        @Override public int doWork() { return 0; }
        @Override public String roleName() { return "fakeReader"; }
    }
}
