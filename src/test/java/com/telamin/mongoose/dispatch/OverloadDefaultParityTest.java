/*
 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.internal.NoOpCountersService;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default policy is today's behaviour (telaminai/mongoose#79, working decision 2): a publisher left on the default,
 * and one set explicitly to DROP_AFTER_SPIN with the default bound, behave exactly as before #79 — 10 ms spin, drop
 * for the full queue only, the same first-loss reason text, the same delivered sequence — and never wait or refuse.
 */
@Timeout(30)
class OverloadDefaultParityTest {

    @Test
    void defaultIsDropAfterSpinWithTheTenMillisecondBound() {
        assertEquals(OverloadPolicy.DROP_AFTER_SPIN, OverloadSettings.DEFAULT.policy());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), OverloadSettings.DEFAULT.spinBoundNanos());
        assertEquals(FeedOverloadSettings.DEFAULT, new EventToQueuePublisher<>("feed").overload());
    }

    @Test
    void implicitAndExplicitDefaultBehaveIdenticallyToPre79() {
        Run implicit = run(null);
        Run explicit = run(FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ofMillis(10))));
        for (Run r : List.of(implicit, explicit)) {
            assertEquals(List.of("filler-0", "filler-1", "x0", "x1"), r.slow, "the newest items are dropped, the stale kept");
            assertEquals(List.of("x0", "x1", "x2", "x3", "x4"), r.fast, "the other subscriber gets every item");
            assertEquals(3, r.drops);
            assertEquals(3, r.dropsForSlow);
            assertEquals(5, r.sequence, "dropped items are still committed and numbered, as before #79");
            assertEquals(0, r.refused);
            assertEquals(0, r.downgraded);
            assertTrue(r.firstDropReason.contains("reason: " + com.telamin.mongoose.control.node.ControlAudit.quote(
                    "queue full after 10 ms spin")), r.firstDropReason);
            assertTrue(r.millisPerDrop >= 10, "spun the full 10 ms before each drop: " + r.millisPerDrop);
        }
    }

    @Test
    void theSpinBoundIsConfigurable() {
        Run zero = run(FeedOverloadSettings.of(OverloadSettings.dropAfterSpin(Duration.ZERO)));
        assertEquals(3, zero.drops);
        assertTrue(zero.firstDropReason.contains("queue full after 0 ms spin"), zero.firstDropReason);
    }

    private static Run run(FeedOverloadSettings settings) {
        var publisher = new EventToQueuePublisher<Object>("feed");
        if (settings != null) {
            publisher.setOverload(settings);
        }
        var slow = new OneToOneConcurrentArrayQueue<Object>(4);
        var fast = new OneToOneConcurrentArrayQueue<Object>(8);
        publisher.addTargetQueue(slow, "slow", NoOpCountersService.INSTANCE.queueDropCounter("slow"), null, "slowGroup", "slow");
        publisher.addTargetQueue(fast, "fast", NoOpCountersService.INSTANCE.queueDropCounter("fast"), null, "fastGroup", "fast");
        slow.offer("filler-0");
        slow.offer("filler-1");
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            publisher.publish("x" + i);
        }
        long elapsed = System.nanoTime() - start;
        Run r = new Run();
        Object o;
        while ((o = slow.poll()) != null) r.slow.add(o);
        while ((o = fast.poll()) != null) r.fast.add(o);
        r.drops = publisher.droppedCount();
        r.dropsForSlow = publisher.droppedCount("slow");
        r.sequence = publisher.getSequenceNumber();
        r.refused = publisher.refusedCount();
        r.downgraded = publisher.downgradedCount();
        r.firstDropReason = publisher.getDropAudit().records().getFirst();
        r.millisPerDrop = TimeUnit.NANOSECONDS.toMillis(elapsed) / Math.max(1, r.drops);
        return r;
    }

    static final class Run {
        final List<Object> slow = new ArrayList<>();
        final List<Object> fast = new ArrayList<>();
        long drops, dropsForSlow, sequence, refused, downgraded, millisPerDrop;
        String firstDropReason;
    }
}
