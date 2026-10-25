package com.telamin.mongoose.control.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Review probe: the design's I7 ("No BuildAndStart, OpenCapture or InstallCapture after the group records Closing") is
 * not implemented in {@link ControlInvariants}. Evidence in which a closing group then builds and starts a processor
 * passes every implemented invariant.
 */
class ReviewInvariantGapTest {

    private static String record(String event, String... nodeLogs) {
        StringBuilder b = new StringBuilder("---\neventLogRecord: \n    eventTime: 1\n    logTime: 1\n    groupingId: null\n    event: ")
                .append(event).append("\n    thread: main\n    nodeLogs: \n");
        for (String n : nodeLogs) b.append("        - ").append(n).append('\n');
        return b.append("    endTime: 1\n").toString();
    }

    @Test
    void aBuildAndStartAfterTheCloseDecisionIsAViolation() {
        String text = record("CloseIntent",
                "controlInputs: { record: Input, seq: 1, requestId: 1, rootId: 1, fact: \"Close\"}",
                "lifecycleRules: { record: Decision, seq: 2, requestId: 1, rootId: 1, rule: \"closeEmpty\", generation: -1, from: \"Running\", to: \"Closed\", effects: \"1.1 Reply(-1)\"}")
                + record("EffectResult",
                "effectOutcomes: { record: Reply, seq: 3, requestId: 1, rootId: 1, opId: \"1.1\", outcome: \"APPLIED\", generation: -1, completed: true}")
                // after the close: an add is admitted, built and started
                + record("AddIntent",
                "controlInputs: { record: Input, seq: 4, requestId: 2, rootId: 2, fact: \"Add\", generation: 5, incarnation: 1, name: \"p\"}",
                "lifecycleRules: { record: Decision, seq: 5, requestId: 2, rootId: 2, rule: \"addAdmitted\", generation: 5, name: \"p\", from: \"Admitted\", to: \"Starting\", effects: \"2.1 BuildAndStart(5)\"}")
                + record("EffectResult",
                "effectOutcomes: { record: EffectOutcome, seq: 6, requestId: 2, rootId: 2, opId: \"2.1\", effect: \"BuildAndStart\", outcome: \"succeeded\", generation: 5, name: \"p\"}",
                "lifecycleRules: { record: Decision, seq: 7, requestId: 2, rootId: 2, rule: \"started\", generation: 5, name: \"p\", from: \"Starting\", to: \"Started\", effects: \"2.2 Reply(5)\"}")
                + record("EffectResult",
                "effectOutcomes: { record: Reply, seq: 8, requestId: 2, rootId: 2, opId: \"2.2\", outcome: \"APPLIED\", generation: 5, name: \"p\", completed: true}")
                + "---\n";
        ControlEvidence evidence = ControlEvidence.parse(text, true);
        ControlInvariants.Result result = ControlInvariants.check(evidence);
        // the design's I7 would flag BuildAndStart 2.1 after seq 2; nothing does
        assertFalse(result.ok(), "I7 is not checked: a processor built after the close passes: " + result);
    }
}
