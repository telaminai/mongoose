/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.spike;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.fluxtion.runtime.eventfeed.BaseEventFeed;
import com.telamin.fluxtion.runtime.eventfeed.ReadStrategy;
import com.telamin.fluxtion.runtime.node.EventSubscription;
import com.telamin.mongoose.dispatch.EventFlowManager;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.extension.AbstractAgentHostedEventSourceService;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

/**
 * SPIKE — adapter that hosts a Mongoose {@link AbstractAgentHostedEventSourceService}
 * (an {@code EventSource}) inside a fluxtion {@code DataFlowConnector} by presenting it as an
 * {@code EventFeedAgent} (via {@link BaseEventFeed}).
 *
 * <p>The friction the spike surfaced: a Mongoose EventSource gets its output queue from the
 * {@code EventFlowManager} (the no-op {@code setEventToQueuePublisher} default isn't the wiring
 * path), and it publishes <em>wrapped</em> {@link NamedFeedEvent}s. So the adapter:
 * <ol>
 *   <li>stands up a standalone {@code EventFlowManager} (works with NoOp counters), calls
 *       {@code setEventFlowManager} to bind the source's {@code output}, and attaches its own
 *       bridge queue as a target;</li>
 *   <li>in {@code doWork()} drives the source (agent), drains the bridge, unwraps the
 *       {@code NamedFeedEvent}, and re-{@code publish()}es the payload to the connector's
 *       subscribers.</li>
 * </ol>
 * Conclusion: feasible, but ~30 lines of glue + the EventFlowManager coupling — not the thin
 * shim the interfaces alone suggested. A clean convergence would decouple {@code EventSource}
 * from {@code EventFlowManager} behind a shared feed SPI.
 */
public class EventSourceFeedAgent<T> extends BaseEventFeed<T> {

    private final AbstractAgentHostedEventSourceService<T> source;
    private final OneToOneConcurrentArrayQueue<Object> bridge = new OneToOneConcurrentArrayQueue<>(1024);
    private volatile boolean started;

    public EventSourceFeedAgent(String feedName, AbstractAgentHostedEventSourceService<T> source) {
        super(feedName, false, ReadStrategy.EARLIEST, true);
        this.source = source;
        // EventFlowManager works standalone (countersService defaults to NoOp). setEventFlowManager
        // assigns source.output = the publisher registerEventSource builds; attach our bridge queue.
        EventFlowManager eventFlowManager = new EventFlowManager();
        source.setEventFlowManager(eventFlowManager, feedName);
        EventToQueuePublisher<T> publisher = eventFlowManager.registerEventSource(feedName, source);
        publisher.addTargetQueue(bridge, feedName);
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    public void onStart() {
        source.init();
        source.start();
        source.startComplete();
        started = true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int doWork() throws Exception {
        int work = source.doWork(); // source drains its pending queue → output.publish → bridge
        Object item;
        while ((item = bridge.poll()) != null) {
            Object payload = (item instanceof NamedFeedEvent) ? ((NamedFeedEvent<?>) item).data() : item;
            publish((T) payload); // fan to the connector's DataFlow subscribers
            work++;
        }
        return work;
    }

    @Override
    protected boolean validSubscription(DataFlow subscriber, EventSubscription<?> subscriptionId) {
        return true; // broadcast: every subscriber receives every event
    }
}
