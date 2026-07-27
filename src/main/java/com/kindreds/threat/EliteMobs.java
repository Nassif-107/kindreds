package com.kindreds.threat;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
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
import net.minecraft.sound.SoundEvents;
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
 *
 * <p>The master switch, {@code enableEnemyScaling}, gates every gameplay effect here (cadence
 * abilities and death loot) exactly as its sibling systems gate theirs - but not the elite's name,
 * which is cosmetic and persists regardless.
 */
public final class EliteMobs {
    private EliteMobs() {
    }

    /**
     * The ability pool, in the fixed order {@link #choose} indexes into - see the class javadoc for
     * why it must be exhaustively reachable (an unreachable entry is a dead ability).
     *
     * <p>Grown from four to nine. At a 40% promotion rate four abilities repeat constantly, and two of
     * the original four ({@code aura}, {@code bulwark}) are passive stat effects the player never has
     * to respond to - so a champion read as an ordinary mob with a gold name more often than not. The
     * five added here all demand a decision instead:
     * <ul>
     *   <li>{@code breaker} - strikes through a raised shield and disables it. A shield blocks 100% of
     *       a melee hit, so before this, any damage number at all could be answered by holding right
     *       click. This is the single largest hole in the difficulty system, not a flourish.</li>
     *   <li>{@code venom} - poisons on hit, so a won fight can still cost you.</li>
     *   <li>{@code sunder} - strips armour for a while, making the next fight the real one.</li>
     *   <li>{@code hunter} - re-acquires you rather than losing interest, so running is a delay
     *       instead of an escape.</li>
     *   <li>{@code warcry} - on death, wakes and hastens its kin nearby: killing the champion first
     *       stops being free.</li>
     * </ul>
     */
    private static final List<String> ABILITIES = List.of(
            "aura", "rally", "swift", "bulwark", "breaker", "venom", "sunder", "hunter", "warcry");
    /** Three name keys per family (the brief's full 18-key lang table). */
    private static final int NAMES_PER_FAMILY = 3;

    private static final int CADENCE_TICKS = 40;
    private static final double AURA_RADIUS = 4.0;
    private static final int AURA_DURATION = 60;
    private static final double RALLY_RADIUS = 12.0;
    private static final int SWIFT_DURATION = 100;
    private static final int BULWARK_DURATION = CADENCE_TICKS + 1; // outlives the gap to the next tick

    /** How long a broken shield stays unusable - vanilla's own axe-disable window. */
    private static final int SHIELD_DISABLE_TICKS = 100;
    private static final int VENOM_DURATION = 100;
    private static final int SUNDER_DURATION = 200;
    /** Radius of a dying champion's last call, and how long its kin are quickened by it. */
    private static final double WARCRY_RADIUS = 16.0;
    private static final int WARCRY_DURATION = 200;

    /**
     * The three hoards, richest last. Datapack extension points, all three optional.
     *
     * <p>There was one tag, and it held {@code minecraft:diamond} - a single vanilla gem, dropped at a
     * flat 15% by champions of every stripe. Two things were wrong with that. It was the wrong world:
     * the base mod generates no diamond ore anywhere, so the only diamonds in Middle-earth were the
     * ones this mod minted out of nothing. And it was the wrong shape: a flat chance pays the same for
     * a champion that never threatened you as for one that nearly killed you, which teaches players to
     * seek out the safest elite they can find - the precise habit the threat system exists to unteach.
     */
    private static final TagKey<Item> BOUNTY_COMMON =
            TagKey.of(RegistryKeys.ITEM, Identifier.of(Kindreds.MOD_ID, "elite_bounty_common"));
    private static final TagKey<Item> BOUNTY_RARE =
            TagKey.of(RegistryKeys.ITEM, Identifier.of(Kindreds.MOD_ID, "elite_bounty_rare"));
    private static final TagKey<Item> BOUNTY_FABLED =
            TagKey.of(RegistryKeys.ITEM, Identifier.of(Kindreds.MOD_ID, "elite_bounty_fabled"));

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
                // Ungated: re-dressing (name/visibility) is cosmetic and LIVE registration is bookkeeping,
                // not an ability - keeping both unconditional lets a re-enabled switch revive abilities on
                // the next cadence tick without waiting for a reload.
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
            // Checked once before iterating LIVE rather than per mob, but the removeIf prune and the
            // empty-map cleanup below stay unconditional - LIVE bookkeeping is not a gameplay effect, and
            // a dead elite must still leave the map even while scaling is off.
            boolean scaling = scalingEnabled();
            for (Map.Entry<ServerWorld, Set<MobEntity>> entry : LIVE.entrySet()) {
                ServerWorld world = entry.getKey();
                Set<MobEntity> elites = entry.getValue();
                elites.removeIf(mob -> !mob.isAlive());
                if (scaling) {
                    for (MobEntity mob : elites) {
                        cadence(world, mob);
                    }
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
            if (!mark.elite() || !scalingEnabled()) {
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

        // The other direction: a champion landing a blow on a player. The handler above fires when an
        // elite is HURT, which is the wrong moment for any ability whose whole point is what it does
        // to you - and until these existed, every ability in the pool was either a passive stat effect
        // or a retarget, so a champion never actually did anything to the player at all.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayerEntity player) || !scalingEnabled()) {
                return;
            }
            if (!(source.getAttacker() instanceof MobEntity attacker)) {
                return;
            }
            String ability = MobMark.of(attacker).eliteAbility();
            switch (ability == null ? "" : ability) {
                case "breaker" -> breakShield(player, blocked);
                case "venom" -> player.addStatusEffect(
                        new StatusEffectInstance(StatusEffects.POISON, VENOM_DURATION, 0));
                // Weakness rather than a literal armour strip. Minecraft ships no armour-reduction
                // effect, so stripping armour means a per-player attribute modifier plus expiry
                // bookkeeping of our own - and the failure mode of that bookkeeping (the elite dies,
                // the world unloads, the tick that would have removed it never runs) is a permanent
                // debuff on a player's character. A self-expiring vanilla effect cannot leak, and
                // "your blows land softer for a while" is the same beat.
                case "sunder" -> player.addStatusEffect(
                        new StatusEffectInstance(StatusEffects.WEAKNESS, SUNDER_DURATION, 0));
                default -> {
                    // every other ability triggers elsewhere
                }
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!MobMark.of(entity).elite() || !(entity.getWorld() instanceof ServerWorld world)
                    || !scalingEnabled()) {
                return;
            }
            if ("warcry".equals(MobMark.of(entity).eliteAbility())) {
                warcry(world, entity, source.getAttacker());
            }
            rerollLoot(world, entity, source);
            rollBounty(world, entity, source);
        });
    }

    /** Whether scaling (and therefore every gameplay effect below - cadence abilities and death loot)
     * is on at all. Mirrors {@link ThreatEvidence#scalingEnabled()} exactly; the name/visibility dress
     * and the {@link #LIVE} bookkeeping do not call this - see their own call sites for why. */
    private static boolean scalingEnabled() {
        return Kindreds.CONFIG != null && Kindreds.CONFIG.enableEnemyScaling;
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

    /** The real elite ability pool, read-only - so the doctor can iterate the actual list instead of
     * a retyped copy that could silently drift from it. {@link #ABILITIES} is already a {@code
     * List.of(...)}, so this is the same immutable list, not a defensive copy. */
    public static List<String> abilityPool() {
        return ABILITIES;
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
        } else if ("hunter".equals(ability)) {
            hunt(world, mob);
        }
    }

    /**
     * A hunter re-acquires the nearest player rather than losing interest.
     *
     * <p>Vanilla mobs forget a target that breaks line of sight or outruns them for a few seconds,
     * which makes running away the universal answer to any fight that is going badly - and a
     * difficulty setting you can walk out of is an optional one. This only re-targets when the mob has
     * nothing to chase, so it never overrides a fight already in progress, and it reaches no further
     * than the mob's own follow range: it is persistence, not omniscience.
     */
    private static void hunt(ServerWorld world, MobEntity mob) {
        if (mob.getTarget() != null && mob.getTarget().isAlive()) {
            return;
        }
        double range = mob.getAttributeValue(EntityAttributes.FOLLOW_RANGE);
        // getClosestPlayer's simple overload does not filter by game mode, so the two checks are the
        // filter: hunting a spectator would have the mob stalking someone who cannot be seen or hurt.
        PlayerEntity nearest = world.getClosestPlayer(mob, range);
        if (nearest != null && !nearest.isSpectator() && !nearest.isCreative()) {
            mob.setTarget(nearest);
        }
    }

    /**
     * Strikes through a raised shield and puts it on cooldown.
     *
     * <p>The most important ability in the pool, because a raised shield blocks <b>100%</b> of a melee
     * hit: before this, every damage number the difficulty system could produce was answered by
     * holding right click, and no amount of {@code maxDamageBonus} changed that. Uses the same
     * hundred-tick lockout vanilla's own axe-disable applies, so it is a punishment for standing still
     * behind a shield rather than a removal of shields.
     *
     * <p>Triggers on {@code blocked}, and also on a hit taken while blocking - the two are not quite
     * the same (a hit from behind the shield's arc lands unblocked while the player is still holding
     * it) and a champion who only breaks shields it was already stopped by is a champion whose
     * ability depends on it having failed first.
     */
    private static void breakShield(ServerPlayerEntity player, boolean blocked) {
        if (!blocked && !player.isBlocking()) {
            return;
        }
        ItemStack active = player.getActiveItem();
        if (active.isEmpty()) {
            return;
        }
        player.getItemCooldownManager().set(active, SHIELD_DISABLE_TICKS);
        player.clearActiveItem();
        player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_SHIELD_BREAK, player.getSoundCategory(), 0.8f, 0.8f);
    }

    /**
     * A dying champion's last call: its kin nearby wake, turn on its killer, and are quickened.
     *
     * <p>Killing the leader first is the obvious play against any group, and it was strictly free -
     * the escorts simply carried on as they were. This makes focusing the champion a real choice with
     * a real cost, which is what an interesting decision is. Same family and same radius rules as
     * {@link #rally}, so it reads as the same kind of event rather than a second, differently-shaped
     * one.
     */
    private static void warcry(ServerWorld world, LivingEntity fallen, Entity killer) {
        Box box = fallen.getBoundingBox().expand(WARCRY_RADIUS);
        String family = MobDanger.family(fallen);
        for (MobEntity ally : world.getEntitiesByClass(MobEntity.class, box,
                m -> m.isAlive() && m != fallen && family.equals(MobDanger.family(m)))) {
            ally.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, WARCRY_DURATION, 0));
            ally.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, WARCRY_DURATION, 0));
            if (killer instanceof LivingEntity living) {
                ally.setTarget(living);
            }
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

    /**
     * The hoard a fallen champion leaves - both <em>whether</em> and <em>what</em> scaled by the
     * danger it actually posed.
     *
     * <p>Danger is read per-killer via {@link ThreatService#scaledAgainst}, not from the world: a
     * champion is only worth what it was worth to the person who felled it, and reading a global
     * figure would let a strong player's presence enrich a weak one's kills. No killer (a fall, a
     * cactus, another mob) means no bounty at all - nobody earned it.
     *
     * <p>Both rolls draw from the same {@link net.minecraft.util.math.random.Random}, and both scale:
     * the drop chance with {@code eliteBountyChance * scaled}, the tier through
     * {@link ThreatMath#bountyTier}, which refuses the better hoards outright below a danger
     * threshold however lucky the roll. An empty or missing tag is skipped in silence, so a datapack
     * may delete a whole tier without breaking anything.
     */
    private static void rollBounty(ServerWorld world, LivingEntity entity, DamageSource source) {
        if (!(source.getAttacker() instanceof ServerPlayerEntity killer)) {
            return;
        }
        float scaled = ThreatService.scaledAgainst(killer, entity);
        int configured = Kindreds.CONFIG == null ? 0 : Kindreds.CONFIG.eliteBountyChance;
        if (world.getRandom().nextFloat() >= (configured / 100f) * scaled) {
            return;
        }
        TagKey<Item> pool = switch (ThreatMath.bountyTier(scaled, world.getRandom().nextFloat())) {
            case FABLED -> BOUNTY_FABLED;
            case RARE -> BOUNTY_RARE;
            case COMMON -> BOUNTY_COMMON;
        };
        Optional<RegistryEntry<Item>> item = Registries.ITEM.getRandomEntry(pool, world.getRandom());
        item.ifPresent(entry -> entity.dropStack(world, new ItemStack(entry.value())));
    }
}
