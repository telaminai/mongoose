/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.fluxtion.runtime.StaticEventProcessor;
import com.fluxtion.runtime.annotations.feature.Experimental;
import com.fluxtion.runtime.event.BroadcastEvent;
import com.fluxtion.runtime.event.NamedFeedEvent;
import com.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import lombok.extern.java.Log;

import java.util.logging.Logger;


@Experimental
@Log
public class EventQueueToEventProcessorAgent implements EventQueueToEventProcessor {

    private final OneToOneConcurrentArrayQueue<?> inputQueue;
    private final EventToInvokeStrategy eventToInvokeStrategy;
    private final String name;
    private final Logger logger;
    private com.telamin.mongoose.dispatch.RetryPolicy retryPolicy = com.telamin.mongoose.dispatch.RetryPolicy.defaultProcessingPolicy();
    private Runnable unsubscribeAction;

    public EventQueueToEventProcessorAgent(
            OneToOneConcurrentArrayQueue<?> inputQueue,
            EventToInvokeStrategy eventToInvokeStrategy,
            String name) {
        this.inputQueue = inputQueue;
        this.eventToInvokeStrategy = eventToInvokeStrategy;
        this.name = name;

        logger = Logger.getLogger("EventQueueToEventProcessorAgent." + name);
    }

    @Override
    public void onStart() {
        logger.info("start");
    }

    @Override
    public int doWork() {
        int processed = 0;
        // Batch up to a fixed number of events per tick to reduce per-event overhead
        final int batchLimit = 64;
        Object event;
        while (processed < batchLimit && (event = inputQueue.poll()) != null) {
            // Release the per-queue reference as we are now publishing to processors
            PoolTracker<?> tracker = trackerOf(event);
            if (tracker != null) {
                try {
                    tracker.releaseReference();
                } catch (Throwable ignored) {
                }
            }

            int attempt = 0;
            boolean done = false;
            Throwable lastError = null;
            while (!done) {
                try {
                    if (event instanceof ReplayRecord replayRecord) {
                        eventToInvokeStrategy.processEvent(replayRecord.getEvent(), replayRecord.getWallClockTime());
                    } else if (event instanceof BroadcastEvent broadcastEvent) {
                        eventToInvokeStrategy.processEvent(broadcastEvent.getEvent());
                    } else {
                        eventToInvokeStrategy.processEvent(event);
                    }
                    done = true;
                } catch (Throwable t) {
                    lastError = t;
                    attempt++;
                    String warnMsg = "event processing failed: agent=" + name +
                            ", attempt=" + attempt +
                            ", eventClass=" + (event == null ? "null" : event.getClass().getName()) +
                            ", event=" + event +
                            ", error=" + t;
                    logger.warning(warnMsg);
                    com.telamin.mongoose.service.error.ErrorReporting.report(
                            "EventQueueToEventProcessorAgent:" + name,
                            warnMsg,
                            t,
                            com.telamin.mongoose.service.error.ErrorEvent.Severity.WARNING);
                    if (!retryPolicy.shouldRetry(t, attempt)) {
                        String errMsg = "dropping event after retries: agent=" + name +
                                ", attempts=" + attempt +
                                ", eventClass=" + (event == null ? "null" : event.getClass().getName()) +
                                ", event=" + event +
                                ", lastError=" + t;
                        logger.severe(errMsg);
                        com.telamin.mongoose.service.error.ErrorReporting.report(
                                "EventQueueToEventProcessorAgent:" + name,
                                errMsg,
                                t,
                                com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
                        break;
                    }
                    retryPolicy.backoff(attempt);
                }
            }

            // After dispatching to all processors attempt to return to pool if no more references remain
            if (tracker != null) {
                try {
                    tracker.returnToPool();
                } catch (Throwable ignored) {
                    logger.warning("unable to return to pool: " + tracker);
                }
            }

            // Count it as processed even if dropped to avoid infinite loops
            processed++;
        }
        return processed;
    }

    @Override
    public void onClose() {
        logger.info("onClose");
    }

    @Override
    public String roleName() {
        return name;
    }

    /**
     * Configure the retry policy for processing events.
     */
    public EventQueueToEventProcessorAgent withRetryPolicy(com.telamin.mongoose.dispatch.RetryPolicy retryPolicy) {
        if (retryPolicy != null) {
            this.retryPolicy = retryPolicy;
        }
        return this;
    }

    /**
     * Provide an unsubscribe action to be called when listenerCount() drops to zero.
     */
    public EventQueueToEventProcessorAgent withUnsubscribeAction(Runnable unsubscribeAction) {
        this.unsubscribeAction = unsubscribeAction;
        return this;
    }

    @Override
    public int registerProcessor(StaticEventProcessor eventProcessor) {
        logger.info("registerProcessor: " + eventProcessor);
        eventToInvokeStrategy.registerProcessor(eventProcessor);
        logger.info("listener count:" + listenerCount());
        return listenerCount();
    }

    @Override
    public int deregisterProcessor(StaticEventProcessor eventProcessor) {
        logger.info("deregisterProcessor: " + eventProcessor);
        eventToInvokeStrategy.deregisterProcessor(eventProcessor);
        int listeners = listenerCount();
        if (listeners < 1 && unsubscribeAction != null) {
            try {
                unsubscribeAction.run();
            } catch (Throwable t) {
                logger.severe("error running unsubscribe action for agent=" + name + ": " + t);
            }
        }
        return listeners;
    }

    @Override
    public int listenerCount() {
        return eventToInvokeStrategy.listenerCount();
    }

    private PoolTracker<?> trackerOf(Object event) {
        if (event == null) return null;
        Object candidate = event;
        if (candidate instanceof ReplayRecord rr) {
            candidate = rr.getEvent();
        }
        if (candidate instanceof BroadcastEvent be) {
            candidate = be.getEvent();
        }
        // If the current candidate is already PoolAware, prefer its tracker (future-proof for pooled wrappers)
        if (candidate instanceof PoolAware paDirect) {
            return paDirect.getPoolTracker();
        }
        if (candidate instanceof NamedFeedEvent<?> nfe) {
            Object data = nfe.data();
            if (data instanceof PoolAware pa) {
                return pa.getPoolTracker();
            }
        }
        return null;
    }
}
