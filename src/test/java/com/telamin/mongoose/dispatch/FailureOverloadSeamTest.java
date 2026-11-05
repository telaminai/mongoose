package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.control.ControlAuditSink;
import com.telamin.mongoose.control.ControlDriver;
import com.telamin.mongoose.control.event.EvidenceStatus;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.control.generated.GroupControlProcessor;
import com.telamin.mongoose.dispatch.*;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.exception.PublishRefusedException;
import com.telamin.mongoose.internal.ComposingEventProcessorAgentRunner;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.health.MongooseHealthService;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class FailureOverloadSeamTest {
    static void await(BooleanSupplier ready, String detail) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(2);
        assertTrue(ready.getAsBoolean(), detail);
    }
    @SuppressWarnings("unchecked")
    static ComposingEventProcessorAgent group(MongooseServer server) throws Exception {
        var field = MongooseServer.class.getDeclaredField("composingEventProcessorAgents"); field.setAccessible(true);
        return ((Map<String,ComposingEventProcessorAgentRunner>)field.get(server)).get("risk").group();
    }
    static class Feed implements EventFlowService<Object> {
        volatile EventToQueuePublisher<Object> publisher;
        public void setEventToQueuePublisher(EventToQueuePublisher<Object> p) { publisher = p; }
        public void subscribe(EventSubscriptionKey<Object> key) { }
        public void unSubscribe(EventSubscriptionKey<Object> key) { }
    }
    static final EventSubscriptionKey<Object> KEY = new EventSubscriptionKey<>(new EventSourceKey<>("orders"),CallBackType.ON_EVENT_CALL_BACK);
    static class Slow implements DataFlow {
        EventFeed feed;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final String failure;
        Slow(String failure) { this.failure = failure; }
        public void init() { }
        public void addEventFeed(EventFeed f) { feed = f; }
        public void start() { feed.subscribe(this,KEY); }
        public void onEvent(Object event) {
            if (!(event instanceof Integer)) return;
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture gate timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                if (failure.equals("fatal")) throw new StackOverflowError("containment seam injected fatal");
                if (failure.equals("quarantine")) throw new IllegalStateException("containment seam generated failure");
            }
        }
    }
    static class GeneratedSlow extends GroupControlProcessor {
        final Slow delegate = new Slow("quarantine"); EventFeed feed;
        @Override public void init() { }
        @Override public void addEventFeed(EventFeed f) { feed=f; }
        @Override public void start() { feed.subscribe(this,KEY); }
        @Override public void startComplete() { }
        @Override public void onEvent(Object event) { delegate.onEvent(event); }
    }
    static class Rig implements AutoCloseable {
        final MongooseServer server = new MongooseServer(new MongooseServerConfig());
        final Feed feed = new Feed(); final Slow slow;
        Rig(String mode, OverloadSettings settings) throws Exception {
            GeneratedSlow generated = new GeneratedSlow();
            slow = mode.equals("quarantine") ? generated.delegate : new Slow(mode);
            server.registerEventSource("orders",feed);
            server.setFeedOverload("orders",FeedOverloadSettings.of(settings));
            server.addEventProcessor("processor","risk",null,()-> mode.equals("quarantine") ? generated : slow);
            server.init(); server.start();
            await(()->feed.publisher!=null && !feed.publisher.getTargetQueues().isEmpty(),"subscription installed");
            feed.publisher.publish(0);
            assertTrue(slow.entered.await(5,TimeUnit.SECONDS));
            var queue=feed.publisher.getTargetQueues().getFirst().targetQueue();
            int capacity=queue.capacity();
            for(int i=0;i<capacity;i++) feed.publisher.publish(i+1);
            assertEquals(0,queue.remainingCapacity());
        }
        public void close() { slow.release.countDown(); server.shutdown(); }
    }
    @Test void refusalAndLossAuditCarryKindsAndDegradedEvidence() {
        var publisher = new EventToQueuePublisher<Object>("orders");
        publisher.setOverload(FeedOverloadSettings.of(OverloadSettings.blockWithDeadline(Duration.ofMillis(5))));
        var queue = new OneToOneConcurrentArrayQueue<Object>(2); queue.offer(1);queue.offer(2);
        var sink = new ControlAuditSink(); sink.processLogRecord(null);
        publisher.addTargetQueue(queue,"q",NoOpCountersService.INSTANCE.counter("unused"),sink,"risk","risk/orders");
        var refused=assertThrows(PublishRefusedException.class,()->publisher.publish(3));
        assertEquals(FailureKind.REFUSED_DEADLINE,refused.kind());
        assertEquals(EvidenceStatus.UNVERIFIED,refused.evidence());
        String record=sink.records().stream().filter(s->s.contains("record: OverloadState")).findFirst().orElseThrow();
        assertTrue(record.contains("OVERLOAD_LOSS"),record); assertTrue(record.contains("UNVERIFIED"),record);
        sink.acknowledgeEvidenceGap(); publisher.clearOverloadState();
        assertFalse(sink.isComplete(),"acknowledgement cannot repair prior evidence");
        String clear=sink.records().stream().filter(s->s.contains("record: OverloadState")).reduce((a,b)->b).orElseThrow();
        assertTrue(clear.contains("NONE"),clear); assertTrue(clear.contains("NOT_DEGRADED"),clear);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void overloadStatesAppearInGroupHealthAndJsonIncludingRemovedLatch(boolean blocking) throws Exception {
        try(var rig=new Rig("none",blocking ? OverloadSettings.blockWithDeadline(Duration.ofMillis(5)) : OverloadSettings.dropAfterSpin(Duration.ZERO))) {
            if(blocking) assertThrows(PublishRefusedException.class,()->rig.feed.publisher.publish(-1));
            else rig.feed.publisher.publish(-1);
            var status=rig.server.controlGroupStatus("risk");
            assertTrue(status.overloadStates().get("orders").containsValue(blocking ? "LATCHED" : "DEGRADED"));
            assertTrue(status.toJson().contains("\"overloadStates\""));
            assertTrue(status.toJson().contains(blocking ? "LATCHED" : "DEGRADED"));
            var health=(MongooseHealthService)rig.server.registeredServices().get(MongooseHealthService.SERVICE_NAME).instance();
            assertEquals(HealthStatus.Verdict.DEGRADED,health.statusOfCheck("group:risk","overload").verdict());
            if(blocking) {
                rig.slow.release.countDown();
                await(()->rig.slow.calls.get()>1,"reader resumed");
                rig.server.stopProcessor("risk","processor",2000);
                await(()->rig.server.controlGroupStatus("risk").overloadStates().get("orders").containsValue("REMOVED"),"removed latch visible");
                assertTrue(rig.server.controlGroupStatus("risk").toJson().contains("REMOVED"));
                assertEquals(1,rig.server.clearOverloadState("orders"));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"dead","fatal","quarantine"})
    void terminalFenceCancelsBlockedPublishBeforeDeadline(String mode) throws Exception {
        try(var rig=new Rig(mode,OverloadSettings.blockWithDeadline(Duration.ofSeconds(20)))) {
            var result=new AtomicReference<Throwable>();
            long before=rig.feed.publisher.getSequenceNumber();
            Thread publisher=new Thread(()->{try {rig.feed.publisher.publish(-42);}catch(Throwable e){result.set(e);}},"seam-blocked-publisher");
            publisher.start();
            try {
                await(()->publisher.getState()==Thread.State.TIMED_WAITING,"publisher parked awaiting room");
                if(mode.equals("dead")) group(rig.server).control().markDead("test dead control plane");
                rig.slow.release.countDown();
                publisher.join(2000);
                assertFalse(publisher.isAlive(),"terminal fence cancels well before 20 second deadline");
                var refused=assertInstanceOf(PublishRefusedException.class,result.get());
                assertEquals(PublishRefusedException.Reason.TARGET_UNAVAILABLE,refused.reason());
                assertEquals(FailureKind.REFUSED_STATE,refused.kind());
                assertEquals(before,rig.feed.publisher.getSequenceNumber(),"refusal never commits or duplicates input");
                if(mode.equals("quarantine")) {
                    await(()->rig.server.controlGroupStatus("risk").quarantinedProcessors().containsKey("processor"),"quarantine observed");
                    assertEquals(1,rig.slow.calls.get(),"backlog fenced after failed callback");
                } else await(()->groupState(rig.server)==ControlDriver.GroupState.DEAD,"group remains DEAD");
            } finally {publisher.interrupt();publisher.join(2000);}
        }
    }
    static ControlDriver.GroupState groupState(MongooseServer server) {return server.controlGroupStatus("risk").state();}
}
