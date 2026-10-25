package com.telamin.mongoose.control.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #77 milestone 2: one forbidden trace per kind-based check in {@link ControlInvariants}, each written with
 * reason text and rule names chosen to be meaningless, so only the structured {@code kind} (or the recorded facts)
 * can reject it. Legacy controls show that the reason-text classification still applies to exports written before
 * the taxonomy, and a positive control shows that a correct taxonomy trace verifies.
 */
class FailureKindEvidenceMutationTest {

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

    private static boolean has(ControlInvariants.Result result, String invariant) {
        return result.violations().stream().anyMatch(v -> v.startsWith(invariant + " "));
    }

    /** A healthy add and stop, every reply carrying its kind: the positive control for every check below. */
    private static final String[] HEALTHY = {
            "record: Input, requestId: 1, fact: Add, generation: 7, name: p",
            "record: Decision, requestId: 1, rule: a, generation: 7, name: p, effects: '1.1 BuildAndStart(7)'",
            "record: EffectOutcome, requestId: 1, opId: '1.1', effect: BuildAndStart, outcome: succeeded, generation: 7, name: p",
            "record: Decision, requestId: 1, rule: b, generation: 7, name: p, effects: '1.2 Reply(7)'",
            "record: Reply, requestId: 1, opId: '1.2', outcome: APPLIED, generation: 7, name: p, completed: true, kind: NONE",
            "record: Input, requestId: 2, fact: Stop, generation: -1, name: p",
            "record: Decision, requestId: 2, rule: c, generation: 7, name: p, effects: '2.1 Release(7)'",
            "record: EffectOutcome, requestId: 2, opId: '2.1', effect: Release, outcome: succeeded, generation: 7, name: p",
            "record: Decision, requestId: 2, rule: d, generation: 7, name: p, effects: '2.2 ReleaseName(7)'",
            "record: EffectOutcome, requestId: 2, opId: '2.2', effect: ReleaseName, outcome: succeeded, generation: 7, name: p",
            "record: Decision, requestId: 2, rule: e, generation: 7, name: p, effects: '2.3 Reply(7)'",
            "record: Reply, requestId: 2, opId: '2.3', outcome: APPLIED, generation: 7, name: p, completed: true, kind: NONE"};

    @Test
    void aCorrectTaxonomyTraceVerifies() {
        ControlInvariants.Result result = check(audit(HEALTHY));
        assertTrue(result.verified(), result.toString());
    }

    /** I18: driver-authored replies and engine-failure kinds, whatever their wording. */
    @ParameterizedTest
    @ValueSource(strings = {"FAILED_RULE", "FAILED_DRIVER_FALLBACK", "FAILED_DEAD", "QUARANTINED"})
    void anUnhealthyKindOnAFallbackReplyIsRejectedWhateverItsWording(String kind) {
        String text = audit(HEALTHY) + "---\ncontrolFallback: {record: FAILED, fallbackSeq: 1, group: g, requestId: 9, "
                + "reason: 'everything is fine', kind: " + kind + "}\n";
        ControlInvariants.Result result = check(text);
        assertTrue(has(result, "I18"), result.toString());
    }

    @Test
    void anAbandonedEffectIsRejectedByItsKindNotTheWordAbandoned() {
        String text = audit(HEALTHY) + "---\ncontrolFallback: {record: EffectOutcome, fallbackSeq: 1, group: g, opId: '9.1', "
                + "effect: Release, outcome: failed, reason: 'routine', kind: FAILED_DRIVER_FALLBACK}\n";
        assertTrue(has(check(text), "I18"));
    }

    @Test
    void aRuleAuthoredQuarantineReplyIsRejected() {
        String[] trace = HEALTHY.clone();
        trace[11] = "record: Reply, requestId: 2, opId: '2.3', outcome: FAILED, generation: 7, name: p, completed: true, "
                + "kind: QUARANTINED, reason: 'nothing to see'";
        assertTrue(has(check(audit(trace)), "I18"));
    }

    /** The kind is authoritative: legacy reason text on a record that names an ordinary kind is not re-read. */
    @Test
    void reasonTextIsNotConsultedWhenAKindIsPresent() {
        String text = audit(HEALTHY) + "---\ncontrolFallback: {record: EffectOutcome, fallbackSeq: 1, group: g, opId: '9.1', "
                + "effect: CloseCapture, outcome: failed, reason: abandoned, kind: FAILED_EFFECT}\n";
        ControlInvariants.Result result = check(text);
        assertFalse(has(result, "I18"), result.toString());
    }

    /** I19: a leftover recovered by offline cleanup is a violation, by kind or by the leftover flag. */
    @Test
    void aLeftoverRecoveryIsRejected() {
        String byKind = audit(HEALTHY) + "---\ncontrolFallback: {record: EffectOutcome, fallbackSeq: 1, group: g, "
                + "requestId: -1, opId: 'x.1', effect: CloseCapture, outcome: succeeded, resourceId: 3, generation: -1, "
                + "offline: true, kind: LEFTOVER_RECOVERED}\n";
        String byFlag = audit(HEALTHY) + "---\ncontrolFallback: {record: EffectOutcome, fallbackSeq: 1, group: g, "
                + "requestId: -1, opId: 'x.1', effect: CloseCapture, outcome: succeeded, resourceId: 3, generation: -1, "
                + "offline: true, leftover: true}\n";
        assertTrue(has(check(byKind), "I19"));
        assertTrue(has(check(byFlag), "I19"));
    }

    /** I20: in a taxonomy export, a reply's kind agrees with its outcome and is never missing. */
    @Test
    void anAppliedReplyClassifiedAsAFailureIsRejected() {
        String[] trace = HEALTHY.clone();
        trace[4] = trace[4].replace("kind: NONE", "kind: FAILED_EFFECT");
        assertTrue(has(check(audit(trace)), "I20"));
    }

    @Test
    void aFailedReplyClassifiedAsNoneIsRejected() {
        String[] trace = HEALTHY.clone();
        trace[11] = "record: Reply, requestId: 2, opId: '2.3', outcome: FAILED, generation: 7, name: p, completed: true, kind: NONE";
        assertTrue(has(check(audit(trace)), "I20"));
    }

    @Test
    void aReplyWithoutAKindInATaxonomyExportIsRejected() {
        String[] trace = HEALTHY.clone();
        trace[11] = trace[11].replace(", kind: NONE", "");
        assertTrue(has(check(audit(trace)), "I20"));
    }

    /** I1: a name-only stop is resolved to the generation live under the name, with no rule name involved. */
    @Test
    void aCaptureInstalledAfterANameOnlyStopIsRejectedWithoutRuleNames() {
        String text = audit(
                "record: EffectOutcome, requestId: 1, opId: '1.1', effect: BuildAndStart, outcome: succeeded, generation: 7, name: p",
                "record: Input, requestId: 2, fact: Stop, generation: -1, name: p",
                "record: Decision, requestId: 2, rule: innocentLookingLabel, generation: 7, effects: ''",
                "record: Decision, requestId: 3, rule: alsoInnocent, generation: 7, resourceId: 1, effects: '3.1 InstallCapture(7)'");
        assertTrue(has(check(text), "I1"), check(text).toString());
    }

    /** Legacy controls: exports written before the taxonomy (no kind field) are still judged by the old reason text. */
    @ParameterizedTest
    @ValueSource(strings = {"no reply after its effects settled", "effects did not settle",
            "control processor threw: java.lang.NullPointerException", "abandoned"})
    void legacyReasonOnlyExportsAreStillRejected(String reason) {
        String text = audit(HEALTHY).replace(", kind: NONE", "")
                + "---\ncontrolFallback: {record: FAILED, fallbackSeq: 1, group: g, requestId: 9, reason: '" + reason + "'}\n";
        ControlInvariants.Result result = check(text);
        assertTrue(has(result, "I18"), result.toString());
        assertFalse(has(result, "I20"), "a pre-taxonomy export is not judged by I20: " + result);
    }
}
