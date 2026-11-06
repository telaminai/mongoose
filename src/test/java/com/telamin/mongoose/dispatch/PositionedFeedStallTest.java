/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.dispatch;

import com.telamin.mongoose.dutycycle.EventQueueToEventProcessorAgent;
import com.telamin.mongoose.service.CallBackType;
import com.telamin.mongoose.service.EventSourceKey;
import com.telamin.mongoose.service.delivery.SourceCommitter;
import com.telamin.mongoose.service.feedstatus.FeedState;
import com.telamin.mongoose.service.feedstatus.FeedStatusReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F2 of the delivery review (docs/design/feed-delivery.md sections 3 and 7): when the last consumer of a positioned
 * feed goes (an unsubscribe, or a quarantine deregistering the only processor), the source pauses, nothing it did not
 * process is acknowledged, and {@code feed.status} reports the feed stalled, never settled.
 */
class PositionedFeedStallTest {

    @Test
    void theLastConsumerGoneStallsThePositionedFeedAndTheReportSaysSo() {
        EventFlowManager fm = new EventFlowManager();
        EventToQueuePublisher<String> pub = fm.registerEventSource("feed", new ProcessingCheckpointRebindTest.NoSource());
        List<Object> acked = new ArrayList<>();
        pub.acknowledgeWith(new SourceCommitter<Object>() {
            @Override public void acknowledged(long sequence, Object position) { acked.add(position); }
        });
        pub.startComplete();
        var group = new ProcessingCheckpointRebindTest.Group();
        var sink = new ProcessingCheckpointRebindTest.Sink();
        var reader = (EventQueueToEventProcessorAgent) fm.getMappingAgent(new EventSourceKey<>("feed"),
                CallBackType.ON_EVENT_CALL_BACK, group);
        reader.registerProcessor(sink);
        for (long i = 1; i <= 3; i++) assertTrue(pub.tryPublish("x" + i, i));
        reader.doWork();
        assertEquals(3, sink.got.size());
        for (long i = 4; i <= 6; i++) assertTrue(pub.tryPublish("x" + i, i));   // queued, never processed
        pub.reportFeedStatus(FeedState.CAUGHT_UP, false, null);
        pub.pollAcknowledgements();
        assertEquals(List.of(1L, 2L, 3L), acked);
        assertNull(pub.stallReason());

        reader.deregisterProcessor(sink);   // the only consumer leaves, with 3 items unprocessed
        assertFalse(pub.tryPublish("x7", 7L), "no live consumer: the source must hold its position");
        pub.pollAcknowledgements();
        assertEquals(List.of(1L, 2L, 3L), acked, "the removed consumer's unprocessed items are not acknowledged");
        assertNotNull(pub.stallReason());
        FeedStatusReport report = fm.feedStatus(List.of("feed"));
        FeedStatusReport.Feed feed = report.feed("feed");
        assertEquals(0, feed.delivery().consumers());
        assertNotNull(feed.delivery().stalled(), report.toString());
        assertFalse(feed.settled(), "a stalled positioned feed is not settled: " + report);
        assertFalse(report.settled());
        assertTrue(feed.lossless(), "nothing was lost: the pointer still holds every unprocessed item");
        assertTrue(report.toJson().contains("\"consumers\":0,\"stalled\":\"no live consumer"), report.toJson());

        // a new consumer: reading resumes, but the removed one's items keep the commit back until a restart
        var sink2 = new ProcessingCheckpointRebindTest.Sink();
        var reader2 = (EventQueueToEventProcessorAgent) fm.getMappingAgent(new EventSourceKey<>("feed"),
                CallBackType.ON_EVENT_CALL_BACK, group);
        reader2.registerProcessor(sink2);
        assertTrue(pub.tryPublish("x7", 7L));
        reader2.doWork();
        pub.pollAcknowledgements();
        assertEquals(List.of(1L, 2L, 3L), acked, "never past an item nobody processed");
        assertTrue(pub.stallReason().startsWith("a removed consumer left unprocessed items"), pub.stallReason());
    }
}
