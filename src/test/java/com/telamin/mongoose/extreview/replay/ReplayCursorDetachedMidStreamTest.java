package com.telamin.mongoose.extreview.replay;

import com.telamin.mongoose.replay.InMemoryReplayStore;
import com.telamin.mongoose.replay.ReplayEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract (control-plane-contracts.md, "When a replay stops"): "complete() with a null stopped(...) means every entry was
 * delivered." Hypothesis: a replayed processor stopped LIVE (by request) while its stream is mid-way drops its cursor
 * in GroupReplayer.detach without recording anything, so complete() turns true and stopped() stays null although
 * entries were never delivered.
 */
class ReplayCursorDetachedMidStreamTest {

    @Test
    void aLiveStopMidStreamMustNotReadAsACompleteSuccessfulReplay() throws Exception {
        var store = new InMemoryReplayStore();
        // a stream of two entries whose first can never be delivered here (no version 1 of "rate" is ever published)
        store.append("p", new ReplayEntry.ServiceChanged("rate", true, 1, 101, List.of()));
        store.append("p", new ReplayEntry.ServiceChanged("rate", false, 2, 102, List.of()));
        var probe = new ExtReviewRig.Probe();
        try (var rig = new ExtReviewRig(store, true, Duration.ofSeconds(60))) {
            rig.add(probe);
            rig.group.onStart();
            rig.work(5);
            var replayer = rig.group.replayer();
            assertFalse(replayer.complete(), "mid-stream: the first entry is waiting for its version");
            assertNull(replayer.stopped("p"));

            // an operator (or an unreplayed processor's live control request) stops the replayed processor now
            rig.group.removeEventProcessorByName("p");
            rig.work(5);
            assertFalse(rig.group.isProcessorRegistered("p"), "stopped live");

            String stopped = replayer.stopped("p");
            boolean complete = replayer.complete();
            System.out.println("after live stop: complete=" + complete + " stopped=" + stopped);
            assertTrue(stopped != null || !complete,
                    "two entries were never delivered, yet complete()=" + complete + " and stopped()=" + stopped);
        }
    }
}
