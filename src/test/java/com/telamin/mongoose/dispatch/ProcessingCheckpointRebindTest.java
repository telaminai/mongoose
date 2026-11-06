/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.input.EventFeed;
import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSource;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.EventSubscriptionKey;
import com.telamin.mongoose.service.delivery.ProcessingCheckpoint;
import com.telamin.mongoose.service.delivery.SourceCommitter;
import org.agrona.concurrent.Agent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3 of the delivery review (docs/design/feed-delivery.md section 5): a processing checkpoint's durable mark belongs
 * to the queue it was taken on. After an unsubscribe and a resubscribe (a new queue, counting from 0) a mark of the old
 * queue must not let the new queue's items be acknowledged before its own state is durable.
 */
class ProcessingCheckpointRebindTest {

    public static final class Group implements Agent {
        @Override public int doWork() { return 0; }
        @Override public String roleName() { return "g"; }
    }

    public static final class Sink implements DataFlow {
        final List<Object> got = new ArrayList<>();
        @Override public void onEvent(Object e) { got.add(e); }
        @Override public void init() {}
        @Override public void start() {}
        @Override public void tearDown() {}
        @Override public void addEventFeed(EventFeed f) {}
    }

    public static final class NoSource implements EventSource<String> {
        @Override public void subscribe(EventSubscriptionKey<String> k) {}
        @Override public void unSubscribe(EventSubscriptionKey<String> k) {}
        @Override public void setEventToQueuePublisher(EventToQueuePublisher<String> p) {}
    }

    @Test
    void aMarkOfTheOldQueueDoesNotReleaseTheNewQueuesItems() {
        EventFlowManager fm = new EventFlowManager();
        EventToQueuePublisher<String> pub = fm.registerEventSource("feed", new NoSource());
        List<Object> acked = new ArrayList<>();
        pub.acknowledgeWith(new SourceCommitter<Object>() {
            @Override public void acknowledged(long sequence, Object position) { acked.add(position); }
        });
        pub.startComplete();
        Group group = new Group();
        ProcessingCheckpoint checkpoint = fm.processingCheckpoint("feed", "g");

        Sink sink = new Sink();
        var reader = (EventQueueToEventProcessorAgent) fm.getMappingAgent(new EventSourceKey<>("feed"),
                CallBackType.ON_EVENT_CALL_BACK, group);
        reader.registerProcessor(sink);
        for (long i = 1; i <= 5; i++) assertTrue(pub.tryPublish("a" + i, i));
        reader.doWork();
        assertEquals(5, sink.got.size());
        checkpoint.durableThrough(checkpoint.mark());
        pub.pollAcknowledgements();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), acked, "durable through the 5 processed");

        // unsubscribe (the reader's last listener leaves), then subscribe again: a new queue, counting from 0
        reader.deregisterProcessor(sink);
        Sink again = new Sink();
        var reader2 = (EventQueueToEventProcessorAgent) fm.getMappingAgent(new EventSourceKey<>("feed"),
                CallBackType.ON_EVENT_CALL_BACK, group);
        reader2.registerProcessor(again);
        for (long i = 6; i <= 8; i++) assertTrue(pub.tryPublish("b" + i, i));
        reader2.doWork();
        assertEquals(3, again.got.size());
        pub.pollAcknowledgements();
        assertEquals(5, acked.size(), "processed on the new queue but not yet durable: not acknowledged " + acked);

        checkpoint.durableThrough(checkpoint.mark());
        pub.pollAcknowledgements();
        assertEquals(8L, acked.get(acked.size() - 1), "durable on the new queue: acknowledged");
    }
}
