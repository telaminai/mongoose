package com.telamin.mongoose.dispatch;

import com.sun.management.ThreadMXBean;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessorFailureClearTest {
    static class UninstrumentedSingleTargetStrategy implements EventToInvokeStrategy {
        final List<DataFlow> processors = new ArrayList<>();
        public void processEvent(Object event) { processors.forEach(p -> p.onEvent(event)); }
        public void processEvent(Object event, long time) { processEvent(event); }
        public void processEventFor(DataFlow target, Object event) { target.onEvent(event); }
        public void registerProcessor(DataFlow processor) { processors.add(processor); }
        public void deregisterProcessor(DataFlow processor) { processors.remove(processor); }
        public int listenerCount() { return processors.size(); }
        public Collection<DataFlow> registeredProcessors() { return List.copyOf(processors); }
    }

    @Test void clearDiscardsOnlyFailureAndDoesNotChangeReadAndClearConsumers() {
        DataFlow processor = event -> {};
        ProcessorContext.setCurrentProcessor(processor);
        try {
            ProcessorContext.recordFailedProcessor(processor, 3);
            ProcessorContext.clearFailure();
            assertNull(ProcessorContext.takeFailure());
            assertSame(processor, ProcessorContext.currentProcessor());
            ProcessorContext.recordFailedProcessor(processor, 7);
            var failure = ProcessorContext.takeFailure();
            assertSame(processor, failure.processor());
            assertEquals(7, failure.position());
            assertNull(ProcessorContext.takeFailure());
            ProcessorContext.recordFailedProcessor(processor);
            assertSame(processor, ProcessorContext.takeFailedProcessor());
            assertNull(ProcessorContext.takeFailedProcessor());
        } finally {
            ProcessorContext.clearFailure();
            ProcessorContext.removeCurrentProcessor();
        }
    }

    @Test void previousFailureCannotBlameAndRedeliverAnAlreadySuccessfulPeer() {
        var queue = new OneToOneConcurrentArrayQueue<Object>(8);
        var reader = new EventQueueToEventProcessorAgent(queue, new UninstrumentedSingleTargetStrategy(), "clear-probe");
        var peerCalls = new AtomicInteger();
        var throwerCalls = new AtomicInteger();
        DataFlow peer = event -> peerCalls.incrementAndGet();
        reader.registerProcessor(peer);
        reader.registerProcessor(event -> { throwerCalls.incrementAndGet(); throw new IllegalStateException("current failure"); });
        ProcessorContext.recordFailedProcessor(peer);
        try {
            queue.offer("input");
            assertDoesNotThrow(reader::doWork);
            assertEquals(1, peerCalls.get(), "a stale identity must never cause a successful peer to be retried");
            assertEquals(1, throwerCalls.get());
        } finally {
            ProcessorContext.clearFailure();
        }
    }

    @Test void successfulDispatchDoesNotAllocateFailureContextPerEvent() {
        var queue = new OneToOneConcurrentArrayQueue<Object>(8);
        var reader = new EventQueueToEventProcessorAgent(queue, new EventToOnEventInvokeStrategy(), "allocation-probe");
        var handled = new AtomicInteger();
        reader.registerProcessor(event -> handled.incrementAndGet());
        Object event = "preallocated";
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported(), "JVM must support allocation measurement");
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 20_000; i++) { queue.offer(event); reader.doWork(); }
        int iterations = 200_000;
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < iterations; i++) { queue.offer(event); reader.doWork(); }
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        assertEquals(iterations + 20_000, handled.get());
        // A null ThreadLocal entry installed and removed per event costs several MB here. Allow incidental
        // one-time allocation while rejecting recurring allocation; this is not a machine-speed threshold.
        assertTrue(allocated < 256_000, "successful dispatch allocated " + allocated + " bytes across " + iterations + " events");
    }
}
