package com.kindreds.config;

/**
 * Difficulty presets. Each one sets the <b>pacing and risk</b> knobs together, so a server owner picks
 * a feel rather than hand-balancing six numbers.
 *
 * <p><b>Deliberately absent:</b> birth traits and racial curses. A Snaga is sun-weak because it is a
 * Snaga, not because you picked a difficulty - racial identity is the heart of this mod and is not a
 * difficulty lever. Those stay available as separate sandbox/accessibility switches
 * ({@code enableBirthTraits} / {@code enableCurses}).
 *
 * <p>{@link #CUSTOM} applies nothing, leaving every value exactly as written in the config file.
 */
public enum Difficulty {
    /** Story pace - twice the xp, no death cost, and no cap on how much of a tree you can master. */
    FIRESIDE(2.0, DeathPenalty.KEEP, 0.25, 0, 1, false),
    /** The default, and exactly what this mod shipped with before presets existed. */
    ROAD(1.0, DeathPenalty.KEEP, 0.25, 60, 1, false),
    /** Committed: slower, death costs your unspent points, real specialization pressure, tougher foes. */
    LONG_DEFEAT(0.7, DeathPenalty.LOSE_UNSPENT, 0.25, 45, 4, true),
    /** Harsh: a deep specialist's game - death burns a quarter of your progress. */
    DOOM(0.45, DeathPenalty.LOSE_PERCENT, 0.25, 30, 8, true),
    /** Hand-tuned: the preset system leaves the file alone. */
    CUSTOM(0, null, 0, 0, 0, false);

    public final double xpRate;
    public final DeathPenalty death;
    public final double deathPercent;
    /** Total points spendable across the whole tree; {@code 0} = unlimited. */
    public final int softCap;
    public final int respecCost;
    public final boolean enemyScaling;

    Difficulty(double xpRate, DeathPenalty death, double deathPercent, int softCap, int respecCost,
               boolean enemyScaling) {
        this.xpRate = xpRate;
        this.death = death;
        this.deathPercent = deathPercent;
        this.softCap = softCap;
        this.respecCost = respecCost;
        this.enemyScaling = enemyScaling;
    }

    /** Writes this preset's values onto {@code config}. No-op for {@link #CUSTOM}. */
    public void applyTo(KindredsConfig config) {
        if (this == CUSTOM) {
            return;
        }
        config.xpRateGlobal = xpRate;
        config.deathPenalty = death;
        config.deathPercent = deathPercent;
        config.pointSoftCap = softCap;
        config.respecCost = respecCost;
        config.enableEnemyScaling = enemyScaling;
    }
}
