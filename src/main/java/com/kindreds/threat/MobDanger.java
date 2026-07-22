package com.kindreds.threat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * How dangerous a mob is on its own terms, and which family it belongs to.
 *
 * <p>Danger is deliberately crude - health times damage - because it is only ever used as a
 * <em>ratio</em> against what a player of a given threat should be handling. It never needs to be
 * accurate, only ordered: a cave troll must outrank a chicken.
 */
public final class MobDanger {
    private MobDanger() {
    }

    /** Danger of a mob at its base, unscaled values. */
    public static double of(LivingEntity entity) {
        double health = entity.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
        double damage = entity.getAttributes().hasAttribute(EntityAttributes.ATTACK_DAMAGE)
                ? entity.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE) : 1.0;
        return Math.max(1.0, health * Math.max(1.0, damage));
    }

    /** What a player at {@code threat} should be able to handle - the yardstick danger is judged by. */
    public static double expectedAt(float threat) {
        // a zombie is 20 x 3 = 60; a cave troll is far above. Linear from a zombie to roughly ten of
        // them across the whole threat range, which is enough to order "trivial" against "serious".
        return 60.0 + 540.0 * (Math.max(0f, Math.min(100f, threat)) / 100f);
    }

    /**
     * The family a mob belongs to, for per-family competence. Resolved from the entity id rather than
     * from tags in phase 1: the tag files land with the replacement ladder in phase 3, and a string
     * here keeps this phase from depending on data that does not exist yet.
     */
    public static String family(LivingEntity entity) {
        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        return family(id.getPath());
    }

    /**
     * The id-path string-matching core of {@link #family(LivingEntity)}, split out so the classifier
     * can be proved by unit test with no Minecraft on the classpath - an entity's registry id is not
     * obtainable without a bootstrapped game, so the public overload above is a thin resolve-then-
     * delegate wrapper and this is where the actual logic (and its test coverage) lives.
     */
    static String family(String path) {
        if (path.contains("troll") || path.equals("giant")) {
            return "trolls";
        }
        if (path.contains("spider") || path.contains("shelob")) {
            return "spiders";
        }
        if (path.contains("warg") || path.equals("wolf")) {
            return "wargs";
        }
        if (path.contains("orc") || path.contains("uruk") || path.contains("goblin")
                || path.contains("snaga") || path.equals("npc")) {
            return "orc_kin";
        }
        if (path.contains("zombie") || path.contains("skeleton") || path.equals("husk")
                || path.equals("drowned") || path.contains("wither")) {
            return "undead";
        }
        return "other";
    }

    /**
     * Whether this mod scales, and takes evidence from, this entity at all against {@code player}.
     *
     * <p>Scope is vanilla hostiles, plus anything actively targeting {@code player} (except a
     * player-owned pet), plus the base Middle-earth mod's wargs and trolls, plus its NPCs when their
     * faction is currently hostile toward {@code player}. The base mod ships its entire army as one
     * {@code PassiveEntity}-derived {@code NpcEntity} class - covering both hostile factions (mordor,
     * isengard, moria...) and friendly ones (gondor, rohan, shire...) - and its wargs/trolls as a
     * horse-lineage {@code AbstractBeastEntity}, so neither is a vanilla {@link Monster} and both
     * would otherwise be invisible here. The faction check keeps a friendly NPC out of scope even when
     * it retaliates against the player that struck it first ({@code RevengeGoal}, which every NPC has
     * with no faction check of its own) - see {@link MiddleEarthFoes} for the lookup.
     *
     * <p><b>Anything actively fighting {@code player} is in scope, faction aside.</b> A friendly mob
     * that retaliates because the player struck it first must still fight - and be fought - at proper
     * strength: it scales, it counts as evidence, and killing it pays credit. {@code mob.getTarget()
     * == player} is what tests this: {@code RevengeGoal} and its kin set the target while a mob is
     * actively engaging, so this one clause covers provoked friendly NPCs/guards, the base mod's
     * retaliating beasts, and provoked vanilla neutrals (iron golems, wolves) alike. An unprovoked,
     * murdered villager still targets nobody and still pays nothing. A player-owned pet is excluded
     * even while targeting {@code player} - see {@link #isOwnedPet} for why.
     *
     * <p><b>Known caveat.</b> On the kill-credit path, the victim's target may already be cleared by
     * the time death is processed, in which case a provoked-friendly kill just pays nothing; the
     * damage and death-evidence paths are unaffected, since the target is set for as long as the mob
     * is actively attacking.
     */
    public static boolean isInScope(Entity entity, ServerPlayerEntity player) {
        return entity instanceof Monster
                || (entity instanceof MobEntity mob && mob.getTarget() == player && !isOwnedPet(entity))
                || MiddleEarthFoes.isHostileBaseMob(entity, player);
    }

    /**
     * A tamed/owned creature is excluded from the "actively targeting" scope rule even while it is
     * targeting {@code player} - a sicced pet is the player attacking by proxy, and player-sourced
     * evidence must never count, the same reason the evidence loop excludes players themselves.
     *
     * <p>Checked via {@link Tameable#getOwnerReference()} rather than {@link Tameable#getOwner()}:
     * the reference records that an owner was ever set, while resolving it to a live entity can
     * legitimately fail (owner offline, chunk unloaded) without the pet having become ownerless - and
     * a momentarily-unresolvable owner must not fall through to counting as scoped.
     */
    private static boolean isOwnedPet(Entity entity) {
        return entity instanceof Tameable tameable && tameable.getOwnerReference() != null;
    }
}
