/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.event.NamedFeedEventImpl;
import com.telamin.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.internal.NoOpCountersService;
import com.telamin.mongoose.replay.EventCodec;
import com.telamin.mongoose.replay.EventJournal;
import com.telamin.mongoose.replay.JournalledItem;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.counters.MongooseCounter;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.java.Log;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * EventToQueuePublisher is a generic class that facilitates the publishing of events to
 * one or more concurrent queues. It supports caching, custom data mapping, and different
 * event wrapping strategies during dispatch.
 *
 * @param <T> the type of event that this publisher handles
 */
@ToString
@Log
@Getter
public class EventToQueuePublisher<T> {

    private final List<NamedQueue> targetQueues = new CopyOnWriteArrayList<>();
    private final List<NamedFeedEvent<?>> eventLog = new ArrayList<>();
    private final String name;
    /**
     * Per-feed publish counter. Hot-path field — incremented once per
     * successful {@link #publish(Object)}. Defaults to a no-op handle so the
     * publisher works in any test harness that constructs it directly without
     * going through EventFlowManager.
     */
    private final MongooseCounter publishCounter;

    public EventToQueuePublisher(String name, MongooseCounter publishCounter) {
        this.name = name;
        this.publishCounter = publishCounter;
    }

    public EventToQueuePublisher(String name) {
        this(name, NoOpCountersService.INSTANCE.feedPublishCounter(name));
    }
    /**
     * Cache published items in the event log so they can be replayed to subscribers at {@link #startComplete()}.
     * Publishes stop adding to the log once {@link #startComplete()} has run (#59): the log holds the pre-start
     * history, not the feed's traffic.
     */
    @Setter
    private boolean cacheEventLog;
    /**
     * Keep logging published items after {@link #startComplete()}. Off by default: the log then grows with every
     * publish for the life of the feed, without bound, so only turn it on for a bounded feed or a test that reads the
     * whole history back through {@link #getEventLog()}.
     */
    @Setter
    private boolean retainEventLogAfterStart;
    /** Set by {@link #startComplete()}, under the publish lock: publishes no longer add to the event log. */
    @Getter(lombok.AccessLevel.NONE)
    private boolean startComplete;
    private long sequenceNumber = 0;
    @Setter
    private EventSource.EventWrapStrategy eventWrapStrategy = EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP;
    @Setter
    private Function<T, ?> dataMapper = Function.identity();
    private int cacheReadPointer = 0;
    /** Replay R3: when set, each item is journalled once, encoded, and carries its sequence number to the queue. */
    private EventJournal journal;
    private EventCodec journalCodec;
    /** Set when a journal append or encoding failed: the feed stops journalling (a gap a replay stops at). */
    private volatile String journalBroken;
    private final boolean logWarning = log.isLoggable(Level.WARNING);
    private final boolean logInfo = log.isLoggable(Level.INFO);
    private final boolean logFine = log.isLoggable(Level.FINE);
    /**
     * Serialises every write into the target queues, and the sequence number and event log with them. Each target
     * queue is a single-producer {@link OneToOneConcurrentArrayQueue}, but a publisher can be called from several
     * threads: a {@code HandlerPipe} sink from every processor that publishes to it, and a source's
     * {@code startComplete} replaying the cache while its own thread publishes. Two unserialised offers can claim the
     * same slot and lose an event (telaminai/mongoose#51). Adding and removing target queues take it too, so a dispatch
     * never sees the list change under it. Uncontended, the lock costs a few nanoseconds per publish.
     */
    @Getter(lombok.AccessLevel.NONE)
    private final Object publishLock = new Object();
    /** Items dropped because a target queue stayed full past the spin bound, over all target queues. */
    @Getter(lombok.AccessLevel.NONE)
    private volatile long droppedCount;

    /** Journal this feed's items (spec-replay-recording R3). */
    public void journal(EventJournal journal, EventCodec codec) {
        this.journal = journal;
        this.journalCodec = codec;
    }

    public void addTargetQueue(OneToOneConcurrentArrayQueue<Object> targetQueue, String name) {
        NamedQueue namedQueue = new NamedQueue(name, targetQueue);
        if (log.isLoggable(Level.FINE)) {
            log.fine("adding a publisher queue:" + namedQueue);
        }
        // under the publish lock: a dispatch loop never sees the list change under it, and contains-then-add is atomic
        synchronized (publishLock) {
            if (!targetQueues.contains(namedQueue)) {
                targetQueues.add(namedQueue);
            }
        }
    }

    public void publish(T itemToPublish) {
        if (itemToPublish == null) {
            log.info("itemToPublish is null");
            return;
        }
        synchronized (publishLock) {
            publishSerialised(itemToPublish);
        }
    }

    private void publishSerialised(T itemToPublish) {

        Object mappedItem = mapItemSafely(itemToPublish, "publish");
        if (mappedItem == null) {
            log.fine("mapped itemToPublish is null");
            return;
        }

        publishCounter.increment();
        sequenceNumber++;
        journalItem(mappedItem, sequenceNumber);

        if (log.isLoggable(Level.FINE)) {
            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);
        }

        // the publisher's own reference to a pooled item (the one acquire handed over), held until every queue holds
        // its own: released first, the first consumer to finish could return it to the pool while it was still being
        // offered to the next queue (#54). Released last, it returns the item when no queue took it
        PoolTracker<?> publisherRef = trackerOf(mappedItem);
        try {
            if (cacheEventLog) {
                dispatchCachedEventLogSerialised();
                if (!startComplete || retainEventLogAfterStart) {
                    // Store a detached snapshot in the cache to avoid retaining pooled instances: the cache holds no
                    // reference
                    Object cachedData = (mappedItem instanceof PoolAware)
                            ? String.valueOf(mappedItem)
                            : mappedItem;
                    NamedFeedEventImpl<Object> namedFeedEvent = new NamedFeedEventImpl<>(name)
                            .data(cachedData)
                            .sequenceNumber(sequenceNumber);
                    eventLog.add(namedFeedEvent);
                    // dispatched below, so never replayed from the log
                    cacheReadPointer = eventLog.size();
                }
            }

            dispatch(mappedItem, sequenceNumber);
        } finally {
            if (publisherRef != null) {
                publisherRef.releaseReference();
            }
        }
    }

    public void cache(T itemToCache) {
        if (itemToCache == null) {
            log.fine("itemToCache is null");
            return;
        }
        synchronized (publishLock) {
            cacheSerialised(itemToCache);
        }
    }

    private void cacheSerialised(T itemToCache) {

        Object mappedItem = mapItemSafely(itemToCache, "cache");
        if (mappedItem == null) {
            return;
        }

        if (log.isLoggable(Level.FINE)) {
            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToCache);
        }
        sequenceNumber++;
        journalItem(mappedItem, sequenceNumber);
        if (cacheEventLog) {
            // For explicit cache without publish, detach from pool and store the original instance
            PoolTracker<?> tracker = trackerOf(mappedItem);
            if (tracker != null) {
                tracker.removeFromPool();
            }
            NamedFeedEventImpl<Object> namedFeedEvent = new NamedFeedEventImpl<>(name)
                    .data(mappedItem)
                    .sequenceNumber(sequenceNumber);
            eventLog.add(namedFeedEvent);
        }
    }

    public void publishReplay(ReplayRecord record) {
        if (record == null) {
            log.fine("itemToPublish is null");
            return;
        }
        if (log.isLoggable(Level.FINE)) {
            log.fine("listenerCount:" + targetQueues.size() + " publish:" + record);
        }

        synchronized (publishLock) {
            for (int i = 0, targetQueuesSize = targetQueues.size(); i < targetQueuesSize; i++) {
                NamedQueue namedQueue = targetQueues.get(i);
                OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
                targetQueue.offer(record);
                if (log.isLoggable(Level.FINE)) {
                    log.fine("queue:" + namedQueue.name() + " size:" + targetQueue.size());
                }
            }
        }
    }

    /**
     * The owning source's start is complete: replay what was cached before it to the target queues, and stop adding
     * published items to the event log (unless {@link #setRetainEventLogAfterStart(boolean) retained}). Items given to
     * {@link #cache(Object)} afterwards are still logged and replayed on the next publish or dispatch.
     */
    public void startComplete() {
        synchronized (publishLock) {
            dispatchCachedEventLogSerialised();
            startComplete = true;
        }
    }

    public void dispatchCachedEventLog() {
        synchronized (publishLock) {
            dispatchCachedEventLogSerialised();
        }
    }

    private void dispatchCachedEventLogSerialised() {
        if (cacheReadPointer < eventLog.size()) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("publishing cached items cacheReadPointer:" + cacheReadPointer + " eventLog.size():" + eventLog.size());
            }
            //send updates
            for (int i = cacheReadPointer, eventLogSize = eventLog.size(); i < eventLogSize; i++) {
                NamedFeedEvent<?> cachedFeedEvent = eventLog.get(i);
                // each cached item with ITS sequence number: it was the current one, which a late subscriber's
                // catch-up then carried on every cached item (found by the replay spike, R3)
                dispatch(cachedFeedEvent.data(), cachedFeedEvent.sequenceNumber());
            }

        }
        cacheReadPointer = eventLog.size();
    }

    /**
     * A snapshot of the event log: what was published before {@link #startComplete()}, and what was given to
     * {@link #cache(Object)}. Empty unless {@code cacheEventLog} is set.
     */
    public List<NamedFeedEvent<?>> getEventLog() {
        if (!cacheEventLog) {
            return Collections.emptyList();
        }
        // a snapshot taken under the publish lock: the log is appended to by whichever thread publishes
        synchronized (publishLock) {
            return Collections.unmodifiableList(new ArrayList<>(eventLog));
        }
    }

    private Object mapItemSafely(T item, String context) {
        try {
            Object mapped = dataMapper.apply(item);
            if (mapped == null) {
                log.fine("mappedItem is null");
            }
            if (item != mapped && (item instanceof PoolAware poolAware)) {
                // the input is not published (its mapping is, or nothing is): drop the reference handed over with it
                poolAware.getPoolTracker().releaseReference();
            }
            return mapped;
        } catch (Throwable t) {
            log.severe("data mapping (" + context + ") failed: publisher=" + name + ", nextSequenceNumber=" + (sequenceNumber + 1) + ", item=" + item + ", error=" + t);
            com.telamin.mongoose.service.error.ErrorReporting.report(
                    "EventToQueuePublisher:" + name,
                    "data mapping failed for " + context + ": nextSeq=" + (sequenceNumber + 1) + ", item=" + item,
                    t,
                    com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
            return null;
        }
    }

    private void journalItem(Object mappedItem, long seq) {
        if (journal == null || journalBroken != null) return;
        try {
            // encoded before dispatch, so before a pooled item can return to its pool
            // a copy: the journal owns what it holds, so a codec that reuses its buffer cannot rewrite a journalled item
            // with the feed's next one (the byte-ownership contract of EventCodec; local review of d2c6428)
            journal.append(name, seq, journalCodec.encode(mappedItem).clone());
        } catch (Throwable failed) {
            // never out of publish: from a feed agent it would reach the default error handler, which exits the process
            // (#47 re-review B). The feed stops journalling; its items still carry their numbers, so a replay of the
            // recording stops at the first missing one ("the journal holds no ..."), naming the gap, never joining across it
            journalBroken = String.valueOf(failed);
            String why = "replay journal of feed " + name + " stopped at item #" + seq + " (" + mappedItem + "): " + failed;
            log.severe(why);
            com.telamin.mongoose.service.error.ErrorReporting.report("EventToQueuePublisher:" + name, why, failed,
                    com.telamin.mongoose.service.error.ErrorEvent.Severity.ERROR);
        }
    }

    /** Items dropped because a target queue stayed full for longer than the 10 ms spin bound, over all target queues. */
    public long droppedCount() {
        return droppedCount;
    }

    /** Why this feed's journal stopped (an append or its encoding failed), or null while it journals. */
    public String journalBroken() {
        return journalBroken;
    }

    private void dispatch(Object mappedItem, long seq) {
        // writeToQueue acquires one PoolAware reference per queue
        for (int i = 0, targetQueuesSize = targetQueues.size(); i < targetQueuesSize; i++) {
            NamedQueue namedQueue = targetQueues.get(i);
            OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
            switch (eventWrapStrategy) {
                case SUBSCRIPTION_NOWRAP, BROADCAST_NOWRAP -> writeToQueue(namedQueue,
                        journal == null ? mappedItem : new JournalledItem(seq, mappedItem));
                case SUBSCRIPTION_NAMED_EVENT, BROADCAST_NAMED_EVENT -> {
                    //TODO reduce memory pressure by using copy or a recyclable wrapper if needed
                    NamedFeedEventImpl<Object> namedFeedEvent = new NamedFeedEventImpl<>(name)
                            .data(mappedItem)
                            .sequenceNumber(seq);
                    writeToQueue(namedQueue, namedFeedEvent);
                }
            }
            if (log.isLoggable(Level.FINE)) {
                log.fine("queue:" + namedQueue.name() + " size:" + targetQueue.size());
            }
        }
    }

    private void writeToQueue(NamedQueue namedQueue, Object itemToPublish) {
        OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
        boolean offered = false;
        long startNs = -1;
        final long maxSpinNs = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10); // bound spin to avoid publisher timeouts under contention
        PoolTracker<?> tracker = trackerOf(itemToPublish);
        // the queue's reference, taken before the offer (a consumer may poll and release at once) and kept across
        // retries; the publisher still holds its own, so releasing this one on a drop never returns the item early
        boolean queueRef = false;
        try {
            if (tracker != null) {
                tracker.acquireReference();
                queueRef = true;
            }
            while (!offered) {
                offered = targetQueue.offer(itemToPublish);
                if (!offered) {
                    if (startNs < 0) {
                        startNs = System.nanoTime();
                    } else if (System.nanoTime() - startNs > maxSpinNs) {
                        droppedCount++; // written under publishLock only
                        if (queueRef) {
                            queueRef = false;
                            tracker.releaseReference();
                        }
                        if (logWarning) {
                            log.warning("dropping publish to slow/contended queue: " + namedQueue.name() +
                                    " after ~" + ((System.nanoTime() - startNs) / 1_000_000) + "ms seq:" + sequenceNumber +
                                    " queueSize:" + targetQueue.size());
                        }
                        return;
                    }
                    Thread.onSpinWait();
                }
            }
        } catch (Throwable t) {
            // ensure no queue ref remains on the exception path when the item never reached the queue
            if (queueRef && !offered) {
                try {
                    tracker.releaseReference();
                } catch (Throwable ignored) {
                }
            }
            log.severe("queue write failed: publisher=" + name + ", queue=" + namedQueue.name() + ", seq=" + sequenceNumber + ", item=" + itemToPublish + ", error=" + t);
            com.telamin.mongoose.service.error.ErrorReporting.report(
                    "EventToQueuePublisher:" + name,
                    "queue write failed: queue=" + namedQueue.name() + ", seq=" + sequenceNumber + ", item=" + itemToPublish,
                    t,
                    com.telamin.mongoose.service.error.ErrorEvent.Severity.CRITICAL);
            throw new com.telamin.mongoose.exception.QueuePublishException("Failed to write to queue '" + namedQueue.name() + "' for publisher '" + name + "'", t);
        }
        if (logFine && startNs > 1) {
            long delta = System.nanoTime() - startNs;
            log.fine("spin wait took " + (delta / 1_000_000) + "ms queue:" + namedQueue.name() + " size:" + targetQueue.size());
        }
    }

    private PoolTracker<?> trackerOf(Object item) {
        if (item instanceof JournalledItem journalled) {
            item = journalled.item();
        }
        if (item instanceof PoolAware pa) {
            return pa.getPoolTracker();
        }
        if (item instanceof NamedFeedEvent<?> nfe) {
            Object data = nfe.data();
            if (data instanceof PoolAware pa) {
                return pa.getPoolTracker();
            }
        }
        return null;
    }

    public record NamedQueue(String name, OneToOneConcurrentArrayQueue<Object> targetQueue) {
    }

    public void removeTargetQueueByName(String queueName) {
        if (queueName == null) {
            return;
        }
        // under the publish lock: an unsubscribe on a processor's thread must not shrink the list while a dispatch on
        // the feed's thread is indexing it (an IndexOutOfBounds out of publish exits the process via the agent's
        // error handler). The remove waits at most for the publish in flight.
        synchronized (publishLock) {
            targetQueues.removeIf(q -> queueName.equals(q.name()));
        }
    }
}
