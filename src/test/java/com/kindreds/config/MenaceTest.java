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
            // The bearing dials climb too, and they matter more than the sponge ones: a preset that
            // raised health but left mobs as easy to stagger, outrun and slip past as the one below it
            // would be a longer fight rather than a harder one.
            assertTrue(higher.armorBonus >= lower.armorBonus,
                    higher + " is no better armoured than " + lower);
            assertTrue(higher.knockbackResistBonus >= lower.knockbackResistBonus,
                    higher + " is no harder to stagger than " + lower);
            assertTrue(higher.mobSpeedBonus >= lower.mobSpeedBonus,
                    higher + " is no faster than " + lower);
            assertTrue(higher.followRangeBonus >= lower.followRangeBonus,
                    higher + " notices you no sooner than " + lower);
        }
    }

    /**
     * No preset may produce a damage sponge.
     *
     * <p>This is the test the first pass of these numbers needed and did not have. Every bearing dial
     * is written as its value at {@code scaledGroup == 1.0}, but that figure is a product of threat,
     * party size and dimension pacing and reaches about 3 in play - so preset numbers chosen as
     * though the multiplier were 1 arrive nearly tripled. {@link Menace#OPEN_WAR} measured out at a
     * <b>12x</b> effective health multiplier that way (armour 16.7 cutting 57% of incoming damage on
     * top of 4.2x health), and the preset above it at 40x. Nothing failed; it simply would have
     * shipped as a mob that takes four minutes to kill.
     *
     * <p>Two tiers, because they are two different questions. The presets people actually play must
     * stay genuinely fightable, so they are held to 10x. {@link Menace#WRATH_OF_SAURON} is documented
     * as not balanced and not pretending to be, so holding it to a playable bound would be testing
     * the opposite of what it is for - it only has to stay the right order of magnitude.
     */
    @Test
    void noPresetBecomesADamageSpongeAtFullThreat() {
        for (Menace m : Menace.values()) {
            if (m == Menace.CUSTOM || !m.enemyScaling) {
                continue;
            }
            double effectiveHealth = effectiveHealthMultiplier(m);
            double bound = m == Menace.WRATH_OF_SAURON ? 20.0 : 10.0;
            assertTrue(effectiveHealth <= bound,
                    m + " arrives at " + Math.round(effectiveHealth) + "x effective health (bound "
                            + Math.round(bound) + "x) - that is a mob that takes minutes to kill, not a"
                            + " harder fight");
        }
    }

    /**
     * How many times over a mob's health a player must chew through at this preset, counting armour.
     *
     * <p>Health and armour are separately innocuous and jointly the whole problem - 4x health is a
     * long fight, 57% damage reduction is a long fight, and together they are 12x - so the sponge test
     * has to measure their product rather than either alone.
     */
    private static double effectiveHealthMultiplier(Menace m) {
        float sg = com.kindreds.threat.ThreatMath.TYPICAL_MAX_SCALED_GROUP;
        double armour = Math.min(com.kindreds.threat.ThreatMath.MAX_SCALED_ARMOR, m.armorBonus * sg);
        double toughness = armour / 2.0;
        // Vanilla's own armour formula, against a 10-damage swing - a decent hit from a real weapon.
        double reduction = Math.min(20.0,
                Math.max(armour / 5.0, armour - 10.0 / (2.0 + toughness / 4.0))) / 25.0;
        return (1.0 + m.maxHealthBonus / 100.0 * sg) / (1.0 - reduction);
    }

    /** Toughness climbs with the ladder in felt terms too, not just in the raw dial - a preset that
     * reads harder but fights identically to the one below it is a row that means nothing. */
    @Test
    void eachPresetIsActuallyTougherToChewThroughThanTheOneBelow() {
        Menace[] ladder = {Menace.WATCHFUL_PEACE, Menace.GATHERING_DARK, Menace.OPEN_WAR,
                Menace.THE_BLACK_TIDE, Menace.WRATH_OF_SAURON};
        for (int i = 1; i < ladder.length; i++) {
            assertTrue(effectiveHealthMultiplier(ladder[i]) > effectiveHealthMultiplier(ladder[i - 1]),
                    ladder[i] + " is no harder to bring down than " + ladder[i - 1]);
        }
    }

    /**
     * Staggering an enemy must survive every preset.
     *
     * <p>Knockback resistance of 1.0 is total immunity, and because the dial is multiplied by a factor
     * reaching 3, every preset with a dial above roughly a third reached it - which silently removed a
     * core verb of the game's combat at <em>every</em> difficulty, including the gentle ones. The
     * ceiling is what prevents that; this proves no preset relies on being clamped by it to stay
     * sane, and that the ceiling itself still leaves room to stagger.
     */
    @Test
    void mobsAreNeverFullyImmuneToKnockback() {
        assertTrue(com.kindreds.threat.ThreatMath.MAX_SCALED_KNOCKBACK < 1.0,
                "a ceiling of 1.0 is total knockback immunity, which is not a ceiling");
        float sg = com.kindreds.threat.ThreatMath.TYPICAL_MAX_SCALED_GROUP;
        for (Menace m : Menace.values()) {
            double knockback = Math.min(com.kindreds.threat.ThreatMath.MAX_SCALED_KNOCKBACK,
                    m.knockbackResistBonus / 100.0 * sg);
            assertTrue(knockback < 1.0, m + " reaches total knockback immunity");
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
