package com.kindreds.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link Menace#applyTo} - the enemy-difficulty axis - and the two properties that make the
 * split from {@link Difficulty} worth having: this preset writes every enemy dial, and it writes
 * <b>only</b> those, leaving pacing entirely alone.
 */
class MenaceTest {

    private static final double SENTINEL_XP_RATE = 42.0;
    private static final int SENTINEL_CAP_PERCENT = 13;
    private static final int SENTINEL_RESPEC_COST = 999;
    private static final int SENTINEL_WEIGHT_GEAR = 222;

    private static KindredsConfig sentinelConfig() {
        KindredsConfig c = new KindredsConfig();
        c.xpRateGlobal = SENTINEL_XP_RATE;
        c.deathPenalty = DeathPenalty.HARDCORE;
        c.pointCapPercent = SENTINEL_CAP_PERCENT;
        c.respecCost = SENTINEL_RESPEC_COST;
        c.weightGear = SENTINEL_WEIGHT_GEAR;
        return c;
    }

    private static void assertPacingUntouched(KindredsConfig c, String label) {
        assertEquals(SENTINEL_XP_RATE, c.xpRateGlobal, 1e-9, label + ": xpRateGlobal is Difficulty's");
        assertEquals(DeathPenalty.HARDCORE, c.deathPenalty, label + ": deathPenalty is Difficulty's");
        assertEquals(SENTINEL_CAP_PERCENT, c.pointCapPercent, label + ": pointCapPercent is Difficulty's");
        assertEquals(SENTINEL_RESPEC_COST, c.respecCost, label + ": respecCost is Difficulty's");
        assertEquals(SENTINEL_WEIGHT_GEAR, c.weightGear, label + ": weightGear is nobody's preset");
    }

    @Test
    void everyPresetWritesItsOwnEnemyDialsAndNoPacing() {
        for (Menace m : Menace.values()) {
            if (m == Menace.CUSTOM) {
                continue; // its contract is the opposite one - see below
            }
            KindredsConfig c = sentinelConfig();
            m.applyTo(c);
            assertEquals(m.enemyScaling, c.enableEnemyScaling, m + ": enableEnemyScaling");
            assertEquals(m.maxHealthBonus, c.maxHealthBonus, m + ": maxHealthBonus");
            assertEquals(m.maxDamageBonus, c.maxDamageBonus, m + ": maxDamageBonus");
            assertEquals(m.eliteChance, c.eliteChance, m + ": eliteChance");
            assertEquals(m.escortChance, c.escortChance, m + ": escortChance");
            assertEquals(m.groupScalingPercent, c.groupScalingPercent, m + ": groupScalingPercent");
            assertEquals(m.xpBonus, c.xpBonus, m + ": xpBonus");
            assertEquals(m.curve, c.scalingCurve, m + ": scalingCurve");
            assertEquals(m.adaptiveStrength, c.adaptiveStrength, m + ": adaptiveStrength");
            assertEquals(m.maxCompetence, c.maxCompetence, 1e-6f, m + ": maxCompetence");
            assertEquals(m.dimensionMultiplierMiddleEarth, c.dimensionMultiplierMiddleEarth, 1e-6f,
                    m + ": dimensionMultiplierMiddleEarth");
            assertEquals(m.dimensionMultiplierOverworld, c.dimensionMultiplierOverworld, 1e-6f,
                    m + ": dimensionMultiplierOverworld");
            assertPacingUntouched(c, m.name());
        }
    }

    @Test
    void customAppliesNothingAtAll() {
        // CUSTOM's entire contract, and the reason it is the default for a config file written before
        // this axis existed: applying it must never overwrite a hand-tuned server.
        KindredsConfig c = sentinelConfig();
        c.maxHealthBonus = 61;
        c.maxDamageBonus = 62;
        c.eliteChance = 63;
        c.escortChance = 64;
        c.groupScalingPercent = 65;
        c.xpBonus = 66;
        c.adaptiveStrength = 67;
        c.maxCompetence = 6.8f;
        c.dimensionMultiplierMiddleEarth = 6.9f;
        c.dimensionMultiplierOverworld = 7.0f;
        c.scalingCurve = ScalingCurve.EXACT_PACE;
        c.enableEnemyScaling = false;

        Menace.CUSTOM.applyTo(c);

        assertEquals(61, c.maxHealthBonus, "CUSTOM: maxHealthBonus");
        assertEquals(62, c.maxDamageBonus, "CUSTOM: maxDamageBonus");
        assertEquals(63, c.eliteChance, "CUSTOM: eliteChance");
        assertEquals(64, c.escortChance, "CUSTOM: escortChance");
        assertEquals(65, c.groupScalingPercent, "CUSTOM: groupScalingPercent");
        assertEquals(66, c.xpBonus, "CUSTOM: xpBonus");
        assertEquals(67, c.adaptiveStrength, "CUSTOM: adaptiveStrength");
        assertEquals(6.8f, c.maxCompetence, 1e-6f, "CUSTOM: maxCompetence");
        assertEquals(6.9f, c.dimensionMultiplierMiddleEarth, 1e-6f, "CUSTOM: dimME");
        assertEquals(7.0f, c.dimensionMultiplierOverworld, 1e-6f, "CUSTOM: dimOW");
        assertEquals(ScalingCurve.EXACT_PACE, c.scalingCurve, "CUSTOM: scalingCurve");
        assertEquals(false, c.enableEnemyScaling, "CUSTOM: enableEnemyScaling");
        assertPacingUntouched(c, "CUSTOM");
    }

    /** The list is a difficulty ladder, so it has to actually climb - a preset that is not harder than
     * the one above it is a row that means nothing to the operator choosing between them. */
    @Test
    void thePresetsAreOrderedFromGentlestToHardest() {
        Menace[] ladder = {Menace.SHIRE, Menace.WATCHFUL_PEACE, Menace.GATHERING_DARK,
                Menace.OPEN_WAR, Menace.THE_BLACK_TIDE, Menace.WRATH_OF_SAURON};
        for (int i = 1; i < ladder.length; i++) {
            Menace lower = ladder[i - 1];
            Menace higher = ladder[i];
            assertTrue(higher.maxDamageBonus > lower.maxDamageBonus,
                    higher + " does not hit harder than " + lower);
            assertTrue(higher.maxHealthBonus > lower.maxHealthBonus,
                    higher + " is no tougher than " + lower);
            assertTrue(higher.eliteChance >= lower.eliteChance,
                    higher + " has fewer champions than " + lower);
            assertTrue(higher.maxCompetence >= lower.maxCompetence,
                    higher + " has a lower ceiling than " + lower);
        }
    }

    /**
     * Every dial the rules screen can edit must be one a preset also sets.
     *
     * <p>Otherwise picking a preset would silently leave a hand-edited value in place, and the
     * preset's name would describe the world only partly - the exact failure the {@code CUSTOM}
     * label exists to make impossible everywhere else.
     *
     * <p>Proved by poisoning every dial with a value no preset uses and checking each one actually
     * moves. A weaker form of this test - comparing against a default-constructed config - passes
     * vacuously for any dial whose preset value happens to equal its field initializer.
     */
    @Test
    void everyEditableDialIsCoveredByThePresets() {
        KindredsConfig c = new KindredsConfig();
        final double poison = 7.0; // inside every dial's range, equal to no preset's value for it
        for (RuleDial dial : RuleDial.values()) {
            dial.write(c, poison);
        }
        Menace.WRATH_OF_SAURON.applyTo(c);
        for (RuleDial dial : RuleDial.values()) {
            assertTrue(Math.abs(dial.read(c) - poison) > 1e-6,
                    dial.key + " is editable in the rules screen but no preset sets it");
        }
    }
}
