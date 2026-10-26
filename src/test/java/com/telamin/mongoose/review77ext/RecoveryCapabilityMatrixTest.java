package com.telamin.mongoose.review77ext;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dispatch.RetryPolicy;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.dutycycle.ProcessorDispatchException;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryCapabilityMatrixTest {
    enum Instrumentation { NONE, IDENTITY, POSITION }
    static class Strategy implements EventToInvokeStrategy {
        final List<DataFlow> targets = new ArrayList<>();
        final Instrumentation instrumentation;
        Strategy(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
        public void processEvent(Object event) { dispatch(event, 0); }
        void dispatch(Object event, int from) {
            for (int i = from; i < targets.size(); i++) {
                DataFlow p = targets.get(i);
                try { p.onEvent(event); }
                catch (RuntimeException failure) {
                    if (instrumentation != Instrumentation.NONE)
                        ProcessorContext.recordFailedProcessor(p, instrumentation == Instrumentation.POSITION ? i : -1);
                    throw failure;
                }
            }
        }
        public void processEvent(Object event, long time) { processEvent(event); }
        public void registerProcessor(DataFlow p) { targets.add(p); }
        public void deregisterProcessor(DataFlow p) { targets.remove(p); }
        public int listenerCount() { return targets.size(); }
        public Collection<DataFlow> registeredProcessors() { return List.copyOf(targets); }
    }
    static class Single extends Strategy {
        Single(Instrumentation i) { super(i); }
        // Deliberately does not record a failure: the host already knows the selected target.
        public void processEventFor(DataFlow p, Object event) { p.onEvent(event); }
    }
    static class Resumable extends Strategy {
        Resumable(Instrumentation i) { super(i); }
        public void processEventFrom(Object event, int from) { dispatch(event, from); }
    }
    static class SingleResumable extends Single {
        SingleResumable(Instrumentation i) { super(i); }
        public void processEventFrom(Object event, int from) { dispatch(event, from); }
    }
    static Stream<Arguments> combinations() {
        var cases = new ArrayList<Arguments>();
        for (var instrumentation : Instrumentation.values())
            for (boolean single : new boolean[]{false, true})
                for (boolean generated : new boolean[]{false, true})
                    for (boolean resume : new boolean[]{false, true})
                        cases.add(Arguments.of(instrumentation, single, generated, resume));
        return cases.stream();
    }
    static class Generated extends GroupControlProcessor {
        int calls;
        @Override public void onEvent(Object event) {
            if (++calls <= 2) throw new IllegalStateException("generated failure");
        }
    }
    @ParameterizedTest
    @MethodSource("combinations")
    void matrix(Instrumentation instrumentation, boolean single, boolean generated, boolean resume) {
        Strategy strategy = resume ? (single ? new SingleResumable(instrumentation) : new Resumable(instrumentation))
                : single ? new Single(instrumentation) : new Strategy(instrumentation);
        boolean resumes = resume && instrumentation == Instrumentation.POSITION;
        var queue = new OneToOneConcurrentArrayQueue<Object>(8);
        var reader = new EventQueueToEventProcessorAgent(queue, strategy, "matrix", "feed")
            .withRetryPolicy(new RetryPolicy(3, 0, 0, 1, Set.of(RuntimeException.class)));
        var before = new AtomicInteger(); var after = new AtomicInteger(); var calls = new AtomicInteger();
        reader.registerProcessor(e -> before.incrementAndGet());
        Generated graph = new Generated();
        DataFlow thrower = generated ? graph : e -> { if (calls.incrementAndGet() <= 2) throw new IllegalStateException("handwritten failure"); };
        reader.registerProcessor(thrower);
        reader.registerProcessor(e -> after.incrementAndGet());
        queue.offer("input");
        if (instrumentation == Instrumentation.NONE && generated) {
            assertThrows(ProcessorDispatchException.class, reader::doWork);
            assertEquals(3, strategy.listenerCount(), "unknown failure must not blame a peer");
        } else {
            assertDoesNotThrow(reader::doWork);
            assertEquals(generated ? 2 : 3, strategy.listenerCount());
            assertEquals(generated ? 1 : ((single && instrumentation != Instrumentation.NONE) || resumes ? 3 : 1),
                generated ? graph.calls : calls.get());
            queue.offer("next");
            assertDoesNotThrow(reader::doWork, "reader remains usable");
        }
        assertEquals(instrumentation == Instrumentation.NONE && generated ? 1 : 2, before.get(), "never replay a successful peer");
        assertEquals(resumes ? 2 : (generated || single) && instrumentation != Instrumentation.NONE ? 1 : 0, after.get());
    }
    @Test void identityOnlySoloFailureCannotBlameHealthyGeneratedPeer() {
        var strategy = new Single(Instrumentation.IDENTITY);
        var q = new OneToOneConcurrentArrayQueue<Object>(8);
        var reader = new EventQueueToEventProcessorAgent(q, strategy, "matrix", "feed");
        var before = new AtomicInteger();
        reader.registerProcessor(e -> before.incrementAndGet());
        reader.registerProcessor(e -> { throw new IllegalStateException("persistent handwritten failure"); });
        reader.registerProcessor(new SoloRetryCompatibilityProbeTest.HealthyGenerated());
        q.offer("input");
        assertDoesNotThrow(reader::doWork);
        assertEquals(1, before.get());
        assertEquals(3, strategy.listenerCount());
    }
}
