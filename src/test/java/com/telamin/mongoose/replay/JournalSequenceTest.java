package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.event.NamedFeedEvent;
import com.telamin.mongoose.dispatch.EventToQueuePublisher;
import com.telamin.mongoose.service.EventSource;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R3: every queue item carries its own sequence number, a late subscriber's cached catch-up included. */
class JournalSequenceTest {

    static List<Object> drain(OneToOneConcurrentArrayQueue<Object> q) {
        List<Object> out = new ArrayList<>();
        for (Object o; (o = q.poll()) != null; ) out.add(o);
        return out;
    }

    @Test
    void aLateSubscribersCachedItemsCarryTheirOwnSequenceNumbers() {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("orders");
        publisher.setCacheEventLog(true);
        publisher.setEventWrapStrategy(EventSource.EventWrapStrategy.SUBSCRIPTION_NAMED_EVENT);
        // items cached before the feed starts (as InMemoryEventSource does before startComplete), then sent at once
        publisher.cache("ord-1");
        publisher.cache("ord-2");
        publisher.cache("ord-3");
        OneToOneConcurrentArrayQueue<Object> late = new OneToOneConcurrentArrayQueue<>(16);
        publisher.addTargetQueue(late, "late");
        publisher.dispatchCachedEventLog();
        List<Long> seqs = drain(late).stream().map(o -> ((NamedFeedEvent<?>) o).sequenceNumber()).toList();
        assertEquals(List.of(1L, 2L, 3L), seqs, "each cached item with its own number, not the latest");
    }

    @Test
    void aJournalledNowrapItemCarriesItsSequenceNumber_andIsJournalledOnce() {
        EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("orders");
        InMemoryEventJournal journal = new InMemoryEventJournal();
        JavaSerializationCodec codec = new JavaSerializationCodec();
        publisher.journal(journal, codec);
        OneToOneConcurrentArrayQueue<Object> a = new OneToOneConcurrentArrayQueue<>(16), b = new OneToOneConcurrentArrayQueue<>(16);
        publisher.addTargetQueue(a, "a");
        publisher.addTargetQueue(b, "b");
        publisher.publish("ord-1");
        publisher.publish("ord-2");
        assertEquals(List.of(new JournalledItem(1, "ord-1"), new JournalledItem(2, "ord-2")), drain(a));
        assertEquals(List.of(new JournalledItem(1, "ord-1"), new JournalledItem(2, "ord-2")), drain(b));
        assertEquals(2, journal.size("orders"), "once for both queues");
        assertEquals("ord-2", codec.decode(journal.get("orders", 2)));
    }
}
