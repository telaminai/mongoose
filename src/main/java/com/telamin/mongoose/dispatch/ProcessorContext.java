/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds per-thread reference to the current DataFlow being invoked.
 * <p>
 * This class exists to reduce coupling between components that previously
 * depended on EventFlowManager for accessing a thread-local current processor.
 * Use this instead of EventFlowManager's static current-processor methods.
 */
public final class ProcessorContext {

    private static final ThreadLocal<AtomicReference<DataFlow>> CURRENT = new ThreadLocal<>();
    /**
     * The processor whose handler threw most recently on this thread and its position in the dispatch, captured at
     * throw time (rc1 review N1, external review 1).
     */
    private static final ThreadLocal<Failure> FAILED = new ThreadLocal<>();

    /**
     * A captured handler failure.
     *
     * @param position the thrower's index in the strategy's dispatch order, so the processors before it are known to
     *                 have handled the event; -1 when the dispatch progress is unknown
     */
    public record Failure(DataFlow processor, int position) { }

    private ProcessorContext() {
    }

    public static void setCurrentProcessor(DataFlow eventProcessor) {
        AtomicReference<DataFlow> ref = CURRENT.get();
        if (ref == null) {
            ref = new AtomicReference<>(eventProcessor);
            CURRENT.set(ref);
        } else {
            ref.set(eventProcessor);
        }
    }

    public static void removeCurrentProcessor() {
        AtomicReference<DataFlow> ref = CURRENT.get();
        if (ref != null) {
            ref.set(null);
        }
    }

    /**
     * Record that {@code processor}'s handler threw. A dispatch strategy calls it as the exception passes, so the host can
     * identify the failed processor even when the strategy's {@code failedPosition()} cannot (an opaque strategy).
     */
    public static void recordFailedProcessor(DataFlow processor) {
        recordFailedProcessor(processor, -1);
    }

    /** As {@link #recordFailedProcessor(DataFlow)}, with the thrower's position in the dispatch order. */
    public static void recordFailedProcessor(DataFlow processor, int position) {
        FAILED.set(new Failure(processor, position));
    }

    /** The failure recorded on this thread, or null; clears it. */
    public static Failure takeFailure() {
        Failure failure = FAILED.get();
        FAILED.remove();
        return failure;
    }

    /** The processor recorded by {@link #recordFailedProcessor} on this thread, or null; clears it. */
    public static DataFlow takeFailedProcessor() {
        Failure failure = takeFailure();
        return failure == null ? null : failure.processor();
    }

    public static DataFlow currentProcessor() {
        AtomicReference<DataFlow> ref = CURRENT.get();
        return ref == null ? null : ref.get();

    }
}
