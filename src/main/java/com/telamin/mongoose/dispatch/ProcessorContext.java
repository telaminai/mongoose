/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.fluxtion.runtime.StaticEventProcessor;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds per-thread reference to the current StaticEventProcessor being invoked.
 * <p>
 * This class exists to reduce coupling between components that previously
 * depended on EventFlowManager for accessing a thread-local current processor.
 * Use this instead of EventFlowManager's static current-processor methods.
 */
public final class ProcessorContext {

    private static final ThreadLocal<AtomicReference<StaticEventProcessor>> CURRENT = new ThreadLocal<>();

    private ProcessorContext() {
    }

    public static void setCurrentProcessor(StaticEventProcessor eventProcessor) {
        AtomicReference<StaticEventProcessor> ref = CURRENT.get();
        if (ref == null) {
            ref = new AtomicReference<>(eventProcessor);
            CURRENT.set(ref);
        } else {
            ref.set(eventProcessor);
        }
    }

    public static void removeCurrentProcessor() {
        AtomicReference<StaticEventProcessor> ref = CURRENT.get();
        if (ref != null) {
            ref.set(null);
        }
    }

    public static StaticEventProcessor currentProcessor() {
        AtomicReference<StaticEventProcessor> ref = CURRENT.get();
        return ref == null ? null : ref.get();

    }
}
