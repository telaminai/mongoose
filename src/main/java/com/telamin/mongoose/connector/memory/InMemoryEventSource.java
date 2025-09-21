/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.memory;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An in-memory event source that allows programmatic offering of events which are
 * then published to the event flow. Supports optional caching of events before
 * startComplete, mirroring the behavior of FileEventSource for pre-start replay.
 */
@Log
@SuppressWarnings("all")
public class InMemoryEventSource<T> extends AbstractAgentHostedEventSourceService<T> {

    private final ConcurrentLinkedQueue<T> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean startComplete = new AtomicBoolean(false);

    @Getter
    @Setter
    private boolean cacheEventLog = false;

    private boolean publishToQueue = false;

    public InMemoryEventSource() {
        super("inMemoryEventFeed");
    }

    @Override
    public void start() {
        // configure caching behavior on publisher
        output.setCacheEventLog(cacheEventLog);
        if (cacheEventLog) {
            // Pre-start: accept items but cache them without publishing
            publishToQueue = false;
        } else {
            publishToQueue = true;
        }
    }

    @Override
    public void startComplete() {
        startComplete.set(true);
        publishToQueue = true;
        // Replay anything cached in the publisher
        output.dispatchCachedEventLog();
    }

    /**
     * Offer an event into this source. Thread-safe.
     * Items are queued and dispatched on the agent thread via doWork to
     * honor back-pressure and lifecycle semantics similar to file source.
     */
    public void offer(T item) {
        if (item == null) {
            return;
        }
        pending.offer(item);
    }

    /**
     * Publish immediately bypassing internal queue. Respects caching state.
     */
    public void publishNow(T item) {
        if (item == null) return;
        if (publishToQueue) {
            output.publish(item);
        } else {
            output.cache(item);
        }
    }

    @Override
    public int doWork() throws Exception {
        int count = 0;
        T item;
        while ((item = pending.poll()) != null) {
            if (publishToQueue) {
                output.publish(item);
            } else {
                output.cache(item);
            }
            count++;
        }
        return count;
    }

    @Override
    public NamedFeedEvent<T>[] eventLog() {
        List<NamedFeedEvent> eventLog = (List) output.getEventLog();
        return eventLog.toArray(new NamedFeedEvent[0]);
    }

    @Override
    public String getFeedName() {
        return getName();
    }

    // for testing
    void setOutput(EventToQueuePublisher<?> output) {
        this.output = (EventToQueuePublisher<T>) output;
    }
}