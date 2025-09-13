/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessor;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.*;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;


/**
 * Manages the flow of events between event sources, sinks, and subscribers. This class is responsible
 * for registering event sources and sinks, managing subscriptions, and ensuring proper interaction
 * between event components through queues.
 * <p>
 * The {@code EventFlowManager} class supports operations for:
 * - Registering event sources that publish events.
 * - Registering event sinks to receive published events.
 * - Subscribing and unsubscribing to specific event flows.
 * - Managing the lifecycle of registered event sources.
 * - Creating mapping agents for specific event source and subscriber combinations.
 * - Logging and diagnosing the configuration of the event queues.
 * <p>
 * Thread Safety:
 * This class leverages {@link ConcurrentHashMap} for thread-safe
 * management of event sources, sinks, and subscriptions. Methods are designed to be thread-safe
 * for concurrent operations in a multi-threaded environment.
 * <p>
 * Key Responsibilities:
 * - Lifecycle events: Initialize and start registered event sources during their lifecycle.
 * - Event source registration: Allows the addition of event sources and their corresponding publishers.
 * - Event sink registration: Maps event sinks and readers for receiving event data.
 * - Subscription management: Handles event source subscriptions and unsubscriptions for a given key.
 * - Mapping agents: Creates mapping agents that manage the processing of events from sources to subscribers.
 * - Queue diagnostics: Appends configurations of event queues for debugging and tracing.
 */
public class EventFlowManager {

    private final ConcurrentHashMap<EventSourceKey<?>, EventSource_QueuePublisher<?>> eventSourceToQueueMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventSinkKey<?>, ManyToOneConcurrentArrayQueue<?>> eventSinkToQueueMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CallBackType, Supplier<EventToInvokeStrategy>> eventToInvokerFactoryMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventSourceKey_Subscriber<?>, OneToOneConcurrentArrayQueue<Object>> subscriberKeyToQueueMap = new ConcurrentHashMap<>();

    public EventFlowManager() {
        eventToInvokerFactoryMap.put(CallBackType.ON_EVENT_CALL_BACK, EventToOnEventInvokeStrategy::new);
    }

    public void init() {
        forEachLifeCycleEventSource(LifeCycleEventSource::init);
    }

    public void start() {
        forEachLifeCycleEventSource(LifeCycleEventSource::start);
    }

    @SuppressWarnings("unchecked")
    public <T> ManyToOneConcurrentArrayQueue<T> registerEventSink(EventSourceKey<T> sinkKey, Object sinkReader) {
        Objects.requireNonNull(sinkKey, "sinkKey must be non-null");
        EventSinkKey<T> eventSinkKey = new EventSinkKey<>(sinkKey, sinkReader);
        return (ManyToOneConcurrentArrayQueue<T>) eventSinkToQueueMap.computeIfAbsent(
                eventSinkKey,
                key -> new ManyToOneConcurrentArrayQueue<T>(1024));
    }

    @SuppressWarnings("unchecked")
    public void subscribe(EventSubscriptionKey<?> subscriptionKey) {
        Objects.requireNonNull(subscriptionKey, "subscriptionKey must be non-null");

        EventSource_QueuePublisher<?> eventSourceQueuePublisher = eventSourceToQueueMap.get(subscriptionKey.eventSourceKey());
        Objects.requireNonNull(eventSourceQueuePublisher, "no EventSource registered for EventSourceKey:" + subscriptionKey);
        eventSourceQueuePublisher.eventSource().subscribe((EventSubscriptionKey) subscriptionKey);
    }

    @SuppressWarnings("unchecked")
    public void unSubscribe(EventSubscriptionKey<?> subscriptionKey) {
        Objects.requireNonNull(subscriptionKey, "subscriptionKey must be non-null");

        EventSource_QueuePublisher<?> eventSourceQueuePublisher = eventSourceToQueueMap.get(subscriptionKey.eventSourceKey());
        Objects.requireNonNull(eventSourceQueuePublisher, "no EventSource registered for EventSourceKey:" + subscriptionKey);
        eventSourceQueuePublisher.eventSource().unSubscribe((EventSubscriptionKey) subscriptionKey);
    }

    @SuppressWarnings("unchecked")
    public <T> EventToQueuePublisher<T> registerEventSource(String sourceName, EventSource<T> eventSource) {
        Objects.requireNonNull(eventSource, "eventSource must be non-null");

        EventSource_QueuePublisher<?> eventSourceQueuePublisher = eventSourceToQueueMap.computeIfAbsent(
                new EventSourceKey<>(sourceName),
                eventSourceKey -> new EventSource_QueuePublisher<>(new EventToQueuePublisher<>(sourceName), eventSource));

        EventToQueuePublisher<T> queuePublisher = (EventToQueuePublisher<T>) eventSourceQueuePublisher.queuePublisher();
        eventSource.setEventToQueuePublisher(queuePublisher);
        return queuePublisher;
    }

    public void registerEventMapperFactory(Supplier<EventToInvokeStrategy> eventMapper, CallBackType type) {
        Objects.requireNonNull(eventMapper, "eventMapper must be non-null");
        Objects.requireNonNull(type, "type must be non-null");

        eventToInvokerFactoryMap.put(type, eventMapper);
    }

    public void registerEventMapperFactory(Supplier<EventToInvokeStrategy> eventMapper, Class<?> type) {
        Objects.requireNonNull(eventMapper, "eventMapper must be non-null");
        Objects.requireNonNull(type, "Callback class type must be non-null");

        registerEventMapperFactory(eventMapper, CallBackType.forClass(type));
    }

    public <T> EventQueueToEventProcessor getMappingAgent(EventSourceKey<T> eventSourceKey, CallBackType type, Agent subscriber) {
        Objects.requireNonNull(eventSourceKey, "eventSourceKey must be non-null");
        Objects.requireNonNull(type, "type must be non-null");
        Objects.requireNonNull(subscriber, "subscriber must be non-null");

        Supplier<EventToInvokeStrategy> eventMapperSupplier = eventToInvokerFactoryMap.get(type);
        Objects.requireNonNull(eventMapperSupplier, "no EventMapper registered for type:" + type);

        EventSource_QueuePublisher<T> sourcePublisher = getEventSourceQueuePublisherOrThrow(eventSourceKey);

        // create or re-use a target queue
        EventSourceKey_Subscriber<T> keySubscriber = new EventSourceKey_Subscriber<>(eventSourceKey, subscriber);
        OneToOneConcurrentArrayQueue<Object> eventQueue = getOrCreateSubscriberQueue(keySubscriber);

        // add as a target to the source
        String name = buildSubscriptionName(subscriber, eventSourceKey, type);
        sourcePublisher.queuePublisher().addTargetQueue(eventQueue, name);

        Runnable unsubscribe = createUnsubscribeAction(sourcePublisher, name, keySubscriber);

        return new EventQueueToEventProcessorAgent(eventQueue, eventMapperSupplier.get(), name)
                .withUnsubscribeAction(unsubscribe);
    }

    public <T> EventQueueToEventProcessor getMappingAgent(EventSubscriptionKey<T> subscriptionKey, Agent subscriber) {
        return getMappingAgent(subscriptionKey.eventSourceKey(), subscriptionKey.callBackType(), subscriber);
    }

    public void appendQueueInformation(Appendable appendable) {
        if (eventSourceToQueueMap.isEmpty()) {
            safeAppend(appendable, "No event readers registered");
            return;
        }
        eventSourceToQueueMap.forEach((key, value) -> appendQueueDetails(appendable, key.sourceName(), value.queuePublisher()));
    }

    private void forEachLifeCycleEventSource(java.util.function.Consumer<LifeCycleEventSource> action) {
        eventSourceToQueueMap.values().stream()
                .map(EventSource_QueuePublisher::eventSource)
                .filter(LifeCycleEventSource.class::isInstance)
                .map(LifeCycleEventSource.class::cast)
                .forEach(action);
    }

    private <T> EventSource_QueuePublisher<T> getEventSourceQueuePublisherOrThrow(EventSourceKey<T> eventSourceKey) {
        @SuppressWarnings("unchecked")
        EventSource_QueuePublisher<T> publisher = (EventSource_QueuePublisher<T>) eventSourceToQueueMap.get(eventSourceKey);
        return Objects.requireNonNull(publisher, "no EventSource registered for EventSourceKey:" + eventSourceKey);
    }

    private <T> OneToOneConcurrentArrayQueue<Object> getOrCreateSubscriberQueue(EventSourceKey_Subscriber<T> keySubscriber) {
        return subscriberKeyToQueueMap.computeIfAbsent(keySubscriber, key -> new OneToOneConcurrentArrayQueue<>(1024));
    }

    private static String buildSubscriptionName(Agent subscriber, EventSourceKey<?> eventSourceKey, CallBackType type) {
        return subscriber.roleName() + "/" + eventSourceKey.sourceName() + "/" + type.name();
    }

    private Runnable createUnsubscribeAction(EventSource_QueuePublisher<?> sourcePublisher, String name, EventSourceKey_Subscriber<?> keySubscriber) {
        return () -> {
            sourcePublisher.queuePublisher().removeTargetQueueByName(name);
            subscriberKeyToQueueMap.remove(keySubscriber);
        };
    }

    private static void safeAppend(Appendable appendable, String text) {
        try {
            appendable.append(text);
        } catch (IOException ex) {
            System.err.println("problem logging event queues, exception:" + ex);
        }
    }

    private static void appendQueueDetails(Appendable appendable, String sourceName, EventToQueuePublisher<?> queue) {
        try {
            appendable.append("eventSource:").append(sourceName)
                    .append("\n\treadQueues:\n");
            for (EventToQueuePublisher.NamedQueue q : queue.getTargetQueues()) {
                appendable.append("\t\t").append(q.name()).append(" -> ").append(q.targetQueue().toString()).append("\n");
            }
        } catch (IOException ex) {
            System.err.println("problem logging event queues, exception:" + ex);
        }
    }

    private record EventSource_QueuePublisher<T>(EventToQueuePublisher<T> queuePublisher, EventSource<T> eventSource) {
    }

    private record EventSourceKey_Subscriber<T>(EventSourceKey<T> eventSourceKey, Object subscriber) {
    }

    private record EventSinkKey<T>(EventSourceKey<T> eventSourceKey, Object subscriber) {
    }
}
