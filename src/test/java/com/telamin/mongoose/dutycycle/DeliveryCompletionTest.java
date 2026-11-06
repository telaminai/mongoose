/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.PositionedItem;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dispatch.RetryPolicy;
import com.telamin.mongoose.service.delivery.Delivery;
import com.telamin.mongoose.service.delivery.DeliveryContext;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The delivery-complete hook (docs/design/feed-delivery.md, section 5b): {@link DeliveryContext#onComplete} callbacks
 * run once the processors of the queue have handled a positioned item, after the events it queued reentrantly, with
 * the delivery still current, and before the queue's {@code dispatched} counter (what the source acknowledges and
 * commits by) counts the item. A failed dispatch discards the failing processor's callbacks; a callback that throws is
 * retried under the retry policy, alone, with the item still not dispatched. Each test drives a real agent and the real
 * strategy over a real queue, on this thread.
 */
class DeliveryCompletionTest {

    private static final RetryPolicy THREE_ATTEMPTS_NO_BACKOFF = new RetryPolicy(3, 0, 0, 1.0, Set.of(RuntimeException.class));
    private static final String AGENT = "completion5b";

    private final List<ErrorEvent> reported = new CopyOnWriteArrayList<>();
    private final ErrorListener listener = reported::add;
    private final List<String> log = new ArrayList<>();
    private final AtomicLong dispatched = new AtomicLong();

    @BeforeEach
    void listen() {
        ErrorReporting.getReporter().addListener(listener);
    }

    @AfterEach
    void stopListening() {
        ErrorReporting.getReporter().removeListener(listener);
        ProcessorContext.removeCurrentProcessor();
        DeliveryContext.exit();
    }

    private List<ErrorEvent> reportedHere(ErrorEvent.Severity severity) {
        return reported.stream().filter(e -> String.valueOf(e.getSource()).equals("EventQueueToEventProcessorAgent:" + AGENT)
                && e.getSeverity() == severity).toList();
    }

    /**
     * Handles an event the way a generated processor does: the handler, then the {@code @AfterEvent} stage, then the
     * events queued reentrantly during the cycle (an {@code onEvent} while processing is queued), each a cycle of its
     * own. The handler is supplied by the test.
     */
    final class Proc implements DataFlow {
        final String name;
        final Consumer<Object> handler;
        private final ArrayDeque<Object> queued = new ArrayDeque<>();
        private boolean processing;
        int received;

        Proc(String name, Consumer<Object> handler) {
            this.name = name;
            this.handler = handler;
        }

        @Override
        public void onEvent(Object event) {
            if (processing) {
                queued.add(event);
                return;
            }
            processing = true;
            try {
                cycle(event);
                Object next;
                while ((next = queued.poll()) != null) cycle(next);
            } finally {
                processing = false;
                queued.clear();
            }
        }

        private void cycle(Object event) {
            received++;
            handler.accept(event);
            log.add(name + " afterEvent " + event);
        }

        @Override
        public void setClockStrategy(ClockStrategy clockStrategy) {
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A callback that logs what it sees: the delivery's position, the attempt and the queue's dispatched count. */
    private Runnable logging(String what) {
        return () -> {
            Delivery d = DeliveryContext.current();
            log.add(what + " position=" + (d == null ? null : d.position()) + " dispatched=" + dispatched.get()
                    + " current=" + ProcessorContext.currentProcessor());
        };
    }

    private EventQueueToEventProcessorAgent agent(OneToOneConcurrentArrayQueue<Object> queue, DataFlow... procs) {
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), AGENT, "feed")
                .withRetryPolicy(THREE_ATTEMPTS_NO_BACKOFF)
                .withDispatchedCounter(dispatched);
        for (DataFlow p : procs) agent.registerProcessor(p);
        return agent;
    }

    private static PositionedItem positioned(long sequence, long position, Object item) {
        return new PositionedItem(sequence, position, 7, item);
    }

    @Test
    void callbacksRunAfterTheReentrantWorkOfTheDeliveryAndBeforeItCountsAsDispatched() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Runnable save = logging("save");
        Proc[] self = new Proc[1];
        self[0] = new Proc("p", event -> {
            log.add("p handle " + event + " attempt=" + DeliveryContext.attempt());
            assertTrue(DeliveryContext.onComplete(save), "a positioned delivery has a completion");
            if ("join".equals(event)) self[0].onEvent("payment");   // a sink sending the processor another event
        });
        EventQueueToEventProcessorAgent agent = agent(queue, self[0]);

        queue.offer(positioned(1, 100, "join"));
        assertEquals(1, agent.doWork());

        assertEquals(List.of(
                "p handle join attempt=0", "p afterEvent join",
                "p handle payment attempt=0", "p afterEvent payment",
                "save position=100 dispatched=0 current=p"), log,
                "registered twice (once per cycle), run once, after the queued payment, with the delivery current and"
                        + " the item not yet dispatched");
        assertEquals(1, dispatched.get(), "counted once the callbacks have run");
        assertNull(DeliveryContext.current(), "the delivery is cleared after its completion");
        assertNull(ProcessorContext.currentProcessor());
    }

    @Test
    void callbacksRunInRegistrationOrderOnceEachAcrossTheProcessorsOfTheQueue() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Runnable a = logging("a"), b = logging("b"), late = logging("late");
        Runnable registersLate = () -> {
            log.add("registersLate");
            assertTrue(DeliveryContext.onComplete(late));
        };
        Proc first = new Proc("first", e -> {
            DeliveryContext.onComplete(a);
            DeliveryContext.onComplete(registersLate);
            DeliveryContext.onComplete(a);
        });
        Proc second = new Proc("second", e -> {
            DeliveryContext.onComplete(b);
            DeliveryContext.onComplete(a);   // another processor's pending callback: still once
        });
        EventQueueToEventProcessorAgent agent = agent(queue, first, second);

        queue.offer(positioned(1, 10, "x"));
        agent.doWork();

        assertEquals(List.of("first afterEvent x", "second afterEvent x",
                "a position=10 dispatched=0 current=first",
                "registersLate",
                "b position=10 dispatched=0 current=second",
                "late position=10 dispatched=0 current=first"), log,
                "after every processor of the queue; in order; a callback registered by a callback runs in the same pass");
    }

    @Test
    void anItemWithoutAPositionHasNoCompletionAndNeitherDoesAThreadOutsideADelivery() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Runnable never = () -> fail("an item without a position has no completion");
        List<Boolean> registered = new ArrayList<>();
        Proc p = new Proc("p", e -> registered.add(DeliveryContext.onComplete(never)));
        EventQueueToEventProcessorAgent agent = agent(queue, p);

        queue.offer("plain");
        agent.doWork();
        assertEquals(List.of(false), registered, "nothing kept: the caller does its work itself");
        assertFalse(DeliveryContext.onComplete(never), "no delivery on this thread");
        assertEquals(0, DeliveryContext.attempt());

        // a positioned item after it: the earlier refusal left nothing behind
        Runnable save = logging("save");
        Proc q = new Proc("q", e -> DeliveryContext.onComplete(save));
        agent.registerProcessor(q);
        agent.deregisterProcessor(p);
        queue.offer(positioned(2, 20, "y"));
        agent.doWork();
        assertEquals(List.of("p afterEvent plain", "q afterEvent y", "save position=20 dispatched=1 current=q"), log);
    }

    @Test
    void aFailedDispatchDiscardsTheFailingProcessorsCallbacksAndItsRetryRegistersThemAgain() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Runnable saveFirst = logging("saveFirst"), saveSecond = logging("saveSecond");
        int[] secondFails = {1};
        Proc first = new Proc("first", e -> DeliveryContext.onComplete(saveFirst));
        Proc second = new Proc("second", e -> {
            log.add("second handle attempt=" + DeliveryContext.attempt());
            DeliveryContext.onComplete(saveSecond);
            if (secondFails[0]-- > 0) throw new IllegalStateException("DEMO second fails once");
        });
        EventQueueToEventProcessorAgent agent = agent(queue, first, second);

        queue.offer(positioned(1, 100, "x"));
        agent.doWork();

        assertEquals(List.of("first afterEvent x",
                "second handle attempt=0",
                "second handle attempt=1", "second afterEvent x",
                "saveFirst position=100 dispatched=0 current=first",
                "saveSecond position=100 dispatched=0 current=second"), log,
                "the failed attempt's callback is discarded (the retry registers it again: run once); the processor"
                        + " that handled the item keeps its callback");
        assertEquals(1, first.received, "not given the item again");
        assertEquals(1, dispatched.get());
    }

    @Test
    void aProcessorWhoseRetriesAreExhaustedRunsNoCallbackAndTheOthersStillDo() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Runnable saveFirst = logging("saveFirst");
        Runnable never = () -> fail("the callback of a dispatch that failed must not run");
        Proc first = new Proc("first", e -> DeliveryContext.onComplete(saveFirst));
        Proc second = new Proc("second", e -> {
            DeliveryContext.onComplete(never);
            throw new IllegalStateException("DEMO second always fails");
        });
        EventQueueToEventProcessorAgent agent = agent(queue, first, second);

        queue.offer(positioned(1, 100, "x"));
        agent.doWork();

        assertEquals(List.of("first afterEvent x", "saveFirst position=100 dispatched=0 current=first"), log);
        assertEquals(3, second.received);
        assertEquals(1, dispatched.get(), "a failed item counts as dispatched once the policy gives up, as before");
        assertFalse(reportedHere(ErrorEvent.Severity.ERROR).isEmpty(), "dropped and reported");
    }

    @Test
    void aCallbackThatThrowsIsRetriedAloneWithTheItemNotYetDispatched() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        int[] fails = {2};
        Runnable before = logging("before");
        Runnable flaky = () -> {
            log.add("flaky dispatched=" + dispatched.get() + " position=" + DeliveryContext.currentPosition());
            if (fails[0]-- > 0) throw new IllegalStateException("DEMO the save fails");
        };
        Runnable after = logging("after");
        Proc p = new Proc("p", e -> {
            DeliveryContext.onComplete(before);
            DeliveryContext.onComplete(flaky);
            DeliveryContext.onComplete(after);
        });
        EventQueueToEventProcessorAgent agent = agent(queue, p);

        queue.offer(positioned(1, 100, "x"));
        agent.doWork();

        assertEquals(List.of("p afterEvent x",
                "before position=100 dispatched=0 current=p",
                "flaky dispatched=0 position=100", "flaky dispatched=0 position=100", "flaky dispatched=0 position=100",
                "after position=100 dispatched=0 current=p"), log,
                "the failing callback alone is retried (three attempts), never the dispatch or the callbacks before it");
        assertEquals(1, p.received, "the processor is not given the item again");
        assertEquals(1, dispatched.get());
        assertEquals(2, reportedHere(ErrorEvent.Severity.WARNING).size(), reported.toString());
        assertTrue(reportedHere(ErrorEvent.Severity.ERROR).isEmpty(), "recovered: nothing dropped");
    }

    @Test
    void aCallbackThatKeepsThrowingIsReportedAndSkippedAndTheRestRun() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
        Runnable broken = () -> {
            log.add("broken");
            throw new IllegalStateException("DEMO the save always fails");
        };
        Runnable after = logging("after");
        Proc p = new Proc("p", e -> {
            DeliveryContext.onComplete(broken);
            DeliveryContext.onComplete(after);
        });
        EventQueueToEventProcessorAgent agent = agent(queue, p);

        queue.offer(positioned(1, 100, "x"));
        queue.offer(positioned(2, 200, "y"));
        agent.doWork();

        assertEquals(List.of("p afterEvent x", "broken", "broken", "broken", "after position=100 dispatched=0 current=p",
                "p afterEvent y", "broken", "broken", "broken", "after position=200 dispatched=1 current=p"), log);
        assertEquals(2, dispatched.get(), "counted as dispatched once the policy gives up, as a failed item is");
        assertEquals(2, reportedHere(ErrorEvent.Severity.ERROR).size(), reported.toString());
        assertTrue(reportedHere(ErrorEvent.Severity.ERROR).get(0).getMessage().contains("completion"),
                reportedHere(ErrorEvent.Severity.ERROR).get(0).getMessage());
    }

    @Test
    void eachQueueCompletesItsOwnDelivery() {
        // the same positioned item offered to two groups' queues (a broadcast feed with two subscribers): each reader
        // completes its own delivery; nothing registered in one is run by, or leaks into, the other
        OneToOneConcurrentArrayQueue<Object> q1 = new OneToOneConcurrentArrayQueue<>(16);
        OneToOneConcurrentArrayQueue<Object> q2 = new OneToOneConcurrentArrayQueue<>(16);
        Runnable one = logging("one"), two = logging("two");
        EventQueueToEventProcessorAgent a1 = agent(q1, new Proc("g1", e -> DeliveryContext.onComplete(one)));
        AtomicLong dispatched2 = new AtomicLong();
        EventQueueToEventProcessorAgent a2 = new EventQueueToEventProcessorAgent(q2, new EventToOnEventInvokeStrategy(), AGENT, "feed")
                .withDispatchedCounter(dispatched2);
        a2.registerProcessor(new Proc("g2", e -> DeliveryContext.onComplete(two)));
        PositionedItem item = positioned(1, 100, "x");
        q1.offer(item);
        q2.offer(item);
        a1.doWork();
        a2.doWork();
        assertEquals(List.of("g1 afterEvent x", "one position=100 dispatched=0 current=g1",
                "g2 afterEvent x", "two position=100 dispatched=1 current=g2"), log);
        assertEquals(1, dispatched2.get());
    }

    @Test
    void exitDiscardsWhatIsStillPending() {
        DeliveryContext.enter("feed", 1, 7, 10L);
        assertTrue(DeliveryContext.onComplete(() -> fail("discarded")));
        DeliveryContext.exit();
        DeliveryContext.enter("feed", 2, 7, 20L);
        assertNull(DeliveryContext.takeCompletion(), "a new delivery starts with none");
        DeliveryContext.exit();
        assertNull(DeliveryContext.takeCompletion());
    }
}
