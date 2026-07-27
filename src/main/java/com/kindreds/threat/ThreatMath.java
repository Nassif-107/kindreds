package com.kindreds.threat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * The five families {@code MobDanger.family(String)} ever returns other than {@code "other"} -
     * the fixed set spec §3a's per-family voice is ever allowed to speak about. Kept as its own list
     * here rather than referenced from {@code MobDanger} so this file stays what its own class
     * javadoc promises (no Minecraft types, no dependency on any other class in the package) -
     * {@code MobDanger.family} is the source of truth for what a mob's family string IS, this is
     * only the fixed set {@link #familyVoiceKeys} is ever asked to voice an opinion about, which
     * already duplicates the same five names the elite-name and family-voice lang tables both
     * hardcode.
     */
    private static final String[] NAMED_FAMILIES = {"trolls", "spiders", "wargs", "orc_kin", "undead"};

    /** How far a family's competence must sit from the player's global record before the world says
     * anything about it (spec §3a) - exactly {@code 0.1} says nothing, only strictly past it does. */
    private static final float VOICE_THRESHOLD = 0.1f;

    /** Absorbs float representation noise around {@link #VOICE_THRESHOLD}: both values on either side
     * of {@code diff > VOICE_THRESHOLD} are ordinary {@code float}s built by ordinary arithmetic
     * elsewhere (evidence folds, EWMA blends), so a value a caller genuinely intends as "exactly 0.1"
     * can land a few ULPs on either side of the literal - without this, "exactly 0.1 says nothing"
     * would depend on which direction that noise happened to fall, rather than the rule itself. */
    private static final float VOICE_EPSILON = 1e-4f;

    /** At most this many voice lines at once - a wall of text is not "a few words". */
    private static final int MAX_VOICE_LINES = 3;

    /**
     * The per-family voice lines for the Deeds page (spec §3a), as translation keys - never the raw
     * numbers behind them. At most {@value #MAX_VOICE_LINES}, strongest divergence first: a family
     * whose synced competence sits more than {@value #VOICE_THRESHOLD} above the player's global
     * record is "mastered", more than {@value #VOICE_THRESHOLD} below is "feared"; a family sitting
     * within that band, or with no evidence recorded for it at all ({@code familyCompetence} has no
     * entry), says nothing. Only the five {@link #NAMED_FAMILIES} are ever eligible - "other" never
     * voices an opinion.
     *
     * <p><b>Why this exists at all (the §3a/§7 reconciliation).</b> Spec §7 keeps the raw per-family
     * table server-side - it is never meant to ride the wire in full. Spec §3a nonetheless promises
     * the Deeds page speaks in that table's voice. The two are reconciled by derivation, not
     * exposure: this pure function is the ONE place that table is ever read to produce something
     * bounded enough to sync - see {@code ThreatState#copy()}, which calls this at the moment a
     * {@link ThreatState} is copied for the network snapshot, and {@code ThreatState#familyVoiceKeys}
     * for what actually rides {@code PACKET_CODEC}. The raw map itself is never part of that codec.
     *
     * <p>Package-private, no Minecraft types on its signature, so the selection (threshold, cap,
     * ordering) can be proved by unit test with no server running - see {@code ThreatMathTest}.
     */
    private record Divergence(String family, boolean mastered, float magnitude) {
    }

    static List<String> familyVoiceKeys(Map<String, Float> familyCompetence, float global) {
        List<Divergence> divergent = new ArrayList<>();
        for (String family : NAMED_FAMILIES) {
            Float value = familyCompetence.get(family);
            if (value == null) {
                continue; // no evidence recorded for this family: nothing to say
            }
            float diff = value - global;
            if (diff > VOICE_THRESHOLD + VOICE_EPSILON) {
                divergent.add(new Divergence(family, true, diff));
            } else if (diff < -VOICE_THRESHOLD - VOICE_EPSILON) {
                divergent.add(new Divergence(family, false, -diff));
            }
        }
        // Stable sort by magnitude descending: ties keep NAMED_FAMILIES order (the loop above's
        // insertion order), a deterministic tie-break rather than one that depends on HashMap
        // iteration order.
        divergent.sort((a, b) -> Float.compare(b.magnitude(), a.magnitude()));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < divergent.size() && i < MAX_VOICE_LINES; i++) {
            Divergence d = divergent.get(i);
            keys.add("kindreds.family." + (d.mastered() ? "mastered" : "feared") + "." + d.family());
        }
        return keys;
    }

    /**
     * The widest the evidence band may ever be. Not a setting: this is the floor that stops threat
     * being farmed by deliberate dying (spec 2.4). A server may narrow the band through
     * {@link ThreatTuning}; {@link #bandFor} refuses to widen it past these.
     */
    public static final float COMPETENCE_MIN = 0.75f;

    /**
     * The ceiling on how far coasting may push difficulty up.
     *
     * <p>Raised from {@code 1.25} once it stopped being a ceiling and started being a wall: on a live
     * server all three players sat pinned at exactly 1.25, with their {@code orc_kin} and
     * {@code spiders} family records pinned there too. The loop had correctly concluded they were
     * coasting through everything and had no way left to say so - which is a control system with its
     * output saturated, i.e. no longer a control system.
     *
     * <p>The two limits are deliberately asymmetric now, because they defend different things.
     * {@link #COMPETENCE_MIN} is the anti-farm floor: it stops a player dying on purpose to make the
     * world soft, so it stays where it was. This one only governs how hard the world may become for
     * someone who keeps winning, and nothing is exploitable about that direction - the only way to
     * reach it is to genuinely stop being threatened. It is also self-correcting rather than a
     * one-way ratchet: the same evidence loop walks it back down the moment fights start costing
     * something.
     */
    public static final float COMPETENCE_MAX_DEFAULT = 2.0f;

    /**
     * The furthest a server may ever push {@link #competenceMax()}.
     *
     * <p>Not a difficulty cap - it is a sanity bound on a number that multiplies into every
     * difficulty decision the mod makes, so a typo'd config field produces a very hard world rather
     * than a nonsensical one. Anything a difficulty preset would plausibly want sits far below it.
     */
    public static final float COMPETENCE_MAX_LIMIT = 10.0f;

    /**
     * Where the configured ceiling is read from.
     *
     * <p>A supplier rather than a field this class caches, because the ceiling is a live server
     * setting an operator can change from the rules screen mid-session, and every cached copy is a
     * chance to serve a stale one. {@code ThreatService} installs the real source at mod init; the
     * default keeps this class usable - and its unit tests meaningful - with no Minecraft loaded,
     * which is the whole point of {@link ThreatMath} being what it is.
     */
    private static volatile java.util.function.DoubleSupplier competenceMaxSource =
            () -> COMPETENCE_MAX_DEFAULT;

    /** Installs the live source. Called once from {@code Kindreds#onInitialize}. */
    public static void competenceMaxSource(java.util.function.DoubleSupplier source) {
        competenceMaxSource = source == null ? () -> COMPETENCE_MAX_DEFAULT : source;
    }

    /**
     * The ceiling on how far coasting may push difficulty up, clamped to
     * {@code [1.0, COMPETENCE_MAX_LIMIT]}.
     *
     * <p>Was a hard constant at {@code 1.25} until a live server found the wall: all three players sat
     * pinned at exactly that value, with their per-family records pinned there too. The loop had
     * correctly concluded they were coasting through everything and had no way left to say so, which
     * is a control system with its output saturated - no longer a control system.
     *
     * <p>It is a setting rather than a bigger constant because how hard a world may become is a
     * server's decision, and there is nothing to defend in this direction: the only route to the
     * ceiling is to genuinely stop being threatened, and the same evidence loop walks it straight
     * back down as soon as fights start costing something. {@link #COMPETENCE_MIN} stays a hard
     * constant precisely because the <em>downward</em> direction is the exploitable one - a player
     * dying on purpose to make the world soft - and that floor is not negotiable.
     */
    public static float competenceMax() {
        double configured = competenceMaxSource.getAsDouble();
        if (Double.isNaN(configured)) {
            return COMPETENCE_MAX_DEFAULT;
        }
        return (float) Math.max(1.0, Math.min(COMPETENCE_MAX_LIMIT, configured));
    }

    /**
     * How far {@link #adaptiveBand} may open downward at {@code adaptiveStrength = 100}.
     *
     * <p>The two directions are deliberately asymmetric. This one stays at its original quarter, so
     * the anti-farm floor is reached at full strength exactly as it always was. The upward span runs
     * the full distance to {@link #competenceMax()} instead - keeping them equal would have meant
     * raising the ceiling changed nothing at all, since this band, not {@link #bandFor}'s clamp, is
     * what actually binds at any normal {@code adaptiveStrength}.
     */
    private static final float ADAPTIVE_SPAN_DOWN = 0.25f;

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
        float ceiling = competenceMax();
        float safeMin = Float.isNaN(min) ? COMPETENCE_MIN : min;
        float safeMax = Float.isNaN(max) ? ceiling : max;
        return new float[]{
                Math.max(COMPETENCE_MIN, Math.min(1.0f, safeMin)),
                Math.min(ceiling, Math.max(1.0f, safeMax))};
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
     * must never widen the band past {@link #COMPETENCE_MIN}/{@link #competenceMax()}), because this
     * is the anti-farm floor and it must hold even if one of the two guards is later "simplified"
     * away. See {@code ThreatMathTest#adaptiveStrengthNeverWidensPastTheFloorEvenWithoutItsOwnClamp}
     * for the proof that {@link #bandFor}'s clamp alone is sufficient.
     */
    public static float[] adaptiveBand(int adaptiveStrength) {
        float s = Math.max(0f, Math.min(1f, adaptiveStrength / 100f));
        return bandFor(1f - ADAPTIVE_SPAN_DOWN * s, 1f + (competenceMax() - 1f) * s);
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
     *
     * <p>The rise branch is <em>also</em> weighted by {@code killShare}: {@code attackerWeight} says
     * how dangerous the kill was, but says nothing about who actually fought it. Without this, a
     * kill-steal exploit stays open even after the {@code attackerWeight} fix above - a friend
     * whittles a dangerous mob to 1 HP over minutes, a third player tags it and lands the last hit
     * within ticks, and collects the same full, danger-weighted rise as if they had fought the whole
     * thing themselves. {@code killShare} is the killer's own fraction of the kill (their damage
     * dealt over the mob's max health, clamped 0..1 by the caller - see {@code ThreatEvidence}); a 1%
     * share earns roughly 1% of the rise a full share would (see
     * {@code ThreatMathTest#killShareScalesTheRiseAndOnlyTheRise}).
     *
     * <p><b>The fall (struggling) branch is deliberately left untouched by {@code killShare}.</b>
     * Taking a mauling is honest evidence of how hard a fight was regardless of who happens to land
     * the finishing blow - a player who tanks 90% of a fight's damage and lets a friend finish it off
     * still genuinely struggled, and gating the fall by kill-share would let that same friend-assist
     * pattern be used to blunt legitimate softening (deliberately let someone else finish every kill
     * to keep the fall from ever landing at full weight). Hardship the player actually took is not
     * staged the way a kill credit is, so it does not need the same defence.
     */
    public static float foldHardship(float competence, float hardship, float attackerWeight, float killShare,
                                      ThreatTuning t) {
        hardship = clamp01(hardship);
        float error = t.hardshipTarget() - hardship;           // positive = coasting
        float weight = clamp01(attackerWeight);
        float alpha;
        float normalized;
        if (error >= 0) {
            alpha = t.riseRate() * weight * clamp01(killShare);
            normalized = error / t.hardshipTarget();
        } else {
            alpha = t.fallRate() * weight;                     // NOT scaled by killShare - see above
            normalized = error / (1f - t.hardshipTarget());
        }
        return band(competence + alpha * normalized * 0.25f, t);
    }

    /** A fast kill is evidence of strength - raise-only (a slow kill proves nothing, it can be
     * staged), and weighted by how dangerous the victim actually was: one-shotting a provoked hen
     * proves as little as taking five minutes over it. Unlike {@link #foldHardship}, {@code
     * killShare} is not a separate parameter here - a caller who also wants to weight by the
     * killer's own share of the kill folds it into {@code attackerWeight} itself (see {@code
     * ThreatEvidence}, which passes {@code clamp01(dangerRatio * killShare)}); the shape stays
     * 3-arg because this method has never had any use for more than one combined weight. */
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
