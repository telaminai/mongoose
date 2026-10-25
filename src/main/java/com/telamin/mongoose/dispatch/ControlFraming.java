/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frames a control action on a data processor in the processor's own audit (design "Record schema"): as its own event
 * cycle, through {@link DataFlow#runInEventCycle}, when the processor implements it, so the action is one audit record
 * and no synthetic event reaches the processor's handlers; otherwise the action runs unframed, as before. Framing never
 * suppresses the action: the cycle's {@code try}/{@code finally} runs it once either way.
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
