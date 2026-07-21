package com.kindreds.threat;

/**
 * Every formula behind enemy scaling, and nothing else - no Minecraft types, no state, no side
 * effects. Kept pure so the rules can be proved by unit test rather than argued about, which matters
 * more here than anywhere else in the mod: this is a control loop, and a control loop that is wrong
 * is wrong quietly.
 *
 * <p>See {@code docs/superpowers/specs/2026-07-21-enemy-scaling-design.md} §2 for why each rule is
 * shaped the way it is. Several are exploit fixes and must not be "simplified" away.
 */
public final class ThreatMath {
    private ThreatMath() {
    }

    /**
     * The widest the evidence band may ever be. Not a setting: this is the floor that stops threat
     * being farmed by deliberate dying (spec 2.4). A server may narrow the band through
     * {@link ThreatTuning}; {@link #bandFor} refuses to widen it past these.
     */
    public static final float COMPETENCE_MIN = 0.75f;
    public static final float COMPETENCE_MAX = 1.25f;
    private static final long TICKS_PER_HOUR = 72000L;

    /**
     * The band a server has asked for, clamped to what the floor allows. Clamped here rather than at
     * the call site deliberately: this must hold however a caller was configured or misconfigured.
     */
    public static float[] bandFor(float min, float max) {
        return new float[]{
                Math.max(COMPETENCE_MIN, Math.min(1.0f, min)),
                Math.min(COMPETENCE_MAX, Math.max(1.0f, max))};
    }

    /** Declared power, 0..100, as the weighted blend of its three terms. */
    public static float prior(float commitment, float gear, float renown, int wc, int wg, int wr) {
        int total = wc + wg + wr;
        if (total <= 0) {
            return 0f;
        }
        float blend = (wc * clamp01(commitment) + wg * clamp01(gear) + wr * clamp01(renown)) / total;
        return blend * 100f;
    }

    /**
     * The high-water mark after {@code playedTicks} of play: it rises to {@code current} at once and
     * falls toward it at no more than {@code perHour}.
     *
     * <p>Played ticks, never in-game days: a bed skips a day in about three seconds, so a day-based
     * decay would be melted by sleep-spam - the exploit the mark exists to prevent.
     */
    public static float decayed(float mark, float current, float perHour, long playedTicks) {
        if (current >= mark) {
            return current;
        }
        float allowance = perHour * (playedTicks / (float) TICKS_PER_HOUR);
        return Math.max(current, mark - allowance);
    }

    /** How much a lowering signal counts, given how dangerous the thing that hurt you actually was. */
    public static float attackerWeight(double attackerDanger, double expectedDanger) {
        if (expectedDanger <= 0) {
            return 1f;
        }
        return (float) Math.max(0.0, Math.min(1.0, attackerDanger / expectedDanger));
    }

    /**
     * Folds one fight's hardship into competence. Rises when coasting, falls when struggling.
     *
     * <p>The error is normalized against the side of the target it falls on -
     * {@code hardshipTarget} above it, {@code 1 - hardshipTarget} below - rather than a single
     * shared denominator. Hardship's range (0..1) is not centred on the target, so a shared
     * denominator would let the struggling side swing further than the coasting side for the same
     * rate, quietly breaking the "rises faster than it falls" guarantee this method is meant to
     * uphold (see {@code ThreatMathTest#hardshipRisesCompetenceWhenCoastingAndLowersItWhenStruggling}).
     */
    public static float foldHardship(float competence, float hardship, float attackerWeight, ThreatTuning t) {
        float error = t.hardshipTarget() - hardship;           // positive = coasting
        float alpha;
        float normalized;
        if (error >= 0) {
            alpha = t.riseRate();
            normalized = error / t.hardshipTarget();
        } else {
            alpha = t.fallRate() * clamp01(attackerWeight);
            normalized = error / (1f - t.hardshipTarget());
        }
        return band(competence + alpha * normalized * 0.25f, t);
    }

    /** A fast kill is evidence of strength. Raise-only: a slow kill proves nothing, it can be staged. */
    public static float foldFastKill(float competence, ThreatTuning t) {
        return band(competence + t.riseRate() * 0.05f, t);
    }

    /** A death, weighted by how dangerous the killer was relative to what the player should handle. */
    public static float foldDeath(float competence, float killerWeight, ThreatTuning t) {
        return band(competence - t.deathPenalty() * clamp01(killerWeight), t);
    }

    /** Half the player's overall record, half their record against this particular family. */
    public static float effectiveCompetence(float global, float family) {
        return band(0.5f * global + 0.5f * family, ThreatTuning.DEFAULTS);
    }

    public static float threat(float prior, float competence) {
        return Math.max(0f, Math.min(100f, prior * band(competence, ThreatTuning.DEFAULTS)));
    }

    /** How much of a player's threat becomes world difficulty, 0..1. The curve is a server setting. */
    public static float scaled(float threat, float exponent) {
        return (float) Math.pow(Math.max(0f, Math.min(100f, threat)) / 100f, exponent);
    }

    /** Shared effects lean on the strongest player present, plus a bump per extra body. */
    public static float group(float strongestScaled, int players, float perPlayer, float cap) {
        float bonus = Math.min(cap, Math.max(0, players - 1) * perPlayer);
        return strongestScaled * (1f + bonus);
    }

    private static float band(float competence, ThreatTuning t) {
        float[] limits = bandFor(t.bandMin(), t.bandMax());
        return Math.max(limits[0], Math.min(limits[1], competence));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
