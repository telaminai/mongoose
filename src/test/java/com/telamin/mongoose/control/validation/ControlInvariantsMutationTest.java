package com.telamin.mongoose.control.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every invariant the checker advertises rejects a minimal trace that breaks it (follow-up review, finding 6: the
 * checker had accepted forbidden traces). These are validator mutation oracles: each trace is written by hand to break
 * exactly one predicate, so a checker that stops enforcing one fails here, whatever the engine does.
 */
class ControlInvariantsMutationTest {

    /** Records as the control audit exports them: one node entry per record, seq numbered from 1. */
    private static String audit(String... records) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < records.length; i++) {
            out.append("---\neventLogRecord:\n    nodeLogs:\n        - node: {seq: ").append(i + 1).append(", ")
                    .append(records[i]).append("}\n");
        }
        return out.append("---\n").toString();
    }

    static Stream<Arguments> forbiddenTraces() {
        return Stream.of(
                // I1 keys on the Stop fact, not on a rule name (issue #77; second review T3): the decision's label is
                // deliberately meaningless
                Arguments.of("I1", audit(
                        "record: Input, requestId: 1, fact: Stop, generation: 7, name: p",
                        "record: Decision, requestId: 1, rule: innocentLookingLabel, generation: 7, effects: ''",
                        "record: Decision, requestId: 2, rule: captureOpened, generation: 7, resourceId: 1, effects: '2.1 InstallCapture(7)'")),
                Arguments.of("I2", audit(
                        "record: Input, requestId: 1, fact: Add, generation: 7")),
                Arguments.of("I3", audit(
                        "record: Decision, requestId: 1, rule: addAdmitted, generation: 7, effects: '1.1 BuildAndStart(7)'")),
                Arguments.of("I4", audit(
                        "record: Decision, requestId: 1, rule: captureOpened, generation: 7, resourceId: 41, effects: ''")),
                Arguments.of("I5", audit(
                        "record: Decision, requestId: 1, rule: started, name: p, generation: 7, effects: ''",
                        "record: Decision, requestId: 2, rule: started, name: p, generation: 8, effects: ''")),
                Arguments.of("I6", audit(
                        "record: Input, requestId: 2, fact: Stop, generation: 7",
                        "record: Decision, requestId: 2, rule: stopStarted, generation: 7, effects: '2.1 Release(8)'")),
                Arguments.of("I7", audit(
                        "record: Decision, requestId: 1, rule: closeEmpty, effects: ''",
                        "record: Decision, requestId: 2, rule: addAdmitted, generation: 7, effects: '2.1 BuildAndStart(7)'")),
                Arguments.of("I8", audit(
                        "record: EffectOutcome, requestId: 1, opId: '1.1', effect: BuildAndStart, outcome: failed",
                        "record: Reply, requestId: 1, opId: '1.2', outcome: APPLIED, completed: true")),
                Arguments.of("I9", audit(
                        "record: EffectOutcome, requestId: 1, opId: '1.1', effect: BuildAndStart, outcome: succeeded",
                        "record: Decision, requestId: 1, rule: addAdmitted, generation: 7, effects: '1.1 BuildAndStart(7)'")),
                Arguments.of("I10", "---\neventLogRecord:\n    nodeLogs:\n        - node: {seq: 1, record: Input, requestId: 1, fact: Run}\n"
                        + "---\neventLogRecord:\n    nodeLogs:\n        - node: {seq: 3, record: Reply, requestId: 1, opId: '1.1', outcome: APPLIED}\n---\n"),
                Arguments.of("I11", audit(
                        "record: EffectOutcome, requestId: 1, opId: '1.2', effect: CloseCapture, outcome: succeeded, resourceId: 41",
                        "record: Decision, requestId: 1, rule: captureOpened, generation: 7, resourceId: 41, effects: ''")),
                Arguments.of("I12", audit(
                        "record: EffectOutcome, requestId: 1, opId: '1.1', effect: InstallCapture, outcome: succeeded, generation: 9")),
                Arguments.of("I12", audit(          // a stale failed uninstall must not excuse a later capture
                        "record: Decision, requestId: 1, rule: started, generation: 7, effects: ''",
                        "record: EffectOutcome, requestId: 2, opId: '2.1', effect: InstallCapture, outcome: succeeded, generation: 7",
                        "record: EffectOutcome, requestId: 3, opId: '3.1', effect: UninstallCapture, outcome: failed, generation: 7",
                        "record: EffectOutcome, requestId: 3, opId: '3.2', effect: UninstallCapture, outcome: succeeded, generation: 7",
                        "record: EffectOutcome, requestId: 4, opId: '4.1', effect: InstallCapture, outcome: succeeded, generation: 7",
                        "record: Decision, requestId: 5, rule: released, generation: 7, effects: ''")),
                Arguments.of("I13", audit(
                        "record: Reply, requestId: 1, opId: '1.1', outcome: APPLIED, completed: true",
                        "record: Reply, requestId: 1, opId: '1.2', outcome: APPLIED, completed: true")),
                Arguments.of("I14", audit(
                        "record: Decision, requestId: 1, rule: captureOpened, generation: 7, resourceId: 41, effects: ''",
                        "record: Decision, requestId: 1, rule: captureDuplicate, duplicate: true, resourceId: 41, effects: '1.c1 CloseCapture(-1)'")),
                Arguments.of("I15", audit(
                        "record: Reply, requestId: 1, opId: '1.1', outcome: APPLIED, completed: true",
                        "record: Decision, requestId: 1, rule: started, generation: 7, effects: ''")),
                Arguments.of("I16", audit(
                        "record: Decision, requestId: 1, rule: closeGroup, effects: '1.1 Release(7);1.2 Release(8)'",
                        "record: EffectOutcome, requestId: 1, opId: '1.2', effect: Release, outcome: succeeded",
                        "record: EffectOutcome, requestId: 1, opId: '1.1', effect: Release, outcome: succeeded"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenTraces")
    void theCheckerRejectsATraceThatBreaksIt(String invariant, String trace) {
        ControlInvariants.Result result = ControlInvariants.check(ControlEvidence.parse(trace, true));
        assertTrue(result.violations().stream().anyMatch(v -> v.startsWith(invariant + " ")),
                invariant + " must reject its forbidden trace; reported: " + result);
    }

    @Test
    void aWellFormedTraceHasNoViolations() {
        String trace = audit(
                "record: Input, requestId: 1, fact: Add, generation: 7",
                "record: Decision, requestId: 1, rule: addAdmitted, name: p, generation: 7, effects: '1.1 BuildAndStart(7)'",
                "record: EffectOutcome, requestId: 1, opId: '1.1', effect: BuildAndStart, outcome: succeeded, generation: 7",
                "record: Decision, requestId: 1, rule: started, name: p, generation: 7, effects: '1.2 Reply(7)'",
                "record: Reply, requestId: 1, opId: '1.2', outcome: APPLIED, completed: true");
        ControlInvariants.Result result = ControlInvariants.check(ControlEvidence.parse(trace, true));
        assertTrue(result.ok(), result.toString());
    }
}
