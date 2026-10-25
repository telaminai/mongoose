/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.callback.InternalEventProcessor;
import org.agrona.concurrent.AgentTerminationException;

/**
 * A generated graph threw after potentially changing its processing flags, callbacks or application state.
 * Its owner must isolate the processor (or terminate an opaque dispatch group); a normal return from a retry cannot establish recovery.
 */
public final class ProcessorDispatchException extends AgentTerminationException {
    private final DataFlow processor;
    private final String phase;

    public ProcessorDispatchException(DataFlow processor, String phase, Throwable cause) {
        super("generated processor execution failed during " + phase + "; processor="
                + (processor == null ? "unknown (opaque dispatch: the failed target cannot be identified)"
                : processor.getClass().getName())
                + "; processor requires isolation; no retry or automatic recovery"
                + (cause == null ? "" : "; cause: " + cause), cause);
        this.processor = processor;
        this.phase = phase;
    }

    public DataFlow processor() { return processor; }
    public String phase() { return phase; }

    /** Generated and interpreted Fluxtion graphs implement this runtime interface. No reflection or flag reset. */
    public static boolean requiresIsolation(DataFlow processor) {
        return processor instanceof InternalEventProcessor;
    }
}
