package com.kindreds.threat;

import com.kindreds.Kindreds;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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
 *       brief for this event does not restate it: {@link ThreatMath#foldHardship}'s coasting branch
 *       (hardship below target) rises by the flat {@code riseRate}, <em>not</em> weighted by
 *       attacker danger - that weighting only applies on the falling/struggling branch. Without this
 *       second check, a player who has taken no damage and then kills a chicken would read as
 *       maximally "coasting" and raise competence for free, which is exactly the "a hundred slow
 *       kills of a trivial mob" exploit spec §12 requires a regression test for. Gating the kill
 *       itself on scope is what makes a chicken punch produce no evidence at all rather than the
 *       best possible evidence.</li>
 * </ul>
 */
public final class ThreatEvidence {
    private ThreatEvidence() {
    }

    /** Damage taken from in-scope mobs since this player's last qualifying kill or death - the
     * running numerator of {@link #hardshipOf}. Cleared whenever a fight closes: a qualifying kill,
     * or any player death (see the {@code AFTER_DEATH} handler below for why death always clears
     * it, even a death this class does not treat as evidence). */
    private static final Map<UUID, Float> ACCUMULATED_DAMAGE = new ConcurrentHashMap<>();

    /** Registers the three listeners. Call once from {@link Kindreds#onInitialize()}. */
    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!scalingEnabled() || !(entity instanceof ServerPlayerEntity player)) {
                return;
            }
            if (!(source.getAttacker() instanceof LivingEntity attacker) || !MobDanger.isInScope(attacker, player)) {
                return; // never another player, never fall/lava/drowning - see the class javadoc
            }
            ACCUMULATED_DAMAGE.merge(player.getUuid(), damageTaken, Float::sum);
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
            float weight = ThreatMath.attackerWeight(MobDanger.of(killed),
                    MobDanger.expectedAt(ThreatService.threatOf(player)));
            ThreatTuning tuning = tuningFor();

            String family = MobDanger.family(killed);
            // A family never seen before starts from the player's overall record, not a neutral
            // 1.0 - the same default ThreatService#scaledAgainst reads when a family entry is
            // absent, so a family's very first fold does not look like a fresh start.
            float familyBefore = state.familyCompetence().getOrDefault(family, state.competence());

            state.setCompetence(ThreatMath.foldHardship(state.competence(), hardship, weight, tuning));
            state.familyCompetence().put(family, ThreatMath.foldHardship(familyBefore, hardship, weight, tuning));

            ThreatService.invalidate(player.getUuid());
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity player)) {
                return;
            }
            // The fight is over the moment the player dies, whatever killed them - clear the
            // accumulator unconditionally so a death to something out of scope (fall, lava, /kill)
            // does not let stale damage bleed into the next fight's hardship.
            ACCUMULATED_DAMAGE.remove(player.getUuid());
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

    /** The {@link ThreatTuning} every fold in this class uses: {@link ThreatTuning#DEFAULTS} with
     * its band narrowed by the server's {@code adaptiveStrength} setting. Only called after {@link
     * #scalingEnabled()} has confirmed {@link Kindreds#CONFIG} is non-null. */
    private static ThreatTuning tuningFor() {
        return ThreatTuning.withAdaptiveStrength(Kindreds.CONFIG.adaptiveStrength);
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
}
