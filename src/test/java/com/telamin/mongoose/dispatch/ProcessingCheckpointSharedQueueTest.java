/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.delivery.ProcessingCheckpoint;
import com.telamin.mongoose.service.delivery.SourceCommitter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding 5 (docs/design/feed-delivery.md section 5): a processor group bound to one feed by two callback types shares
 * one physical queue. Its processing checkpoint belongs to that queue, not to the last binding made: a second binding
 * must not replace it, and one binding leaving must neither unbind the checkpoint nor drop the queue's dispatched
 * counter while the other binding still reads the queue.
 */
class ProcessingCheckpointSharedQueueTest {

    private record Fixture(EventFlowManager fm, EventToQueuePublisher<String> pub, List<Object> acked,
                           ProcessingCheckpointRebindTest.Group group) {
    }

    private static Fixture fixture() {
        EventFlowManager fm = new EventFlowManager();
        fm.registerEventMapperFactory(EventToOnEventInvokeStrategy::new, String.class);
        EventToQueuePublisher<String> pub = fm.registerEventSource("feed", new ProcessingCheckpointRebindTest.NoSource());
        List<Object> acked = new ArrayList<>();
        pub.acknowledgeWith(new SourceCommitter<Object>() {
            @Override public void acknowledged(long sequence, Object position) { acked.add(position); }
        });
        pub.startComplete();
        return new Fixture(fm, pub, acked, new ProcessingCheckpointRebindTest.Group());
    }

    private static EventQueueToEventProcessorAgent bind(Fixture f, CallBackType type) {
        return (EventQueueToEventProcessorAgent) f.fm().getMappingAgent(new EventSourceKey<>("feed"), type, f.group());
    }

    @Test
    void aHeldCheckpointReleasesAfterOneOfTwoBindingsOfTheQueueLeaves() {
        Fixture f = fixture();
        ProcessingCheckpoint checkpoint = f.fm().processingCheckpoint("feed", "g");
        var s1 = new ProcessingCheckpointRebindTest.Sink();
        var s2 = new ProcessingCheckpointRebindTest.Sink();
        var r1 = bind(f, CallBackType.ON_EVENT_CALL_BACK);
        r1.registerProcessor(s1);
        var r2 = bind(f, CallBackType.forClass(String.class));
        r2.registerProcessor(s2);
        r2.deregisterProcessor(s2);          // the second binding leaves; the first stays on the shared queue
        for (long i = 1; i <= 3; i++) assertTrue(f.pub().tryPublish("a" + i, i));
        r1.doWork();
        assertEquals(3, s1.got.size());
        ProcessingCheckpoint.Mark mark = checkpoint.mark();
        assertNotEquals(-1, mark.binding(), "still bound to the queue the first binding reads");
        assertEquals(3, mark.count());
        f.pub().pollAcknowledgements();
        assertEquals(List.of(), f.acked(), "held: nothing durable yet");
        checkpoint.durableThrough(mark);
        f.pub().pollAcknowledgements();
        assertEquals(List.of(1L, 2L, 3L), f.acked(), "durable through the 3 processed: acknowledged");
        assertTrue(f.fm().feedStatus(List.of("feed")).feeds().get(0).subscribers().get(0).dispatched() >= 3,
                "the shared queue's dispatched counter survives the other binding leaving");
    }

    @Test
    void aSecondBindingOfTheSameQueueKeepsTheCheckpointsDurableMark() {
        Fixture f = fixture();
        ProcessingCheckpoint checkpoint = f.fm().processingCheckpoint("feed", "g");
        var s1 = new ProcessingCheckpointRebindTest.Sink();
        var r1 = bind(f, CallBackType.ON_EVENT_CALL_BACK);
        r1.registerProcessor(s1);
        for (long i = 1; i <= 2; i++) assertTrue(f.pub().tryPublish("a" + i, i));
        r1.doWork();
        checkpoint.durableThrough(checkpoint.mark());
        // a second callback type of the same group binds the same queue: the same binding, its mark still valid
        var s2 = new ProcessingCheckpointRebindTest.Sink();
        bind(f, CallBackType.forClass(String.class)).registerProcessor(s2);
        f.pub().pollAcknowledgements();
        assertEquals(List.of(1L, 2L), f.acked(), "the durable mark of the shared queue still counts");
    }
}
