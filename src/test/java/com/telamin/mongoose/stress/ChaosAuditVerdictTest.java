/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.stress;

import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChaosAuditVerdictTest {
    @Test
    void completeRecordWithFailedPhysicalClosureCannotPassChaos() {
        String text = "---\ncontrolFallback: {record: EffectOutcome, effect: OpenCapture, outcome: succeeded, resourceId: 41}\n"
                + "---\ncontrolFallback: {record: EffectOutcome, effect: CloseCapture, outcome: failed, resourceId: 41}\n";
        var evidence = ControlEvidence.parse(text, true);
        var result = ControlInvariants.check(evidence);
        assertTrue(evidence.complete());
        assertTrue(result.ok(), "no counterexample alone is not proof of resource closure");
        assertFalse(result.verified());
        assertTrue(ChaosWorkload.auditFailures("g", result).stream()
                .anyMatch(f -> f.contains("unverifiable") && f.contains("41")));
    }
}
