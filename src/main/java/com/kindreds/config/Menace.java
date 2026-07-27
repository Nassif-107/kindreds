package com.kindreds.config;

/**
 * How dangerous the world's enemies are - the second difficulty axis, independent of {@link
 * Difficulty}.
 *
 * <h2>Why two axes</h2>
 * {@link Difficulty} is about <b>you</b>: how fast you grow, what a death costs, how much of your
 * tree you may ever master. This is about <b>them</b>: how hard the things you meet hit, how much
 * they can take, how often one of them is a named champion with friends. They were one setting, and
 * that was wrong in both directions - a player who wanted a punishing world had to accept crawling xp
 * and a quarter of their progress burned on every death to get it, and a player who wanted a
 * committed, high-stakes progression had to accept whatever enemy difficulty came bundled with it.
 * Splitting them means "gentle pace, terrifying world" and "brutal pace, gentle world" are both
 * expressible, and neither is a compromise.
 *
 * <p>Concretely, {@link Difficulty#applyTo} no longer touches {@code enableEnemyScaling} or {@code
 * scalingCurve}; both now belong here, along with every output bound the threat system has.
 *
 * <h2>The range</h2>
 * The presets deliberately span further than the old bounds allowed. {@link #SHIRE} turns scaling off
 * entirely - vanilla mobs, nothing added. {@link #WRATH_OF_SAURON} is not a balanced setting and is
 * not meant to be: it exists because "make it as hard as it goes" is a legitimate thing to want, and
 * a difficulty list whose top entry is merely "quite hard" is a list with a missing row.
 *
 * <p>{@link #CUSTOM} applies nothing, leaving every value exactly as written in the config file -
 * which is what any hand-edit in the rules screen switches the config onto, so the screen never
 * claims a preset is active while showing numbers that do not match it.
 */
public enum Menace {
    /** No scaling at all. A vanilla orc is a vanilla orc; the world never answers. */
    SHIRE(false, 0, 0, 0, 0, 0, 0, ScalingCurve.FEEL_STRONGER, 0, 1.0f, 1.0f, 1.0f),

    /** The Watchful Peace: the world notices you, and little more. Growing strength outruns it. */
    WATCHFUL_PEACE(true, 60, 40, 10, 10, 10, 25, ScalingCurve.FEEL_STRONGER, 60, 1.25f, 1.0f, 0.75f),

    /** A shadow lengthening: enemies keep pace with you rather than falling behind, and champions
     * start appearing among them. The intended default for a server that wants the system on. */
    GATHERING_DARK(true, 120, 110, 25, 30, 20, 60, ScalingCurve.EXACT_PACE, 100, 1.5f, 1.25f, 0.9f),

    /** Open war: most fights bring a champion or an escort, and blows land hard enough to be feared.
     * The world now outgrows you rather than merely matching you. */
    OPEN_WAR(true, 150, 175, 40, 50, 25, 100, ScalingCurve.LONG_DEFEAT, 100, 2.0f, 2.0f, 1.0f),

    /** The Black Tide: you are outmatched and travelling in company is no longer optional. */
    THE_BLACK_TIDE(true, 250, 300, 65, 80, 40, 150, ScalingCurve.LONG_DEFEAT, 100, 3.0f, 3.0f, 1.5f),

    /** Everything the dials will give. Not balanced, and not pretending to be. */
    WRATH_OF_SAURON(true, 400, 500, 100, 100, 60, 200, ScalingCurve.LONG_DEFEAT, 100, 5.0f, 4.0f, 2.0f),

    /** Hand-tuned: the preset system leaves the file alone. */
    CUSTOM(true, 0, 0, 0, 0, 0, 0, ScalingCurve.FEEL_STRONGER, 0, 1.0f, 1.0f, 1.0f);

    public final boolean enemyScaling;
    public final int maxHealthBonus;
    public final int maxDamageBonus;
    public final int eliteChance;
    public final int escortChance;
    public final int groupScalingPercent;
    public final int xpBonus;
    public final ScalingCurve curve;
    public final int adaptiveStrength;
    /** How far coasting may push the evidence multiplier up. See {@code ThreatMath#competenceMax}. */
    public final float maxCompetence;
    public final float dimensionMultiplierMiddleEarth;
    public final float dimensionMultiplierOverworld;

    Menace(boolean enemyScaling, int maxHealthBonus, int maxDamageBonus, int eliteChance,
           int escortChance, int groupScalingPercent, int xpBonus, ScalingCurve curve,
           int adaptiveStrength, float maxCompetence, float dimensionMultiplierMiddleEarth,
           float dimensionMultiplierOverworld) {
        this.enemyScaling = enemyScaling;
        this.maxHealthBonus = maxHealthBonus;
        this.maxDamageBonus = maxDamageBonus;
        this.eliteChance = eliteChance;
        this.escortChance = escortChance;
        this.groupScalingPercent = groupScalingPercent;
        this.xpBonus = xpBonus;
        this.curve = curve;
        this.adaptiveStrength = adaptiveStrength;
        this.maxCompetence = maxCompetence;
        this.dimensionMultiplierMiddleEarth = dimensionMultiplierMiddleEarth;
        this.dimensionMultiplierOverworld = dimensionMultiplierOverworld;
    }

    /** Writes this preset's values onto {@code config}. No-op for {@link #CUSTOM}. */
    public void applyTo(KindredsConfig config) {
        if (this == CUSTOM) {
            return;
        }
        config.enableEnemyScaling = enemyScaling;
        config.maxHealthBonus = maxHealthBonus;
        config.maxDamageBonus = maxDamageBonus;
        config.eliteChance = eliteChance;
        config.escortChance = escortChance;
        config.groupScalingPercent = groupScalingPercent;
        config.xpBonus = xpBonus;
        config.scalingCurve = curve;
        config.adaptiveStrength = adaptiveStrength;
        config.maxCompetence = maxCompetence;
        config.dimensionMultiplierMiddleEarth = dimensionMultiplierMiddleEarth;
        config.dimensionMultiplierOverworld = dimensionMultiplierOverworld;
    }
}
