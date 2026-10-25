package com.telamin.mongoose.extreview.evidence;

import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External review probes: traces the engine could plausibly produce under a bug, which {@link ControlInvariants}
 * nevertheless reports as {@code verified()}. Each test documents a hole; a test here that FAILS means the hole has
 * been closed.
 */
class InvariantVacuityProbesTest {

    private static String audit(String... records) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < records.length; i++) {
            out.append("---\neventLogRecord:\n    nodeLogs:\n        - node: {seq: ").append(i + 1).append(", ")
                    .append(records[i]).append("}\n");
        }
        return out.append("---\n").toString();
    }

    private static ControlInvariants.Result check(String text) {
        return ControlInvariants.check(ControlEvidence.parse(text, true));
    }

    /** Hole 1: engine-declared anomalies (Anomaly, Dead) are not violations; a dead group's export verifies. */
    @Test
    void anomalyAndDeadFallbackRecordsVerify() {
        String text = audit(
                "record: Input, requestId: 1, fact: Add, generation: 7, name: p",
                "record: Decision, requestId: 1, rule: addAdmitted, generation: 7, name: p, effects: '1.1 BuildAndStart(7)'")
                + "---\ncontrolFallback: {record: Anomaly, fallbackSeq: 1, group: g, requestId: 1, description: 'control processor threw during batchEnd: java.lang.NullPointerException'}\n"
                + "---\ncontrolFallback: {record: FAILED, fallbackSeq: 2, group: g, requestId: 1, reason: 'control processor threw: java.lang.NullPointerException'}\n"
                + "---\ncontrolFallback: {record: Dead, fallbackSeq: 3, group: g, reason: 'control processor threw during batchEnd'}\n"
                + "---\ncontrolFallback: {record: EffectOutcome, fallbackSeq: 4, group: g, opId: '1.1', effect: BuildAndStart, outcome: failed, reason: abandoned}\n";
        ControlInvariants.Result result = check(text);
        assertTrue(result.verified(), "hole still open: " + result);
    }

    /**
     * Hole 2: a rule that performs its effects but never requests a Reply is answered FAILED by the driver's safety net
     * ("no reply after its effects settled"); I2 is satisfied by that fallback and nothing inspects its reason.
     */
    @Test
    void driverSafetyNetFailedReplySatisfiesI2() {
        String text = audit(
                "record: Decision, requestId: 1, rule: started, name: p, generation: 7, effects: ''",
                "record: Input, requestId: 2, fact: Stop, generation: 7, name: p",
                "record: Decision, requestId: 2, rule: stopStarted, generation: 7, name: p, effects: '2.1 Release(7)'",
                "record: EffectOutcome, requestId: 2, opId: '2.1', effect: Release, outcome: succeeded, generation: 7, name: p",
                "record: Decision, requestId: 2, rule: released, generation: 7, name: p, effects: '2.2 ReleaseName(7)'",
                "record: EffectOutcome, requestId: 2, opId: '2.2', effect: ReleaseName, outcome: succeeded, generation: 7, name: p",
                "record: Decision, requestId: 2, rule: stopComplete, generation: 7, name: p, effects: ''")
                + "---\ncontrolFallback: {record: FAILED, fallbackSeq: 1, group: g, requestId: 2, reason: 'no reply after its effects settled'}\n";
        ControlInvariants.Result result = check(text);
        assertTrue(result.verified(), "hole still open: " + result);
    }

    /**
     * Hole 3: a reply issued BEFORE its dependent effect has an outcome passes I15, because the recorder marks any
     * outcome after the reply {@code cleanup: true} (its chain is closed by the reply) and I15 skips cleanup records.
     * I8 only looks at failures with seq < reply seq, so even a FAILED Release after an APPLIED reply passes.
     */
    @Test
    void replyBeforeItsEffectsOutcomeVerifies() {
        String text = audit(
                "record: Decision, requestId: 1, rule: started, name: p, generation: 7, effects: ''",
                "record: Input, requestId: 2, fact: Stop, generation: 7, name: p",
                "record: Decision, requestId: 2, rule: stopStarted, generation: 7, name: p, effects: '2.1 Reply(7);2.2 Release(7)'",
                "record: Reply, requestId: 2, opId: '2.1', outcome: APPLIED, generation: 7, name: p, completed: true",
                "record: EffectOutcome, requestId: 2, opId: '2.2', effect: Release, outcome: failed, generation: 7, name: p, reason: 'stop threw', cleanup: true",
                "record: Decision, requestId: 2, rule: releasedWithErrors, generation: 7, name: p, cleanup: true, effects: '2.3 ReleaseName(7)'",
                "record: EffectOutcome, requestId: 2, opId: '2.3', effect: ReleaseName, outcome: succeeded, generation: 7, name: p, cleanup: true");
        ControlInvariants.Result result = check(text);
        assertTrue(result.verified(), "hole still open: " + result);
    }

    /** Hole 4: a stop answered APPLIED that requested no Release at all (processor keeps running) verifies. */
    @Test
    void appliedStopWithoutReleaseVerifies() {
        String text = audit(
                "record: Input, requestId: 1, fact: Add, generation: 7, name: p",
                "record: Decision, requestId: 1, rule: addAdmitted, name: p, generation: 7, effects: '1.1 BuildAndStart(7)'",
                "record: EffectOutcome, requestId: 1, opId: '1.1', effect: BuildAndStart, outcome: succeeded, generation: 7",
                "record: Decision, requestId: 1, rule: started, name: p, generation: 7, effects: '1.2 Reply(7)'",
                "record: Reply, requestId: 1, opId: '1.2', outcome: APPLIED, generation: 7, name: p, completed: true",
                "record: Input, requestId: 2, fact: Stop, generation: 7, name: p",
                "record: Decision, requestId: 2, rule: stopStarted, generation: 7, name: p, effects: '2.1 Reply(7)'",
                "record: Reply, requestId: 2, opId: '2.1', outcome: APPLIED, generation: 7, name: p, completed: true",
                "record: Input, requestId: 3, fact: Close",
                "record: Decision, requestId: 3, rule: closeEmpty, generation: -1, effects: '3.1 Reply(-1)'",
                "record: Reply, requestId: 3, opId: '3.1', outcome: APPLIED, generation: -1, completed: true");
        ControlInvariants.Result result = check(text);
        assertTrue(result.verified(), "hole still open: " + result);
    }

    /** Positive control: I13 does see a sender-side Refused fallback plus a delivered Reply for one request. */
    @Test
    void refusedFallbackPlusDeliveredReplyIsADoubleReply() {
        String text = "---\ncontrolFallback: {record: Refused, fallbackSeq: 1, group: g, requestId: 5, reason: 'mailbox full'}\n"
                + audit("record: Input, requestId: 5, fact: Stop, generation: 7, name: p",
                        "record: Decision, requestId: 5, rule: stopReleased, generation: 7, name: p, effects: '5.1 Reply(7)'",
                        "record: Reply, requestId: 5, opId: '5.1', outcome: APPLIED, generation: 7, name: p, completed: true");
        ControlInvariants.Result result = check(text);
        assertFalse(result.ok(), result.toString());
        assertTrue(result.violations().stream().anyMatch(v -> v.startsWith("I13 ")), result.toString());
    }
}
