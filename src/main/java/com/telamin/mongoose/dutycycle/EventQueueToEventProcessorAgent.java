/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import com.telamin.fluxtion.runtime.event.BroadcastEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.replay.GroupRecorder;
import com.telamin.mongoose.replay.JournalledItem;
import com.telamin.mongoose.replay.ReplayRoute;
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
    private ProcessorDispatchException terminalFailure;
    /** Last receiver removed: leave queued commands/resources for onClose, never drain into an empty strategy. */
    private boolean drainDisabled;
    private volatile long failureFenceVersion;
    private volatile boolean failureFenced;

    /** Cancellation token for a blocking publisher whose intended subscriber was quarantined. */
    public long failureFenceVersion() { return failureFenceVersion; }
    public boolean failureFenced() { return failureFenced; }
    /** Invalidate pending admissions before removing a quarantined subscriber, including timer/service failures. */
    public void fenceFailedSubscriber() {
        failureFenced = true;
        failureFenceVersion++;
    }
    private java.util.function.BiConsumer<DataFlow, Throwable> processorFailureHandler;
    /** The source this queue drains (spec-replay-recording R2: an entry names its source). */
    private final String sourceName;
    /** The configured route (callback type) this queue delivers by: an entry names it (review of 90f0d9b, finding 4). */
    private final String route;
    /**
     * The processors this queue registered with its strategy, known here whatever the strategy reports: RECORD uses it
     * only to fail a recording the strategy cannot name the processors of (cd52628 F4). Registration only, not dispatch.
     */
    private final java.util.Set<DataFlow> subscribed =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    /** RECORD mode: set by the group when it subscribes this queue. */
    private GroupRecorder recorder;
    private com.telamin.mongoose.dispatch.DropMarks dropMarks;
    /** RECORD mode: the recorder's per-processor copy of each input, taken just before the processor is given it. */
    private java.util.function.BiConsumer<DataFlow, Object> received;

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
        this(inputQueue, eventToInvokeStrategy, name, sourceName, "");
    }

    public EventQueueToEventProcessorAgent(
            OneToOneConcurrentArrayQueue<?> inputQueue,
            EventToInvokeStrategy eventToInvokeStrategy,
            String name,
            String sourceName,
            String route) {
        this.route = route;
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
        if (terminalFailure != null) throw terminalFailure;
        if (drainDisabled) return 0;
        int processed = 0;
        // Batch up to a fixed number of events per tick to reduce per-event overhead
        final int batchLimit = 64;
        Object event;
        boolean marking = recorder != null && dropMarks != null;
        while (processed < batchLimit && (!marking || markDropsThen()) && (event = inputQueue.poll()) != null) {
            // a drop marked after the check above but before this item was offered precedes it: mark it first, so the
            // Dropped entry lands exactly where the items were lost (review of 1d925ee, finding 7)
            if (marking) markDrops(inputQueue.removedCount() - 1);
            // this queue's reference to a pooled event is held until every processor has been given it (released in
            // the finally below, so a failure that escapes the retry loop cannot leak it)
            PoolTracker<?> tracker = trackerOf(event);
            try {
                // Replay OFF pays one instanceof and one null check here (measured: DispatchPathJmh). A journalled item
                // exists only in RECORD mode; it carries its feed's sequence number, and the processor receives the bare
                // item (R3)
                long seq = -1;
                java.util.Collection<DataFlow> targets = null;
                // a journalled feed writes JournalledItems to EVERY queue, including those of groups that do not record:
                // always unwrap, so every processor receives the bare item (review of #63)
                boolean journalledItem = event instanceof JournalledItem;
                if (journalledItem) {
                    JournalledItem journalled = (JournalledItem) event;
                    seq = journalled.seq();
                    event = journalled.item();
                }
                if (recorder != null) {
                    if (journalledItem) {
                        // the journalled item's sequence number, read above
                    } else if (event instanceof com.telamin.fluxtion.runtime.event.NamedFeedEvent<?> named) {
                        seq = named.sequenceNumber();
                    }
                    targets = eventToInvokeStrategy.registeredProcessors();
                    if (event instanceof ReplayRecord || event instanceof BroadcastEvent) seq = -1;
                    // a strategy that delivers but names no processor (registeredProcessors() left at its empty default)
                    // cannot be recorded: the processors this queue registered with it are known here, so each recorded one
                    // is marked failed, by name, rather than recording nothing for an input it delivered (cd52628 F4)
                    if (targets.isEmpty() && !subscribed.isEmpty()) {
                        recorder.cannotRecord(sourceName, java.util.List.copyOf(subscribed), eventToInvokeStrategy.getClass().getName()
                                + " delivers to processors it does not name (registeredProcessors() is empty), so what each"
                                + " received cannot be recorded; implement registeredProcessors() to record through it");
                    }
                }

                // mongoose#53: the unit of retry is ONE processor. A strategy that names the processor whose handler threw
                // (failedPosition() >= 0) is resumed at it: that processor is retried, then the processors after it are
                // given the event; those before it handled it and never see it again. Each failing processor has its own
                // attempts under the RetryPolicy, and one whose retries are exhausted is skipped, not the rest of the queue.
                // Without a resumable position, only an identified target can be retried; unknown dispatch is never repeated.
                int from = 0;                   // the dispatch position the next attempt starts at
                int failingAt = -1;             // the position of the processor being retried, -1 when none is
                int attempt = 0;                // attempts of the processor being retried
                boolean wholeFailed = false;    // unknown progress: recording cannot attribute a successful dispatch
                boolean done = false;
                // a handwritten thrower known by identity only (no dispatch position): it alone is retried and the event
                // skipped for the other subscribers, so no peer that handled it is given it again (re-verification A)
                DataFlow solo = null;
                if (recorder != null) recorder.beforeDispatch(sourceName, seq, targets);
                while (!done) {
                    com.telamin.mongoose.dispatch.ProcessorContext.clearFailure(); // never a previous dispatch's
                    try {
                        if (recorder != null && wholeFailed && solo == null) recorder.beforeDispatch(sourceName, seq, targets);
                        if (solo != null) {
                            eventToInvokeStrategy.processEventFor(solo, delivered(event));
                        } else if (event instanceof ReplayRecord replayRecord) {
                            if (recorder == null) {
                                if (from == 0) {
                                    eventToInvokeStrategy.processEvent(replayRecord.getEvent(), replayRecord.getWallClockTime());
                                } else {
                                    // the synthetic clocks were set by the attempt that failed: resume with them
                                    eventToInvokeStrategy.processEventFrom(replayRecord.getEvent(), from);
                                }
                            } else {
                                // a recorded processor keeps its recording clock, pinned to the record's instant as the
                                // strategy's synthetic clock would be, so its reads are still recorded; the rest as before
                                long time = replayRecord.getWallClockTime();
                                for (DataFlow target : targets) {
                                    if (!recorder.pinSyntheticTime(target, time)) eventToInvokeStrategy.setSyntheticTime(target, time);
                                }
                                if (from == 0) {
                                    eventToInvokeStrategy.processEventRecording(replayRecord.getEvent(), received);
                                } else {
                                    eventToInvokeStrategy.processEventRecordingFrom(replayRecord.getEvent(), received, from);
                                }
                            }
                        } else if (recorder != null) {
                            // RECORD: each recorded processor's input is copied just before it is given it (review of
                            // 90f0d9b, finding 2), once: a failed processor's retries are not recorded (D4, #53)
                            if (from == 0) {
                                eventToInvokeStrategy.processEventRecording(delivered(event), received);
                            } else {
                                eventToInvokeStrategy.processEventRecordingFrom(delivered(event), received, from);
                            }
                        } else if (from != 0) {
                            eventToInvokeStrategy.processEventFrom(delivered(event), from);
                        } else if (event instanceof BroadcastEvent broadcastEvent) {
                            eventToInvokeStrategy.processEvent(broadcastEvent.getEvent());
                        } else {
                            eventToInvokeStrategy.processEvent(event);
                        }
                        done = true;
                    } catch (ProcessorDispatchException fatal) {
                        terminalFailure = fatal;
                        throw fatal;
                    } catch (VirtualMachineError fatal) {
                        throw fatal;        // OutOfMemoryError, StackOverflowError, ...: never retried or swallowed (as #57)
                    } catch (Throwable t) {
                        // the processor whose handler threw and its dispatch position, captured at throw time by the
                        // strategy (rc1 review N1)
                        var captured = com.telamin.mongoose.dispatch.ProcessorContext.takeFailure();
                        DataFlow thrower = solo != null ? solo : captured == null ? null : captured.processor();
                        int at = wholeFailed ? -1 : eventToInvokeStrategy.failedPosition();
                        DataFlow failed = at < 0 ? null : eventToInvokeStrategy.failedProcessor();
                        if (at < 0 && !wholeFailed && thrower != null && captured != null && captured.position() >= 0) {
                            boolean present;
                            synchronized (subscribed) { present = subscribed.contains(thrower); }
                            if (present) {
                                // an opaque strategy's progress is known from the capture: resume at the thrower, so a
                                // processor that already handled the event is never given it again (external review 1)
                                at = captured.position();
                                failed = thrower;
                            }
                        }
                        if (at >= 0 && !supportsResumeDelivery()) {
                            // Capturing progress does not implement the optional resume SPI. Fall back to identity-only
                            // handling, never invoke an unsupported default and misclassify it as a processor failure.
                            if (failed != null) thrower = failed;
                            at = -1;
                            failed = null;
                        }
                        if (at < 0) {
                            // Unknown progress: the recording cannot attribute successful input delivery and is
                            // marked for every target, not continued as if nothing happened (the recorder never throws)
                            if (recorder != null && !wholeFailed) recorder.failed(sourceName, delivered(event), t, targets);
                            if (!wholeFailed) {
                                wholeFailed = true;
                                attempt = 0;        // a resumed processor's attempts were its own; the whole dispatch starts its count
                            }
                            from = 0;
                        } else {
                            if (at != failingAt) {
                                // a processor failing for the first time on this event: its own attempts, and its input alone
                                // is marked failed (D4); the processors before it are recorded as they handled it
                                failingAt = at;
                                attempt = 0;
                                if (recorder != null) recorder.failed(sourceName, delivered(event), t, failed);
                            }
                            from = at;
                        }
                        // Generated graphs are not retry-safe after a throw: processing/callback state may be
                        // wedged and a retry can return normally without dispatching anything. This is failure-only.
                        // An opaque strategy (failedPosition() == -1) cannot name the target, but the processor captured at
                        // throw time can (rc1 review N1): an identified handwritten thrower is retried under its policy and
                        // never quarantines its generated peers; a generated thrower is quarantined alone.
                        DataFlow identified = failed;
                        if (identified == null && thrower != null) {
                            synchronized (subscribed) { if (subscribed.contains(thrower)) identified = thrower; }
                        }
                        if (at < 0 && solo == null && identified != null
                                && !ProcessorDispatchException.requiresIsolation(identified)) {
                            if (!supportsSingleTargetDelivery()) {
                                // the strategy cannot deliver to one processor (processEventFor is optional): never retry
                                // the whole fan-out and never end the group for this recoverable failure; the event is
                                // skipped for the thrower and the remaining subscribers, and why is reported
                                // (re-verification 2)
                                String why = "event skipped after a handwritten failure of " + identified + ": the dispatch"
                                        + " progress is unknown and strategy " + eventToInvokeStrategy.getClass().getName()
                                        + " cannot deliver to a single processor, so neither the thrower alone nor the"
                                        + " whole fan-out is retried; agent=" + name + ", event=" + event + ", error=" + t;
                                logger.warning(why);
                                com.telamin.mongoose.service.error.ErrorReporting.report(
                                        "EventQueueToEventProcessorAgent:" + name, why, t,
                                        com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
                                done = true;
                                continue;
                            }
                            solo = identified;
                            logger.warning("retrying only " + identified + " and skipping the event for the other"
                                    + " subscribers: the dispatch progress is unknown; agent=" + name);
                        }
                        boolean unsafe = ProcessorDispatchException.requiresIsolation(identified);
                        if (identified == null) {
                            synchronized (subscribed) {
                                unsafe = subscribed.stream().anyMatch(ProcessorDispatchException::requiresIsolation);
                            }
                        }
                        if (unsafe) {
                            if (identified == null) {
                                // truly unidentifiable (a strategy that records no processor), with a generated graph on the
                                // reader that may be the one left half-processed: contain by ending the group, blame no peer
                                var unknown = new ProcessorDispatchException(null, "opaque event dispatch (no failed position and"
                                        + " no processor captured at throw time; no subscriber is quarantined, the group ends)", t);
                                terminalFailure = unknown;
                                throw unknown;
                            }
                            var failure = new ProcessorDispatchException(identified, "event dispatch", t);
                            failureFenced = true;
                            failureFenceVersion++;
                            try {
                                if (processorFailureHandler != null) processorFailureHandler.accept(identified, failure);
                                else {
                                    deregisterProcessor(identified);
                                    com.telamin.mongoose.service.error.ErrorReporting.report(
                                            "EventQueueToEventProcessorAgent:" + name,
                                            "generated processor quarantined; no retry: " + failure.getMessage(), failure,
                                            com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
                                }
                            } catch (VirtualMachineError fatal) {
                                throw fatal;
                            } catch (Throwable isolationFailure) {
                                if (isolationFailure != failure) failure.addSuppressed(isolationFailure);
                                terminalFailure = failure;
                                throw failure;
                            }
                            if (!drainDisabled) failureFenced = false;
                            if (drainDisabled) return processed + 1; // finally releases this input; close owns backlog
                            if (at < 0) {
                                // the thrower is known but not the dispatch progress: never redispatch to peers that may
                                // already have handled the event; the event is skipped for the rest (external review 1)
                                logger.warning("event skipped for the remaining subscribers after quarantining " + identified
                                        + ": the dispatch progress is unknown, so no peer is given it again; agent=" + name);
                                done = true;
                                continue;
                            }
                            // Isolation removes this target from the strategy. Its successor shifts into this index.
                            // Earlier processors already received the event and must not see it again.
                            from = at;
                            failingAt = -1;
                            attempt = 0;
                            continue;
                        }
                        if (identified == null) {
                            // With no identity there is no safe retry target. Keep the handwritten reader alive, but
                            // never redispatch peers that may already have produced external effects.
                            String why = "event skipped after an unidentified handwritten dispatch failure; no safe retry target; agent="
                                    + name + ", error=" + t;
                            logger.severe(why);
                            com.telamin.mongoose.service.error.ErrorReporting.report(
                                    "EventQueueToEventProcessorAgent:" + name, why, t,
                                    com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
                            done = true;
                            continue;
                        }
                        attempt++;
                        String warnMsg = "event processing failed: agent=" + name +
                                ", attempt=" + attempt +
                                (failed == null ? "" : ", processor=" + failed) +
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
                                    (failed == null ? "" : ", processor=" + failed
                                            + " (the processors after it are still given the event)") +
                                    ", eventClass=" + (event == null ? "null" : event.getClass().getName()) +
                                    ", event=" + event +
                                    ", lastError=" + t;
                            logger.severe(errMsg);
                            com.telamin.mongoose.service.error.ErrorReporting.report(
                                    "EventQueueToEventProcessorAgent:" + name,
                                    errMsg,
                                    t,
                                    com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
                            if (at < 0) break;
                            // #53: this processor's retries are exhausted; it is skipped, the processors after it are not
                            from = at + 1;
                            failingAt = -1;
                            attempt = 0;
                            continue;
                        }
                        retryPolicy.backoff(attempt);
                    }
                }

                // recorded after the dispatch, outside it: a recording failure is not a dispatch failure, so it is never
                // retried as one (the processor would handle the input again) and never marks the processor as failing.
                // A processor whose handler threw is already marked Failed (D4, a retry is a failure: marked, not
                // reproduced) and is skipped here; recording it too would make a replay re-run it, with the retry's clock
                // reads rather than those of the attempt that happened. With a failure resumed at its processor (#53), every
                // other processor handled the input exactly once, first time, and is recorded as usual. A failure the
                // strategy could not place marked every target, so nothing more is recorded (as before #53).
                if (done && recorder != null && !wholeFailed) {
                    recorder.afterDispatch(sourceName, route, delivered(event), seq, targets);
                }

            } finally {
                // After dispatching to all processors, drop this queue's reference: released before dispatch, another
                // queue's last release could recycle the event while a processor here still read it (#54). In a
                // finally: a throw out of the retry loop (a backoff, the recorder, failure logging) must not leak it.
                // The last release returns it to its pool
                if (tracker != null) {
                    try {
                        tracker.releaseReference();
                    } catch (Throwable t) {
                        String why = "pooled event reference release failed: agent=" + name + ", error=" + t;
                        logger.severe(why);
                        com.telamin.mongoose.service.error.ErrorReporting.report(
                                "EventQueueToEventProcessorAgent:" + name, why, t,
                                com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
                    }
                }
            }

            // Count it as processed even if dropped to avoid infinite loops
            processed++;
        }
        return processed;
    }

    /** What the processor was given: the event a ReplayRecord or BroadcastEvent carries, else the event itself. */
    private Boolean singleTargetDelivery;

    /** Whether the strategy implements the optional single-processor delivery ({@code processEventFor}). */
    private boolean supportsSingleTargetDelivery() {
        Boolean supported = singleTargetDelivery;
        if (supported == null) {
            try {
                supported = eventToInvokeStrategy.getClass().getMethod("processEventFor", DataFlow.class, Object.class)
                        .getDeclaringClass() != com.telamin.mongoose.service.EventToInvokeStrategy.class;
            } catch (NoSuchMethodException unexpected) {
                supported = false;
            }
            singleTargetDelivery = supported;
        }
        return supported;
    }

    /** A reported position alone does not guarantee that a custom strategy implements resumption. */
    private boolean supportsResumeDelivery() {
        try {
            var method = recorder == null
                    ? eventToInvokeStrategy.getClass().getMethod("processEventFrom", Object.class, int.class)
                    : eventToInvokeStrategy.getClass().getMethod("processEventRecordingFrom", Object.class,
                            java.util.function.BiConsumer.class, int.class);
            return method.getDeclaringClass() != EventToInvokeStrategy.class;
        } catch (NoSuchMethodException unexpected) {
            return false;
        }
    }

    private static Object delivered(Object event) {
        if (event instanceof ReplayRecord replayRecord) return replayRecord.getEvent();
        if (event instanceof BroadcastEvent broadcastEvent) return broadcastEvent.getEvent();
        return event;
    }

    /** The source this queue drains. */
    public String sourceName() {
        return sourceName;
    }

    /**
     * RECORD mode (#79): where the publisher dropped items for this queue. Each mark becomes a
     * {@link com.telamin.mongoose.replay.ReplayEntry.Dropped} entry in the recordings of this queue's processors, placed
     * where the items were lost; a replay reports the loss and carries on. Null when not recording.
     */
    public EventQueueToEventProcessorAgent withDropMarks(com.telamin.mongoose.dispatch.DropMarks dropMarks) {
        this.dropMarks = dropMarks;
        return this;
    }

    private java.util.Collection<DataFlow> dropTargets() {
        java.util.Collection<DataFlow> targets = eventToInvokeStrategy.registeredProcessors();
        return targets.isEmpty() ? java.util.List.copyOf(subscribed) : targets;
    }

    private boolean markDropsThen() {
        markDrops(inputQueue.removedCount());
        return true;
    }

    /**
     * Turn the drop marks that precede the next item into Dropped entries: those whose producer position is at most
     * {@code consumed}, the number of items offered before the drop that have been taken. Recording only, on the group's
     * thread.
     */
    private void markDrops(long consumed) {
        com.telamin.mongoose.dispatch.DropMarks.Mark m = dropMarks.peek();
        if (m == null) {
            // drops counted after the last mark was taken (the marks were full) are exported now, never stranded
            // (external review of d9eec79, finding 5); their position is approximate
            long unmarked = dropMarks.takeOverflow();
            if (unmarked > 0) {
                recorder.inputDropped(sourceName, dropTargets(), unmarked + " item(s) of " + sourceName
                        + " dropped by the full queue " + name + " while the drop marks were full (position "
                        + "approximate); the processor never received them");
            }
            return;
        }
        if (m.position() > consumed) {
            return;
        }
        java.util.Collection<DataFlow> targets = dropTargets();
        while ((m = dropMarks.peek()) != null && m.position() <= consumed) {
            dropMarks.poll();
            long position = m.position();
            long first = m.sequence(), last = m.sequence(), count = 1;
            String reason = m.reason();
            com.telamin.mongoose.dispatch.DropMarks.Mark next;
            while ((next = dropMarks.peek()) != null && next.position() == position) {
                dropMarks.poll();
                last = next.sequence();
                count++;
            }
            long unmarked = dropMarks.takeOverflow();
            recorder.inputDropped(sourceName, targets, (count + unmarked) + " item(s) of " + sourceName
                    + " dropped by the full queue " + name + " (sequence " + first + (last != first ? ".." + last : "")
                    + (unmarked > 0 ? ", plus " + unmarked + " dropped while the drop marks were full" : "") + "): "
                    + reason + "; the processor never received them");
        }
    }

    /** RECORD mode: record what this queue dispatches (spec-replay-recording R2). */
    public void recordWith(GroupRecorder recorder) {
        this.recorder = recorder;
        this.received = recorder == null ? null : recorder::received;
    }

    /** The configured route (callback type name) this queue delivers by; empty when it was built without one. */
    public String route() {
        return route;
    }

    /** REPLAY mode: this queue's live inputs no longer reach {@code target}, which receives only its replay. */
    public void muteLiveInputs(DataFlow target) {
        logger.info("replay: live inputs from " + sourceName + " muted for " + target);
        eventToInvokeStrategy.muteLive(target);
    }

    /** REPLAY mode: deliver a recorded input to {@code target} alone, as this queue delivered it (R5). */
    @Override
    public void replayTo(DataFlow target, Object event) {
        boolean owned = subscribed.contains(target);
        try {
            eventToInvokeStrategy.processEventFor(target, event);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException | Error failure) {
            if (owned && ProcessorDispatchException.requiresIsolation(target)) {
                failureFenced = true;
                failureFenceVersion++;
                if (processorFailureHandler != null) processorFailureHandler.accept(target, failure);
                else deregisterProcessor(target);
                if (!drainDisabled) failureFenced = false;
            }
            throw failure; // the replay must also report its divergence
        }
    }

    @Override
    public void onClose() {
        logger.info("onClose");
        // Its producer has been detached (or stopped at server shutdown). Discarding the reader must release the
        // references held by its abandoned backlog, including wrappers, rather than exhaust the source's pool.
        Object event;
        while ((event = inputQueue.poll()) != null) {
            Object payload = delivered(event);
            if (payload instanceof com.telamin.mongoose.service.admin.impl.AdminCommand command) {
                try {
                    command.refuseUnavailable("its processor route closed before dispatch");
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable refused) {
                    logger.warning("failed to deliver abandoned admin command refusal: " + refused);
                }
            }
            PoolTracker<?> tracker = trackerOf(event);
            if (tracker != null) {
                try {
                    tracker.releaseReference();
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable t) {
                    com.telamin.mongoose.service.error.ErrorReporting.report(
                            "EventQueueToEventProcessorAgent:" + name, "failed to release discarded queued event", t,
                            com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
                }
            }
        }
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

    /** Group-owned isolation; invoked on the group's thread only after a generated processor throws. */
    public EventQueueToEventProcessorAgent withProcessorFailureHandler(java.util.function.BiConsumer<DataFlow, Throwable> handler) {
        processorFailureHandler = handler;
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
        subscribed.add(eventProcessor);
        drainDisabled = false;
        logger.info("listener count:" + listenerCount());
        return listenerCount();
    }

    @Override
    public int deregisterProcessor(DataFlow eventProcessor) {
        logger.info("deregisterProcessor: " + eventProcessor);
        eventToInvokeStrategy.deregisterProcessor(eventProcessor);
        subscribed.remove(eventProcessor);
        int listeners = listenerCount();
        if (listeners < 1) drainDisabled = true;
        else failureFenced = false;
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
        if (candidate instanceof JournalledItem journalled) { // any group: see doWork
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
