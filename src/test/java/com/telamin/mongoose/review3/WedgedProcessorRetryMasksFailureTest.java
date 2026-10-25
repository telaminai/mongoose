package com.telamin.mongoose.review3;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.time.ClockStrategy;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dispatch.RetryPolicy;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Review 3: a generated data processor whose handler throws keeps processing=true (generated processEvent has no
 * finally; see GroupControlProcessor.processEvent, the same template). Its next onEvent is queued as re-entrant and
 * returns normally. The host's retry therefore "succeeds" without any handler running, and every later event is
 * silently queued: Mongoose reports one WARNING and nothing else. PASSING this test confirms the defect.
 */
class WedgedProcessorRetryMasksFailureTest {

    /** Mirrors the generated processEvent: flag set, handler throws, flag never cleared; re-entrant events queued. */
    static final class GeneratedShape implements DataFlow {
        boolean processing;
        boolean throwNext = true;
        final List<Object> handled = new ArrayList<>();
        final ArrayDeque<Object> reentrantQueue = new ArrayDeque<>();

        @Override public void onEvent(Object event) {
            if (processing) { reentrantQueue.add(event); return; }
            processing = true;
            if (throwNext) { throwNext = false; throw new IllegalStateException("handler failed on " + event); }
            handled.add(event);
            processing = false;
        }
        @Override public void setClockStrategy(ClockStrategy clockStrategy) { }
    }

    private final List<ErrorEvent> reported = new CopyOnWriteArrayList<>();
    private final ErrorListener listener = reported::add;

    @BeforeEach void listen() { ErrorReporting.getReporter().addListener(listener); }
    @AfterEach void stop() { ErrorReporting.getReporter().removeListener(listener); ProcessorContext.removeCurrentProcessor(); }

    @Test
    void retryReportsRecoveryWhileTheProcessorIsWedgedAndQueuesEveryLaterEvent() {
        OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(64);
        GeneratedShape proc = new GeneratedShape();
        EventQueueToEventProcessorAgent agent = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(),
                "review3-wedge", "feed").withRetryPolicy(new RetryPolicy(3, 0, 0, 1.0, Set.of(RuntimeException.class)));
        agent.registerProcessor(proc);
        for (int i = 1; i <= 20; i++) queue.offer("E" + i);
        while (agent.doWork() > 0) { }

        List<ErrorEvent> mine = reported.stream()
                .filter(e -> String.valueOf(e.getSource()).equals("EventQueueToEventProcessorAgent:review3-wedge")).toList();
        assertEquals(0, proc.handled.size(), "no handler ever ran after the first failure");
        assertEquals(20, proc.reentrantQueue.size(), "the retried E1 and all 19 later events are queued, unbounded");
        assertEquals(1, mine.size(), "exactly one WARNING (attempt 1); the retry is treated as a recovery: " + mine);
        assertEquals(ErrorEvent.Severity.WARNING, mine.get(0).getSeverity());
    }
}
