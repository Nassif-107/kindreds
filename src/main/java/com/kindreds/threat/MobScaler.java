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
    /** The bearing attributes - see {@link #applyBearing}. Distinct ids so each dial can be turned off
     * on its own without disturbing the others. */
    public static final Identifier SCALED_ARMOR_ID = Identifier.of(Kindreds.MOD_ID, "scaled/armor");
    public static final Identifier SCALED_TOUGHNESS_ID = Identifier.of(Kindreds.MOD_ID, "scaled/armor_toughness");
    public static final Identifier SCALED_KNOCKBACK_ID = Identifier.of(Kindreds.MOD_ID, "scaled/knockback_resistance");
    public static final Identifier SCALED_SPEED_ID = Identifier.of(Kindreds.MOD_ID, "scaled/movement_speed");
    public static final Identifier SCALED_FOLLOW_ID = Identifier.of(Kindreds.MOD_ID, "scaled/follow_range");

    // The ceilings on what a mob may actually arrive with live in ThreatMath - the class with no
    // Minecraft in it - so a test can prove no preset breaches them with no server running. See that
    // class for why bounding the dials alone is not enough.
    private static final double MAX_SCALED_ARMOR = ThreatMath.MAX_SCALED_ARMOR;
    private static final double MAX_SCALED_KNOCKBACK = ThreatMath.MAX_SCALED_KNOCKBACK;
    private static final double MAX_SCALED_SPEED = ThreatMath.MAX_SCALED_SPEED;
    private static final double MAX_SCALED_FOLLOW = ThreatMath.MAX_SCALED_FOLLOW;

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
            applyBearing(mob, scaledGroup);

            // Threaded through rather than re-fetched: mark is about to gain elite fields below, and
            // a second MobMark.of(mob) read here would lose that write the moment escort rolls
            // (Task 5) land between the two - see Task 3's review finding #1.
            if (!mark.escort() && Kindreds.CONFIG.eliteChance > 0
                    && world.getRandom().nextFloat() < (Kindreds.CONFIG.eliteChance / 100f) * scaledGroup) {
                MobMark promoted = EliteMobs.choose(MobDanger.family(mob), new java.util.Random(world.getRandom().nextLong()));
                mark = mark.withElite(promoted.eliteAbility(), promoted.eliteName());
                EliteMobs.dress(mob, mark);   // name + visibility + LIVE registration
            }
            // Natural-occurring spawns only (hard bound #2): SPAWNER/SPAWN_EGG/BREEDING/COMMAND and
            // reloads never roll. The base Middle-earth mod spawns its hostile NPCs (orcs, uruks,
            // brigands) with SpawnReason.JOCKEY rather than NATURAL, so they'd never get escorts under
            // a NATURAL-only gate even though they're exactly the enemies escorts are meant for -
            // JOCKEY is therefore accepted too. mark.escort() (hard bound #1, structural not a dial)
            // keeps an escort from ever escorting - no recursion is even possible via this guard.
            if (!mark.escort() && isNaturalOccurring(mark.spawnReason())
                    && Kindreds.CONFIG.escortChance > 0
                    && world.getRandom().nextFloat() < (Kindreds.CONFIG.escortChance / 100f) * scaledGroup) {
                spawnEscorts(mob, world);
            }
            MobMark.set(mob, mark.withScaled(true));
        });
    }

    /** Spawn reasons that count as a natural-occurring hostile worth escorting: vanilla's own
     * {@code NATURAL}, plus {@code JOCKEY} - the reason the base Middle-earth mod spawns its hostile
     * NPCs under. Every other reason (spawner, egg, breeding, command, reload) is excluded, same as
     * before. */
    static boolean isNaturalOccurring(String spawnReason) {
        return "NATURAL".equals(spawnReason) || "JOCKEY".equals(spawnReason);
    }

    /** How many escorts the dimension can absorb right now: 0 at or past 80% of the monster cap,
     * else up to 2. cap = capacity * spawningChunks / 289 (17x17 = SpawnHelper.CHUNK_AREA - that
     * constant and the cap check itself are package-private in vanilla, so the formula is
     * reproduced here; if a Minecraft update changes CHUNK_AREA this number silently drifts, which
     * is why the doctor asserts it against a sane range rather than trusting it forever). Public
     * (not package-private) specifically so {@code KindredsDoctor}, in another package, can recompute
     * {@code escortBudget(56, 70, 289)} itself as the 289 tripwire this comment describes. */
    public static int escortBudget(int currentMonsters, int capacity, int spawningChunks) {
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
            spawnEscortOf(leader.getType(), world, pos, leader);
        }
    }

    /** Binds the wildcard captured by {@code EntityType<?>.getType()} to a single type variable so
     * the consumer below type-checks without raw types. */
    private static <T extends Entity> void spawnEscortOf(EntityType<T> type, ServerWorld world, BlockPos pos, MobEntity leader) {
        // The 6-arg spawn runs initialize() (difficulty gear) and the consumer BEFORE adding, so the
        // escort flag is set - and the base-mod NPC identity is copied from the leader - before its
        // own ENTITY_LOAD fires. It scales, but never rolls escorts or promotion of its own (see the
        // guards above and Task 4's elite-roll guard). EscortNpcSupport is a no-op for vanilla
        // leaders and when the base mod is absent; for a base-mod NPC leader it stamps the escort with
        // the leader's exact faction/rank/gear so it can't come out a mismatched or blank NPC.
        Consumer<T> markAsEscort = escort -> {
            MobMark.set(escort, MobMark.of(escort).withEscort(true));
            EscortNpcSupport.copyNpcIdentity(escort, leader);
        };
        type.spawn(world, markAsEscort, pos, SpawnReason.NATURAL, false, false);
    }

    /**
     * The four attributes that change how a fight <em>goes</em>, rather than how long it takes.
     *
     * <h2>Why these exist at all</h2>
     * Max health was, for a long time, the only attribute this class touched. That makes for a
     * difficulty setting a player experiences entirely as duration: the same rotation, the same
     * shield, the same backpedal, more clicks. A mob with triple health is not a harder fight, it is
     * a longer one - and past a point that reads as tedium rather than danger, which is why the
     * health dial is deliberately the most conservative of the set.
     *
     * <p>Each of these changes a decision instead:
     * <ul>
     *   <li><b>Armour</b> makes chip damage worthless, so committing to real hits beats flailing.
     *       Toughness rides along at half the armour value, because armour alone is fully defeated by
     *       a big enough hit and the pair is what actually holds up.</li>
     *   <li><b>Knockback resistance</b> ends stun-locking - the single most reliable way to make any
     *       melee fight in this game free.</li>
     *   <li><b>Movement speed</b> ends disengaging, and difficulty you can walk away from is optional
     *       difficulty.</li>
     *   <li><b>Follow range</b> means they notice you sooner and further, so "slip past it" becomes a
     *       real decision rather than a default.</li>
     * </ul>
     *
     * <h2>Operations, and why they differ</h2>
     * Armour and follow range are {@code ADD_VALUE}: they are absolute quantities, and a percentage of
     * a zombie's zero armour is zero. Movement speed is {@code ADD_MULTIPLIED_BASE} so it stays
     * proportionate - a cave troll should not end up as fast as a warg because both got the same flat
     * grant. Knockback resistance is {@code ADD_VALUE} onto a 0..1 scale where 1 is immunity, and
     * vanilla clamps it there itself.
     *
     * <p>Every modifier is added under a fixed id after removing that same id, exactly as
     * {@link #applyHealth} does, so a re-apply replaces rather than stacks.
     */
    private static void applyBearing(MobEntity mob, float scaledGroup) {
        if (scaledGroup <= 0) {
            return;
        }
        var c = Kindreds.CONFIG;
        // Armour, and toughness at half of it.
        double armour = Math.min(MAX_SCALED_ARMOR, c.armorBonus * scaledGroup);
        addModifier(mob, EntityAttributes.ARMOR, SCALED_ARMOR_ID, armour,
                EntityAttributeModifier.Operation.ADD_VALUE);
        addModifier(mob, EntityAttributes.ARMOR_TOUGHNESS, SCALED_TOUGHNESS_ID, armour / 2.0,
                EntityAttributeModifier.Operation.ADD_VALUE);
        addModifier(mob, EntityAttributes.KNOCKBACK_RESISTANCE, SCALED_KNOCKBACK_ID,
                Math.min(MAX_SCALED_KNOCKBACK, (c.knockbackResistBonus / 100.0) * scaledGroup),
                EntityAttributeModifier.Operation.ADD_VALUE);
        addModifier(mob, EntityAttributes.MOVEMENT_SPEED, SCALED_SPEED_ID,
                Math.min(MAX_SCALED_SPEED, (c.mobSpeedBonus / 100.0) * scaledGroup),
                EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        addModifier(mob, EntityAttributes.FOLLOW_RANGE, SCALED_FOLLOW_ID,
                Math.min(MAX_SCALED_FOLLOW, c.followRangeBonus * scaledGroup),
                EntityAttributeModifier.Operation.ADD_VALUE);
    }

    /** Removes {@code id} then re-adds it at {@code amount}, skipping entirely at zero so a disabled
     * dial leaves no modifier behind at all. Silently skips an attribute the entity does not carry -
     * not every mob has every attribute, and that is not an error worth a log line per spawn. */
    private static void addModifier(MobEntity mob,
                                    net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
                                    Identifier id, double amount,
                                    EntityAttributeModifier.Operation operation) {
        EntityAttributeInstance instance = mob.getAttributeInstance(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(id);
        if (amount == 0) {
            return;
        }
        instance.addPersistentModifier(new EntityAttributeModifier(id, amount, operation));
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
