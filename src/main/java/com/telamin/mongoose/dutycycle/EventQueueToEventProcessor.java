/*
 * SPDX-FileCopyrightText: © 2024 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import org.agrona.concurrent.Agent;

/**
 * Reads from an event queue and invokes callbacks on registered {@link DataFlow}'s. Acts as a multiplexer
 * for an event queue to registered DataFlow
 */
@Experimental
public interface EventQueueToEventProcessor extends Agent {

    int registerProcessor(DataFlow eventProcessor);

    int deregisterProcessor(DataFlow eventProcessor);

    int listenerCount();

    /**
     * Read-only snapshot of the processors currently subscribed to this queue,
     * for introspection (admin UIs, diagnostics). The default returns an empty
     * collection so external implementations remain wire-compatible.
     */
    default java.util.Collection<DataFlow> subscribers() {
        return java.util.Collections.emptyList();
    }
}
