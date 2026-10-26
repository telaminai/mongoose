package com.telamin.mongoose.review77ext;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IdentityOnlyRecoveryFollowupTest {
    static class CustomStrategy extends EventToOnEventInvokeStrategy {
        final boolean identify;
        CustomStrategy(boolean identify) { this.identify=identify; }
        @Override public void processEvent(Object event) {
            for(DataFlow processor : eventProcessorSinks) {
                try { processor.onEvent(event); }
                catch(Throwable failure) {
                    if(identify) ProcessorContext.recordFailedProcessor(processor);
                    throw failure;
                }
            }
        }
        @Override public int failedPosition() { return -1; }
        @Override public DataFlow failedProcessor() { return null; }
    }
    @Test void identityOnlyHandwrittenFailureMustNotRedeliverHealthyPeers() {
        run(true,1);
    }
    @Test void uninstrumentedHandwrittenStrategySkipsWithoutPeerRedelivery() {
        run(false,1);
    }
    @Test void identityOnlyGeneratedFailureSkipsRemainingSubscribers() {
        var before=new AtomicInteger(); var after=new AtomicInteger();
        var queue=new OneToOneConcurrentArrayQueue<Object>(8);
        var reader=new EventQueueToEventProcessorAgent(queue,new CustomStrategy(true),"review-generated","feed");
        reader.registerProcessor(event -> before.incrementAndGet());
        reader.registerProcessor(new OpaquePeerRedeliveryTest.BrokenGenerated());
        reader.registerProcessor(event -> after.incrementAndGet());
        queue.offer(new com.telamin.mongoose.control.event.RunIntent(1));
        assertEquals(1,reader.doWork());
        assertEquals(1,before.get()); assertEquals(0,after.get());
    }
    static void run(boolean identify,int expectedHealthyCalls) {
        var healthy=new AtomicInteger(); var failing=new AtomicInteger();
        var queue=new OneToOneConcurrentArrayQueue<Object>(8);
        var reader=new EventQueueToEventProcessorAgent(queue,new CustomStrategy(identify),"review","feed");
        reader.registerProcessor(event -> healthy.incrementAndGet());
        reader.registerProcessor(event -> {
            if(failing.incrementAndGet()==1) throw new IllegalStateException("first attempt fails");
        });
        queue.offer("input");
        assertEquals(1,reader.doWork());
        assertEquals(expectedHealthyCalls,healthy.get(),"identity-only contract skips remaining input rather than redispatching healthy peers");
    }
}

