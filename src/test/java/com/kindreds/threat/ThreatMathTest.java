package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // a negative playedTicks (clock skew, bad save data) must not be read as extra decay time
        assertEquals(80f, ThreatMath.decayed(80f, 10f, 2f, -72000L), 0.001f);
    }

    @Test
    void negativePerHourNeverGrowsTheMark() {
        // priorDecayPerHour is exposed and (before FIX 3) unvalidated at /kindreds config: a negative
        // value makes allowance negative, so mark - allowance RAISES the mark instead of lowering it.
        // Without the clamp this exact call computes 82f (above its own 80f starting mark) from a
        // single hour of play, and would keep compounding on every 40-tick refresh thereafter.
        float mark = ThreatMath.decayed(80f, 10f, -2f, 72000L);
        assertEquals(80f, mark, 0.001f, "a negative perHour changed the mark via decay: " + mark);
    }

    @Test
    void competenceCannotEscapeItsBand() {
        float high = ThreatMath.foldFastKill(1.25f, 1.0f, ThreatTuning.DEFAULTS);
        assertTrue(high <= ThreatMath.competenceMax(), "rose past the ceiling: " + high);
        float low = 1.0f;
        for (int i = 0; i < 500; i++) {
            low = ThreatMath.foldDeath(low, 1.0f, ThreatTuning.DEFAULTS);
        }
        assertEquals(ThreatMath.COMPETENCE_MIN, low, 0.001f);
    }

    @Test
    void foldFastKillOnlyEverRaisesCompetence() {
        float start = 1.0f;
        float raised = ThreatMath.foldFastKill(start, 1.0f, ThreatTuning.DEFAULTS);
        assertTrue(raised > start, "a fast kill should strictly raise competence, got " + raised);

        // repeated application must never move it backwards, all the way up to the ceiling
        float competence = start;
        float previous = competence;
        for (int i = 0; i < 50; i++) {
            competence = ThreatMath.foldFastKill(competence, 1.0f, ThreatTuning.DEFAULTS);
            assertTrue(competence >= previous, "competence decreased on iteration " + i);
            previous = competence;
        }
    }

    @Test
    void fastKillsOfTrivialMobsProveNothing() {
        float c = 1.0f;
        for (int i = 0; i < 100; i++) {
            c = ThreatMath.foldFastKill(c, 0.003f, ThreatTuning.DEFAULTS);  // chicken-danger weight
        }
        assertTrue(c < 1.01f, "100 trivial fast kills must not meaningfully raise competence, got " + c);
    }

    @Test
    void fastKillsOfRealThreatsCount() {
        float once = ThreatMath.foldFastKill(1.0f, 1.0f, ThreatTuning.DEFAULTS);
        assertEquals(1.0f + 0.10f * 0.05f, once, 0.0001f);
    }

    @Test
    void threatIsThePriorMovedOnlyWithinTheBand() {
        assertEquals(75f, ThreatMath.threat(100f, ThreatMath.COMPETENCE_MIN), 0.001f);
        assertEquals(100f, ThreatMath.threat(100f, ThreatMath.competenceMax()), 0.001f); // clamped
        assertEquals(50f, ThreatMath.threat(50f, 1.0f), 0.001f);
    }

    @Test
    void threatNeverEscapesAsNaNEvenFromABadPrior() {
        assertEquals(0f, ThreatMath.threat(Float.NaN, 1.0f), 0.001f);
        assertEquals(0f, ThreatMath.threat(Float.NaN, Float.NaN), 0.001f);
    }

    @Test
    void theCurveDecidesHowMuchOfThreatBecomesDifficulty() {
        // at full threat every curve agrees - kept as documentation of that identity
        assertEquals(1.0f, ThreatMath.scaled(100f, 0.8f), 0.001f);
        // below full, the exponent must actually change the answer: half threat squared is a quarter
        assertEquals(0.25f, ThreatMath.scaled(50f, 2.0f), 0.001f);
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
        float coasting = ThreatMath.foldHardship(1.0f, 0.0f, 1.0f, 1.0f, ThreatTuning.DEFAULTS);
        float struggling = ThreatMath.foldHardship(1.0f, 1.0f, 1.0f, 1.0f, ThreatTuning.DEFAULTS);
        assertTrue(coasting > 1.0f, "an untouched win should read as coasting");
        assertTrue(struggling < 1.0f, "a near-death should read as struggling");
    }

    @Test
    void killShareScalesTheRiseAndOnlyTheRise() {
        float fullShare = ThreatMath.foldHardship(1.0f, 0.0f, 1.0f, 1.0f, ThreatTuning.DEFAULTS);
        float tinyShare = ThreatMath.foldHardship(1.0f, 0.0f, 1.0f, 0.01f, ThreatTuning.DEFAULTS);
        assertTrue(fullShare > 1.0f);
        assertTrue(tinyShare < 1.0f + (fullShare - 1.0f) * 0.02f, "a 1% share must earn ~1% of the rise");
        // THE ASYMMETRY: a struggling fold is untouched by share
        float fallFull = ThreatMath.foldHardship(1.0f, 1.0f, 1.0f, 1.0f, ThreatTuning.DEFAULTS);
        float fallTiny = ThreatMath.foldHardship(1.0f, 1.0f, 1.0f, 0.01f, ThreatTuning.DEFAULTS);
        assertEquals(fallFull, fallTiny, 1e-6f, "share must never gate the falling branch");
    }

    @Test
    void hardshipRiseIsAlwaysLargerInMagnitudeThanFall() {
        // hardship=5f is the regression case for the un-clamped-hardship exploit: before hardship was
        // clamped to 0..1 inside foldHardship, this single value produced a fall over five times the
        // largest possible rise, letting a player tank a fight forever and drive competence to the
        // floor without ever dying. It must stay in this list.
        float[] hardships = {0f, 0.25f, 0.5f, 1f, 5f};
        float[] attackerWeights = {0.1f, 1.0f};

        float largestRise = 0f;
        float largestFallMagnitude = 0f;
        for (float hardship : hardships) {
            for (float attackerWeight : attackerWeights) {
                float result = ThreatMath.foldHardship(1.0f, hardship, attackerWeight, 1.0f, ThreatTuning.DEFAULTS);
                float delta = result - 1.0f;
                if (delta > 0f) {
                    largestRise = Math.max(largestRise, delta);
                } else {
                    largestFallMagnitude = Math.max(largestFallMagnitude, -delta);
                }
            }
        }

        assertTrue(largestFallMagnitude < largestRise,
                "largest fall " + largestFallMagnitude + " was not smaller than largest rise " + largestRise);
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
        assertEquals(ThreatMath.competenceMax(), ThreatMath.bandFor(0.0f, 2.0f)[1], 0.001f);
        // but a tighter band is honoured
        assertEquals(0.9f, ThreatMath.bandFor(0.9f, 1.1f)[0], 0.001f);
        assertEquals(1.1f, ThreatMath.bandFor(0.9f, 1.1f)[1], 0.001f);
    }

    @Test
    void bandForHandlesInvertedArguments() {
        // min > max is nonsensical input, not a case ThreatMath gets to trust - it must still resolve
        // to something inside the hard floor rather than propagate the inversion
        float[] inverted = ThreatMath.bandFor(2.0f, 0.0f);
        assertEquals(1.0f, inverted[0], 0.001f);
        assertEquals(1.0f, inverted[1], 0.001f);
    }

    @Test
    void bandForCoercesNaNToTheHardLimitOnItsSide() {
        // hardship is a division computed elsewhere in the mod, so a 0/0 can reach here as NaN; if
        // Math.max/Math.min were left to propagate it, the band would silently become [NaN, NaN]
        float[] band = ThreatMath.bandFor(Float.NaN, Float.NaN);
        assertEquals(ThreatMath.COMPETENCE_MIN, band[0], 0.001f);
        assertEquals(ThreatMath.competenceMax(), band[1], 0.001f);
    }

    @Test
    void effectiveCompetenceIsTheEvenBlendOfGlobalAndFamily() {
        assertEquals(1.0f, ThreatMath.effectiveCompetence(1.0f, 1.0f), 0.001f);
        assertEquals(0.9f, ThreatMath.effectiveCompetence(0.8f, 1.0f), 0.001f);
        // a family record far outside the band still can't push the blend past the floor
        assertEquals(ThreatMath.COMPETENCE_MIN, ThreatMath.effectiveCompetence(0.75f, 0.0f), 0.001f);
    }

    @Test
    void adaptiveStrengthZeroCollapsesTheBandToExactlyOne() {
        // 0% adaptive strength: no adaptation at all, the prior alone decides threat.
        float[] band = ThreatMath.adaptiveBand(0);
        assertEquals(1.0f, band[0], 0.0001f);
        assertEquals(1.0f, band[1], 0.0001f);
    }

    @Test
    void adaptiveStrengthOneHundredIsTheFullFloorBand() {
        float[] band = ThreatMath.adaptiveBand(100);
        assertEquals(ThreatMath.COMPETENCE_MIN, band[0], 0.0001f);
        assertEquals(ThreatMath.competenceMax(), band[1], 0.0001f);
    }

    @Test
    void adaptiveStrengthFiftyIsHalfwayNarrowed() {
        // Halfway between "no adaptation" (1.0) and each end of the full band. The two ends are
        // deliberately asymmetric - the floor is the anti-farm bound and stayed where it was, while
        // the ceiling governs how hard the world may become for someone who keeps winning and was
        // widened - so each side is derived from its own end rather than from one shared span.
        float[] band = ThreatMath.adaptiveBand(50);
        assertEquals(1.0f - (1.0f - ThreatMath.COMPETENCE_MIN) * 0.5f, band[0], 0.0001f);
        assertEquals(1.0f + (ThreatMath.competenceMax() - 1.0f) * 0.5f, band[1], 0.0001f);
    }

    @Test
    void adaptiveStrengthNeverWidensPastTheFloorEvenWithoutItsOwnClamp() {
        // A misconfigured adaptiveStrength of 200% must never widen the band past the anti-farm
        // floor. Prove it is genuinely enforced (not just numerically coincidental) by computing the
        // RAW, unclamped band the naive formula would produce at s=2.0 - twice each side's span -
        // and showing that raw pair sits outside the floor. If adaptiveBand stopped calling bandFor
        // (or its own s-clamp), it would return this raw, floor-violating pair instead of the actual
        // assertion below. Derived from the constants rather than written out, so retuning either end
        // cannot quietly turn this into a test that proves nothing.
        float rawMin = 1f - (1f - ThreatMath.COMPETENCE_MIN) * 2.0f;
        float rawMax = 1f + (ThreatMath.competenceMax() - 1f) * 2.0f;
        assertTrue(rawMin < ThreatMath.COMPETENCE_MIN, "test setup: raw band should violate the floor");
        assertTrue(rawMax > ThreatMath.competenceMax(), "test setup: raw band should violate the floor");

        float[] band = ThreatMath.adaptiveBand(200);
        assertEquals(ThreatMath.COMPETENCE_MIN, band[0], 0.0001f,
                "adaptiveStrength=200 widened past the floor: " + band[0]);
        assertEquals(ThreatMath.competenceMax(), band[1], 0.0001f,
                "adaptiveStrength=200 widened past the floor: " + band[1]);
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
        assertEquals("kindreds.threat.rank.hunted", ThreatRank.HUNTED.translationKey());
    }

    // --- familyVoiceKeys (spec §3a's Deeds-page voice, derived - see ThreatState#copy()) ----------

    @Test
    void familyVoiceLinesRequireStrictlyMoreThanOneTenthDivergence() {
        float global = 1.0f;
        Map<String, Float> family = new HashMap<>();
        family.put("trolls", global + 0.10f);   // exactly +0.10 - must say nothing
        family.put("wargs", global - 0.10f);    // exactly -0.10 - must say nothing
        assertTrue(ThreatMath.familyVoiceKeys(family, global).isEmpty(),
                "a divergence of exactly 0.1 must not voice a line");

        family.put("trolls", global + 0.11f);   // clearly past +0.10
        family.put("wargs", global - 0.11f);    // clearly past -0.10
        List<String> keys = ThreatMath.familyVoiceKeys(family, global);
        assertTrue(keys.contains("kindreds.family.mastered.trolls"), keys.toString());
        assertTrue(keys.contains("kindreds.family.feared.wargs"), keys.toString());
    }

    @Test
    void familyVoiceLinesCapAtThreeStrongestDivergenceFirst() {
        // Magnitudes deliberately spaced well apart (0.40/0.35/0.20/0.15, plus a below-threshold
        // 0.05) so the ordering is unambiguous even after float rounding - a near-tie belongs to a
        // dedicated float-precision test, not this one, which is only about the cap and the ordering.
        Map<String, Float> family = new HashMap<>();
        family.put("spiders", 1.40f);  // +0.40 mastered - strongest
        family.put("wargs", 0.65f);    // -0.35 feared - second strongest
        family.put("trolls", 1.20f);   // +0.20 mastered - third strongest, makes the cut
        family.put("undead", 0.85f);   // -0.15 feared - the weakest qualifier, dropped by the cap
        family.put("orc_kin", 1.05f);  // +0.05 - under threshold, never appears
        List<String> keys = ThreatMath.familyVoiceKeys(family, 1.0f);

        assertEquals(3, keys.size(), "four families qualify but at most three lines: " + keys);
        assertEquals(List.of("kindreds.family.mastered.spiders", "kindreds.family.feared.wargs",
                "kindreds.family.mastered.trolls"), keys);
        assertFalse(keys.contains("kindreds.family.mastered.orc_kin"), "0.05 is under threshold: " + keys);
        assertFalse(keys.contains("kindreds.family.feared.undead"), "the weakest qualifier is dropped: " + keys);
    }

    @Test
    void onlyTheFiveNamedFamiliesEverVoiceAnOpinion() {
        Map<String, Float> family = new HashMap<>();
        family.put("other", 5.0f); // wildly divergent, but "other" is not one of the five named families
        assertTrue(ThreatMath.familyVoiceKeys(family, 1.0f).isEmpty(),
                "an 'other' entry must never produce a voice line");
    }

    @Test
    void aFamilyWithNoEvidenceRecordedSaysNothing() {
        // familyCompetence has no entry at all for this family (never fought it) - must not be read
        // as "diverges by the full global-vs-zero amount".
        assertTrue(ThreatMath.familyVoiceKeys(Map.of(), 1.0f).isEmpty());
    }
}
