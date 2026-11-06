/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.replay.EventCodec;
import com.telamin.mongoose.replay.InMemoryEventJournal;
import com.telamin.mongoose.service.delivery.SourceCommitter;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent review R2: a positioned cache (a file source's cached startup) is reserved from nesting as a positioned
 * commit is (docs/design/feed-delivery.md, section 3). User code the cache runs (the data mapper, the journal codec)
 * cannot take the outer item's position, nor put another item ahead of it, and a positioned cache deferred by a commit
 * keeps its position.
 */
class PositionedCacheNestingTest {

    private final OneToOneConcurrentArrayQueue<Object> queue = new OneToOneConcurrentArrayQueue<>(16);
    private final EventToQueuePublisher<String> publisher = new EventToQueuePublisher<>("feed");
    private final AtomicLong processed = new AtomicLong();
    private final List<Object> acknowledged = new ArrayList<>();

    PositionedCacheNestingTest() {
        publisher.addTargetQueue(queue, "q");
        publisher.setPauseByDefault(true);
        publisher.setCacheEventLog(true);
        publisher.bindAcknowledgementPoint(queue, processed::get);
        publisher.acknowledgeWith(new SourceCommitter<Object>() {
            @Override
            public void acknowledged(long sequence, Object position) {
                acknowledged.add(position);
            }
        });
    }

    private void mapper(Function<String, ?> mapper) {
        publisher.setDataMapper(mapper);
    }

    private List<String> queued() {
        List<Object> out = new ArrayList<>();
        queue.drainTo(out, 16);
        return out.stream().map(String::valueOf).toList();
    }

    /** The reviewer's case: the mapper of a positioned cache calls tryPublish. */
    @Test
    void aTryPublishFromTheMapperOfAPositionedCacheIsToldToRetry() {
        List<Boolean> nested = new ArrayList<>();
        mapper(item -> {
            if ("outer".equals(item)) nested.add(publisher.tryPublish("nested", 99L));
            return item;
        });
        publisher.cache("outer", 10L);
        publisher.startComplete();
        assertEquals(List.of(false), nested, "the positioned cache needs the same nesting protection as tryPublish");
        assertEquals(List.of("outer"), queued());
        publisher.pollAcknowledgements();
        assertEquals(List.of(), acknowledged, "nothing processed: nothing acknowledged");
        processed.set(1);
        publisher.pollAcknowledgements();
        assertEquals(List.of(10L), acknowledged);
    }

    /** A plain publish from the mapper waits until the outer item is cached: it never takes the outer position. */
    @Test
    void aPublishFromTheMapperOfAPositionedCacheFollowsTheOuterItem() {
        mapper(item -> {
            if ("outer".equals(item)) publisher.publish("nested");
            return item;
        });
        publisher.cache("outer", 10L);
        publisher.startComplete();
        assertEquals(List.of("outer", "nested"), queued());
        processed.set(1);
        publisher.pollAcknowledgements();
        assertEquals(List.of(10L), acknowledged, "the outer item is acknowledged at its own position once processed");
    }

    /** A positioned cache made during another positioned cache is deferred, and keeps its own position. */
    @Test
    void aDeferredPositionedCacheKeepsItsPosition() {
        mapper(item -> {
            if ("outer".equals(item)) publisher.cache("inner", 20L);
            return item;
        });
        publisher.cache("outer", 10L);
        publisher.startComplete();
        assertEquals(List.of("outer", "inner"), queued());
        processed.set(1);
        publisher.pollAcknowledgements();
        assertEquals(List.of(10L), acknowledged, "inner is not processed: 20 is not acknowledged");
        processed.set(2);
        publisher.pollAcknowledgements();
        assertEquals(List.of(10L, 20L), acknowledged);
    }

    /** The journal codec runs inside the positioned cache too: a tryPublish from it is told to retry. */
    @Test
    void aTryPublishFromTheJournalCodecOfAPositionedCacheIsToldToRetry() {
        List<Boolean> nested = new ArrayList<>();
        publisher.journal(new InMemoryEventJournal(), new EventCodec() {
            @Override
            public byte[] encode(Object item) {
                if ("outer".equals(item)) nested.add(publisher.tryPublish("nested", 99L));
                return new byte[0];
            }

            @Override
            public Object decode(byte[] bytes) {
                return null;
            }
        });
        publisher.cache("outer", 10L);
        publisher.startComplete();
        assertEquals(List.of(false), nested);
        processed.set(1);
        publisher.pollAcknowledgements();
        assertEquals(List.of(10L), acknowledged);
    }
}
