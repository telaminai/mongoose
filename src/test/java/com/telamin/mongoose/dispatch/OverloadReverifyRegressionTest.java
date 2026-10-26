/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.replay.*;
import com.telamin.mongoose.exception.PublishRefusedException;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests from the external re-verification of a6f9c62 on PR #81 (telaminai/mongoose#79): the reviewer's
 * reproductions ({@code review79ext.FollowupReservationTest}), which already assert the fixed behaviour and are kept as
 * written, plus the pooled-reference cleanup when a deferred operation fails.
 */
@Timeout(10)
class OverloadReverifyRegressionTest {
    @Test void nestedPublishOrCacheCannotConsumeOuterReservation() {
        var p = OverloadExternalReviewRegressionTest.publisher("nested-cache", 30);
        p.setCacheEventLog(true);
        var a = new OneToOneConcurrentArrayQueue<Object>(8);
        var b = new OneToOneConcurrentArrayQueue<Object>(2);
        b.offer("filler"); p.addTargetQueue(a, "a"); p.addTargetQueue(b, "b");
        p.journal(new InMemoryEventJournal(), new EventCodec() {
            boolean nested;
            public byte[] encode(Object item) {
                if (item.equals("outer") && !nested) { nested=true; p.publishOrCache("nested"); }
                return item.toString().getBytes(StandardCharsets.UTF_8);
            }
            public Object decode(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
        });
        p.publish("outer");
        var aa = drain(a); var bb = drain(b);
        assertTrue(aa.contains("outer"));
        assertTrue(bb.contains("outer"), "outer returned successfully: a="+aa+", b="+bb);
    }
    @Test void finiteDeferredBatchDrainsIterativelyWithoutStackOverflow() {
        var p = OverloadExternalReviewRegressionTest.publisher("deferred-batch", 30);
        var q = new OneToOneConcurrentArrayQueue<Object>(32768); p.addTargetQueue(q,"q");
        p.journal(new InMemoryEventJournal(), new EventCodec() {
            public byte[] encode(Object item) {
                if (item.equals("outer")) for(int i=0;i<20000;i++) p.publish(i);
                return item.toString().getBytes(StandardCharsets.UTF_8);
            }
            public Object decode(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
        });
        assertDoesNotThrow(() -> p.publish("outer"), "finite deferred batch must not recurse once per item");
        assertEquals(20001,q.size(),"every deferred item was delivered with room available");
        assertNull(p.journalBroken(), "finite nested batch must not break the journal");
    }
    static List<Object> drain(OneToOneConcurrentArrayQueue<Object> q) {
        var items = new ArrayList<Object>(); Object o;
        while((o=q.poll())!=null) items.add(o instanceof JournalledItem j ? j.item() : o);
        return items;
    }
    @Test void interruptDuringMonitorAcquisitionCancelsAlreadyWaitingPublish() throws Exception {
        var p = OverloadExternalReviewRegressionTest.publisher("interrupt-monitor", 5000);
        var q = OverloadExternalReviewRegressionTest.full(); p.addTargetQueue(q,"q");
        var result = new AtomicReference<Object>();
        Thread sender = new Thread(() -> {
            try { p.publish("late"); result.set("APPLIED"); } catch(Throwable t) { result.set(t); }
        });
        sender.start();
        try {
            var waiters=(Set<?>)OverloadExternalReviewRegressionTest.field(p,"waiters");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(waiters.isEmpty()&&System.nanoTime()<end) Thread.onSpinWait();
            assertFalse(waiters.isEmpty());
            synchronized(OverloadExternalReviewRegressionTest.field(p,"publishLock")) {
                q.poll();
                end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(sender.getState()!=Thread.State.BLOCKED&&System.nanoTime()<end) Thread.onSpinWait();
                assertEquals(Thread.State.BLOCKED,sender.getState(),"sender passed capacity trigger and is acquiring monitor");
                sender.interrupt();
            }
            sender.join(2000);
            assertFalse(sender.isAlive());
            var refusal=assertInstanceOf(PublishRefusedException.class,result.get(),"interrupted waiter must not commit");
            assertEquals(PublishRefusedException.Reason.INTERRUPTED,refusal.reason());
        } finally { p.cancelBlockedPublishes(); sender.interrupt(); sender.join(2000); }
    }

    static final class Msg implements com.telamin.mongoose.service.pool.PoolAware {
        final com.telamin.mongoose.service.pool.impl.PoolTracker<Msg> tracker = new com.telamin.mongoose.service.pool.impl.PoolTracker<>();
        public com.telamin.mongoose.service.pool.impl.PoolTracker<Msg> getPoolTracker() { return tracker; }
    }

    /**
     * Finding B, cleanup: when a deferred operation throws during the drain, the pooled items still queued behind it are
     * released, each exactly once, and the failure reaches the outermost caller.
     */
    @Test void aFailingDeferredOperationReleasesThePooledItemsQueuedBehindIt() {
        var pool = com.telamin.mongoose.service.pool.impl.Pools.SHARED.getOrCreate(Msg.class, Msg::new, m -> { }, 2);
        try {
            var p = OverloadExternalReviewRegressionTest.publisher("deferred-failure", 30);
            var q = new OneToOneConcurrentArrayQueue<Object>(64) {
                @Override public boolean offer(Object e) {
                    if ("boom".equals(e instanceof JournalledItem j ? j.item() : e)) throw new IllegalStateException("offer failed");
                    return super.offer(e);
                }
            };
            p.addTargetQueue(q, "q");
            Msg[] held = new Msg[2];
            p.journal(new InMemoryEventJournal(), new EventCodec() {
                public byte[] encode(Object item) {
                    if ("outer".equals(item)) {
                        held[0] = pool.acquire(); held[1] = pool.acquire();
                        p.publish(held[0]); p.publish("boom"); p.publish(held[1]);
                    }
                    return String.valueOf(item).getBytes(StandardCharsets.UTF_8);
                }
                public Object decode(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
            });
            assertThrows(RuntimeException.class, () -> p.publish("outer"), "the failure reaches the outermost caller");
            assertEquals(0, held[1].tracker.currentRefCount(), "the item queued behind the failure was released");
            assertEquals(1, pool.availableCount(), "returned to the pool exactly once (the first is still queued)");
            var first = drain(q);
            assertTrue(first.contains(held[0]) && first.contains("outer"), first.toString());
        } finally {
            com.telamin.mongoose.service.pool.impl.Pools.SHARED.remove(Msg.class);
        }
    }
}
