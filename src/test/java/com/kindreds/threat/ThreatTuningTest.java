package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link ThreatTuning#withAdaptiveStrength}: it must carry {@link ThreatTuning#DEFAULTS}'
 * calibration constants through unchanged and substitute only the band (proved discriminatingly for
 * the band arithmetic itself in {@code ThreatMathTest}'s {@code adaptiveStrength*} tests - this
 * class only proves the wiring between the two).
 */
class ThreatTuningTest {

    @Test
    void withAdaptiveStrengthKeepsTheCalibrationConstantsUnchanged() {
        ThreatTuning tuning = ThreatTuning.withAdaptiveStrength(50);
        assertEquals(ThreatTuning.DEFAULTS.hardshipTarget(), tuning.hardshipTarget(), 0.0001f);
        assertEquals(ThreatTuning.DEFAULTS.riseRate(), tuning.riseRate(), 0.0001f);
        assertEquals(ThreatTuning.DEFAULTS.fallRate(), tuning.fallRate(), 0.0001f);
        assertEquals(ThreatTuning.DEFAULTS.deathPenalty(), tuning.deathPenalty(), 0.0001f);
    }

    @Test
    void withAdaptiveStrengthSubstitutesTheBandFromThreatMath() {
        // Must agree with ThreatMath.adaptiveBand exactly - this is a thin wiring method, not a
        // second place the narrowing formula is (re)implemented.
        float[] expected = ThreatMath.adaptiveBand(50);
        ThreatTuning tuning = ThreatTuning.withAdaptiveStrength(50);
        assertEquals(expected[0], tuning.bandMin(), 0.0001f);
        assertEquals(expected[1], tuning.bandMax(), 0.0001f);
    }

    @Test
    void withAdaptiveStrengthActuallyChangesWhatAFoldCanDo() {
        // The discriminating proof that adaptiveStrength is wired into folding at all: a full-strength
        // fold and a zero-strength fold of the SAME evidence must land on different competence.
        float fullyAdaptive = ThreatMath.foldDeath(1.0f, 1.0f, ThreatTuning.withAdaptiveStrength(100));
        float notAdaptive = ThreatMath.foldDeath(1.0f, 1.0f, ThreatTuning.withAdaptiveStrength(0));
        assertEquals(1.0f, notAdaptive, 0.0001f, "adaptiveStrength=0 must pin competence at exactly 1.0");
        assertEquals(0.95f, fullyAdaptive, 0.0001f);
    }
}
