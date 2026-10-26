package com.telamin.mongoose.review77ext;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Re-verification 2 (companion to the reviewer's {@link SoloRetryCompatibilityProbeTest}): an identity-only handwritten
 * failure on a strategy WITHOUT single-processor delivery skips the event (no retry of the thrower, no redelivery to the
 * healthy peer, no group termination); WITH it, the thrower alone is retried.
 */
class SoloRetryCompatibilityOwnTest {

    static class LiveOnlyStrategy implements EventToInvokeStrategy {
        final List<DataFlow> processors = new ArrayList<>();
        public void processEvent(Object e) {
            for (var p : processors) {
                try { p.onEvent(e); } catch (RuntimeException t) { ProcessorContext.recordFailedProcessor(p); throw t; }
            }
        }
        public void processEvent(Object e, long time) { processEvent(e); }
        public void registerProcessor(DataFlow p) { processors.add(p); }
        public void deregisterProcessor(DataFlow p) { processors.remove(p); }
        public int listenerCount() { return processors.size(); }
        public Collection<DataFlow> registeredProcessors() { return List.copyOf(processors); }
    }

    static class SingleTargetStrategy extends LiveOnlyStrategy {
        @Override public void processEventFor(DataFlow target, Object event) {
            try { target.onEvent(event); } catch (RuntimeException t) { ProcessorContext.recordFailedProcessor(target); throw t; }
        }
    }

    private static int[] run(EventToInvokeStrategy strategy) {
        var q = new OneToOneConcurrentArrayQueue<Object>(8);
        var reader = new EventQueueToEventProcessorAgent(q, strategy, "solo-own", "feed");
        var healthy = new AtomicInteger();
        var attempts = new AtomicInteger();
        var handled = new AtomicInteger();
        reader.registerProcessor(e -> healthy.incrementAndGet());
        reader.registerProcessor(e -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("handwritten first failure");
            handled.incrementAndGet();
        });
        reader.registerProcessor(new SoloRetryCompatibilityProbeTest.HealthyGenerated());
        q.offer("input");
        assertDoesNotThrow(reader::doWork, "a recoverable handwritten failure never ends the group");
        return new int[]{healthy.get(), attempts.get(), handled.get()};
    }

    @Test
    void withoutSingleTargetDeliveryTheEventIsSkippedNeverRedeliveredOrRetried() {
        int[] r = run(new LiveOnlyStrategy());
        assertEquals(1, r[0], "the healthy peer is never given the event again");
        assertEquals(1, r[1], "the thrower is not retried: the strategy cannot deliver to it alone");
        assertEquals(0, r[2]);
    }

    @Test
    void withSingleTargetDeliveryTheThrowerAloneIsRetried() {
        int[] r = run(new SingleTargetStrategy());
        assertEquals(1, r[0], "the healthy peer receives the event once");
        assertEquals(2, r[1]);
        assertEquals(1, r[2], "the thrower alone is retried and handles it");
    }
}
