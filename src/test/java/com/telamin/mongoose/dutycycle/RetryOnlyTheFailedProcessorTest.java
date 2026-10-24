/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dispatch.RetryPolicy;
import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayConfig;
import com.telamin.mongoose.replay.ReplayEntry;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventFlowService;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mongoose#53: when one processor's handler throws, the agent's retry re-delivers the event to that processor alone,
 * then to the processors after it that have not had it. A processor that handled it never sees it again, one whose
 * retries are exhausted does not starve the rest, the processor context is always cleared, and a recording marks only
 * the failed processor's input failed. Each test drives a real agent and the real strategy over a real queue, on this
 * thread, so the processor context checked afterwards is the one the dispatch used.
 */
class RetryOnlyTheFailedProcessorTest {

    private final List<ErrorEvent> reported = new CopyOnWriteArrayList<>();
    private final ErrorListener listener = reported::add;

    @BeforeEach
    void listen() {
        ErrorReporting.getReporter().addListener(listener);
    }

    @AfterEach
    void stopListening() {
        ErrorReporting.getReporter().removeListener(listener);
        ProcessorContext.removeCurrentProcessor();
    }

    /** Reported by this test's agents (the listener is global, so other agents' reports are left out). */
    private List<ErrorEvent> reportedHere() {
        return reported.stream().filter(e -> String.valueOf(e.getSource()).equals("EventQueueToEventProcessorAgent:retry53")).toList();
    }

    /** Records every event it is given, and the processor context it was given it in; throws the first {@code fails} times. */
    static final class Proc implements DataFlow {
        final String name;
        final List<Object> received = new CopyOnWriteArrayList<>();
        final List<DataFlow> contexts = new CopyOnWriteArrayList<>();
        int fails;

        Proc(String name, int fails) {
            this.name = name;
            this.fails = fails;
        }

        @Override
        public void onEvent(Object event) {
            received.add(event);
            contexts.add(ProcessorContext.currentProcessor());
            if (fails > 0) {
                fails--;
                throw new IllegalStateException("DEMO " + name + " fails on " + event);
            }
        }

        @Override
        public void setClockStrategy(ClockStrategy clockStrategy) {
            // a recording clock is accepted; these processors read no clock
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final RetryPolicy THREE_ATTEMPTS_NO_BACKOFF = new RetryPolicy(3, 0, 0, 1.0, Set.of(RuntimeException.class));

    private static EventQueueToEventProcessorAgent agent(OneToOneConcurrentArrayQueue<Object> queue, Proc... procs) {
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "retry53", "feed")
                .withRetryPolicy(THREE_ATTEMPTS_NO_BACKOFF);
        for (Proc p : procs) agent.registerProcessor(p);
        return agent;
    }

    private static void assertContextCleared(String when) {
        assertNull(ProcessorContext.currentProcessor(), "the processor context is cleared " + when);
    }

    @Test
    void secondProcessorThrowsOnce_firstReceivesTheEventOnce_secondTwice() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Proc first = new Proc("first", 0);
        Proc second = new Proc("second", 1);
        EventQueueToEventProcessorAgent agent = agent(queue, first, second);

        queue.offer("E1");
        assertEquals(1, agent.doWork());

        assertEquals(List.of("E1"), first.received, "the processor that handled the event is not given it again");
        assertEquals(List.of("E1", "E1"), second.received, "the failed processor: its attempt and one retry");
        assertEquals(List.of(second, second), second.contexts, "it is the current processor on every attempt");
        assertContextCleared("after a retried dispatch");
        assertTrue(reportedHere().stream().noneMatch(e -> e.getSeverity() == ErrorEvent.Severity.ERROR),
                "a retry that recovers drops nothing: " + reportedHere());
    }

    @Test
    void firstProcessorThrowsOnce_secondStillReceivesTheEventExactlyOnce() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Proc first = new Proc("first", 1);
        Proc second = new Proc("second", 0);
        Proc third = new Proc("third", 0);
        EventQueueToEventProcessorAgent agent = agent(queue, first, second, third);

        queue.offer("E1");
        assertEquals(1, agent.doWork());

        assertEquals(List.of("E1", "E1"), first.received, "the failed processor: its attempt and one retry");
        assertEquals(List.of("E1"), second.received, "a processor after the failed one is given the event once");
        assertEquals(List.of("E1"), third.received, "as is the one after that");
        assertEquals(List.of(second), second.contexts);
        assertContextCleared("after a retried dispatch");
    }

    @Test
    void firstProcessorAlwaysThrows_secondStillReceivesEveryEventOnce() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Proc first = new Proc("first", Integer.MAX_VALUE);
        Proc second = new Proc("second", 0);
        EventQueueToEventProcessorAgent agent = agent(queue, first, second);

        queue.offer("E1");
        queue.offer("E2");
        assertEquals(2, agent.doWork());

        assertEquals(List.of("E1", "E2"), second.received, "the first processor's exhausted retries do not starve the second");
        assertEquals(6, first.received.size(), "the policy's three attempts per event");
        assertContextCleared("after the first processor's retries are exhausted");
    }

    /** A VirtualMachineError is not a processor failure: it is neither retried nor swallowed, as on the group's paths. */
    @Test
    void aVirtualMachineErrorFromAHandlerIsNeitherRetriedNorSwallowed() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        List<Object> received = new CopyOnWriteArrayList<>();
        DataFlow fatal = new DataFlow() {
            @Override public void onEvent(Object event) {
                received.add(event);
                throw new StackOverflowError("DEMO fatal");
            }
            @Override public void setClockStrategy(ClockStrategy clockStrategy) {}
        };
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(),
                "retry53", "feed").withRetryPolicy(new RetryPolicy(3, 0, 0, 1.0, Set.of(Throwable.class)));
        agent.registerProcessor(fatal);

        queue.offer("E1");
        assertThrows(StackOverflowError.class, agent::doWork, "it reaches the agent's error handler");
        assertEquals(List.of("E1"), received, "and is not retried");
        assertContextCleared("after a fatal error");
    }

    @Test
    void aHandlerThatThrows_neverLeavesItsProcessorAsTheCurrentProcessor() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Proc broken = new Proc("broken", Integer.MAX_VALUE);
        EventQueueToEventProcessorAgent agent = agent(queue, broken);

        queue.offer("E1");
        assertEquals(1, agent.doWork());

        assertEquals(3, broken.received.size());
        assertEquals(List.of(broken, broken, broken), broken.contexts, "the current processor while it handles the event");
        assertContextCleared("once its handler has thrown and its retries are exhausted");
    }

    @Test
    void oneProcessorAlwaysThrows_theOthersStillReceiveEveryEventOnce() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Proc before = new Proc("before", 0);
        Proc broken = new Proc("broken", Integer.MAX_VALUE);
        Proc after = new Proc("after", 0);
        EventQueueToEventProcessorAgent agent = agent(queue, before, broken, after);

        queue.offer("E1");
        queue.offer("E2");
        assertEquals(2, agent.doWork());

        assertEquals(List.of("E1", "E2"), before.received, "before the broken processor: every event, once");
        assertEquals(List.of("E1", "E2"), after.received, "after it: every event once, its exhausted retries starve nothing");
        assertEquals(List.of("E1", "E1", "E1", "E2", "E2", "E2"), broken.received,
                "the broken processor: the policy's three attempts per event, no more");
        assertContextCleared("after a processor's retries are exhausted");

        List<ErrorEvent> dropped = reportedHere().stream().filter(e -> e.getSeverity() == ErrorEvent.Severity.ERROR).toList();
        assertEquals(2, dropped.size(), "one drop reported per event: " + dropped);
        assertTrue(dropped.get(0).getMessage().contains("attempts=3") && dropped.get(0).getMessage().contains("processor=broken"),
                "the drop names the processor and its attempts: " + dropped.get(0).getMessage());
        assertEquals(6, reportedHere().stream().filter(e -> e.getSeverity() == ErrorEvent.Severity.WARNING).count(),
                "each failed attempt is reported, as before");
    }

    @Test
    void replayRecordInput_resumesAtTheFailedProcessor() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Proc first = new Proc("first", 0);
        Proc second = new Proc("second", 1);
        EventQueueToEventProcessorAgent agent = agent(queue, first, second);

        ReplayRecord record = new ReplayRecord();
        record.setEvent("R1");
        record.setWallClockTime(42);
        queue.offer(record);
        assertEquals(1, agent.doWork());

        assertEquals(List.of("R1"), first.received, "the ReplayRecord path: the processor that handled it once");
        assertEquals(List.of("R1", "R1"), second.received);
        assertContextCleared("after a retried ReplayRecord dispatch");
    }

    // ---- a real server: two processors in one group, subscribed to one feed ------------------------------------

    private static final String FEED = "feed53";

    /** A processor that subscribes to {@link #FEED} when it starts, so both share the group's queue for it. */
    public static final class FeedProc implements DataFlow {
        final List<Object> received = new CopyOnWriteArrayList<>();
        final List<EventFeed> feeds = new ArrayList<>();
        volatile boolean started;
        volatile int fails;

        FeedProc(int fails) {
            this.fails = fails;
        }

        @Override public void init() {}
        @Override public void start() {
            for (EventFeed f : feeds) f.subscribe(this,
                    new EventSubscriptionKey<>(new EventSourceKey<>(FEED), CallBackType.ON_EVENT_CALL_BACK));
            started = true;
        }
        @Override public void tearDown() { for (EventFeed f : feeds) f.removeAllSubscriptions(this); }
        @Override public void addEventFeed(EventFeed f) { feeds.add(f); }
        @Override public void onEvent(Object e) {
            if (!(e instanceof String)) return;
            received.add(e);
            if (fails > 0) {
                fails--;
                throw new IllegalStateException("DEMO fails on " + e);
            }
        }
    }

    public static final class Feed implements EventFlowService<String> {
        volatile EventToQueuePublisher<String> publisher;
        @Override public void setEventToQueuePublisher(EventToQueuePublisher<String> p) { publisher = p; }
        @Override public void subscribe(EventSubscriptionKey<String> k) {}
        @Override public void unSubscribe(EventSubscriptionKey<String> k) {}
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < end && !condition.getAsBoolean()) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    @Test
    void server_secondProcessorOnTheFeedThrowsOnce_firstReceivesEachEventOnce() throws Exception {
        MongooseServer server = new MongooseServer(new MongooseServerConfig());
        List<Throwable> agentErrors = new CopyOnWriteArrayList<>();
        server.setDefaultErrorHandler(agentErrors::add);
        Feed feed = new Feed();
        server.registerEventSource(FEED, feed);
        FeedProc first = new FeedProc(0);
        FeedProc second = new FeedProc(1);
        server.addEventProcessor("first", "g53", null, () -> first);
        server.addEventProcessor("second", "g53", null, () -> second);
        server.init();
        server.start();
        try {
            await(() -> first.started && second.started, "both started");
            await(() -> feed.publisher != null && feed.publisher.getTargetQueues().size() == 1, "one queue for the group");
            feed.publisher.publish("a");
            feed.publisher.publish("b");
            await(() -> second.received.size() == 3, "second handled a (attempt and retry) and b");
            Thread.sleep(100);                                   // anything the old whole-queue retry re-delivered

            assertEquals(List.of("a", "b"), first.received, "first handled each event once: a retry is second's alone");
            assertEquals(List.of("a", "a", "b"), second.received, "second: a, its retry of a, then b");
            assertEquals(List.of(), agentErrors, "nothing reaches the agent error handler");
        } finally {
            server.stop();
        }
    }

    // ---- RECORD: only the failed processor's input is marked failed ----------------------------------------------

    private static GroupRecorder recorder(InMemoryReplayStore store, Proc... procs) {
        Set<String> names = new java.util.HashSet<>();
        for (Proc p : procs) names.add(p.name);
        GroupRecorder recorder = new GroupRecorder(ReplayConfig.record(names, Map.of(), null, store), () -> 7L);
        for (Proc p : procs) recorder.attach(p.name, p);
        return recorder;
    }

    private static void assertRecordedOnce(InMemoryReplayStore store, Proc p, Object input) {
        List<ReplayEntry> entries = store.entries(p.name);
        assertEquals(1, entries.size(), p.name + ": one entry for the one input it handled: " + entries);
        assertInstanceOf(ReplayEntry.Inline.class, entries.get(0), p.name + " handled it first time: " + entries);
        assertEquals(input, ((ReplayEntry.Inline) entries.get(0)).event(), p.name + ": what it received");
    }

    private static void assertRecordedFailedOnce(InMemoryReplayStore store, Proc p) {
        List<ReplayEntry> entries = store.entries(p.name);
        assertEquals(1, entries.size(), p.name + ": one entry, however many attempts: " + entries);
        assertInstanceOf(ReplayEntry.Failed.class, entries.get(0), p.name + " threw: marked, not reproduced (D4): " + entries);
    }

    @Test
    void recording_secondThrowsOnce_onlyItsInputIsMarkedFailed() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Proc first = new Proc("first", 0);
        Proc second = new Proc("second", 1);
        Proc third = new Proc("third", 0);
        EventQueueToEventProcessorAgent agent = agent(queue, first, second, third);
        InMemoryReplayStore store = new InMemoryReplayStore();
        agent.recordWith(recorder(store, first, second, third));

        queue.offer("E1");
        assertEquals(1, agent.doWork());

        assertEquals(List.of("E1"), first.received);
        assertEquals(List.of("E1", "E1"), second.received);
        assertEquals(List.of("E1"), third.received);
        assertRecordedOnce(store, first, "E1");
        assertRecordedFailedOnce(store, second);
        assertRecordedOnce(store, third, "E1");
        assertContextCleared("after a recorded, retried dispatch");
    }

    @Test
    void recording_aProcessorWhoseRetriesAreExhausted_isMarkedFailedOnce_andTheRestAreRecorded() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Proc broken = new Proc("broken", Integer.MAX_VALUE);
        Proc after = new Proc("after", 0);
        EventQueueToEventProcessorAgent agent = agent(queue, broken, after);
        InMemoryReplayStore store = new InMemoryReplayStore();
        agent.recordWith(recorder(store, broken, after));

        queue.offer("E1");
        assertEquals(1, agent.doWork());

        assertEquals(3, broken.received.size(), "the policy's three attempts");
        assertEquals(List.of("E1"), after.received);
        assertRecordedFailedOnce(store, broken);
        assertRecordedOnce(store, after, "E1");
        assertContextCleared("after a recorded dispatch whose retries were exhausted");
    }
}
