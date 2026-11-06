/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.service.feedstatus.FeedState;
import com.telamin.mongoose.service.feedstatus.FeedStatus;
import com.telamin.mongoose.service.feedstatus.FeedStatusReport;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent delta review of PR #98, finding 2: nine cached items into an eight-slot PAUSE queue, the startup status
 * waiting behind them. A later report replaced the waiting startup status and with it the hold on completion
 * ({@code cachedPending}); once the last cached item was dispatched the host saw the startup CAUGHT_UP, taken at queue
 * position 0, and reported the feed settled and drained with eight items unprocessed. The hold is now kept until the
 * feed's status has been delivered and its positions refreshed, whichever status wins.
 */
class CachedStartupStatusReplacementTest {

    private record Setup(EventFlowManager manager, EventToQueuePublisher<String> publisher,
                         OneToOneConcurrentArrayQueue<Object> queue) {
    }

    private static Setup nineCachedIntoEight() {
        EventFlowManager manager = new EventFlowManager();
        EventToQueuePublisher<String> p = manager.registerEventSource("feed",
                new com.telamin.mongoose.connector.file.FileEventSource());
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        p.addTargetQueue(q, "q");
        p.setPauseByDefault(true);
        p.setCacheEventLog(true);
        p.setDeliverFeedStatus(true);
        for (int i = 0; i < 9; i++) p.cache("item" + i);
        p.reportFeedStatus(FeedState.CAUGHT_UP, false, "startup");
        p.startComplete();
        assertTrue(p.feedPosition().cachedPending());
        return new Setup(manager, p, q);
    }

    private static FeedStatusReport report(Setup s) {
        return s.manager().feedStatus(List.of("feed"));
    }

    @Test
    void replacingAWaitingStartupStatusStillHoldsDrainUntilDelivery() {
        Setup s = nineCachedIntoEight();
        s.publisher().reportFeedStatus(FeedState.BEHIND, false, "new report while startup paused");
        assertNotNull(s.queue().poll());          // one slot: enough for the last cached item, not its status
        assertFalse(s.publisher().flushPaused());
        FeedStatusReport report = report(s);
        assertFalse(report.settled(), "must not claim caught up and drained while cached work and its status remain; "
                + report.toJson());
        assertTrue(s.publisher().feedPosition().cachedPending());
    }

    @Test
    void theReplacingStatusIsDeliveredAfterTheCacheAndTheFeedSettlesOnlyOnceEverythingIsProcessed() {
        Setup s = nineCachedIntoEight();
        s.publisher().reportFeedStatus(FeedState.BEHIND, false, "while paused");
        s.publisher().reportFeedStatus(FeedState.CAUGHT_UP, false, "caught up again");
        List<Object> taken = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            for (Object x; (x = s.queue().poll()) != null; ) taken.add(x);
            s.publisher().flushPaused();
        }
        assertEquals(10, taken.size(), taken.toString());
        for (int i = 0; i < 9; i++) assertEquals("item" + i, String.valueOf(taken.get(i)));
        assertInstanceOf(FeedStatus.class, taken.get(9), "the latest status, after every cached item");
        assertEquals(FeedState.CAUGHT_UP, ((FeedStatus) taken.get(9)).state());
        assertEquals("caught up again", ((FeedStatus) taken.get(9)).detail());
        assertFalse(s.publisher().feedPosition().cachedPending());
        assertEquals(10, s.publisher().feedPosition().positionOf(s.queue()), "positions taken after the status");
    }

    /**
     * Hook review R3: A, then B, then A again with the same detail while the startup status waits. The last report
     * equalled the published (still undelivered) startup status, so it was ignored and the waiting B was delivered:
     * the feed stayed BEHIND although its latest report said CAUGHT_UP.
     */
    @Test
    void aReturnToThePublishedStatusWhileAnotherWaitsIsTheOneDelivered() {
        Setup s = nineCachedIntoEight();
        s.publisher().reportFeedStatus(FeedState.BEHIND, false, "while paused");
        s.publisher().reportFeedStatus(FeedState.CAUGHT_UP, false, "startup");
        List<Object> taken = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            for (Object x; (x = s.queue().poll()) != null; ) taken.add(x);
            s.publisher().flushPaused();
        }
        assertEquals(10, taken.size(), taken.toString());
        assertInstanceOf(FeedStatus.class, taken.get(9));
        assertEquals(FeedState.CAUGHT_UP, ((FeedStatus) taken.get(9)).state(), "the latest report: " + taken);
        assertTrue(s.publisher().flushPaused(), "nothing left waiting");
        assertFalse(s.publisher().feedPosition().cachedPending());
        assertEquals(FeedState.CAUGHT_UP, s.publisher().feedPosition().status().state());
        assertEquals(10, s.publisher().feedPosition().positionOf(s.queue()));
    }

    /**
     * After the start, a guard for the coalescing: CAUGHT_UP is delivered, a full PAUSE queue makes a BEHIND report
     * wait, then CAUGHT_UP is reported again at a later sequence. The waiting BEHIND is replaced: subscribers get the
     * items, then CAUGHT_UP, never BEHIND.
     */
    @Test
    void aLaterReportReplacesTheWaitingOne() {
        EventFlowManager manager = new EventFlowManager();
        EventToQueuePublisher<String> p = manager.registerEventSource("feed",
                new com.telamin.mongoose.connector.file.FileEventSource());
        OneToOneConcurrentArrayQueue<Object> q = new OneToOneConcurrentArrayQueue<>(8);
        p.addTargetQueue(q, "q");
        p.setPauseByDefault(true);
        p.setDeliverFeedStatus(true);
        p.startComplete();
        p.reportFeedStatus(FeedState.CAUGHT_UP, false, "idle");
        for (Object x; (x = q.poll()) != null; ) { }
        for (int i = 0; i < 8; i++) assertTrue(p.tryPublish("item" + i, null), "room for item" + i);
        p.reportFeedStatus(FeedState.BEHIND, false, "busy");          // no room: waits
        p.reportFeedStatus(FeedState.CAUGHT_UP, false, "idle");       // the latest report
        List<Object> taken = new ArrayList<>();
        for (Object x; (x = q.poll()) != null; ) taken.add(x);
        assertTrue(p.flushPaused(), "nothing waits once there is room");
        for (Object x; (x = q.poll()) != null; ) taken.add(x);
        assertEquals(9, taken.size(), taken.toString());
        assertEquals(FeedState.CAUGHT_UP, ((FeedStatus) taken.get(8)).state());
        assertTrue(taken.stream().noneMatch(x -> x instanceof FeedStatus f && f.state() == FeedState.BEHIND), taken.toString());
    }
}
