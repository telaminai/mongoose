package com.telamin.mongoose.control.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Independent I8 examples preserve ordering, request identity and multiplicity when indexing failures. */
class AppliedReplyFailureIndexTest {
    private static String record(long seq, String fields) {
        return "---\neventLogRecord:\n  nodeLogs:\n    - node: {seq: " + seq + ", " + fields + "}\n";
    }

    private static List<String> i8(String text) {
        return ControlInvariants.check(ControlEvidence.parse(text, true)).violations().stream()
                .filter(v -> v.startsWith("I8 ")).toList();
    }

    @Test
    void onlyEarlierFailuresForTheAppliedRequestsIdentityCount() {
        String text = record(9, "record: EffectOutcome, requestId: 1, opId: 'late', effect: Release, outcome: failed")
                + record(2, "record: EffectOutcome, requestId: 2, opId: 'other', effect: BuildAndStart, outcome: failed")
                + record(3, "record: EffectOutcome, requestId: 1, opId: 'close', effect: CloseCapture, outcome: failed")
                + record(4, "record: EffectOutcome, requestId: 1, opId: 'success', effect: BuildAndStart, outcome: succeeded")
                + record(5, "record: Reply, requestId: 1, outcome: APPLIED, completed: true")
                // Sequence, not physical record order, defines whether the effect preceded this reply.
                + record(1, "record: EffectOutcome, requestId: 1, opId: 'early', effect: BuildAndStart, outcome: failed")
                + record(6, "record: Reply, requestId: 2, outcome: FAILED, completed: true")
                + "---\ncontrolFallback: {record: EffectOutcome, requestId: 1, opId: 'fallback', effect: Release, outcome: failed}\n";
        assertEquals(List.of("I8 request 1 replied APPLIED after a failed BuildAndStart (early)"), i8(text));
    }

    @Test
    void duplicateOutcomesAndRepliesRetainEveryViolation() {
        String text = record(1, "record: EffectOutcome, requestId: 7, opId: '7.1', effect: Release, outcome: failed")
                + record(2, "record: EffectOutcome, requestId: 7, opId: '7.1', effect: Release, outcome: failed, duplicate: true")
                + record(3, "record: Reply, requestId: 7, outcome: APPLIED, completed: true")
                + record(4, "record: Reply, requestId: 7, outcome: APPLIED, completed: false");
        assertEquals(java.util.Collections.nCopies(4, "I8 request 7 replied APPLIED after a failed Release (7.1)"), i8(text));
    }
}
