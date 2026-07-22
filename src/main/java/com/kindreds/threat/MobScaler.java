package com.kindreds.threat;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.SpawnHelper;

import java.util.function.Consumer;

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
            // Natural spawns only (hard bound #2): SPAWNER/SPAWN_EGG/BREEDING/COMMAND/reload never
            // roll. mark.escort() (hard bound #1, structural not a dial) keeps an escort from ever
            // escorting - no recursion is even possible via this guard.
            if (!mark.escort() && "NATURAL".equals(mark.spawnReason())
                    && Kindreds.CONFIG.escortChance > 0
                    && world.getRandom().nextFloat() < (Kindreds.CONFIG.escortChance / 100f) * scaledGroup) {
                spawnEscorts(mob, world);
            }
            MobMark.set(mob, mark.withScaled(true));
        });
    }

    /** How many escorts the dimension can absorb right now: 0 at or past 80% of the monster cap,
     * else up to 2. cap = capacity * spawningChunks / 289 (17x17 = SpawnHelper.CHUNK_AREA - that
     * constant and the cap check itself are package-private in vanilla, so the formula is
     * reproduced here; if a Minecraft update changes CHUNK_AREA this number silently drifts, which
     * is why the doctor asserts it against a sane range rather than trusting it forever). */
    static int escortBudget(int currentMonsters, int capacity, int spawningChunks) {
        int cap = capacity * spawningChunks / 289;
        return currentMonsters >= 0.8 * cap ? 0 : 2;
    }

    /** Hard bound #3 (suppressed at >= 80% of the monster cap) and #1 (at most 2, same species as
     * {@code leader}). Never called for an escort itself - the {@code mark.escort()} guard above
     * keeps this off the recursive path entirely. */
    private static void spawnEscorts(MobEntity leader, ServerWorld world) {
        SpawnHelper.Info info = world.getChunkManager().getSpawnInfo();
        if (info == null) {
            return;
        }
        int budget = escortBudget(info.getGroupToCount().getInt(SpawnGroup.MONSTER),
                SpawnGroup.MONSTER.getCapacity(), info.getSpawningChunkCount());
        int wanted = Math.min(budget, 1 + world.getRandom().nextInt(2));   // 1-2, budget-capped
        for (int i = 0; i < wanted; i++) {
            BlockPos pos = leader.getBlockPos().add(
                    world.getRandom().nextInt(5) - 2, 0, world.getRandom().nextInt(5) - 2);
            double x = pos.getX() + 0.5;
            double y = pos.getY();
            double z = pos.getZ() + 0.5;
            // Leader and escort share the type, so the leader's own hitbox is the escort's box too -
            // skip this position on a collision rather than searching for another one.
            if (!world.isSpaceEmpty(leader.getDimensions(leader.getPose()).getBoxAt(x, y, z))) {
                continue;
            }
            spawnEscortOf(leader.getType(), world, pos);
        }
    }

    /** Binds the wildcard captured by {@code EntityType<?>.getType()} to a single type variable so
     * the consumer below type-checks without raw types. */
    private static <T extends Entity> void spawnEscortOf(EntityType<T> type, ServerWorld world, BlockPos pos) {
        // The 6-arg spawn runs initialize() (difficulty gear) and the consumer BEFORE adding, so the
        // escort flag is set before its own ENTITY_LOAD fires - it scales, but never rolls escorts or
        // promotion of its own (see the guards above and Task 4's elite-roll guard).
        Consumer<T> markAsEscort = escort -> MobMark.set(escort, MobMark.of(escort).withEscort(true));
        type.spawn(world, markAsEscort, pos, SpawnReason.NATURAL, false, false);
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
