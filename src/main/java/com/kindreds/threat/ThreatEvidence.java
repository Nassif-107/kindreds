package com.kindreds.threat;

import com.kindreds.Kindreds;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The evidence loop (design spec §2.3): listens to damage, kills and deaths, and folds them into a
 * player's {@link ThreatState#competence()} and per-family record. Knows nothing about the effects
 * that read competence back out (that is {@link ThreatService}'s job) - only how it is earned.
 *
 * <p>Damage is accumulated per player between kills/deaths rather than folded on every hit, because
 * hardship is a property of the <em>whole fight</em>, not any one blow (spec §2.3: {@code hardship =
 * damageTakenFromScopedMobs / highWaterMaxHealth}).
 *
 * <h2>What counts as a qualifying fight</h2>
 * A fight only produces evidence if the mob on the other end of it is {@link MobDanger#isInScope}.
 * This is deliberately checked twice, at two different moments, for two different reasons:
 * <ul>
 *   <li>{@code AFTER_DAMAGE} filters by the <b>attacker</b> - never another player, never fall, lava
 *       or drowning damage counts, or the exploit is a friend beating you up, or a leap off a cliff
 *       mid-fight (spec §2.3).</li>
 *   <li>{@code AFTER_KILLED_OTHER_ENTITY} filters by the <b>kill target</b> too, even though the
 *       brief for this event does not restate it. {@link ThreatMath#foldHardship} now weights
 *       <em>both</em> branches by attacker danger (the coasting rise included - the final review
 *       closed the provoked-trivial-kill farm by making the rise earn its weight the same way the
 *       fall does), so a chicken kill folds to almost nothing even when it slips in scope. The gate
 *       remains as defence-in-depth: it keeps out-of-scope kills from resetting the damage
 *       accumulator or touching the per-family table at all, so a chicken punch produces no
 *       evidence rather than merely negligible evidence - the "a hundred slow kills of a trivial
 *       mob" exploit spec §12 requires a regression test for.</li>
 * </ul>
 *
 * <h2>Time-to-kill</h2>
 * {@code AFTER_DAMAGE} also captures, per player, which in-scope mob they first struck and when
 * (see {@link #ENGAGEMENTS}) - the fight's start. {@code AFTER_KILLED_OTHER_ENTITY} closes the
 * loop: a kill of that same mob, fast enough relative to its danger (see {@link #isFastKill}),
 * folds through {@link ThreatMath#foldFastKill} the same way a coasting hardship fold does -
 * raise-only, and weighted by the same {@code dangerRatio}, so killing a trivial mob quickly
 * proves as little as killing it slowly (spec §2.3).
 */
public final class ThreatEvidence {
    private ThreatEvidence() {
    }

    /** Damage taken from in-scope mobs since this player's last qualifying kill or death - the
     * running numerator of {@link #hardshipOf}. Cleared whenever a fight closes: a qualifying kill,
     * or any player death (see the {@code AFTER_DEATH} handler below for why death always clears
     * it, even a death this class does not treat as evidence). */
    private static final Map<UUID, Float> ACCUMULATED_DAMAGE = new ConcurrentHashMap<>();

    /** One player's current fight, for the time-to-kill signal: which mob they first struck, and
     * when. First hit wins for a given mob - a re-hit on the same mob does not reset the clock, so
     * a fight that runs long cannot quietly restart its own timer. Cleared on kill (its purpose
     * served), on player death, and on disconnect - the same three moments {@link #ACCUMULATED_DAMAGE}
     * clears on - so a stale engagement can never bleed into an unrelated later fight. */
    private static final Map<UUID, Engagement> ENGAGEMENTS = new ConcurrentHashMap<>();

    /** A player's in-progress fight: the mob first struck, and the world tick that first hit landed. */
    private record Engagement(UUID mob, long firstHitTick) {
    }

    /** 8 seconds - the internal yardstick an at-level, full-weight kill is judged against. Not a
     * config value: exposing this would let a server tune the fast-kill bar independently of
     * {@link MobDanger#expectedAt}, which is the one place "at-level" is defined. */
    private static final long TTK_BASE_TICKS = 160;

    /** Registers the four listeners. Call once from {@link Kindreds#onInitialize()}. */
    public static void register() {
        // Mirrors ThreatService#register's own disconnect handler: without this, a player who logs
        // out mid-fight carries that accumulation into a fight days later, and the map itself grows
        // for the server's lifetime, since nothing else ever removes an entry from it.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ACCUMULATED_DAMAGE.remove(handler.player.getUuid());
            ENGAGEMENTS.remove(handler.player.getUuid());
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!scalingEnabled()) {
                return;
            }
            if (entity instanceof ServerPlayerEntity player) {
                if (!(source.getAttacker() instanceof LivingEntity attacker) || !MobDanger.isInScope(attacker, player)) {
                    return; // never another player, never fall/lava/drowning - see the class javadoc
                }
                ACCUMULATED_DAMAGE.merge(player.getUuid(), damageTaken, Float::sum);
                return;
            }
            // Attacker side: capture when a fight against a NEW in-scope mob started, for the
            // time-to-kill signal. First hit wins - a re-hit on the same mob (still the current
            // engagement) must not push the clock forward, or a fight that runs long would keep
            // resetting its own timer and could never be judged "slow".
            if (entity instanceof LivingEntity mob && source.getAttacker() instanceof ServerPlayerEntity player
                    && MobDanger.isInScope(mob, player)) {
                Engagement current = ENGAGEMENTS.get(player.getUuid());
                if (current == null || !current.mob().equals(mob.getUuid())) {
                    ENGAGEMENTS.put(player.getUuid(), new Engagement(mob.getUuid(), player.getWorld().getTime()));
                }
            }
        });

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killed) -> {
            if (!scalingEnabled() || !(entity instanceof ServerPlayerEntity player)
                    || !MobDanger.isInScope(killed, player)) {
                return; // only a kill of a mob in scope is a "qualifying fight" - see class javadoc
            }
            float accumulated = ACCUMULATED_DAMAGE.getOrDefault(player.getUuid(), 0f);
            ACCUMULATED_DAMAGE.remove(player.getUuid());

            KindredData data = KindredAttachment.get(player);
            ThreatState state = data.threat();
            // The mark rises at once, same as ThreatService#refresh: a fresh player's mark defaults
            // to 0, and this is the floor that keeps hardshipOf's denominator from ever being that
            // zero (see hardshipOf's javadoc).
            state.setMaxHealthMark(Math.max(state.maxHealthMark(),
                    (float) player.getAttributeValue(EntityAttributes.MAX_HEALTH)));

            float hardship = hardshipOf(accumulated, state.maxHealthMark());
            // dangerRatio doubles as both foldHardship's attackerWeight and the TTK signal's
            // weighting below - both are "how dangerous was this kill relative to what this
            // player's threat expects", the same quantity spec §2.3 defines once.
            float dangerRatio = ThreatMath.attackerWeight(MobDanger.of(killed),
                    MobDanger.expectedAt(ThreatService.threatOf(player)));
            ThreatTuning tuning = tuningFor();

            // Time-to-kill: evidence a fight started AND ended fast - see class javadoc and spec
            // §2.3. Checked, and the engagement cleared, before the hardship fold below so a kill
            // that qualifies folds both signals in the same pass.
            Engagement engagement = ENGAGEMENTS.remove(player.getUuid());
            if (engagement != null && engagement.mob().equals(killed.getUuid())) {
                long ttk = world.getTime() - engagement.firstHitTick();
                if (isFastKill(ttk, TTK_BASE_TICKS, dangerRatio)) {
                    state.setCompetence(ThreatMath.foldFastKill(state.competence(), dangerRatio, tuning));
                }
            }

            String family = MobDanger.family(killed);
            // A family never seen before starts from the player's overall record, not a neutral
            // 1.0 - the same default ThreatService#scaledAgainst reads when a family entry is
            // absent, so a family's very first fold does not look like a fresh start.
            float familyBefore = state.familyCompetence().getOrDefault(family, state.competence());

            state.setCompetence(ThreatMath.foldHardship(state.competence(), hardship, dangerRatio, tuning));
            state.familyCompetence().put(family, ThreatMath.foldHardship(familyBefore, hardship, dangerRatio, tuning));

            ThreatService.invalidate(player.getUuid());
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity player)) {
                return;
            }
            // The fight is over the moment the player dies, whatever killed them - clear the
            // accumulator unconditionally so a death to something out of scope (fall, lava, /kill)
            // does not let stale damage bleed into the next fight's hardship. The engagement clears
            // the same way, for the same reason - a death mid-fight must not let that fight's TTK
            // clock survive into whatever comes next.
            ACCUMULATED_DAMAGE.remove(player.getUuid());
            ENGAGEMENTS.remove(player.getUuid());
            if (!scalingEnabled() || !(source.getAttacker() instanceof LivingEntity killer)
                    || !MobDanger.isInScope(killer, player)) {
                return; // only a death to a mob in scope is evidence - see class javadoc
            }
            KindredData data = KindredAttachment.get(player);
            ThreatState state = data.threat();
            float weight = ThreatMath.attackerWeight(MobDanger.of(killer),
                    MobDanger.expectedAt(ThreatService.threatOf(player)));
            ThreatTuning tuning = tuningFor();

            // Mirrors the per-family fold in AFTER_KILLED_OTHER_ENTITY above, so a death is as much
            // evidence against the killer's family as a kill is for it - without this, per-family
            // competence could only ever rise, and dying to family X would never record "X is hard
            // for me" (spec §2.3 symmetry).
            String family = MobDanger.family(killer);
            float familyBefore = state.familyCompetence().getOrDefault(family, state.competence());

            state.setCompetence(ThreatMath.foldDeath(state.competence(), weight, tuning));
            state.familyCompetence().put(family, ThreatMath.foldDeath(familyBefore, weight, tuning));

            ThreatService.invalidate(player.getUuid());
        });
    }

    /** Whether scaling (and therefore this whole evidence loop) is on at all. Checked first in
     * every handler so a world with scaling off, or a config not yet loaded, accrues no evidence
     * and folds nothing - the same guard {@link ThreatService#scaledFor} applies to effects. */
    private static boolean scalingEnabled() {
        return Kindreds.CONFIG != null && Kindreds.CONFIG.enableEnemyScaling;
    }

    /** Delegates to {@link ThreatService#tuningFor()} - the one place {@link ThreatTuning} is built
     * from config, so this class's folds and {@code ThreatService#refresh}'s re-band can never
     * quietly drift apart on what "the current tuning" means. Only called after {@link
     * #scalingEnabled()} has confirmed {@link Kindreds#CONFIG} is non-null. */
    private static ThreatTuning tuningFor() {
        return ThreatService.tuningFor();
    }

    /**
     * The hardship signal for a closed fight: {@code accumulatedDamage} as a fraction of {@code
     * effectiveMaxHealth}. Pure and Minecraft-free so it is provable without a running game (see
     * {@code ThreatEvidenceTest}), even though the class around it is not.
     *
     * <p>{@code effectiveMaxHealth} is floored at {@code 1f} rather than trusted as-is. In practice
     * the caller above always passes {@code state.maxHealthMark()} <em>after</em> raising it to at
     * least the player's live max health, so it is never actually {@code 0} by the time it reaches
     * here - but this method has no way to enforce that from its own signature, and a {@code 0} (or
     * negative, from corrupted save data) denominator would otherwise produce {@code NaN} or {@code
     * Infinity}, which {@link ThreatMath#foldHardship} is not required to defend against and which
     * would poison competence on write. The floor is the second, independent guard - belt and
     * braces, not a substitute for the caller raising the mark.
     */
    static float hardshipOf(float accumulatedDamage, float effectiveMaxHealth) {
        return Math.max(0f, accumulatedDamage) / Math.max(1f, effectiveMaxHealth);
    }

    /**
     * The pure decision core of the time-to-kill signal (spec §2.3): was {@code ttkTicks} fast
     * enough, against a mob of this danger, to count as evidence of strength? {@code
     * dangerRatio} - {@link ThreatMath#attackerWeight}'s output - scales the {@code baseTicks}
     * yardstick down for a below-expected mob, so besting a trivial mob quickly proves as little
     * as besting it slowly: {@code expected} collapses toward {@code 0} as danger falls, so
     * {@code ttkTicks} (never negative) can only clear the bar for a mob genuinely near or above
     * what this player's threat expects. Provable without a running game (see {@code TtkTest}),
     * even though the engagement bookkeeping around it is not.
     */
    static boolean isFastKill(long ttkTicks, long baseTicks, float dangerRatio) {
        long expected = (long) (baseTicks * clamp01(dangerRatio));
        return ttkTicks < expected / 2;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
