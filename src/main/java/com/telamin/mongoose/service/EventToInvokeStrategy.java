/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.service;

import com.telamin.fluxtion.runtime.DataFlow;

import java.util.Collection;
import java.util.Collections;

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
     * Replay RECORD: process as {@link #processEvent(Object)} does, calling {@code beforeEach} with each processor and
     * the event just before that processor is given it, so the recording can copy what each one actually received
     * (with fan-out, a later processor receives what an earlier one's handler left). Replay OFF never calls it.
     *
     * <p>This default cannot see inside {@link #processEvent(Object)}, so it cannot say what a later processor received.
     * With one registered processor the event it is given IS what that processor receives, and it is recorded. With
     * more, it FAILS CLOSED (re-review N4): each is given an {@link com.telamin.mongoose.replay.UncapturedInput} naming
     * this strategy, so the recording marks the input failed instead of recording a copy that may not be what the
     * processor received. The live dispatch is exactly {@code processEvent(event)}. A fan-out strategy that should be
     * recordable overrides this, as {@code AbstractEventToInvocationStrategy} does.
     */
    default void processEventRecording(Object event, java.util.function.BiConsumer<DataFlow, Object> beforeEach) {
        Collection<DataFlow> targets = registeredProcessors();
        Object given = targets.size() <= 1 ? event : new com.telamin.mongoose.replay.UncapturedInput(getClass().getName()
                + " dispatches to " + targets.size() + " processors through the default processEventRecording, which cannot"
                + " say what each received; override it to record this fan-out");
        for (DataFlow target : targets) beforeEach.accept(target, given);
        processEvent(event);
    }

    /**
     * Retry support (mongoose#53): the position, in dispatch order, of the processor whose handler threw out of the last
     * {@code processEvent}, {@code processEventRecording} or {@code ...From} call on this strategy, or -1 when the
     * strategy cannot say. With a position the agent retries that processor alone and then resumes with the processors
     * after it ({@link #processEventFrom}), so a processor that already handled the event never sees it again. With -1
     * (this default) the agent retries the whole call, as it did before #53, and every processor sees the event again.
     * Read only on the agent thread, straight after the call threw.
     */
    default int failedPosition() {
        return -1;
    }

    /**
     * Retry support (mongoose#53): the processor at {@link #failedPosition()}, or null when the strategy cannot say.
     * The recording marks this processor's input failed, and only this one's.
     */
    default DataFlow failedProcessor() {
        return null;
    }

    /**
     * Retry support (mongoose#53): deliver {@code event} as {@link #processEvent(Object)} does, but only to the
     * processors at dispatch position {@code from} and after. The agent calls it only when {@link #failedPosition()}
     * named a position, so a strategy that keeps the -1 default never receives it.
     */
    default void processEventFrom(Object event, int from) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot resume a dispatch at a processor");
    }

    /**
     * Retry support (mongoose#53): as {@link #processEventRecording}, but only to the processors at dispatch position
     * {@code from} and after. Called only when {@link #failedPosition()} named a position.
     */
    default void processEventRecordingFrom(Object event, java.util.function.BiConsumer<DataFlow, Object> beforeEach, int from) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot resume a dispatch at a processor");
    }

    /**
     * Set {@code target}'s synthetic clock to {@code time}, as {@link #processEvent(Object, long)} does for each
     * processor before it dispatches. Replay recording uses it to give a processor that is NOT recorded the same clock
     * a {@code ReplayRecord} input gives it today, while a recorded one keeps its recording clock.
     */
    default void setSyntheticTime(DataFlow target, long time) {
        target.setClockStrategy(() -> time);
    }

    /**
     * Deliver {@code event} to ONE registered processor, as {@link #processEvent(Object)} would deliver it to each
     * (spec-replay-recording R5: a replay delivers each recorded input to the processor that received it, alone).
     * The caller owns the processor's clock.
     *
     * @throws IllegalArgumentException when {@code target} is not registered with this strategy
     */
    default void processEventFor(DataFlow target, Object event) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot deliver to a single processor");
    }

    /**
     * REPLAY: stop delivering live inputs to {@code target}, which receives only its replay through
     * {@link #processEventFor}. A strategy that cannot mute refuses, so a replay is never silently mixed with live input.
     */
    default void muteLive(DataFlow target) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot mute a processor's live inputs for a replay");
    }

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

    /**
     * Return a read-only view of the processors currently registered as
     * targets for dispatch. Returned for introspection only — callers must
     * not assume modification of the returned collection affects subscription
     * state. The default implementation returns an empty collection so
     * pre-existing custom strategies remain wire-compatible.
     *
     * @return read-only collection of registered processors
     */
    default Collection<DataFlow> registeredProcessors() {
        return Collections.emptyList();
    }
}
