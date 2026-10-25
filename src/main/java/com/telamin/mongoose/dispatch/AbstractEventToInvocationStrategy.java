/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import lombok.extern.java.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract class to simplify create an EventToInvokeStrategy, by implementing two methods:
 *
 * <ul>
 *     <li>isValidTarget - is an eventProcessor a suitable target for callbacks</li>
 *     <li>dispatchEvent - process the event and dispatch to target eventProcessor's</li>
 * </ul>
 */
@Experimental
@Log
public abstract class AbstractEventToInvocationStrategy implements EventToInvokeStrategy {

    /**
     * The set of registered target event processors that should receive callbacks from this strategy.
     */
    protected final List<DataFlow> eventProcessorSinks = new CopyOnWriteArrayList<>();
    /**
     * Per-processor synthetic clocks used to supply a custom time source to processors when dispatching with an explicit time.
     */
    protected static final Map<DataFlow, AtomicLong> syntheticClocks = new ConcurrentHashMap<>();
    /**
     * Monotonic id generator for instances of this strategy, also used for logging context.
     */
    protected static final AtomicLong syntheticClock = new AtomicLong();
    /**
     * Unique identifier for this strategy instance for tracing/logging purposes.
     */
    private final long id;
    /**
     * Cached flag indicating whether FINE logging is enabled to avoid recomputing per event.
     */
    private final boolean fineLogEnabled;

    /**
     * Create a new invocation strategy instance, assigning a unique id and caching log level state.
     */
    public AbstractEventToInvocationStrategy() {
        this.id = syntheticClock.incrementAndGet();
        fineLogEnabled = log.isLoggable(java.util.logging.Level.FINE);
        if (fineLogEnabled) {
            log.fine(() -> "AbstractEventToInvocationStrategy created with id: " + id);
        }
    }

    /** REPLAY: processors whose live inputs are muted (they receive only their replay); empty otherwise. */
    private final java.util.Set<DataFlow> mutedForReplay =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** False unless a replay muted something: the only cost replay OFF pays on the dispatch path, one field read. */
    private boolean anyMuted;

    @Override
    public void muteLive(DataFlow target) {
        mutedForReplay.add(target);
        anyMuted = true;
    }

    /** Whether {@code target} is muted for a replay: a subclass that must answer a muted target's caller asks (#48 3). */
    protected final boolean mutedForReplay(DataFlow target) {
        return anyMuted && mutedForReplay.contains(target);
    }

    /**
     * mongoose#53: the dispatch position, and the processor, being given the event when the last dispatch threw; -1 and
     * null while no processor is being given it. Plain fields: written and read on the agent thread only.
     */
    private int failedPosition = -1;
    private DataFlow failedProcessor;

    @Override
    public void processEvent(Object event) {
        processEventFrom(event, 0);
    }

    /**
     * Deliver to the processors at position {@code from} and after. A processor's handler that throws ends the call with
     * {@link #failedPosition()} naming it, so the agent's retry resumes there and the processors before it, which
     * handled the event, are not given it again (mongoose#53). The processor context is cleared however the handler
     * returns.
     */
    @Override
    public void processEventFrom(Object event, int from) {
        failedPosition = -1;
        failedProcessor = null;
        if (fineLogEnabled) {
            log.fine(() -> "invokerId: " + id + " processEvent: " + event + " to " + eventProcessorSinks.size() + " processors");
        }
        for (int i = from, targetQueuesSize = eventProcessorSinks.size(); i < targetQueuesSize; i++) {
            DataFlow eventProcessor = eventProcessorSinks.get(i);
            if (anyMuted && mutedForReplay.contains(eventProcessor)) {
                skippedForReplay(event, eventProcessor);
                continue;
            }
            if (fineLogEnabled) {
                log.fine(() -> "invokerId: " + id + " dispatchEvent to " + eventProcessor);
            }
            failedPosition = i;
            failedProcessor = eventProcessor;
            ProcessorContext.setCurrentProcessor(eventProcessor);
            try {
                dispatchEvent(event, eventProcessor);
            } catch (Throwable thrown) {
                // captured at throw time, whatever failedPosition() reports (rc1 review N1)
                ProcessorContext.recordFailedProcessor(eventProcessor);
                throw thrown;
            } finally {
                ProcessorContext.removeCurrentProcessor();
            }
        }
        failedPosition = -1;
        failedProcessor = null;
    }

    /**
     * A live input {@code target} is not given because it is muted for a replay. Nothing by default; a strategy whose
     * caller waits on the input answers it here (#48 3).
     */
    protected void skippedForReplay(Object event, DataFlow target) {
    }

    /** RECORD: as {@link #processEvent(Object)}, telling the recording each processor's input just before it is given it. */
    @Override
    public void processEventRecording(Object event, java.util.function.BiConsumer<DataFlow, Object> beforeEach) {
        processEventRecordingFrom(event, beforeEach, 0);
    }

    /** RECORD: as {@link #processEventFrom}, telling the recording each processor's input just before it is given it. */
    @Override
    public void processEventRecordingFrom(Object event, java.util.function.BiConsumer<DataFlow, Object> beforeEach, int from) {
        failedPosition = -1;
        failedProcessor = null;
        for (int i = from, targetQueuesSize = eventProcessorSinks.size(); i < targetQueuesSize; i++) {
            DataFlow eventProcessor = eventProcessorSinks.get(i);
            if (anyMuted && mutedForReplay.contains(eventProcessor)) continue;
            failedPosition = i;
            failedProcessor = eventProcessor;
            beforeEach.accept(eventProcessor, event);
            ProcessorContext.setCurrentProcessor(eventProcessor);
            try {
                dispatchEvent(event, eventProcessor);
            } catch (Throwable thrown) {
                // captured at throw time, whatever failedPosition() reports (rc1 review N1)
                ProcessorContext.recordFailedProcessor(eventProcessor);
                throw thrown;
            } finally {
                ProcessorContext.removeCurrentProcessor();
            }
        }
        failedPosition = -1;
        failedProcessor = null;
    }

    @Override
    public int failedPosition() {
        return failedPosition;
    }

    @Override
    public DataFlow failedProcessor() {
        return failedProcessor;
    }

    @Override
    public void processEvent(Object event, long time) {
        failedPosition = -1;
        failedProcessor = null;
        for (int i = 0, targetQueuesSize = eventProcessorSinks.size(); i < targetQueuesSize; i++) {
            setSyntheticTime(eventProcessorSinks.get(i), time);
        }

        processEvent(event);
    }

    @Override
    public void setSyntheticTime(DataFlow eventProcessor, long time) {
        // REPLAY: a replayed processor is muted, and its clock is its ReplayClock; a live ReplayRecord must not swap
        // it for a synthetic clock, or every later entry replays on the live record's instant (#47 re-review A)
        if (anyMuted && mutedForReplay.contains(eventProcessor)) return;
        syntheticClocks.computeIfAbsent(eventProcessor, k -> {
            AtomicLong atomicLong = new AtomicLong();
            eventProcessor.setClockStrategy(atomicLong::get);
            return atomicLong;
        }).set(time);
    }

    @Override
    public void processEventFor(DataFlow target, Object event) {
        if (!eventProcessorSinks.contains(target)) {
            throw new IllegalArgumentException("invokerId: " + id + " " + target + " is not registered with this strategy");
        }
        ProcessorContext.setCurrentProcessor(target);
        try {
            dispatchEvent(event, target);
        } catch (Throwable thrown) {
            ProcessorContext.recordFailedProcessor(target);
            throw thrown;
        } finally {
            ProcessorContext.removeCurrentProcessor();
        }
    }

    /**
     * Map the event to a callback invocation on the supplied eventProcessor
     *
     * @param event          the incoming event to map to a callback method
     * @param eventProcessor the target of the callback method
     */
    abstract protected void dispatchEvent(Object event, DataFlow eventProcessor);

    @Override
    public void registerProcessor(DataFlow eventProcessor) {
        if (isValidTarget(eventProcessor) && !eventProcessorSinks.contains(eventProcessor)) {
            eventProcessorSinks.add(eventProcessor);
            log.fine(() -> "invokerId: " + id + " registerProcessor: " + eventProcessor + " added to " + eventProcessorSinks.size() + " processors");
        } else {
            log.warning("invokerId: " + id + " registerProcessor: " + eventProcessor + " is not a valid target");
        }
    }

    /**
     * Return true if the eventProcessor is a valid target for receiving callbacks from this invocation strategy.
     *
     * @param eventProcessor the potential target of this invocation strategy
     * @return is a valid target
     */
    abstract protected boolean isValidTarget(DataFlow eventProcessor);

    @Override
    public void deregisterProcessor(DataFlow eventProcessor) {
        eventProcessorSinks.remove(eventProcessor);
    }

    @Override
    public int listenerCount() {
        return eventProcessorSinks.size();
    }

    @Override
    public java.util.Collection<DataFlow> registeredProcessors() {
        return java.util.Collections.unmodifiableList(eventProcessorSinks);
    }
}
