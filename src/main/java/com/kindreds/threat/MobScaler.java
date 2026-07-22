package com.kindreds.threat;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.Identifier;

/**
 * The spawn-time half of the world's answer: a mob entering the world is weighed once against the
 * group's threat (never re-weighed - reloads are recognised by the {@link MobMark#scaled()} flag)
 * and arrives heavier, possibly promoted, possibly accompanied. Knows nothing about how threat is
 * derived; that is {@link ThreatService#scaledGroupAt}'s job.
 *
 * <p>{@code ENTITY_LOAD} fires for every entity including item frames and dropped items (spec §11),
 * so the early-outs come before anything that touches config values, registries or player lists.
 */
public final class MobScaler {
    private MobScaler() {
    }

    /** One fixed id + remove-then-add = idempotent across any code path that re-runs (the
     * AbilityApplier lesson: a persistent modifier re-added under a fresh id compounds every
     * chunk cycle). */
    public static final Identifier SCALED_HEALTH_ID = Identifier.of(Kindreds.MOD_ID, "scaled/max_health");

    /** Registers the {@code ENTITY_LOAD} handler that weighs, scales and (possibly) promotes every
     * mob entering the world. Call once from {@link Kindreds#onInitialize()}. */
    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof MobEntity mob)) {
                return;                                      // item frames, arrows, armour stands
            }
            if (Kindreds.CONFIG == null || !Kindreds.CONFIG.enableEnemyScaling) {
                return;
            }
            MobMark mark = MobMark.of(mob);
            if (mark.scaled()) {
                return;                                      // chunk reload - already weighed
            }
            if (mark.spawnReason().isEmpty()) {
                return;   // loaded from a pre-phase-2 save, or never initialize()d: never retro-scale
            }
            if (!MobDanger.isScalableAtSpawn(mob)) {
                return;
            }
            float scaledGroup = ThreatService.scaledGroupAt(world, mob.getBlockPos());
            applyHealth(mob, scaledGroup);

            // Threaded through rather than re-fetched: mark is about to gain elite fields below, and
            // a second MobMark.of(mob) read here would lose that write the moment escort rolls
            // (Task 5) land between the two - see Task 3's review finding #1.
            if (!mark.escort() && Kindreds.CONFIG.eliteChance > 0
                    && world.getRandom().nextFloat() < (Kindreds.CONFIG.eliteChance / 100f) * scaledGroup) {
                MobMark promoted = EliteMobs.choose(MobDanger.family(mob), new java.util.Random(world.getRandom().nextLong()));
                mark = mark.withElite(promoted.eliteAbility(), promoted.eliteName());
                EliteMobs.dress(mob, mark);   // name + visibility + LIVE registration
            }
            // Escort rolls are appended here by Task 5.
            MobMark.set(mob, mark.withScaled(true));
        });
    }

    /** Health x (1 + maxHealthBonus * scaledGroup), then top up: raising max health does not raise
     * current health, and a mob must not arrive looking pre-damaged (spec §11). */
    private static void applyHealth(MobEntity mob, float scaledGroup) {
        double bonus = (Kindreds.CONFIG.maxHealthBonus / 100.0) * scaledGroup;
        if (bonus <= 0) {
            return;
        }
        EntityAttributeInstance health = mob.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (health == null) {
            return;
        }
        float before = mob.getMaxHealth();
        health.removeModifier(SCALED_HEALTH_ID);
        health.addPersistentModifier(new EntityAttributeModifier(SCALED_HEALTH_ID, bonus,
                EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        mob.setHealth(mob.getHealth() + (mob.getMaxHealth() - before));
    }
}
