package com.kindreds.threat;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.loot.context.LootWorldContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named elites: the point of phase 2 (spec §3). A promoted mob (rolled by {@link MobScaler}) carries
 * a translated name above its head, one of four abilities, and pays out extra loot on death.
 *
 * <p>Knows nothing about the promotion roll itself - {@link #choose} is the pure 1-of-4-ability,
 * 1-of-3-name-per-family core {@link MobScaler} calls after its own chance roll; {@link #dress}
 * applies the result to a live entity and starts ticking it.
 *
 * <h2>Ticking without scanning the world</h2>
 * {@link #LIVE} is populated from the same {@code ENTITY_LOAD} event {@link MobScaler} uses (any mob
 * whose stored {@link MobMark} is already elite - covering both a freshly promoted mob, via
 * {@link #dress}, and a reloaded one, whose name is re-applied here in case the custom name itself
 * did not survive the round trip), pruned on {@code ENTITY_UNLOAD} and whenever a tick finds a mob no
 * longer alive. The 40-tick cadence work rides {@code ServerTickEvents.END_SERVER_TICK}, the same
 * timer shape {@link ThreatService} uses for its own slow refresh.
 */
public final class EliteMobs {
    private EliteMobs() {
    }

    /** The four abilities, in the fixed order {@link #choose} indexes into - see the class javadoc
     * for why the pool must be exhaustively reachable (an unreachable entry is a dead ability). */
    private static final List<String> ABILITIES = List.of("aura", "rally", "swift", "bulwark");
    /** Three name keys per family (the brief's full 18-key lang table). */
    private static final int NAMES_PER_FAMILY = 3;

    private static final int CADENCE_TICKS = 40;
    private static final double AURA_RADIUS = 4.0;
    private static final int AURA_DURATION = 60;
    private static final double RALLY_RADIUS = 12.0;
    private static final int SWIFT_DURATION = 100;
    private static final int BULWARK_DURATION = CADENCE_TICKS + 1; // outlives the gap to the next tick

    private static final float BOUNTY_CHANCE = 0.15f;
    private static final TagKey<Item> ELITE_BOUNTY_TAG =
            TagKey.of(RegistryKeys.ITEM, Identifier.of(Kindreds.MOD_ID, "elite_bounty"));

    /** Every live elite, grouped by world, so the 40-tick cadence never has to scan the whole world
     * for the (rare) elites in it. See the class javadoc for how entries enter and leave this map. */
    private static final Map<ServerWorld, Set<MobEntity>> LIVE = new ConcurrentHashMap<>();
    private static int tickCounter;

    /** Registers the elite lifecycle (load/unload tracking, the tick cadence) and the two combat
     * hooks (ability triggers on hurt, loot bonus on death). Call once from
     * {@link Kindreds#onInitialize()}. Mirrors {@link ThreatEvidence#register}'s exact event
     * signatures for the {@code ServerLivingEntityEvents} registrations. */
    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof MobEntity mob)) {
                return;
            }
            MobMark mark = MobMark.of(mob);
            if (mark.elite()) {
                dress(mob, mark); // re-registers a reloaded elite and re-applies its name if lost
            }
        });

        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!(entity instanceof MobEntity mob)) {
                return;
            }
            untrack(world, mob);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % CADENCE_TICKS != 0) {
                return;
            }
            for (Map.Entry<ServerWorld, Set<MobEntity>> entry : LIVE.entrySet()) {
                ServerWorld world = entry.getKey();
                Set<MobEntity> elites = entry.getValue();
                elites.removeIf(mob -> !mob.isAlive());
                for (MobEntity mob : elites) {
                    cadence(world, mob);
                }
                if (elites.isEmpty()) {
                    LIVE.remove(world, elites);
                }
            }
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof MobEntity mob)) {
                return;
            }
            MobMark mark = MobMark.of(mob);
            if (!mark.elite()) {
                return;
            }
            if ("swift".equals(mark.eliteAbility())) {
                mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, SWIFT_DURATION, 0));
            }
            if ("rally".equals(mark.eliteAbility()) && source.getAttacker() instanceof PlayerEntity attacker
                    && mob.getWorld() instanceof ServerWorld world) {
                rally(world, mob, attacker);
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!MobMark.of(entity).elite() || !(entity.getWorld() instanceof ServerWorld world)) {
                return;
            }
            rerollLoot(world, entity, source);
            rollBounty(world, entity);
        });
    }

    /**
     * The pure selection core: picks 1-of-4 ability and 1-of-3 name key for {@code family}, returned
     * as a fresh {@link MobMark#DEFAULT} carrying only the elite fields - the caller ({@link
     * MobScaler}, via {@link #promote}) merges it into the mob's real, already-scaled mark. Provable
     * with no Minecraft on the classpath (see {@code EliteMobsTest}).
     */
    static MobMark choose(String family, Random random) {
        String ability = ABILITIES.get(random.nextInt(ABILITIES.size()));
        int slot = random.nextInt(NAMES_PER_FAMILY) + 1;
        String nameKey = "kindreds.elite.name." + family + "." + slot;
        return MobMark.DEFAULT.withElite(ability, nameKey);
    }

    /** {@link #choose}, resolving the mob's own family via {@link MobDanger#family(LivingEntity)}. */
    public static MobMark promote(MobEntity mob, Random random) {
        return choose(MobDanger.family(mob), random);
    }

    /** The ability id a mark carries, resolved to whether it is one of the four real abilities - used
     * by the doctor (a diagnostic command) to validate a mark rather than trust it blindly. */
    public static boolean abilityFor(String id) {
        return ABILITIES.contains(id);
    }

    /** Applies a promoted (or reloaded) mark to a live entity: the translated name, its visibility,
     * and registration into {@link #LIVE} so the tick cadence picks it up. Safe to call repeatedly -
     * {@code Set#add} is idempotent, and re-setting an already-correct name is harmless. */
    static void dress(MobEntity mob, MobMark mark) {
        if (!mark.elite()) {
            return;
        }
        mob.setCustomName(Text.translatable(mark.eliteName()));
        mob.setCustomNameVisible(true);
        if (mob.getWorld() instanceof ServerWorld world) {
            LIVE.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet()).add(mob);
        }
    }

    private static void untrack(ServerWorld world, MobEntity mob) {
        Set<MobEntity> elites = LIVE.get(world);
        if (elites == null) {
            return;
        }
        elites.remove(mob);
        if (elites.isEmpty()) {
            LIVE.remove(world, elites);
        }
    }

    /** The 40-tick cadence body for one live elite: {@code aura} chills nearby players, {@code
     * bulwark} keeps its wearer standing while above half health. {@code rally} and {@code swift}
     * are hurt-triggered instead (see the {@code AFTER_DAMAGE} handler in {@link #register}) - they
     * have nothing to do on a timer. */
    private static void cadence(ServerWorld world, MobEntity mob) {
        String ability = MobMark.of(mob).eliteAbility();
        if ("aura".equals(ability)) {
            aura(world, mob);
        } else if ("bulwark".equals(ability)) {
            bulwark(mob);
        }
    }

    /** Every player within {@link #AURA_RADIUS} blocks of the elite is slowed. */
    private static void aura(ServerWorld world, MobEntity mob) {
        Box box = mob.getBoundingBox().expand(AURA_RADIUS);
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || !box.contains(player.getPos())) {
                continue;
            }
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, AURA_DURATION, 0));
        }
    }

    /** While above half health, the elite stands hard to put down. Re-applied every cadence tick
     * rather than once, so an elite that drops below half loses the buff on schedule instead of
     * carrying a stale Resistance instance to zero. */
    private static void bulwark(MobEntity mob) {
        if (mob.getHealth() > mob.getMaxHealth() / 2f) {
            mob.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, BULWARK_DURATION, 0));
        }
    }

    /** Every {@link MobEntity} of the same {@link MobDanger#family} within {@link #RALLY_RADIUS}
     * blocks turns on the attacker - a champion does not fall alone. */
    private static void rally(ServerWorld world, MobEntity mob, PlayerEntity attacker) {
        String family = MobDanger.family(mob);
        Box box = mob.getBoundingBox().expand(RALLY_RADIUS);
        for (MobEntity ally : world.getEntitiesByClass(MobEntity.class, box,
                m -> m.isAlive() && family.equals(MobDanger.family(m)))) {
            ally.setTarget(attacker);
        }
    }

    /** Re-rolls the dead elite's own loot table once, exactly the 1.21.8 recipe verified against the
     * mapped jar (there is no {@code LivingEntity.getLootTable()}): the required {@code THIS_ENTITY},
     * {@code ORIGIN} and {@code DAMAGE_SOURCE} parameters, {@code ATTACKING_ENTITY} optional. An
     * entity with no loot table key (already empty, or none configured) skips silently. */
    private static void rerollLoot(ServerWorld world, LivingEntity entity, DamageSource damageSource) {
        Optional<RegistryKey<LootTable>> key = entity.getLootTableKey();
        if (key.isEmpty()) {
            return;
        }
        LootTable table = world.getServer().getReloadableRegistries().getLootTable(key.get());
        LootWorldContext ctx = new LootWorldContext.Builder(world)
                .add(LootContextParameters.THIS_ENTITY, entity)
                .add(LootContextParameters.ORIGIN, entity.getPos())
                .add(LootContextParameters.DAMAGE_SOURCE, damageSource)
                .addOptional(LootContextParameters.ATTACKING_ENTITY, damageSource.getAttacker())
                .build(LootContextTypes.ENTITY);
        table.generateLoot(ctx, entity.getLootTableSeed(), stack -> entity.dropStack(world, stack));
    }

    /** A 15%% chance for one random item from {@code kindreds:elite_bounty} - a datapack extension
     * point, empty or missing tag skipped silently. */
    private static void rollBounty(ServerWorld world, LivingEntity entity) {
        if (world.getRandom().nextFloat() >= BOUNTY_CHANCE) {
            return;
        }
        Optional<RegistryEntry<Item>> item = Registries.ITEM.getRandomEntry(ELITE_BOUNTY_TAG, world.getRandom());
        item.ifPresent(entry -> entity.dropStack(world, new ItemStack(entry.value())));
    }
}
