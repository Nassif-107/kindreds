package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers {@link ThreatEvidence#hardshipOf}, the one piece of {@link ThreatEvidence}'s arithmetic
 * that does not need a running game to prove. The rest of the class (accumulating per-player damage,
 * reading/writing {@code KindredData.threat()}, the five Fabric event registrations) is exercised
 * in-game instead - see the class javadoc's note on the testability boundary.
 */
class ThreatEvidenceTest {

    @Test
    void hardshipIsAccumulatedDamageOverMaxHealth() {
        assertEquals(0.25f, ThreatEvidence.hardshipOf(5f, 20f), 0.0001f);
        assertEquals(1.0f, ThreatEvidence.hardshipOf(20f, 20f), 0.0001f);
        assertEquals(0f, ThreatEvidence.hardshipOf(0f, 20f), 0.0001f);
    }

    @Test
    void zeroMaxHealthNeverProducesNaNOrInfinity() {
        // A fresh player's ThreatState.maxHealthMark() defaults to 0 before the first refresh - this
        // must not divide by it. The discriminating half: NaN/Infinity are exactly what plain
        // division would hand back here, and both would silently poison every fold downstream
        // (ThreatMath's own band() has a NaN escape hatch, but nothing catches Infinity).
        float result = ThreatEvidence.hardshipOf(5f, 0f);
        assertFalse(Float.isNaN(result), "hardship must never be NaN");
        assertFalse(Float.isInfinite(result), "hardship must never be Infinite");
        assertEquals(5f, result, 0.0001f, "with the 1f floor, 5 accumulated / 1 effective max health");
    }

    @Test
    void negativeMaxHealthAlsoFloorsRatherThanFlippingSign() {
        // Corrupted save data (a negative mark) must not flip hardship negative, which would read as
        // a hardship far BELOW target - i.e. maximal false "coasting" evidence.
        float result = ThreatEvidence.hardshipOf(5f, -20f);
        assertEquals(5f, result, 0.0001f);
    }

    @Test
    void negativeAccumulatedDamageIsClampedToZero() {
        // Should never happen (damageTaken from the Fabric event is non-negative), but a defensive
        // floor here keeps a hypothetical negative accumulation from reading as negative hardship.
        assertEquals(0f, ThreatEvidence.hardshipOf(-5f, 20f), 0.0001f);
    }
}
