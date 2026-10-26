package com.telamin.mongoose.review77ext;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SoloRetryCompatibilityProbeTest {
    static class LiveOnlyStrategy implements EventToInvokeStrategy {
        final List<DataFlow> processors=new ArrayList<>();
        public void processEvent(Object e) {
            for(var p:processors) try { p.onEvent(e); }
            catch(Throwable t) { ProcessorContext.recordFailedProcessor(p); throw t; }
        }
        public void processEvent(Object e,long time) { processEvent(e); }
        public void registerProcessor(DataFlow p) { processors.add(p); }
        public void deregisterProcessor(DataFlow p) { processors.remove(p); }
        public int listenerCount() { return processors.size(); }
        public Collection<DataFlow> registeredProcessors() { return List.copyOf(processors); }
        // processEventFor is the public SPI's optional, unsupported-by-default replay capability.
    }
    static class HealthyGenerated extends GroupControlProcessor { @Override public void onEvent(Object event) {} }
    @Test void unsupportedSoloCapabilityMustNotBecomeAnUnidentifiedGeneratedFailure() {
        var q=new OneToOneConcurrentArrayQueue<Object>(8);
        var r=new EventQueueToEventProcessorAgent(q,new LiveOnlyStrategy(),"solo-compat","feed");
        var healthy=new AtomicInteger(); var attempts=new AtomicInteger();
        r.registerProcessor(e -> healthy.incrementAndGet());
        r.registerProcessor(e -> { if(attempts.incrementAndGet()==1) throw new IllegalStateException("handwritten first failure"); });
        r.registerProcessor(new HealthyGenerated());
        q.offer("input");
        assertDoesNotThrow(r::doWork,"known handwritten failure must not kill its group because an optional SPI method is unsupported");
    }
}

