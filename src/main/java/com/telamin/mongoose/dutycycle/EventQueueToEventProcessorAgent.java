/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.JournalledItem;
import com.telamin.mongoose.replay.ReplayRoute;
import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.event.BroadcastEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.service.EventToInvokeStrategy;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import lombok.extern.java.Log;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.util.logging.Logger;


@Experimental
@Log
public class EventQueueToEventProcessorAgent implements EventQueueToEventProcessor, ReplayRoute {

    private final OneToOneConcurrentArrayQueue<?> inputQueue;
    private final EventToInvokeStrategy eventToInvokeStrategy;
    private final String name;
    private final Logger logger;
    private com.telamin.mongoose.dispatch.RetryPolicy retryPolicy = com.telamin.mongoose.dispatch.RetryPolicy.defaultProcessingPolicy();
    private Runnable unsubscribeAction;
    /** The source this queue drains (spec-replay-recording R2: an entry names its source). */
    private final String sourceName;
    /** RECORD mode: set by the group when it subscribes this queue. */
    private GroupRecorder recorder;

    public EventQueueToEventProcessorAgent(
            OneToOneConcurrentArrayQueue<?> inputQueue,
            EventToInvokeStrategy eventToInvokeStrategy,
            String name) {
        this(inputQueue, eventToInvokeStrategy, name, name);
    }

    public EventQueueToEventProcessorAgent(
            OneToOneConcurrentArrayQueue<?> inputQueue,
            EventToInvokeStrategy eventToInvokeStrategy,
            String name,
            String sourceName) {
        this.inputQueue = inputQueue;
        this.eventToInvokeStrategy = eventToInvokeStrategy;
        this.name = name;
        this.sourceName = sourceName;

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

            // RECORD mode only, so replay OFF pays one null check (measured: DispatchPathJmh). A journalled item exists
            // only in RECORD mode; it carries its feed's sequence number, and the processor receives the bare item (R3)
            long seq = -1;
            java.util.Collection<DataFlow> targets = null;
            if (recorder != null) {
                if (event instanceof JournalledItem journalled) {
                    seq = journalled.seq();
                    event = journalled.item();
                } else if (event instanceof com.telamin.fluxtion.runtime.event.NamedFeedEvent<?> named) {
                    seq = named.sequenceNumber();
                }
                targets = eventToInvokeStrategy.registeredProcessors();
            }

            int attempt = 0;
            boolean done = false;
            Throwable lastError = null;
            while (!done) {
                try {
                    if (recorder != null) recorder.beforeDispatch(targets);
                    if (event instanceof ReplayRecord replayRecord) {
                        if (recorder == null) {
                            eventToInvokeStrategy.processEvent(replayRecord.getEvent(), replayRecord.getWallClockTime());
                        } else {
                            // a recorded processor keeps its recording clock, pinned to the record's instant as the
                            // strategy's synthetic clock would be, so its reads are still recorded; the rest as before
                            long time = replayRecord.getWallClockTime();
                            for (DataFlow target : targets) {
                                if (!recorder.pinSyntheticTime(target, time)) eventToInvokeStrategy.setSyntheticTime(target, time);
                            }
                            eventToInvokeStrategy.processEvent(replayRecord.getEvent());
                        }
                    } else if (event instanceof BroadcastEvent broadcastEvent) {
                        eventToInvokeStrategy.processEvent(broadcastEvent.getEvent());
                    } else {
                        eventToInvokeStrategy.processEvent(event);
                    }
                    done = true;
                } catch (Throwable t) {
                    // D4: a retry is a failure; the recording is marked, not continued as if nothing happened. The
                    // recorder never throws (its own failures are its own), so this path stays the dispatch's
                    if (recorder != null && attempt == 0) recorder.failed(sourceName, delivered(event), t, targets);
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

            // recorded after the dispatch, outside it: a recording failure is not a dispatch failure, so it is never
            // retried as one (the processor would handle the input again) and never marks the processor as failing.
            // Only a dispatch that succeeded FIRST TIME is recorded: one that a retry recovered is already marked
            // Failed (D4, a retry is a failure: marked, not reproduced), and recording it too would make a replay re-run
            // it, with the retry's clock reads rather than those of the attempt that happened
            if (done && recorder != null && attempt == 0) {
                boolean wrapped = event instanceof ReplayRecord || event instanceof BroadcastEvent;
                recorder.afterDispatch(sourceName, delivered(event), wrapped ? -1 : seq, targets);
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

    /** What the processor was given: the event a ReplayRecord or BroadcastEvent carries, else the event itself. */
    private static Object delivered(Object event) {
        if (event instanceof ReplayRecord replayRecord) return replayRecord.getEvent();
        if (event instanceof BroadcastEvent broadcastEvent) return broadcastEvent.getEvent();
        return event;
    }

    /** The source this queue drains. */
    public String sourceName() {
        return sourceName;
    }

    /** RECORD mode: record what this queue dispatches (spec-replay-recording R2). */
    public void recordWith(GroupRecorder recorder) {
        this.recorder = recorder;
    }

    /** REPLAY mode: this queue's live inputs no longer reach {@code target}, which receives only its replay. */
    public void muteLiveInputs(DataFlow target) {
        logger.info("replay: live inputs from " + sourceName + " muted for " + target);
        eventToInvokeStrategy.muteLive(target);
    }

    /** REPLAY mode: deliver a recorded input to {@code target} alone, as this queue delivered it (R5). */
    @Override
    public void replayTo(DataFlow target, Object event) {
        eventToInvokeStrategy.processEventFor(target, event);
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
    public int registerProcessor(DataFlow eventProcessor) {
        logger.info("registerProcessor: " + eventProcessor);
        eventToInvokeStrategy.registerProcessor(eventProcessor);
        logger.info("listener count:" + listenerCount());
        return listenerCount();
    }

    @Override
    public int deregisterProcessor(DataFlow eventProcessor) {
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

    @Override
    public java.util.Collection<DataFlow> subscribers() {
        return eventToInvokeStrategy.registeredProcessors();
    }

    private PoolTracker<?> trackerOf(Object event) {
        if (event == null) return null;
        Object candidate = event;
        if (recorder != null && candidate instanceof JournalledItem journalled) {
            candidate = journalled.item();
        }
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
