/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frames an action on a data processor in the processor's own audit: as its own event cycle, through
 * {@link DataFlow#runInEventCycle}, when the processor implements it; otherwise the action runs unframed. Used for
 * admin lambdas. Service delivery and audit-listener swaps are not framed: Fluxtion already audits each as a cycle of
 * its own (an exported service call, an {@code EventLogControlEvent}).
 */
public final class ControlFraming {

    private static final Map<Class<?>, Boolean> IMPLEMENTS_THE_CYCLE = new ConcurrentHashMap<>();

    private ControlFraming() {
    }

    /** Run {@code action} on {@code processor}, framed by {@code frame} where the processor supports it. */
    public static void frame(DataFlow processor, Object frame, Runnable action) {
        if (processor != null && implementsTheCycle(processor.getClass())) {
            processor.runInEventCycle(frame, action);
        } else {
            action.run();
        }
    }

    /**
     * Whether {@code type} implements runInEventCycle, rather than inheriting the interface default that refuses (a
     * processor generated before fluxtion runtime 1.1.0): decided once per class, from the method itself.
     */
    public static boolean implementsTheCycle(Class<?> type) {
        return IMPLEMENTS_THE_CYCLE.computeIfAbsent(type, c -> {
            try {
                return !c.getMethod("runInEventCycle", Object.class, Runnable.class).isDefault();
            } catch (NoSuchMethodException predatesTheRuntime) {
                return false;
            }
        });
    }
}
