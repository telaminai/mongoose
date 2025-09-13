/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.annotations.feature.Experimental;
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
    protected final List<StaticEventProcessor> eventProcessorSinks = new CopyOnWriteArrayList<>();
    /**
     * Per-processor synthetic clocks used to supply a custom time source to processors when dispatching with an explicit time.
     */
    protected static final Map<StaticEventProcessor, AtomicLong> syntheticClocks = new ConcurrentHashMap<>();
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

    @Override
    public void processEvent(Object event) {
        if (fineLogEnabled) {
            log.fine(() -> "invokerId: " + id + " processEvent: " + event + " to " + eventProcessorSinks.size() + " processors");
        }
        for (int i = 0, targetQueuesSize = eventProcessorSinks.size(); i < targetQueuesSize; i++) {
            StaticEventProcessor eventProcessor = eventProcessorSinks.get(i);
            if (fineLogEnabled) {
                log.fine(() -> "invokerId: " + id + " dispatchEvent to " + eventProcessor);
            }
            ProcessorContext.setCurrentProcessor(eventProcessor);
            dispatchEvent(event, eventProcessor);
            ProcessorContext.removeCurrentProcessor();
        }
    }

    @Override
    public void processEvent(Object event, long time) {
        for (int i = 0, targetQueuesSize = eventProcessorSinks.size(); i < targetQueuesSize; i++) {
            StaticEventProcessor eventProcessor = eventProcessorSinks.get(i);
            syntheticClocks.computeIfAbsent(eventProcessor, k -> {
                AtomicLong atomicLong = new AtomicLong();
                eventProcessor.setClockStrategy(atomicLong::get);
                return atomicLong;
            }).set(time);
        }

        processEvent(event);
    }

    /**
     * Map the event to a callback invocation on the supplied eventProcessor
     *
     * @param event          the incoming event to map to a callback method
     * @param eventProcessor the target of the callback method
     */
    abstract protected void dispatchEvent(Object event, StaticEventProcessor eventProcessor);

    @Override
    public void registerProcessor(StaticEventProcessor eventProcessor) {
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
    abstract protected boolean isValidTarget(StaticEventProcessor eventProcessor);

    @Override
    public void deregisterProcessor(StaticEventProcessor eventProcessor) {
        eventProcessorSinks.remove(eventProcessor);
    }

    @Override
    public int listenerCount() {
        return eventProcessorSinks.size();
    }
}
