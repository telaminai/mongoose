/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.concurrent.Agent;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.annotations.feature.Experimental;

/**
 * Reads from an event queue and invokes callbacks on registered {@link StaticEventProcessor}'s. Acts as a multiplexer
 * for an event queue to registered StaticEventProcessor
 */
@Experimental
public interface EventQueueToEventProcessor extends Agent {

    int registerProcessor(StaticEventProcessor eventProcessor);

    int deregisterProcessor(StaticEventProcessor eventProcessor);

    int listenerCount();
}
