package com.telamin.mongoose.dutycycle;

import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dispatch.RetryPolicy;
import com.telamin.mongoose.replay.generated.AlarmProcessor;
import com.telamin.mongoose.service.error.ErrorEvent;
import com.telamin.mongoose.service.error.ErrorListener;
import com.telamin.mongoose.service.error.ErrorReporting;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Review-3 F1 regression, using a real pre-fix generated class rather than a copy of its template. */
class WedgedProcessorRetryMasksFailureTest {
    static class Legacy extends AlarmProcessor {
        int handlerCalls;
        @Override public void onEventInternal(Object event) {
            if (event instanceof String) {
                handlerCalls++;
                if (handlerCalls == 1) throw new IllegalStateException("legacy handler failure");
            } else super.onEventInternal(event);
        }
    }

    @Test void legacyWedgeIsQuarantinedAndNeverReportedAsRecovered() {
        Legacy witness = new Legacy(); witness.init(); witness.start();
        assertThrows(IllegalStateException.class, () -> witness.onEvent("first"));
        witness.onEvent("next");
        assertEquals(1, witness.handlerCalls, "precondition: this old compiled graph really remains wedged");

        Legacy graph = new Legacy(); graph.init(); graph.start();
        var queue = new OneToOneConcurrentArrayQueue<Object>(64);
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "legacy-wedge")
                .withRetryPolicy(new RetryPolicy(3, 0, 0, 1.0, Set.of(RuntimeException.class)));
        List<ErrorEvent> errors = new CopyOnWriteArrayList<>();
        ErrorListener listener = errors::add;
        ErrorReporting.getReporter().addListener(listener);
        reader.registerProcessor(graph);
        try {
            for (int i = 1; i <= 20; i++) queue.offer("E" + i);
            assertEquals(1, reader.doWork());
            assertEquals(0, reader.doWork());
            assertEquals(1, graph.handlerCalls);
            assertEquals(0, reader.listenerCount());
            assertEquals(19, queue.size(), "remaining inputs stay outside the damaged processor until reader close");
            var mine = errors.stream().filter(e -> String.valueOf(e.getSource())
                    .equals("EventQueueToEventProcessorAgent:legacy-wedge")).toList();
            assertEquals(1, mine.size(), mine.toString());
            assertEquals(ErrorEvent.Severity.ERROR, mine.getFirst().getSeverity());
        } finally {
            reader.onClose();
            ErrorReporting.getReporter().removeListener(listener);
            ProcessorContext.removeCurrentProcessor();
        }
        assertTrue(queue.isEmpty());
    }
}
