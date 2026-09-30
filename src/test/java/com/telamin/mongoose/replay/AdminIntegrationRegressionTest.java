package com.telamin.mongoose.replay;

import com.telamin.mongoose.replay.ReplayIndependentReviewTest.ProbeProcessor;
import com.telamin.mongoose.replay.ReplayIndependentReviewTest.Server;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.telamin.mongoose.replay.ReplayIndependentReviewTest.boot;
import static com.telamin.mongoose.replay.ReplayIndependentReviewTest.invoke;
import static com.telamin.mongoose.replay.ReplayIndependentReviewTest.one;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #48 integrated with released main (1.0.31): main's recorder is told each processor's input by a per-target hook that
 * runs BEFORE the invoker claims an admin command, so receiving a command is not proof it ran. Only the post-dispatch
 * outcome ({@code AdminCommand.ran()}, checked in {@code GroupRecorder.afterDispatch}) keeps refused work out of the
 * recording. The cancelled case is {@code AdminCorrectionRegressionTest#n1_...}; this is the refused one, through a
 * real server's queue, invoker, recorder and replayer.
 */
class AdminIntegrationRegressionTest {

    /** A processor whose declared event cycle fails while setting up, before the command's action runs. */
    public static class RefusingProbe extends ProbeProcessor {
        static volatile boolean refuse;

        public RefusingProbe() {
            super(false, false);
        }

        @Override
        public void runInEventCycle(Object auditEvent, Runnable action) {
            if (refuse) throw new IllegalStateException("DEMO-refused before the command ran");
            super.runInEventCycle(auditEvent, action);
        }
    }

    @Test
    void aRefusedCommand_throughMainsPerTargetCapture_isNeverRecorded_norReplayed() throws Exception {
        InMemoryReplayStore store = new InMemoryReplayStore();
        try (Server s = boot(ReplayConfig.record(Set.of("probe"), Map.of(), null, store), false,
                Map.of("probe", new RefusingProbe()))) {
            RefusingProbe.refuse = true;
            List<Object> refused = invoke(s.admin(), "probe.noop");
            assertEquals(1, refused.size(), "the refused caller is answered once: " + refused);
            assertTrue(String.valueOf(refused.get(0)).startsWith("ERR "), "answered as an error: " + refused);
            RefusingProbe.refuse = false;
            assertEquals(List.of("noop"), invoke(s.admin(), "probe.noop"), "the next command runs");
        } finally {
            RefusingProbe.refuse = false;
        }
        List<ReplayEntry> entries = store.entries("probe");
        long invocations = entries.stream().filter(e -> e instanceof ReplayEntry.AdminInvoked).count();
        assertEquals(1, invocations, "only the command that ran is an invocation: " + entries);
        assertEquals(1, entries.size(), "and nothing else is recorded for the refused one: " + entries);

        try (Server r = boot(ReplayConfig.replay(Set.of("probe"), Map.of(), null, store), false, one())) {
            r.awaitReplayDone();
            assertNull(r.replayer().stopped("probe"), "the replay completes");
            assertEquals(List.of("probe: noop"), r.replayer().adminReplies(), "the replay runs the one command that ran");
        }
    }
}
