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
     *
     * <p>A NaN argument is coerced to the hard limit on its side rather than allowed through: NaN
     * poisons every comparison it touches, so {@code Math.max}/{@code Math.min} would otherwise hand
     * back a {@code [NaN, NaN]} band - the floor silently gone rather than merely wrong.
     */
    public static float[] bandFor(float min, float max) {
        float safeMin = Float.isNaN(min) ? COMPETENCE_MIN : min;
        float safeMax = Float.isNaN(max) ? COMPETENCE_MAX : max;
        return new float[]{
                Math.max(COMPETENCE_MIN, Math.min(1.0f, safeMin)),
                Math.min(COMPETENCE_MAX, Math.max(1.0f, safeMax))};
    }

    /**
     * The band {@link #bandFor} should be narrowed to for a given {@code adaptiveStrength} setting
     * (0..100 by convention; see {@code KindredsConfig#adaptiveStrength}): {@code 100} keeps the
     * full evidence band, {@code 0} collapses it to exactly {@code 1.0..1.0} (no adaptation at
     * all - the prior alone decides threat), and values between narrow linearly.
     *
     * <p>{@code adaptiveStrength} is deliberately clamped to {@code 0..1} here (via {@code s})
     * <em>and</em> the resulting band is passed through {@link #bandFor}, which clamps again - two
     * independent guards against the same invariant (a misconfigured {@code adaptiveStrength > 100}
     * must never widen the band past {@link #COMPETENCE_MIN}/{@link #COMPETENCE_MAX}), because this
     * is the anti-farm floor and it must hold even if one of the two guards is later "simplified"
     * away. See {@code ThreatMathTest#adaptiveStrengthNeverWidensPastTheFloorEvenWithoutItsOwnClamp}
     * for the proof that {@link #bandFor}'s clamp alone is sufficient.
     */
    public static float[] adaptiveBand(int adaptiveStrength) {
        float s = Math.max(0f, Math.min(1f, adaptiveStrength / 100f));
        return bandFor(1f - 0.25f * s, 1f + 0.25f * s);
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
        long safeTicks = Math.max(0L, playedTicks);
        // priorDecayPerHour is an exposed, unvalidated-at-the-formula-level setting (KindredsCommand
        // rejects a negative one, but this must hold even if that guard is ever bypassed): a negative
        // perHour would make allowance negative, and mark - allowance would then RAISE the mark on
        // every refresh - decay working in reverse, compounding forever. Clamped to >= 0 so the worst
        // a bad value can do is stop decay entirely, never invert it.
        float safePerHour = Math.max(0f, perHour);
        float allowance = safePerHour * (safeTicks / (float) TICKS_PER_HOUR);
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
     *
     * <p>{@code hardship} is clamped to 0..1 before anything else: it will later be computed as a
     * division elsewhere in the mod and is not guaranteed bounded by its caller, and the fall branch's
     * {@code 1 - hardshipTarget} denominator only stays correct while hardship does not exceed 1 - past
     * that the fall grows without limit, which is exactly the "tank a fight forever and never die"
     * exploit this method exists to prevent.
     *
     * <p>The rise (coasting) branch is weighted by {@code attackerWeight} exactly like the fall
     * branch already was - spec §2.3 says kills are weighted by the target's base danger ("deleting
     * a cave troll counts and deleting a chicken does not"), but the rise branch never actually
     * applied it. Left unweighted, the retaliation rule's widened scope (a provoked bee/wolf/friendly
     * guard is now in scope) reopened trivial-kill coasting credit: provoke something harmless, kill
     * it untouched, collect a full unweighted rise every time (see {@code ThreatExploitTest}). The
     * asymmetry (rise EWMA α = 0.10 vs fall α = 0.04) survives, since both are scaled by the same
     * weight at equal attacker danger.
     */
    public static float foldHardship(float competence, float hardship, float attackerWeight, ThreatTuning t) {
        hardship = clamp01(hardship);
        float error = t.hardshipTarget() - hardship;           // positive = coasting
        float weight = clamp01(attackerWeight);
        float alpha;
        float normalized;
        if (error >= 0) {
            alpha = t.riseRate() * weight;
            normalized = error / t.hardshipTarget();
        } else {
            alpha = t.fallRate() * weight;
            normalized = error / (1f - t.hardshipTarget());
        }
        return band(competence + alpha * normalized * 0.25f, t);
    }

    /** A fast kill is evidence of strength - raise-only (a slow kill proves nothing, it can be
     * staged), and weighted by how dangerous the victim actually was: one-shotting a provoked hen
     * proves as little as taking five minutes over it. */
    public static float foldFastKill(float competence, float attackerWeight, ThreatTuning t) {
        return band(competence + t.riseRate() * 0.05f * clamp01(attackerWeight), t);
    }

    /** A death, weighted by how dangerous the killer was relative to what the player should handle. */
    public static float foldDeath(float competence, float killerWeight, ThreatTuning t) {
        return band(competence - t.deathPenalty() * clamp01(killerWeight), t);
    }

    /**
     * The competence actually applied to a fight: half the player's overall record, half their record
     * against this particular family.
     *
     * <p>The split is even because both halves matter for a different reason and neither can be let to
     * dominate. The global record is what stops a player who is simply good at the game from reading as
     * a novice; the family record is what lets the world notice a player who has specifically mastered
     * (or is specifically struggling against) one kind of threat. Weighting either above the other would
     * make the other nearly meaningless - a family record that only nudges the global one is not
     * tracking anything, and a global record drowned out by one family stops meaning "overall" at all.
     */
    public static float effectiveCompetence(float global, float family, ThreatTuning t) {
        return band(0.5f * global + 0.5f * family, t);
    }

    /** Delegates to {@link #effectiveCompetence(float, float, ThreatTuning)} with the default band. */
    public static float effectiveCompetence(float global, float family) {
        return effectiveCompetence(global, family, ThreatTuning.DEFAULTS);
    }

    /**
     * The number that actually drives world difficulty: declared power scaled by how well its owner has
     * been backing that declaration up.
     *
     * <p>{@code competence} is banded before it is applied, not merely clamped afterward, so a server's
     * narrowed band is honoured at the one place a caller would otherwise be tempted to skip it. The
     * result is defensively NaN-proofed on top of that: {@code competence} cannot come out of
     * {@link #band} as NaN, but {@code prior} is caller-supplied and not this method's to trust, and a
     * NaN threat would otherwise poison every difficulty decision downstream of it silently.
     */
    public static float threat(float prior, float competence, ThreatTuning t) {
        float safePrior = Float.isNaN(prior) ? 0f : prior;
        return Math.max(0f, Math.min(100f, safePrior * band(competence, t)));
    }

    /** Delegates to {@link #threat(float, float, ThreatTuning)} with the default band. */
    public static float threat(float prior, float competence) {
        return threat(prior, competence, ThreatTuning.DEFAULTS);
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

    /**
     * Clamps an already-stored competence value into {@code t}'s band right now - the exact clamp
     * {@link #band} applies at fold time, exposed publicly so a value can be re-clamped when the
     * tuning itself changes (an operator lowering {@code adaptiveStrength}), not only on that
     * family's next fold. See {@code ThreatService#refresh}, which writes the result back to
     * {@link ThreatState} so a narrowed band applies to stored competence immediately rather than
     * waiting on evidence that may never arrive.
     */
    public static float rebanded(float competence, ThreatTuning t) {
        return band(competence, t);
    }

    private static float band(float competence, ThreatTuning t) {
        if (Float.isNaN(competence)) {
            // Neither "farm the floor" nor "farm the ceiling" - NaN reads as untouched, not exploited.
            return 1.0f;
        }
        float[] limits = bandFor(t.bandMin(), t.bandMax());
        return Math.max(limits[0], Math.min(limits[1], competence));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
