package com.telamin.mongoose.review77ext;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.event.RunIntent;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.EventToOnEventInvokeStrategy;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
public class OpaquePeerRedeliveryTest {
    static EventSubscriptionKey<Object> key() { return new EventSubscriptionKey<>(new EventSourceKey<>("feed"), CallBackType.ON_EVENT_CALL_BACK); }
    static class Feed implements EventFlowService<RunIntent> {
        volatile EventToQueuePublisher<RunIntent> publisher;
        public void setEventToQueuePublisher(EventToQueuePublisher<RunIntent> p) { publisher = p; }
        public void subscribe(EventSubscriptionKey<RunIntent> key) {}
        public void unSubscribe(EventSubscriptionKey<RunIntent> key) {}
    }
    static class Healthy implements DataFlow {
        final AtomicInteger calls = new AtomicInteger(); EventFeed feed;
        public void init() {}
        public void addEventFeed(EventFeed f) { feed=f; }
        public void start() { feed.subscribe(this,key()); }
        public void onEvent(Object event) { if(event instanceof RunIntent) calls.incrementAndGet(); }
    }
    static class BrokenGenerated extends GroupControlProcessor {
        EventFeed feed;
        BrokenGenerated() { init(); }
        @Override public void addEventFeed(EventFeed f) { super.addEventFeed(f); feed=f; }
        @Override public void startComplete() { super.startComplete(); feed.subscribe(this,key()); }
        @Override public void onEvent(Object event) { if(event instanceof RunIntent) throw new IllegalStateException("generated throw"); super.onEvent(event); }
    }
    @Test void previouslySuccessfulPeerMustNotReceiveInputTwice() throws Exception {
        var config=new MongooseServerConfig(); config.setShutdownTimeoutMillis(500);
        var server=new MongooseServer(config);
        server.setDefaultErrorHandler(Throwable::printStackTrace);
        server.registerEventMapperFactory(() -> new EventToOnEventInvokeStrategy() {
            @Override public int failedPosition() { return -1; }
            @Override public DataFlow failedProcessor() { return null; }
        }, CallBackType.ON_EVENT_CALL_BACK);
        Feed feed=new Feed(); Healthy healthy=new Healthy(); BrokenGenerated broken=new BrokenGenerated();
        server.registerEventSource("feed",feed);
        server.addEventProcessor("healthy","g",null,()->healthy);
        server.addEventProcessor("broken","g",null,()->broken);
        server.init(); server.start();
        try {
            Thread.sleep(200);
            feed.publisher.publish(new RunIntent(1));
            long end=System.nanoTime()+3_000_000_000L;
            while(server.controlGroupStatus("g").quarantinedProcessors().isEmpty() && System.nanoTime()<end) Thread.sleep(5);
            assertTrue(server.controlGroupStatus("g").quarantinedProcessors().containsKey("broken"));
            Thread.sleep(50);
            assertEquals(1,healthy.calls.get(),"contract says healthy subscribers receive failed input once");
        } finally { server.stop(); }
    }
}

