package com.telamin.mongoose.extreview.replay;

import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayEntry;
import com.telamin.mongoose.replay.ReplayStreams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * buildAndStart consumes the per-name build ordinal before start() runs, and a start() that throws records nothing in
 * the stream. Hypothesis: recorded run = [add p: start fails] [add p: starts, gets service] gives streams p=empty and
 * p#2=[ServiceChanged]. A replay whose first add of p starts fine (the failure was environmental) replays the empty
 * stream to completion, the second add is a duplicate name, and the replay reports complete() with no stop reason
 * though p#2 was never replayed.
 */
class RecordedStartFailureReplaysAsSuccessTest {

    @Test
    void aRecordedStartFailureMustNotLeaveAStreamThatReplaysAsSuccess() throws Exception {
        var store = new InMemoryReplayStore();
        var service = ExtReviewRig.rate(7);
        var liveSecond = new ExtReviewRig.Probe();
        try (var rig = new ExtReviewRig(store, false, Duration.ofSeconds(5))) {
            rig.group.onStart();
            var failing = new ExtReviewRig.Probe();
            failing.failStart = true;
            var firstAdd = rig.add(failing);
            rig.work(3);
            assertFalse(firstAdd.reply().getNow(null).applied(), "the first add failed to start: " + firstAdd.reply().getNow(null));
            assertFalse(rig.group.isProcessorRegistered("p"));
            var secondAdd = rig.add(liveSecond);
            rig.work(3);
            assertTrue(secondAdd.reply().getNow(null).applied(), String.valueOf(secondAdd.reply().getNow(null)));
            rig.broadcast(service, true, 1);
            assertEquals(List.of("start", "register:7:true"), liveSecond.trace);
        }
        String second = ReplayStreams.key("p", 2);
        System.out.println("stream p    = " + store.entries("p") + " has=" + store.has("p"));
        System.out.println("stream p#2  = " + store.entries(second));
        assertTrue(store.entries(second).stream().anyMatch(e -> e instanceof ReplayEntry.ServiceChanged),
                "the second build's stream holds the recorded service change");

        var replayed = new ExtReviewRig.Probe();
        try (var rig = new ExtReviewRig(store, true, Duration.ofSeconds(5))) {
            rig.group.onStart();
            var firstAdd = rig.add(replayed);         // this time start() succeeds
            rig.work(3);
            assertTrue(firstAdd.reply().getNow(null).applied());
            var duplicate = rig.add(new ExtReviewRig.Probe());
            rig.work(3);
            assertFalse(duplicate.reply().getNow(null).applied(), "a running name is not replaced");
            rig.services.put(service.serviceName(), service);
            rig.group.broadcastService(service, true, 1);
            rig.work(10);
            var replayer = rig.group.replayer();
            System.out.println("replay: complete=" + replayer.complete() + " stopped(p)=" + replayer.stopped("p")
                    + " stopped(p#2)=" + replayer.stopped(second) + " trace=" + replayed.trace);
            assertTrue(replayer.stopped("p") != null || replayer.stopped(second) != null || !replayer.complete(),
                    "the recorded service change (stream p#2) was never replayed, yet the replay reads as complete and "
                            + "unstopped; trace=" + replayed.trace);
        }
    }
}
