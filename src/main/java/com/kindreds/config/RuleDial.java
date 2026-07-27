package com.kindreds.config;

import java.util.Locale;

/**
 * The numeric enemy-difficulty settings the rules screen may edit directly, each with the bounds and
 * step sizes that describe it.
 *
 * <h2>Why this exists</h2>
 * The rules screen used to render these as text and stop there - the whole "The world answers"
 * section was display-only, with no click handler at any row. Picking {@code CUSTOM} therefore
 * selected a mode in which, by definition, nothing was preset-driven and yet nothing was editable
 * either: the one setting whose entire purpose is hand-tuning was the one that could not be
 * hand-tuned without a console. This is the missing half.
 *
 * <p>Keeping the bounds here rather than in the screen or the packet handler means the client's
 * arrows and the server's clamp are the same numbers by construction. The server still applies
 * {@link #clamp} itself on every write - the client is a UI, not an authority - but there is no
 * second table to drift out of step with this one.
 *
 * <h2>On the bounds</h2>
 * They are far wider than the ones they replace (health was capped at 400%, the dimension
 * multipliers at 2.0). These are not balance opinions; they exist so a typo produces a very hard
 * world rather than an arithmetic one. Where a bound is genuinely meaningful it is kept tight:
 * chances are percentages and stop at 100, and {@link #MAX_COMPETENCE} starts at 1.0 because below
 * that it would be inverting the adaptive loop rather than tuning it.
 */
public enum RuleDial {
    MAX_HEALTH_BONUS("maxHealthBonus", 0, 2000, 25, 100, false),
    MAX_DAMAGE_BONUS("maxDamageBonus", 0, 2000, 25, 100, false),
    ELITE_CHANCE("eliteChance", 0, 100, 5, 25, false),
    ESCORT_CHANCE("escortChance", 0, 100, 5, 25, false),
    GROUP_SCALING_PERCENT("groupScalingPercent", 0, 300, 5, 25, false),
    XP_BONUS("xpBonus", 0, 1000, 25, 100, false),
    ADAPTIVE_STRENGTH("adaptiveStrength", 0, 100, 10, 25, false),
    MAX_COMPETENCE("maxCompetence", 1.0, 10.0, 0.25, 1.0, true),
    DIMENSION_MULTIPLIER_MIDDLE_EARTH("dimensionMultiplierMiddleEarth", 0, 10, 0.25, 1.0, true),
    DIMENSION_MULTIPLIER_OVERWORLD("dimensionMultiplierOverworld", 0, 10, 0.25, 1.0, true);

    /** The config field name, which doubles as the wire key and the lang-key suffix. */
    public final String key;
    public final double min;
    public final double max;
    /** Step for a plain click, and for a shift-click. */
    public final double step;
    public final double bigStep;
    /** Whether this reads as a multiplier ({@code x2.0}) rather than a percentage ({@code 200%}). */
    public final boolean multiplier;

    RuleDial(String key, double min, double max, double step, double bigStep, boolean multiplier) {
        this.key = key;
        this.min = min;
        this.max = max;
        this.step = step;
        this.bigStep = bigStep;
        this.multiplier = multiplier;
    }

    public static RuleDial byKey(String key) {
        for (RuleDial d : values()) {
            if (d.key.equals(key)) {
                return d;
            }
        }
        return null;
    }

    public double clamp(double value) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public double read(KindredsConfig c) {
        return switch (this) {
            case MAX_HEALTH_BONUS -> c.maxHealthBonus;
            case MAX_DAMAGE_BONUS -> c.maxDamageBonus;
            case ELITE_CHANCE -> c.eliteChance;
            case ESCORT_CHANCE -> c.escortChance;
            case GROUP_SCALING_PERCENT -> c.groupScalingPercent;
            case XP_BONUS -> c.xpBonus;
            case ADAPTIVE_STRENGTH -> c.adaptiveStrength;
            case MAX_COMPETENCE -> c.maxCompetence;
            case DIMENSION_MULTIPLIER_MIDDLE_EARTH -> c.dimensionMultiplierMiddleEarth;
            case DIMENSION_MULTIPLIER_OVERWORLD -> c.dimensionMultiplierOverworld;
        };
    }

    /** Writes {@code value} (clamped) onto {@code c}. */
    public void write(KindredsConfig c, double value) {
        double v = clamp(value);
        switch (this) {
            case MAX_HEALTH_BONUS -> c.maxHealthBonus = (int) Math.round(v);
            case MAX_DAMAGE_BONUS -> c.maxDamageBonus = (int) Math.round(v);
            case ELITE_CHANCE -> c.eliteChance = (int) Math.round(v);
            case ESCORT_CHANCE -> c.escortChance = (int) Math.round(v);
            case GROUP_SCALING_PERCENT -> c.groupScalingPercent = (int) Math.round(v);
            case XP_BONUS -> c.xpBonus = (int) Math.round(v);
            case ADAPTIVE_STRENGTH -> c.adaptiveStrength = (int) Math.round(v);
            case MAX_COMPETENCE -> c.maxCompetence = (float) v;
            case DIMENSION_MULTIPLIER_MIDDLE_EARTH -> c.dimensionMultiplierMiddleEarth = (float) v;
            case DIMENSION_MULTIPLIER_OVERWORLD -> c.dimensionMultiplierOverworld = (float) v;
        }
    }

    /** How this value reads to a player: {@code "200%"} or {@code "x2.00"}. */
    public String format(double value) {
        return multiplier
                ? String.format(Locale.ROOT, "x%.2f", value)
                : String.valueOf((int) Math.round(value));
    }
}
