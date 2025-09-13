/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

import com.telamin.mongoose.dispatch.EventToQueuePublisher;

import java.util.function.Function;

/**
 * Interface representing a source of events that supports subscription management
 * and event delivery customization. Defines methods to subscribe and unsubscribe
 * consumers of events, as well as to configure event handling strategies.
 *
 * @param <T> the type of event data managed by the event source
 */
@SuppressWarnings("EmptyMethod")
public interface EventSource<T> {

    enum EventWrapStrategy {SUBSCRIPTION_NOWRAP, SUBSCRIPTION_NAMED_EVENT, BROADCAST_NOWRAP, BROADCAST_NAMED_EVENT}

    enum SlowConsumerStrategy {DISCONNECT, EXIT_PROCESS, BACKOFF}

    /**
     * Subscribe to this event source with the given subscription key.
     *
     * @param eventSourceKey the subscription key representing the subscriber
     */
    void subscribe(EventSubscriptionKey<T> eventSourceKey);

    /**
     * Unsubscribe from this event source with the given subscription key.
     *
     * @param eventSourceKey the subscription key representing the subscriber
     */
    void unSubscribe(EventSubscriptionKey<T> eventSourceKey);

    /**
     * Provide the target queue/publisher that this source will publish events to.
     *
     * @param targetQueue the target publisher to emit events to
     */
    void setEventToQueuePublisher(EventToQueuePublisher<T> targetQueue);

    /**
     * Configure how events are wrapped when delivered to subscribers.
     *
     * @param eventWrapStrategy wrapping strategy
     */
    default void setEventWrapStrategy(EventWrapStrategy eventWrapStrategy) {
    }

    /**
     * Configure the strategy used when consumers cannot keep up with event rate.
     *
     * @param slowConsumerStrategy slow consumer handling strategy
     */
    default void setSlowConsumerStrategy(SlowConsumerStrategy slowConsumerStrategy) {
    }

    /**
     * Set a mapping function to transform outbound events before delivery.
     *
     * @param dataMapper mapping function from T -> outbound
     */
    default void setDataMapper(Function<T, ?> dataMapper) {
    }
}
