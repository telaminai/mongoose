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
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mongoose#57: one processor's failure must not reach the group's agent error handler (which, under bootServer, is
 * GlobalErrorHandler and exits the JVM). It is reported through ErrorReporting and the processor is dropped (a failed
 * start or stop) or the failing callback skipped (a service broadcast, a timer), and the rest of the group runs on.
 * No test here installs an exiting handler: each records what reaches the agent's handler and asserts nothing did.
 */
class ProcessorFailureIsolationTest {

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

    public static class Proc implements DataFlow {
        final AtomicInteger received = new AtomicInteger();
        final List<String> services = new CopyOnWriteArrayList<>();
        final List<EventFeed> feeds = new ArrayList<>();
        volatile boolean started;
        boolean failStart, failStop, failRegister;

        Proc failStart() { failStart = true; return this; }
        Proc failStop() { failStop = true; return this; }
        Proc failRegister() { failRegister = true; return this; }

        static EventSubscriptionKey<Object> key() {
            return new EventSubscriptionKey<>(new EventSourceKey<>(FEED), CallBackType.ON_EVENT_CALL_BACK);
        }

        @Override public void init() {}
        @Override public void start() {
            for (EventFeed f : feeds) f.subscribe(this, key());   // subscribe first, so a failed start leaves a subscription
            if (failStart) throw new IllegalStateException("boom in start");
            started = true;
        }
        @Override public void stop() { if (failStop) throw new IllegalStateException("boom in stop"); }
        @Override public void tearDown() { for (EventFeed f : feeds) f.removeAllSubscriptions(this); }
        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }
        @Override public void onEvent(Object e) { if (e instanceof String) received.incrementAndGet(); }
        @Override public void registerService(Service<?> s) {
            if (failRegister && s.serviceName().equals("dynamic")) throw new IllegalStateException("boom in registerService");
            services.add(s.serviceName());
        }
    }

    public static class Feed implements EventFlowService<String> {
        volatile EventToQueuePublisher<String> publisher;
        @Override public void setEventToQueuePublisher(EventToQueuePublisher<String> p) { publisher = p; }
        @Override public void subscribe(EventSubscriptionKey<String> k) {}
        @Override public void unSubscribe(EventSubscriptionKey<String> k) {}
    }

    @Test
    void processorWhoseStartThrowsAtRuntimeIsDroppedAndTheGroupRunsOn() throws Exception {
        List<Throwable> agentErrors = new CopyOnWriteArrayList<>();
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        server.setDefaultErrorHandler(agentErrors::add);   // what would be GlobalErrorHandler -> System.exit
        Feed feed = new Feed();
        server.registerEventSource(FEED, feed);
        Proc good = new Proc();
        server.addEventProcessor("good", "g", null, () -> good);
        server.init();
        server.start();
        try {
            await(() -> good.started, "good started");
            await(() -> feed.publisher.getTargetQueues().size() == 1, "good subscribed");
            feed.publisher.publish("a");
            await(() -> good.received.get() == 1, "good received a");

            Proc bad = new Proc().failStart();
            Proc later = new Proc();
            server.addEventProcessor("bad", "g", null, () -> bad);
            server.addEventProcessor("later", "g", null, () -> later);
            await(() -> later.started, "a processor added after the bad one started");
            Thread.sleep(100);

            feed.publisher.publish("b");
            feed.publisher.publish("c");
            await(() -> good.received.get() == 3 && later.received.get() == 2, "the group keeps dispatching");

            assertEquals(List.of(), agentErrors, "a processor's failed start must not reach the agent error handler");
            assertTrue(reported.stream().anyMatch(e -> String.valueOf(e.getMessage()).contains("bad")),
                    "the failure is reported through ErrorReporting");
            Set<String> names = server.registeredProcessors().get("g").stream()
                    .map(NamedEventProcessor::name).collect(Collectors.toSet());
            assertEquals(Set.of("good", "later"), names, "the failed processor is dropped from the group");
            assertEquals(0, bad.received.get(), "the failed processor receives nothing");
        } finally {
            server.stop();
        }
    }

    @Test
    void processorWhoseStartThrowsAtGroupStartIsDroppedAndTheOthersStartWhenBootIsolationIsOn() {
        ComposingEventProcessorAgent group = newGroup(new FakeFlowManager());
        group.setIsolateStartFailuresAtBoot(true);
        Proc bad = new Proc().failStart();
        Proc good = new Proc();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("bad", bad));
        group.addNamedEventProcessor(() -> new NamedEventProcessor("good", good));

        assertDoesNotThrow(group::onStart, "a processor's failure must not fail the group's onStart");

        assertTrue(good.started);
        assertFalse(group.isProcessorRegistered("bad"));
        assertTrue(group.isProcessorRegistered("good"));
        assertTrue(reported.stream().anyMatch(e -> e.getSeverity() == ErrorEvent.Severity.ERROR
                && String.valueOf(e.getMessage()).contains("bad")), "the failure is reported: " + reported);
        group.onClose();
    }

    @Test
    void failedStartReleasesTheSubscriptionsItMade() throws Exception {
        FakeFlowManager flowManager = new FakeFlowManager();
        ComposingEventProcessorAgent group = newGroup(flowManager);
        group.setIsolateStartFailuresAtBoot(true);
        group.addNamedEventProcessor(() -> new NamedEventProcessor("bad", new Proc().failStart()));
        group.onStart();
        assertEquals(1, flowManager.subscribed.get());
        assertEquals(1, flowManager.unSubscribed.get(), "the dropped processor's subscription is released");
        group.onClose();
    }

    @Test
    void processorWhoseStopThrowsIsStillRemoved() throws Exception {
        FakeFlowManager flowManager = new FakeFlowManager();
        ComposingEventProcessorAgent group = newGroup(flowManager);
        group.addNamedEventProcessor(() -> new NamedEventProcessor("p", new Proc().failStop()));
        group.onStart();

        group.removeEventProcessorByName("p");
        assertDoesNotThrow(group::doWork);

        assertFalse(group.isProcessorRegistered("p"));
        assertEquals(1, flowManager.unSubscribed.get(), "its subscription is released though stop threw");
        assertFalse(reported.isEmpty(), "the failure is reported");
        group.onClose();
    }

    @Test
    void serviceBroadcastThatOneProcessorRejectsStillReachesTheOthers() throws Exception {
        ComposingEventProcessorAgent group = newGroup(new FakeFlowManager());
        Proc rejecting = new Proc().failRegister();
        Proc other = new Proc();
        group.addNamedEventProcessor(() -> new NamedEventProcessor("rejecting", rejecting));
        group.addNamedEventProcessor(() -> new NamedEventProcessor("other", other));
        group.onStart();

        group.broadcastServiceRegistered(new Service<>(new Object(), Object.class, "dynamic"));
        assertDoesNotThrow(group::doWork);

        assertTrue(other.services.contains("dynamic"));
        assertTrue(group.isProcessorRegistered("rejecting"), "a rejected service does not drop a running processor");
        assertFalse(reported.isEmpty(), "the failure is reported");
        group.onClose();
    }

    @Test
    void timerCallbackThatThrowsDoesNotStopTheOtherTimers() {
        DeadWheelScheduler scheduler = new DeadWheelScheduler();
        AtomicInteger fired = new AtomicInteger();
        long now = scheduler.milliTime();
        scheduler.scheduleAtTime(now - 5, () -> { throw new IllegalStateException("boom in timer"); });
        scheduler.scheduleAtTime(now - 5, fired::incrementAndGet);
        scheduler.scheduleAtTime(now - 4, fired::incrementAndGet);

        long end = System.currentTimeMillis() + 2_000;
        while (fired.get() < 2 && System.currentTimeMillis() < end) {
            assertDoesNotThrow(scheduler::doWork, "a timer callback's failure must not escape the scheduler");
        }

        assertEquals(2, fired.get());
        assertTrue(reported.stream().anyMatch(e -> e.getError() != null
                && "boom in timer".equals(e.getError().getMessage())), "the failure is reported");
    }

    private static ComposingEventProcessorAgent newGroup(EventFlowManager flowManager) {
        return new ComposingEventProcessorAgent("g", flowManager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !condition.getAsBoolean()) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    private static class FakeFlowManager extends EventFlowManager {
        final AtomicInteger subscribed = new AtomicInteger();
        final AtomicInteger unSubscribed = new AtomicInteger();

        @Override public void subscribe(EventSubscriptionKey<?> key) { subscribed.incrementAndGet(); }
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
