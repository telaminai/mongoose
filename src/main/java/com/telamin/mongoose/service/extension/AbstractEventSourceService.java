/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service.extension;

import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.fluxtion.runtime.input.NamedFeed;
import com.fluxtion.runtime.input.SubscriptionManager;
import com.fluxtion.runtime.node.EventSubscription;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.*;
import com.telamin.mongoose.service.scheduler.SchedulerService;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Base class for event source services that participate in Fluxtion's event flow.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Register the service as an {@link EventSource} with the event flow manager</li>
 *   <li>Manage subscription keys and subscription lifecycle for processors</li>
 *   <li>Expose knobs for event wrapping, slow-consumer handling, and data mapping</li>
 * </ul>
 * <p>
 * Subclasses typically:
 * <ul>
 *   <li>Construct with a unique service name and desired callback type</li>
 *   <li>Publish data via the configured {@link EventToQueuePublisher} obtained in {@link #setEventFlowManager}</li>
 *   <li>Call {@link #subscribe()} when a processor should begin receiving events</li>
 * </ul>
 *
 * @param <T> event type emitted by this source
 */
@Log
public abstract class AbstractEventSourceService<T>
        implements
        NamedFeed,
        LifeCycleEventSource<T> {

    /**
     * Unique service name for this event source.
     */
    @Getter
    @Setter
    protected String name;
    /**
     * Callback type used to map published events to subscriber invocations.
     */
    private final CallBackType eventToInvokeType;
    /**
     * Optional supplier for a custom event-to-invoke mapping strategy.
     */
    private final Supplier<EventToInvokeStrategy> eventToInokeStrategySupplier;
    /**
     * Publisher provided by the EventFlowManager used to emit events to subscribers.
     */
    protected EventToQueuePublisher<T> output;
    /**
     * Logical service name as registered with the EventFlowManager.
     */
    protected String serviceName;
    /**
     * Subscription key used by processors to subscribe to this source.
     */
    protected EventSubscriptionKey<T> subscriptionKey;
    /**
     * Scheduler service injected at runtime for time-based operations.
     */
    protected SchedulerService scheduler;
    private EventWrapStrategy eventWrapStrategy = EventWrapStrategy.SUBSCRIPTION_NOWRAP;
    private SlowConsumerStrategy slowConsumerStrategy = SlowConsumerStrategy.BACKOFF;
    @Getter(AccessLevel.PROTECTED)
    private Function<T, ?> dataMapper = Function.identity();

    /**
     * Construct an event source with default ON_EVENT callback type.
     *
     * @param name unique service name
     */
    protected AbstractEventSourceService(String name) {
        this(name, CallBackType.ON_EVENT_CALL_BACK);
    }

    /**
     * Construct an event source with an explicit callback type.
     *
     * @param name              unique service name
     * @param eventToInvokeType callback type for event delivery
     */
    public AbstractEventSourceService(String name, CallBackType eventToInvokeType) {
        this(name, eventToInvokeType, null);
    }

    /**
     * Construct an event source with explicit callback and mapping strategy.
     *
     * @param name                         unique service name
     * @param eventToInvokeType            callback type for event delivery
     * @param eventToInokeStrategySupplier optional supplier for custom event mapping
     */
    public AbstractEventSourceService(
            String name,
            CallBackType eventToInvokeType,
            Supplier<EventToInvokeStrategy> eventToInokeStrategySupplier) {
        this.name = name;
        this.eventToInvokeType = eventToInvokeType;
        this.eventToInokeStrategySupplier = eventToInokeStrategySupplier;
    }

    @Override
    public void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName) {
        this.serviceName = serviceName;
        output = eventFlowManager.registerEventSource(serviceName, this);
        output.setEventWrapStrategy(eventWrapStrategy);
        output.setDataMapper(dataMapper);
        subscriptionKey = new EventSubscriptionKey<>(
                new EventSourceKey<>(serviceName),
                eventToInvokeType
        );

        if (eventToInokeStrategySupplier != null) {
            eventFlowManager.registerEventMapperFactory(eventToInokeStrategySupplier, eventToInvokeType);
        }
    }

    /**
     * Injection point for the shared scheduler service.
     *
     * @param scheduler the scheduler service instance provided by the server
     */
    @ServiceRegistered
    public void scheduler(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void init() {

    }

    /**
     * Subscribe the current processor (from ProcessorContext) to this event source.
     * If no processor is present in context, logs a warning and does nothing.
     */
    public void subscribe() {
        var current = com.telamin.mongoose.dispatch.ProcessorContext.currentProcessor();
        log.info("adding subscription for service '" + serviceName + "' to '" + current + "'");
        if (current == null) {
            log.warning("subscribe called with no current processor in context; skipping subscription for service '" + serviceName + "'");
            return;
        }
        SubscriptionManager subscriptionManager = current.getSubscriptionManager();
        subscriptionManager.subscribe(subscriptionKey);
    }

    @Override
    public void tearDown() {

    }

    @Override
    public void registerSubscriber(StaticEventProcessor subscriber) {
        if (eventWrapStrategy == EventWrapStrategy.BROADCAST_NOWRAP || eventWrapStrategy == EventWrapStrategy.BROADCAST_NAMED_EVENT) {
            log.info("registerSubscriber for broadcast receive " + subscriber);
            subscribe();
        }
    }

    @Override
    public void subscribe(StaticEventProcessor subscriber, EventSubscription<?> eventSubscription) {
        log.info("subscribe request for " + eventSubscription + " from " + subscriber);
        if (serviceName.equals(eventSubscription.filterString())) {
            log.info("subscribe request for " + eventSubscription + " from " + subscriber
                    + " matches service name:" + serviceName);
            subscribe();
        } else {
            log.info("ignoring subscribe request for " + eventSubscription + " from " + subscriber
                    + " does not match service name:" + serviceName);
        }
    }

    @Override
    public void unSubscribe(StaticEventProcessor subscriber, EventSubscription<?> eventSubscription) {
        subscriber.getSubscriptionManager().unSubscribe(subscriptionKey);
    }

    @Override
    public void removeAllSubscriptions(StaticEventProcessor subscriber) {
        //do nothing
    }

    @Override
    public void setEventWrapStrategy(EventWrapStrategy eventWrapStrategy) {
        this.eventWrapStrategy = eventWrapStrategy;
        if (output != null) {
            output.setEventWrapStrategy(eventWrapStrategy);
        }
    }

    @Override
    public void setSlowConsumerStrategy(SlowConsumerStrategy slowConsumerStrategy) {
        this.slowConsumerStrategy = slowConsumerStrategy;
    }

    @Override
    public void setDataMapper(Function<T, ?> dataMapper) {
        this.dataMapper = dataMapper;
        if (output != null) {
            output.setDataMapper(dataMapper);
        }
    }
}
