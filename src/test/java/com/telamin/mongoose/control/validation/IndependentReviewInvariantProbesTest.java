package com.telamin.mongoose.control.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent review of the PR 76 fix commits: the I12 implementation added for follow-up finding 6. A failure at
 * 0dea3b3 demonstrates a forbidden trace the checker accepts.
 */
class IndependentReviewInvariantProbesTest {

    private static String audit(String... records) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < records.length; i++) {
            out.append("---\neventLogRecord:\n    nodeLogs:\n        - node: {seq: ").append(i + 1).append(", ")
                    .append(records[i]).append("}\n");
        }
        return out.append("---\n").toString();
    }

    /**
     * I12's exemption ("a recorded failed uninstall explains a capture that outlives its release") is keyed by
     * generation and never cleared. One failed uninstall of an earlier capture, later retried successfully and closed,
     * excuses a different capture, installed afterwards on the same generation, outliving the generation's release
     * with no failed uninstall of its own. The checker returns no violation for this complete, contiguous trace.
     */
    @Test
    void aStaleFailedUninstallMustNotExcuseALaterCaptureOutlivingItsRelease() {
        String text = audit(
                "record: Decision, requestId: 1, rule: started, name: p, generation: 7, effects: ''",
                "record: Decision, requestId: 2, rule: captureOpened, generation: 7, resourceId: 41, effects: ''",
                "record: EffectOutcome, requestId: 2, opId: '2.1', effect: InstallCapture, outcome: succeeded, generation: 7",
                "record: EffectOutcome, requestId: 3, opId: '3.1', effect: UninstallCapture, outcome: failed, generation: 7, resourceId: 41",
                "record: EffectOutcome, requestId: 3, opId: '3.2', effect: UninstallCapture, outcome: succeeded, generation: 7, resourceId: 41",
                "record: EffectOutcome, requestId: 3, opId: '3.3', effect: CloseCapture, outcome: succeeded, resourceId: 41",
                "record: Decision, requestId: 4, rule: captureOpened, generation: 7, resourceId: 42, effects: ''",
                "record: EffectOutcome, requestId: 4, opId: '4.1', effect: InstallCapture, outcome: succeeded, generation: 7",
                "record: Decision, requestId: 5, rule: released, name: p, generation: 7, effects: ''",
                "record: EffectOutcome, requestId: 5, opId: '5.1', effect: CloseCapture, outcome: succeeded, resourceId: 42");
        var result = ControlInvariants.check(ControlEvidence.parse(text, true));
        assertTrue(result.unverifiable().isEmpty(), result.toString());
        assertTrue(result.violations().stream().anyMatch(v -> v.startsWith("I12")),
                "capture 42 was installed on generation 7 and never uninstalled before its release, with no failed"
                        + " uninstall of its own; the stale exemption from capture 41 hides it: " + result);
    }
}
