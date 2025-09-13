/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.service.extension.AbstractEventSourceService;

/**
 * Represents a service interface for managing event flows within a system.
 * Classes implementing this interface are responsible for registering and
 * managing event flow configurations using an {@link EventFlowManager}.
 * This interface allows for dynamic assignment of an event flow manager
 * with an associated service name.
 * <p>
 * Services implementing this interface typically:
 * <ul>
 *   <li>Register themselves as event sources with the event flow manager</li>
 *   <li>Manage event publishing and subscription lifecycles</li>
 *   <li>Support configuration of event wrapping and slow-consumer handling strategies</li>
 *   <li>Enable data mapping and transformation of published events</li>
 * </ul>
 *
 * @param <T> The type of events that this service publishes to subscribers
 * @see AbstractEventSourceService for a base implementation
 */
public interface EventFlowService<T> extends EventSource<T>{

    /**
     * Inject the event flow manager and logical service name into this service.
     *
     * @param eventFlowManager the event flow manager used to register and route events
     * @param serviceName      the unique service name under which this source will be registered
     */
    default void setEventFlowManager(EventFlowManager eventFlowManager, String serviceName){
        eventFlowManager.registerEventSource(serviceName, this);
    }
}
