package com.telamin.mongoose.review77ext;
import com.telamin.mongoose.control.validation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class FallbackKindInvariantTest {
    @Test void appliedFallbackCannotCarryFailureKind() {
        var evidence=ControlEvidence.parse("controlFallback: {record: APPLIED, requestId: 99, kind: FAILED_EFFECT}\n",true);
        var result=ControlInvariants.check(evidence);
        assertFalse(result.verified(), "I20 accepts contradictory fallback: "+result);
    }
}

