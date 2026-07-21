package com.kindreds.config;

/**
 * How much of a player's threat becomes world difficulty (design spec §2.6). A named choice
 * rather than a raw exponent field: {@code FEEL_STRONGER} vs {@code LONG_DEFEAT} says something a
 * bare {@code 0.8} does not, matching {@link Difficulty}'s "pick a feel" philosophy.
 */
public enum ScalingCurve {
    /** The world grows slower than you. A wolf stops mattering; new things start to. Default. */
    FEEL_STRONGER(0.8f),
    /** Every fight stays about as dangerous as your first. */
    EXACT_PACE(1.0f),
    /** The world outgrows you. Grim, hardest on a solo player. */
    LONG_DEFEAT(1.2f);

    public final float exponent;

    ScalingCurve(float exponent) {
        this.exponent = exponent;
    }
}
