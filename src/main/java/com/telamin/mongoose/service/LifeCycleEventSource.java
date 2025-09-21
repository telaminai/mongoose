/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;

/**
 * Represents a specialized event source that integrates lifecycle management capabilities
 * in addition to event subscription and data publishing functionalities. This interface
 * combines the responsibilities of an {@link EventSource} for managing events and a
 * {@link Lifecycle} for providing initialization, start, and teardown mechanics.
 *
 * @param <T> the type of event data managed by the source
 * @see EventSource
 * @see Lifecycle
 * @see EventSubscriptionKey
 */
@Experimental
public interface LifeCycleEventSource<T> extends EventFlowService<T>, Lifecycle {

    @Override
    default void subscribe(EventSubscriptionKey<T> eventSourceKey) {

    }

    @Override
    default void unSubscribe(EventSubscriptionKey<T> eventSourceKey) {

    }

    @Override
    default void setEventToQueuePublisher(EventToQueuePublisher<T> targetQueue) {

    }
}
