package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules test - no Minecraft on the classpath, so every formula is provable here. */
class ThreatMathTest {

    @Test
    void priorIsTheWeightedBlendOfItsThreeTerms() {
        // all terms full -> 100, whatever the weights
        assertEquals(100f, ThreatMath.prior(1f, 1f, 1f, 3, 2, 1), 0.001f);
        // all terms empty -> 0
        assertEquals(0f, ThreatMath.prior(0f, 0f, 0f, 3, 2, 1), 0.001f);
        // only commitment, default weights 3/2/1 -> 3/6 of 100
        assertEquals(50f, ThreatMath.prior(1f, 0f, 0f, 3, 2, 1), 0.001f);
        // a zeroed gear weight removes gear from the blend entirely
        assertEquals(75f, ThreatMath.prior(1f, 0f, 0f, 3, 0, 1), 0.001f);
    }

    @Test
    void theHighWaterMarkRisesAtOnceAndFallsSlowly() {
        // rises instantly to a higher reading
        assertEquals(80f, ThreatMath.decayed(50f, 80f, 2f, 0L), 0.001f);
        // falls at no more than perHour, measured in PLAYED ticks (72000 ticks = 1 hour)
        assertEquals(78f, ThreatMath.decayed(80f, 10f, 2f, 72000L), 0.001f);
        // never falls below the current reading
        assertEquals(10f, ThreatMath.decayed(80f, 10f, 2f, 72000L * 100), 0.001f);
        // no played time, no decay at all
        assertEquals(80f, ThreatMath.decayed(80f, 10f, 2f, 0L), 0.001f);
    }

    @Test
    void competenceCannotEscapeItsBand() {
        float high = ThreatMath.foldFastKill(1.25f, ThreatTuning.DEFAULTS);
        assertTrue(high <= ThreatMath.COMPETENCE_MAX, "rose past the ceiling: " + high);
        float low = 1.0f;
        for (int i = 0; i < 500; i++) {
            low = ThreatMath.foldDeath(low, 1.0f, ThreatTuning.DEFAULTS);
        }
        assertEquals(ThreatMath.COMPETENCE_MIN, low, 0.001f);
    }

    @Test
    void threatIsThePriorMovedOnlyWithinTheBand() {
        assertEquals(75f, ThreatMath.threat(100f, ThreatMath.COMPETENCE_MIN), 0.001f);
        assertEquals(100f, ThreatMath.threat(100f, ThreatMath.COMPETENCE_MAX), 0.001f); // clamped
        assertEquals(50f, ThreatMath.threat(50f, 1.0f), 0.001f);
    }

    @Test
    void theCurveDecidesHowMuchOfThreatBecomesDifficulty() {
        // at full threat every curve agrees
        assertEquals(1.0f, ThreatMath.scaled(100f, 0.8f), 0.001f);
        assertEquals(1.0f, ThreatMath.scaled(100f, 1.2f), 0.001f);
        // below full, a sub-linear curve is gentler and a super-linear one harsher
        assertTrue(ThreatMath.scaled(50f, 0.8f) > ThreatMath.scaled(50f, 1.0f));
        assertTrue(ThreatMath.scaled(50f, 1.2f) < ThreatMath.scaled(50f, 1.0f));
    }

    @Test
    void aTrivialAttackerBarelyCounts() {
        // attacker as dangerous as expected -> full weight
        assertEquals(1.0f, ThreatMath.attackerWeight(100.0, 100.0), 0.001f);
        // a tenth as dangerous -> a tenth of the weight
        assertEquals(0.1f, ThreatMath.attackerWeight(10.0, 100.0), 0.001f);
        // more dangerous than expected is still only full weight
        assertEquals(1.0f, ThreatMath.attackerWeight(500.0, 100.0), 0.001f);
    }

    @Test
    void hardshipRisesCompetenceWhenCoastingAndLowersItWhenStruggling() {
        float coasting = ThreatMath.foldHardship(1.0f, 0.0f, 1.0f, ThreatTuning.DEFAULTS);
        float struggling = ThreatMath.foldHardship(1.0f, 1.0f, 1.0f, ThreatTuning.DEFAULTS);
        assertTrue(coasting > 1.0f, "an untouched win should read as coasting");
        assertTrue(struggling < 1.0f, "a near-death should read as struggling");
        // and it rises faster than it falls, by design
        assertTrue(coasting - 1.0f > 1.0f - struggling);
    }

    @Test
    void groupScalingLeansOnTheStrongestAndIsCapped() {
        assertEquals(0.5f, ThreatMath.group(0.5f, 1, 0.15f, 0.45f), 0.001f);
        assertEquals(0.5f * 1.15f, ThreatMath.group(0.5f, 2, 0.15f, 0.45f), 0.001f);
        assertEquals(0.5f * 1.45f, ThreatMath.group(0.5f, 9, 0.15f, 0.45f), 0.001f);
    }

    @Test
    void aServerMayTightenTheBandButNeverWidenIt() {
        // asking for a wider band than the floor allows is silently refused - this is the exploit
        // guard, not a preference, so ThreatMath clamps its own inputs rather than trusting callers
        assertEquals(ThreatMath.COMPETENCE_MIN, ThreatMath.bandFor(0.0f, 2.0f)[0], 0.001f);
        assertEquals(ThreatMath.COMPETENCE_MAX, ThreatMath.bandFor(0.0f, 2.0f)[1], 0.001f);
        // but a tighter band is honoured
        assertEquals(0.9f, ThreatMath.bandFor(0.9f, 1.1f)[0], 0.001f);
        assertEquals(1.1f, ThreatMath.bandFor(0.9f, 1.1f)[1], 0.001f);
    }

    @Test
    void everyThreatHasARankAndTheRanksCoverTheWholeRange() {
        assertSame(ThreatRank.UNNOTICED, ThreatRank.of(0f));
        assertSame(ThreatRank.UNNOTICED, ThreatRank.of(19f));
        assertSame(ThreatRank.WATCHED, ThreatRank.of(20f));
        assertSame(ThreatRank.MARKED, ThreatRank.of(40f));
        assertSame(ThreatRank.HUNTED, ThreatRank.of(60f));
        assertSame(ThreatRank.SHADOW, ThreatRank.of(80f));
        assertSame(ThreatRank.SHADOW, ThreatRank.of(100f));
        for (ThreatRank rank : ThreatRank.values()) {
            assertTrue(rank.translationKey().startsWith("kindreds.threat.rank."));
        }
    }
}
