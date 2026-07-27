package com.kindreds.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers {@link Difficulty#applyTo}: each preset's pacing values, and the "presets don't clobber
 * operator dials" guarantee - the free dials ({@link KindredsConfig#weightCommitment} and friends)
 * are calibration an operator tunes independently of picking a feel, and {@link Difficulty#applyTo}
 * must never touch them.
 *
 * <p>{@code enableEnemyScaling} and {@code scalingCurve} were asserted here as values this preset
 * <em>writes</em>. They belong to {@link Menace} now - the separate enemy-difficulty axis - and are
 * asserted below as values this one must <b>not</b> write. That inversion is the whole point of the
 * split: wanting a punishing world should not oblige you to also accept crawling xp and a quarter of
 * your progress burned on every death.
 */
class DifficultyTest {

    private static final int SENTINEL_WC = 111;
    private static final int SENTINEL_WG = 222;
    private static final int SENTINEL_WR = 333;
    private static final float SENTINEL_DECAY = 987.5f;
    private static final int SENTINEL_MAX_DMG = 777;
    private static final int SENTINEL_XP = 444;
    private static final int SENTINEL_ADAPTIVE = 55;
    private static final int SENTINEL_MAX_HEALTH_BONUS = 61;
    private static final int SENTINEL_ELITE_CHANCE = 62;
    private static final int SENTINEL_ESCORT_CHANCE = 63;
    private static final int SENTINEL_GROUP_SCALING_PERCENT = 64;
    private static final float SENTINEL_DIM_MULT_MIDDLE_EARTH = 0.65f;
    private static final float SENTINEL_DIM_MULT_OVERWORLD = 0.66f;

    /** The enemy-axis values {@link Difficulty} must leave alone; {@link Menace} owns both. */
    private static final boolean SENTINEL_SCALING = false;
    private static final ScalingCurve SENTINEL_CURVE = ScalingCurve.EXACT_PACE;

    private static KindredsConfig sentinelConfig() {
        KindredsConfig c = new KindredsConfig();
        c.enableEnemyScaling = SENTINEL_SCALING;
        c.scalingCurve = SENTINEL_CURVE;
        c.weightCommitment = SENTINEL_WC;
        c.weightGear = SENTINEL_WG;
        c.weightRenown = SENTINEL_WR;
        c.priorDecayPerHour = SENTINEL_DECAY;
        c.maxDamageBonus = SENTINEL_MAX_DMG;
        c.xpBonus = SENTINEL_XP;
        c.adaptiveStrength = SENTINEL_ADAPTIVE;
        c.maxHealthBonus = SENTINEL_MAX_HEALTH_BONUS;
        c.eliteChance = SENTINEL_ELITE_CHANCE;
        c.escortChance = SENTINEL_ESCORT_CHANCE;
        c.groupScalingPercent = SENTINEL_GROUP_SCALING_PERCENT;
        c.dimensionMultiplierMiddleEarth = SENTINEL_DIM_MULT_MIDDLE_EARTH;
        c.dimensionMultiplierOverworld = SENTINEL_DIM_MULT_OVERWORLD;
        return c;
    }

    private static void assertFreeDialsUntouched(KindredsConfig c, String label) {
        assertEquals(SENTINEL_SCALING, c.enableEnemyScaling, label + ": enableEnemyScaling is Menace's");
        assertEquals(SENTINEL_CURVE, c.scalingCurve, label + ": scalingCurve is Menace's");
        assertEquals(SENTINEL_WC, c.weightCommitment, label + ": weightCommitment");
        assertEquals(SENTINEL_WG, c.weightGear, label + ": weightGear");
        assertEquals(SENTINEL_WR, c.weightRenown, label + ": weightRenown");
        assertEquals(SENTINEL_DECAY, c.priorDecayPerHour, 0.0001f, label + ": priorDecayPerHour");
        assertEquals(SENTINEL_MAX_DMG, c.maxDamageBonus, label + ": maxDamageBonus");
        assertEquals(SENTINEL_XP, c.xpBonus, label + ": xpBonus");
        assertEquals(SENTINEL_ADAPTIVE, c.adaptiveStrength, label + ": adaptiveStrength");
        assertEquals(SENTINEL_MAX_HEALTH_BONUS, c.maxHealthBonus, label + ": maxHealthBonus");
        assertEquals(SENTINEL_ELITE_CHANCE, c.eliteChance, label + ": eliteChance");
        assertEquals(SENTINEL_ESCORT_CHANCE, c.escortChance, label + ": escortChance");
        assertEquals(SENTINEL_GROUP_SCALING_PERCENT, c.groupScalingPercent, label + ": groupScalingPercent");
        assertEquals(SENTINEL_DIM_MULT_MIDDLE_EARTH, c.dimensionMultiplierMiddleEarth, 0.0001f,
                label + ": dimensionMultiplierMiddleEarth");
        assertEquals(SENTINEL_DIM_MULT_OVERWORLD, c.dimensionMultiplierOverworld, 0.0001f,
                label + ": dimensionMultiplierOverworld");
    }

    /** Every preset writes its own pacing values and nothing beyond them. */
    private static void assertPreset(Difficulty d) {
        KindredsConfig c = sentinelConfig();
        d.applyTo(c);
        assertEquals(d.xpRate, c.xpRateGlobal, 1e-9, d + ": xpRateGlobal");
        assertEquals(d.death, c.deathPenalty, d + ": deathPenalty");
        assertEquals(d.deathPercent, c.deathPercent, 1e-9, d + ": deathPercent");
        assertEquals(d.capPercent, c.pointCapPercent, d + ": pointCapPercent");
        assertEquals(d.respecCost, c.respecCost, d + ": respecCost");
        assertFreeDialsUntouched(c, d.name());
    }

    @Test
    void firesidePacesForStory() {
        assertPreset(Difficulty.FIRESIDE);
    }

    @Test
    void roadPacesAsTheDefault() {
        assertPreset(Difficulty.ROAD);
    }

    @Test
    void longDefeatPacesForCommitment() {
        assertPreset(Difficulty.LONG_DEFEAT);
    }

    @Test
    void doomPacesHarshly() {
        assertPreset(Difficulty.DOOM);
    }

    @Test
    void customPresetAppliesNothing() {
        // Sentinel values across every field applyTo would otherwise touch, not just the free
        // dials - CUSTOM's whole contract is "leaves the file alone".
        KindredsConfig c = sentinelConfig();
        c.xpRateGlobal = 42.0;
        c.deathPenalty = DeathPenalty.HARDCORE;
        c.deathPercent = 0.99;
        c.pointCapPercent = 13;
        c.pointSoftCap = 77;
        c.respecCost = 999;

        Difficulty.CUSTOM.applyTo(c);

        assertEquals(42.0, c.xpRateGlobal, 1e-9, "CUSTOM: xpRateGlobal");
        assertEquals(DeathPenalty.HARDCORE, c.deathPenalty, "CUSTOM: deathPenalty");
        assertEquals(0.99, c.deathPercent, 1e-9, "CUSTOM: deathPercent");
        assertEquals(13, c.pointCapPercent, "CUSTOM: pointCapPercent");
        assertEquals(77, c.pointSoftCap, "CUSTOM: pointSoftCap");
        assertEquals(999, c.respecCost, "CUSTOM: respecCost");
        assertFreeDialsUntouched(c, "CUSTOM");
    }
}
