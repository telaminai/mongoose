package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.config.HandlerPipeConfig;
import com.telamin.mongoose.dutycycle.ComposingEventProcessorAgent;
import com.telamin.mongoose.internal.AgronaCountersService;
import com.telamin.mongoose.internal.DefaultMongooseHealth;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.health.HealthStatus;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import com.telamin.mongoose.service.pool.impl.Pools;
import com.telamin.mongoose.service.scheduler.DeadWheelScheduler;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class QueueDropObservabilityTest {
    @Test void fullProcessorQueueCountsLossWithoutChangingTheDeliveredOrder() throws Exception {
        var counters = new AgronaCountersService();
        var health = new DefaultMongooseHealth(counters);
        var manager = new EventFlowManager();
        manager.setCountersService(counters); manager.setHealthService(health);
        var source = new Source();
        var publisher = manager.registerEventSource("feed", source);
        var slow = new ComposingEventProcessorAgent("slow", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var fast = new ComposingEventProcessorAgent("fast", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var key = new EventSourceKey<Integer>("feed");
        var slowReader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, slow);
        var fastReader = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, fast);
        List<Object> slowEvents = new ArrayList<>(), fastEvents = new ArrayList<>();
        DataFlow slowFlow = slowEvents::add, fastFlow = fastEvents::add;
        slowReader.registerProcessor(slowFlow); fastReader.registerProcessor(fastFlow);
        try {
            for (int i=0;i<1026;i++) { publisher.publish(i); fastReader.doWork(); }
            assertEquals(2, publisher.droppedCount());
            assertEquals(2, publisher.droppedCount("slow/feed/onEventCallBack"));
            assertEquals(0, publisher.droppedCount("fast/feed/onEventCallBack"));
            var values = new HashMap<String, Long>();
            counters.forEachCounter((id, label, value) -> values.put(label, value));
            assertEquals(2L, values.entrySet().stream().filter(e -> e.getKey().contains("/group/slow/")
                    && e.getKey().endsWith(".dropped")).findFirst().orElseThrow().getValue());
            assertEquals(0L, values.entrySet().stream().filter(e -> e.getKey().contains("/group/fast/")
                    && e.getKey().endsWith(".dropped")).findFirst().orElseThrow().getValue());
            assertEquals(HealthStatus.Verdict.DEGRADED, health.aggregatedVerdict("feed").verdict());
            assertEquals(1, publisher.getDropAudit().records().size());
            assertEquals(1, slow.control().audit().records().stream().filter(r -> r.contains("record: QueueDrop")).count());
            assertTrue(fast.control().audit().records().stream().noneMatch(r -> r.contains("record: QueueDrop")));
            String first = publisher.getDropAudit().records().getFirst();
            assertTrue(first.contains("sequence: 1025"));
            assertTrue(first.contains("group: " + com.telamin.mongoose.control.node.ControlAudit.quote("slow")), first);
            while(slowReader.doWork()>0) { }
            publisher.publish(1026); publisher.publish(1027);
            while(slowReader.doWork()>0) { }
            while(fastReader.doWork()>0) { }
            var expectedSlow = new ArrayList<Object>(IntStream.range(0,1024).boxed().toList());
            expectedSlow.add(1026); expectedSlow.add(1027);
            assertEquals(expectedSlow, slowEvents, "only the two dropped inputs may be absent");
            assertEquals(IntStream.range(0,1028).boxed().toList(), fastEvents, "fast queue keeps every input exactly once");
        } finally {
            slowReader.deregisterProcessor(slowFlow); fastReader.deregisterProcessor(fastFlow);
            slowReader.onClose(); fastReader.onClose();
        }
        assertEquals(HealthStatus.Verdict.UNKNOWN, health.aggregatedVerdict("feed").verdict(), "detached queue checks are removed");
    }

    @Test void disabledCountersAreUnknownButFirstDropEvidenceStillExists() throws Exception {
        var manager = new EventFlowManager();
        var health = new DefaultMongooseHealth(NoOpCountersService.INSTANCE); manager.setHealthService(health);
        var publisher = manager.registerEventSource("feed", new Source());
        var group = new ComposingEventProcessorAgent("slow", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var reader = manager.getMappingAgent(new EventSourceKey<Integer>("feed"), CallBackType.ON_EVENT_CALL_BACK, group);
        DataFlow flow = e -> { }; reader.registerProcessor(flow);
        try {
            for(int i=0;i<1025;i++) publisher.publish(i);
            assertEquals(1,publisher.droppedCount());
            assertEquals(HealthStatus.Verdict.UNKNOWN,health.aggregatedVerdict("feed").verdict());
            assertEquals(1,group.control().audit().records().stream().filter(r -> r.contains("record: QueueDrop")).count());
        } finally { reader.deregisterProcessor(flow); reader.onClose(); }
    }

    @Test void fullQueueReleasesDroppedPooledReferenceAndCountsLegacyReplayDrops() {
        var publisher = new EventToQueuePublisher<Object>("feed");
        var queue = new OneToOneConcurrentArrayQueue<Object>(2); publisher.addTargetQueue(queue,"slow");
        queue.offer("one"); queue.offer("two");
        var pool = Pools.SHARED.getOrCreate(Message.class, Message::new, m -> { }, 1);
        try {
            Message message=pool.acquire(); publisher.publish(message);
            assertEquals(0,message.tracker.currentRefCount()); assertEquals(1,pool.availableCount());
            var replay = new ReplayRecord(); replay.setEvent("replay"); replay.setWallClockTime(1);
            publisher.publishReplay(replay);
            assertEquals(2,publisher.droppedCount("slow"));
            assertEquals(1,publisher.getDropAudit().records().size(),"one first-drop record per binding");
            assertEquals("one",queue.poll()); assertEquals("two",queue.poll()); assertNull(queue.poll());
        } finally { Pools.SHARED.remove(Message.class); }
    }

    @Test void removingOneCallbackBindingKeepsTheOtherQueueHealthCheck() throws Exception {
        var counters = new AgronaCountersService();
        var health = new DefaultMongooseHealth(counters);
        var manager = new EventFlowManager(); manager.setCountersService(counters); manager.setHealthService(health);
        var other = CallBackType.forClass(Runnable.class);
        manager.registerEventMapperFactory(EventToOnEventInvokeStrategy::new, other);
        var publisher = manager.registerEventSource("feed", new Source());
        var group = new ComposingEventProcessorAgent("slow", manager, null, new DeadWheelScheduler(), new ConcurrentHashMap<>());
        var key = new EventSourceKey<Integer>("feed");
        var first = manager.getMappingAgent(key, CallBackType.ON_EVENT_CALL_BACK, group);
        var second = manager.getMappingAgent(key, other, group);
        DataFlow flow = e -> { }; first.registerProcessor(flow); second.registerProcessor(flow);
        try {
            first.deregisterProcessor(flow);
            for (int i=0; i<1025; i++) publisher.publish(i);
            assertEquals(1, publisher.droppedCount());
            assertEquals(HealthStatus.Verdict.DEGRADED, health.aggregatedVerdict("feed").verdict());
        } finally { second.deregisterProcessor(flow); first.onClose(); second.onClose(); }
        assertEquals(HealthStatus.Verdict.UNKNOWN, health.aggregatedVerdict("feed").verdict());
    }

    @Test void telemetryFailureDoesNotPreventHealthyQueueDelivery() {
        var publisher = new EventToQueuePublisher<Object>("feed");
        var slow = new OneToOneConcurrentArrayQueue<Object>(2);
        var fast = new OneToOneConcurrentArrayQueue<Object>(2);
        var failedCounter = new com.telamin.mongoose.service.counters.MongooseCounter() {
            public long increment() { throw new IllegalStateException("counter unavailable"); }
            public long incrementRelease() { return increment(); }
            public void setOrdered(long value) { }
            public long get() { return 0; }
        };
        publisher.addTargetQueue(slow,"slow",failedCounter,null,null,"slow");
        publisher.addTargetQueue(fast,"fast");
        slow.offer("one"); slow.offer("two");
        publisher.publish("next");
        assertEquals("next",fast.poll());
        assertEquals(1,publisher.droppedCount("slow"));
        assertEquals(1,publisher.getDropAudit().records().size());
    }

    @Test @SuppressWarnings("deprecation") void ineffectiveStrategyApiIsExplicitlyDeprecated() throws Exception {
        assertTrue(EventSource.SlowConsumerStrategy.class.isAnnotationPresent(Deprecated.class));
        for(Class<?> type:List.of(EventSource.class,EventFeedConfig.class,HandlerPipeConfig.class))
            assertTrue(type.getMethod("setSlowConsumerStrategy",EventSource.SlowConsumerStrategy.class)
                    .isAnnotationPresent(Deprecated.class),type.getName());
    }

    static class Message implements PoolAware {
        final PoolTracker<Message> tracker=new PoolTracker<>();
        public PoolTracker<Message> getPoolTracker(){ return tracker; }
    }
    static class Source implements EventSource<Integer> {
        public void subscribe(EventSubscriptionKey<Integer> key) { }
        public void unSubscribe(EventSubscriptionKey<Integer> key) { }
        public void setEventToQueuePublisher(EventToQueuePublisher<Integer> publisher) { }
    }
}
