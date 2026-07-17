package com.kindreds.config;

/**
 * Controls what happens to a player's Kindred progress/points when they die.
 */
public enum DeathPenalty {
    /** No penalty — points and progress are untouched. */
    KEEP,
    /** Lose only unspent (unallocated) points. */
    LOSE_UNSPENT,
    /** Lose a percentage of total progress (see {@link KindredsConfig#deathPercent}). */
    LOSE_PERCENT,
    /** Full wipe — represented by the sentinel {@link Integer#MAX_VALUE}. */
    HARDCORE
}
