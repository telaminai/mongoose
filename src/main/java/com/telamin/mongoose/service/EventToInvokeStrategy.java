/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

import com.telamin.fluxtion.runtime.DataFlow;

/**
 * Defines a strategy for processing events and dispatching them to {@link DataFlow} instances.
 * Implementations of this interface manage the registration and deregistration of processors,
 * as well as invoking the appropriate processing logic for incoming events.
 */
public interface EventToInvokeStrategy {

    /**
     * Process an incoming event and dispatch it to registered processors.
     *
     * @param event the event to process
     */
    void processEvent(Object event);

    /**
     * Process an incoming event with an explicit timestamp and dispatch it to registered processors.
     * Implementations may use the time to set a synthetic clock for processors.
     *
     * @param event the event to process
     * @param time  the time associated with the event (units defined by implementation)
     */
    void processEvent(Object event, long time);

    /**
     * Register a processor as a target for dispatched events.
     *
     * @param eventProcessor the processor to register
     */
    void registerProcessor(DataFlow eventProcessor);

    /**
     * Deregister a processor so it no longer receives dispatched events.
     *
     * @param eventProcessor the processor to deregister
     */
    void deregisterProcessor(DataFlow eventProcessor);

    /**
     * Return the number of currently registered processors.
     *
     * @return number of listeners
     */
    int listenerCount();
}
