/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.BusySpinIdleStrategy;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.EventProcessorConfig;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates building a custom EventToInvokeStrategy by extending
 * AbstractEventToInvocationStrategy. This custom strategy:
 * - Only accepts processors that implement the MarkerProcessor interface (isValidTarget filter)
 * - On dispatch, forwards only String events and uppercases them before invoking onEvent
 * - Ensures ProcessorContext is set correctly when invoking target processors
 */
public class CustomEventToInvokeStrategyTest {

    /**
     * Marker interface to be used by the strategy to filter valid targets. Now includes a strongly-typed callback.
     */
    interface MarkerProcessor {
        void onString(String s);
    }

    /**
     * Test StaticEventProcessor that records received events and asserts ProcessorContext correctness.
     */
    static class RecordingProcessor implements StaticEventProcessor, MarkerProcessor {
        final List<Object> received = new ArrayList<>();
        final List<Object> receivedOnEvent = new ArrayList<>();
        StaticEventProcessor seenCurrentProcessor;

        @Override
        public void onString(String s) {
            // ProcessorContext should point to this processor during dispatch
            System.out.println("Received: " + s + "");
            seenCurrentProcessor = ProcessorContext.currentProcessor();
            received.add(s);
        }

        @Override
        public void onEvent(Object event) {
            // capture onEvent deliveries to prove dual-callback behavior with custom CallBackType
            System.out.println("Received onEvent: " + event + "");
            receivedOnEvent.add(event);
        }

        @Override
        public String toString() {
            return "RecordingProcessor{" + Integer.toHexString(System.identityHashCode(this)) + "}";
        }
    }

    /**
     * A processor that should be rejected by isValidTarget.
     */
    static class NonMarkedProcessor implements StaticEventProcessor {
        final List<Object> received = new ArrayList<>();

        @Override
        public void onEvent(Object event) {
            received.add(event);
        }
    }

    /**
     * Custom strategy implementation showing filtering and transformation, invoking a strongly-typed callback.
     */
    static class UppercaseStringStrategy extends AbstractEventToInvocationStrategy {
        @Override
        protected void dispatchEvent(Object event, StaticEventProcessor eventProcessor) {
            if (event instanceof String s && eventProcessor instanceof MarkerProcessor marker) {
                marker.onString(s.toUpperCase());
            } else {
                //normal dispatch to onEvent
                eventProcessor.onEvent(event);
            }
        }

        @Override
        protected boolean isValidTarget(StaticEventProcessor eventProcessor) {
            return eventProcessor instanceof MarkerProcessor;
        }
    }

    @Test
    void customStrategy_filtersTargets_transformsEvents_and_setsProcessorContext() throws Exception {
        // Arrange the flow and register the custom strategy for ON_EVENT_CALL_BACK
        EventFlowManager flow = new EventFlowManager();
        flow.registerEventMapperFactory(UppercaseStringStrategy::new, CallBackType.ON_EVENT_CALL_BACK);

        // Create an event source and subscribe a consumer via mapping agent
        String sourceName = "testSource";
        TestEventSource source = new TestEventSource();
        EventToQueuePublisher<Object> publisher = flow.registerEventSource(sourceName, source);

        Agent subscriber = new TestAgent();
        var mappingAgent = flow.getMappingAgent(new EventSourceKey<>(sourceName), CallBackType.ON_EVENT_CALL_BACK, subscriber);

        // Register processors: one accepted, one rejected
        RecordingProcessor accepted = new RecordingProcessor();
        NonMarkedProcessor rejected = new NonMarkedProcessor();
        mappingAgent.registerProcessor(accepted);
        mappingAgent.registerProcessor(rejected);

        assertEquals(1, mappingAgent.listenerCount(), "Only the marked processor should be registered");

        // Act: publish a String and a non-String event through the source publisher
        publisher.publish("hello");
        publisher.publish(123);
        // Drive the agent to drain the queue
        int work1 = mappingAgent.doWork();
        int work2 = mappingAgent.doWork();
        assertTrue(work1 + work2 >= 2, "Expected the agent to process at least two enqueued items");

        // Assert: accepted processor received the transformed String and saw itself in ProcessorContext
        assertEquals(List.of("HELLO"), accepted.received, "String should be uppercased and delivered");
        assertSame(accepted, accepted.seenCurrentProcessor, "ProcessorContext should point to the target during dispatch");

        // Rejected processor must not be called at all
        assertTrue(rejected.received.isEmpty(), "Rejected processor should never receive events");
    }

    // Minimal test doubles for EventSource and Agent to wire a mapping agent
    static class TestEventSource implements EventSource<Object> {
        final List<EventSubscriptionKey<Object>> subscriptions = new ArrayList<>();

        @Override
        public void subscribe(EventSubscriptionKey<Object> eventSourceKey) {
            subscriptions.add(eventSourceKey);
        }

        @Override
        public void unSubscribe(EventSubscriptionKey<Object> eventSourceKey) {
            subscriptions.remove(eventSourceKey);
        }

        @Override
        public void setEventToQueuePublisher(EventToQueuePublisher<Object> targetQueue) {
            // not used directly in this test
        }
    }

    static class TestAgent implements Agent {
        @Override
        public int doWork() {
            return 0;
        }

        @Override
        public String roleName() {
            return "test-agent";
        }
    }

    // --- Fluent builder API server boot example ---
    @Test
    void fluentBuilder_bootsServer_and_applies_custom_strategy() throws Exception {
        // Build a minimal MongooseServerConfig with one feed and one processor using the fluent builder API
        var eventSource = new com.telamin.mongoose.connector.memory.InMemoryEventSource<Object>();

        // Create a processor that subscribes to the feed via EventProcessor.start() and records uppercased strings
        var processor = new FluentRecordingProcessor("fluentEventFeed");

        // Processor group
        var processorGroup = com.telamin.mongoose.config.EventProcessorGroupConfig.builder()
                .agentName("fluent-group")
                .idleStrategy(new BusySpinIdleStrategy())
                .put("fluent-processor", EventProcessorConfig.<FluentRecordingProcessor>builder().handler(processor).build())
                .build();

        // Event feed config
        var feedCfg = com.telamin.mongoose.config.EventFeedConfig.builder()
                .instance(eventSource)
                .name("fluentEventFeed")
                .agent("fluent-agent", new BusySpinIdleStrategy())
                .broadcast(true)
                .wrapWithNamedEvent(false)
                .build();

        // Build MongooseServerConfig
        var appConfig = MongooseServerConfig.builder()
                .addProcessorGroup(processorGroup)
                .addEventFeed(feedCfg)
                .onEventInvokeStrategy(UppercaseStringStrategy::new)
                .build();

        // Boot server which will register our custom EventToInvokeStrategy from config
        var server = MongooseServer.bootServer(appConfig, rec -> {
        });
        try {

            // Publish both a String and a non-String event
            eventSource.offer("hello");
            eventSource.offer(42);

            // Spin-wait briefly for async processing
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < end && processor.received.size() < 1) {
                Thread.sleep(10);
            }

            // Assert our processor received the transformed string via the custom strategy
            assertEquals(List.of("HELLO"), processor.received);
            // And it still received the raw events via onEvent (demonstrates dual subscription)
            assertFalse(processor.receivedOnEvent.contains("hello"));
            assertTrue(processor.receivedOnEvent.contains(42));
            assertSame(processor, processor.seenCurrentProcessor);
        } finally {
            server.stop();
        }
    }

    // Processor used with the fluent builder server boot example
    static class FluentRecordingProcessor extends RecordingProcessor implements com.fluxtion.runtime.EventProcessor<FluentRecordingProcessor> {
        private final List<EventFeed> feeds = new ArrayList<>();
        private final String feedName;

        FluentRecordingProcessor(String feedName) {
            this.feedName = feedName;
        }

        @Override
        public void addEventFeed(EventFeed eventFeed) {
            feeds.add(eventFeed);
        }

        @Override
        public void init() {
        }

        @Override
        public void start() {
            var onEventKey = EventSubscriptionKey.onEvent(feedName);
            var customKey = EventSubscriptionKey.of(feedName, CallBackType.forClass(MarkerProcessor.class));
            feeds.forEach(f -> {
                f.subscribe(this, onEventKey);
            });
        }

        @Override
        public void tearDown() {
        }
    }
}
