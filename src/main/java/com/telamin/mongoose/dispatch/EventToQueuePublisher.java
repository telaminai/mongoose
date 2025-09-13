/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.dispatch;

import com.fluxtion.agrona.concurrent.OneToOneConcurrentArrayQueue;
import com.fluxtion.runtime.event.NamedFeedEvent;
import com.fluxtion.runtime.event.NamedFeedEventImpl;
import com.fluxtion.runtime.event.ReplayRecord;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.pool.PoolAware;
import com.telamin.mongoose.service.pool.impl.PoolTracker;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.java.Log;

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
@RequiredArgsConstructor
@ToString
@Log
@Getter
public class EventToQueuePublisher<T> {

    private final List<NamedQueue> targetQueues = new CopyOnWriteArrayList<>();
    private final List<NamedFeedEvent<?>> eventLog = new ArrayList<>();
    private final String name;
    @Setter
    private boolean cacheEventLog;
    private long sequenceNumber = 0;
    @Setter
    private EventSource.EventWrapStrategy eventWrapStrategy = EventSource.EventWrapStrategy.SUBSCRIPTION_NOWRAP;
    @Setter
    private Function<T, ?> dataMapper = Function.identity();
    private int cacheReadPointer = 0;
    private final boolean logWarning = log.isLoggable(Level.WARNING);
    private final boolean logInfo = log.isLoggable(Level.INFO);
    private final boolean logFine = log.isLoggable(Level.FINE);

    public void addTargetQueue(OneToOneConcurrentArrayQueue<Object> targetQueue, String name) {
        NamedQueue namedQueue = new NamedQueue(name, targetQueue);
        if (log.isLoggable(Level.FINE)) {
            log.fine("adding a publisher queue:" + namedQueue);
        }
        if (!targetQueues.contains(namedQueue)) {
            targetQueues.add(namedQueue);
        }
    }

    public void publish(T itemToPublish) {
        if (itemToPublish == null) {
            log.info("itemToPublish is null");
            return;
        }

        Object mappedItem = mapItemSafely(itemToPublish, "publish");
        if (mappedItem == null) {
            log.fine("mapped itemToPublish is null");
            return;
        }

        sequenceNumber++;

        if (log.isLoggable(Level.FINE)) {
            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToPublish);
        }

        if (cacheEventLog) {
            dispatchCachedEventLog();
            // Store a detached snapshot in the cache to avoid retaining pooled instances
            Object cachedData = (mappedItem instanceof PoolAware)
                    ? String.valueOf(mappedItem)
                    : mappedItem;
            NamedFeedEventImpl<Object> namedFeedEvent = new NamedFeedEventImpl<>(name)
                    .data(cachedData)
                    .sequenceNumber(sequenceNumber);
            eventLog.add(namedFeedEvent);
        } else {
            // Hold a reference while retained in the cache/event log
            PoolTracker<?> tracker = trackerOf(mappedItem);
            if (tracker != null) {
                tracker.releaseReference();
            }
        }

        cacheReadPointer++;
        dispatch(mappedItem);
    }

    public void cache(T itemToCache) {
        if (itemToCache == null) {
            log.fine("itemToCache is null");
            return;
        }

        Object mappedItem = mapItemSafely(itemToCache, "cache");
        if (mappedItem == null) {
            return;
        }

        if (log.isLoggable(Level.FINE)) {
            log.fine("listenerCount:" + targetQueues.size() + " sequenceNumber:" + sequenceNumber + " publish:" + itemToCache);
        }
        sequenceNumber++;
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

        for (int i = 0, targetQueuesSize = targetQueues.size(); i < targetQueuesSize; i++) {
            NamedQueue namedQueue = targetQueues.get(i);
            OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
            targetQueue.offer(record);
            if (log.isLoggable(Level.FINE)) {
                log.fine("queue:" + namedQueue.name() + " size:" + targetQueue.size());
            }
        }
    }

    public void dispatchCachedEventLog() {
        if (cacheReadPointer < eventLog.size()) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("publishing cached items cacheReadPointer:" + cacheReadPointer + " eventLog.size():" + eventLog.size());
            }
            //send updates
            for (int i = cacheReadPointer, eventLogSize = eventLog.size(); i < eventLogSize; i++) {
                NamedFeedEvent<?> cachedFeedEvent = eventLog.get(i);
                dispatch(cachedFeedEvent.data());
            }

        }
        cacheReadPointer = eventLog.size();
    }

    public List<NamedFeedEvent<?>> getEventLog() {
        if (!cacheEventLog) {
            return Collections.emptyList();
        }
        // Return a thread-safe snapshot for concurrent readers while maintaining
        // single-writer performance characteristics for the underlying eventLog.
        return Collections.unmodifiableList(new ArrayList<>(eventLog));
    }

    private Object mapItemSafely(T item, String context) {
        try {
            Object mapped = dataMapper.apply(item);
            if (mapped == null) {
                log.fine("mappedItem is null");
            } else if (item != mapped && (item instanceof PoolAware poolAware)) {
                poolAware.getPoolTracker().releaseReference();
                poolAware.getPoolTracker().returnToPool();
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

    private void dispatch(Object mappedItem) {
        // no-op here; writeToQueue will handle PoolAware reference acquisition per queue
        for (int i = 0, targetQueuesSize = targetQueues.size(); i < targetQueuesSize; i++) {
            NamedQueue namedQueue = targetQueues.get(i);
            OneToOneConcurrentArrayQueue<Object> targetQueue = namedQueue.targetQueue();
            switch (eventWrapStrategy) {
                case SUBSCRIPTION_NOWRAP, BROADCAST_NOWRAP -> writeToQueue(namedQueue, mappedItem);
                case SUBSCRIPTION_NAMED_EVENT, BROADCAST_NAMED_EVENT -> {
                    //TODO reduce memory pressure by using copy or a recyclable wrapper if needed
                    NamedFeedEventImpl<Object> namedFeedEvent = new NamedFeedEventImpl<>(name)
                            .data(mappedItem)
                            .sequenceNumber(sequenceNumber);
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
        try {
            while (!offered) {
                boolean attemptRef = false;
                if (tracker != null) {
                    tracker.acquireReference();
                    attemptRef = true;
                }
                offered = targetQueue.offer(itemToPublish);
                if (!offered) {
                    // release per-attempt ref before retry/abandon
                    if (attemptRef) {
                        tracker.releaseReference();
                    }
                    if (startNs < 0) {
                        startNs = System.nanoTime();
                    } else if (System.nanoTime() - startNs > maxSpinNs) {
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
            // ensure no attempt ref remains on exception path
            if (tracker != null) {
                tracker.returnToPool();
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
        targetQueues.removeIf(q -> queueName.equals(q.name()));
    }
}
