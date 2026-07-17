package com.kindreds.progression;

import com.kindreds.Kindreds;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Wires "earn by doing" activity → discipline XP hooks: fabric-api events where one exists
 * cleanly, a {@code com.kindreds.mixin} Mixin where it doesn't (see each mixin class's javadoc for
 * exactly which vanilla method it targets and why).
 *
 * <h2>Race-null policy</h2>
 * Every award goes through {@link #award(ServerPlayerEntity, Identifier, long)} (or the
 * pre-resolved-race overload {@link #award(ServerPlayerEntity, Identifier, Identifier, long)}),
 * both of which <b>skip silently</b> (no xp, no sync packet) when {@link RaceAccess#getRace}
 * returns empty (base mod not installed, or the player hasn't picked a race yet). This was chosen
 * over "award unscaled" because race is central to this mod's design — a player with no race has
 * no discipline trees to spend points in yet, so banking xp for them would just be silently lost
 * progress with no way to ever redeem it. Once {@code RaceAccess} reports a race, xp starts
 * accruing normally; nothing is retroactively lost from the skipped window.
 *
 * <h2>Creative/spectator</h2>
 * All hooks skip creative and spectator mode players (no free xp from creative-mode block
 * breaking, etc.) — see {@link #isEligible(ServerPlayerEntity)}.
 *
 * <h2>Re-sync</h2>
 * Every award re-sends {@link SyncKindredDataS2C} to the acting player, since {@code KindredData}
 * only auto-syncs on {@code ServerPlayConnectionEvents.JOIN} (see {@link Kindreds}) — without this,
 * xp gained here would be invisible client-side until the player reconnects.
 */
public final class ActivityHooks {
    private ActivityHooks() {
    }

    // --- Discipline ids (data/kindreds/discipline/*.json) --------------------------------------

    private static final Identifier COMBAT = Identifier.of(Kindreds.MOD_ID, "combat");
    private static final Identifier ARCHERY = Identifier.of(Kindreds.MOD_ID, "archery");
    private static final Identifier MINING = Identifier.of(Kindreds.MOD_ID, "mining");
    private static final Identifier STEALTH = Identifier.of(Kindreds.MOD_ID, "stealth");
    private static final Identifier SMITHING = Identifier.of(Kindreds.MOD_ID, "smithing");
    private static final Identifier SURVIVAL = Identifier.of(Kindreds.MOD_ID, "survival");
    private static final Identifier LORE = Identifier.of(Kindreds.MOD_ID, "lore");

    // --- Tuning (baseXp, before race scaling / xpRateGlobal) ------------------------------------

    /** Mining: {@code round(hardness * K)}, floored to a minimum of 1 (see brief). */
    private static final double MINING_HARDNESS_FACTOR = 3.0;

    private static final long COMBAT_HIT_XP = 1;
    private static final long COMBAT_KILL_XP = 8;

    private static final long ARCHERY_HIT_ENTITY_XP = 6;
    private static final long ARCHERY_HIT_BLOCK_XP = 1;

    /** Ticks of continuous sneaking between each stealth-tick xp tick (100 ticks = 5s @ 20 tps). */
    private static final int STEALTH_TICKS_PER_AWARD = 100;
    private static final long STEALTH_TICK_XP = 1;
    private static final long STEALTH_SNEAK_KILL_BONUS_XP = 5;

    private static final long SMITHING_CRAFT_XP = 3;

    private static final long SURVIVAL_EAT_XP = 2;
    private static final long SURVIVAL_NEW_BIOME_XP = 10;
    /** How often (in ticks) each online player's biome is polled for "new biome discovered". */
    private static final int BIOME_CHECK_INTERVAL_TICKS = 100;

    private static final long LORE_ADVANCEMENT_XP = 15;

    /** How often the per-player race cache (used by the tick-driven hooks only) is refreshed once
     * a race has actually been resolved (present). */
    private static final int RACE_CACHE_TICKS_PRESENT = 6000; // 5 minutes @ 20 tps

    /** How often the cache is rechecked while still empty (no race resolved yet — e.g. player just
     * joined, or hasn't picked a race yet). Much shorter than {@link #RACE_CACHE_TICKS_PRESENT} so a
     * freshly-picked race is picked up quickly instead of being stuck behind a stale empty cache for
     * up to 5 minutes. */
    private static final int RACE_CACHE_TICKS_EMPTY = 200; // 10 seconds @ 20 tps

    private static final Map<UUID, PlayerTickState> TICK_STATE = new HashMap<>();

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register(ActivityHooks::onBlockBreak);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(ActivityHooks::onAfterDamage);
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(ActivityHooks::onKilledOtherEntity);
        ServerTickEvents.END_SERVER_TICK.register(ActivityHooks::onEndServerTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                TICK_STATE.remove(handler.player.getUuid()));
    }

    // --- Mining: PlayerBlockBreakEvents.AFTER ---------------------------------------------------

    private static void onBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || !isEligible(serverPlayer)) {
            return;
        }
        float hardness = state.getHardness(world, pos);
        long baseXp = Math.max(1, Math.round(hardness * MINING_HARDNESS_FACTOR));
        award(serverPlayer, MINING, baseXp);
    }

    // --- Combat: ServerLivingEntityEvents.AFTER_DAMAGE (hit) + ServerEntityCombatEvents (kill) ---

    private static void onAfterDamage(LivingEntity entity, DamageSource source, float baseDamageTaken, float damageTaken, boolean blocked) {
        // AFTER_DAMAGE is never fired for the killing blow (fabric-api javadoc), so this can't
        // double-award alongside onKilledOtherEntity below for the same hit.
        if (!(source.getAttacker() instanceof ServerPlayerEntity attacker) || attacker == entity || !isEligible(attacker)) {
            return;
        }
        award(attacker, COMBAT, COMBAT_HIT_XP);
    }

    private static void onKilledOtherEntity(ServerWorld world, Entity killer, LivingEntity killedEntity) {
        if (!(killer instanceof ServerPlayerEntity player) || !isEligible(player)) {
            return;
        }
        // Resolve once and reuse for both awards below, instead of resolving RaceAccess.getRace
        // twice on a sneak-kill (mirrors how the tick path reuses cachedRace).
        Optional<Identifier> race = RaceAccess.getRace(player);
        race.ifPresent(r -> award(player, r, COMBAT, COMBAT_KILL_XP));
        if (player.isSneaking()) {
            race.ifPresent(r -> award(player, r, STEALTH, STEALTH_SNEAK_KILL_BONUS_XP));
        }
    }

    // --- Archery: called from PersistentProjectileEntityMixin -----------------------------------

    /** Called by {@code com.kindreds.mixin.PersistentProjectileEntityMixin} when a player-owned
     * arrow hits an entity. */
    public static void onArrowHitEntity(ServerPlayerEntity player) {
        if (!isEligible(player)) {
            return;
        }
        award(player, ARCHERY, ARCHERY_HIT_ENTITY_XP);
    }

    /** Called by {@code com.kindreds.mixin.PersistentProjectileEntityMixin} when a player-owned
     * arrow hits a block. */
    public static void onArrowHitBlock(ServerPlayerEntity player) {
        if (!isEligible(player)) {
            return;
        }
        award(player, ARCHERY, ARCHERY_HIT_BLOCK_XP);
    }

    // --- Smithing/Crafting: called from CraftingResultSlotMixin ----------------------------------

    /** Called by {@code com.kindreds.mixin.CraftingResultSlotMixin} when a player takes a crafted
     * result out of a crafting result slot. */
    public static void onCraftedItemTaken(ServerPlayerEntity player, ItemStack craftedStack) {
        if (!isEligible(player)) {
            return;
        }
        award(player, SMITHING, SMITHING_CRAFT_XP);
    }

    // --- Survival (eating): called from ItemMixin ------------------------------------------------

    /** Called by {@code com.kindreds.mixin.ItemMixin} when a player finishes eating a food item. */
    public static void onFoodEaten(ServerPlayerEntity player) {
        if (!isEligible(player)) {
            return;
        }
        award(player, SURVIVAL, SURVIVAL_EAT_XP);
    }

    // --- Lore (advancements): called from PlayerAdvancementTrackerMixin --------------------------

    /** Called by {@code com.kindreds.mixin.PlayerAdvancementTrackerMixin} when a player newly
     * completes an advancement (all criteria granted). */
    public static void onAdvancementCompleted(ServerPlayerEntity player, AdvancementEntry advancement) {
        if (player == null || !isEligible(player)) {
            return;
        }
        award(player, LORE, LORE_ADVANCEMENT_XP);
    }

    // --- Stealth (sneak-tick) + Survival (new biome): ServerTickEvents.END_SERVER_TICK ----------

    private static void onEndServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!isEligible(player)) {
                continue;
            }
            PlayerTickState state = TICK_STATE.computeIfAbsent(player.getUuid(), id -> new PlayerTickState());
            // Resolved (and cached) once per player per tick, then reused by both sub-checks below,
            // rather than calling RaceAccess.getRace per sub-check — see class javadoc + Task 8 note
            // ("avoid per-tick getRace calls; cache race per player where frequent").
            Optional<Identifier> race = cachedRace(player, state);
            tickSneak(player, state, race);
            tickBiome(player, state, race);
        }
    }

    private static void tickSneak(ServerPlayerEntity player, PlayerTickState state, Optional<Identifier> race) {
        if (!player.isSneaking()) {
            state.sneakTicks = 0;
            return;
        }
        state.sneakTicks++;
        if (state.sneakTicks < STEALTH_TICKS_PER_AWARD) {
            return;
        }
        state.sneakTicks = 0;
        race.ifPresent(r -> award(player, r, STEALTH, STEALTH_TICK_XP));
    }

    private static void tickBiome(ServerPlayerEntity player, PlayerTickState state, Optional<Identifier> race) {
        state.ticksSinceBiomeCheck++;
        if (state.ticksSinceBiomeCheck < BIOME_CHECK_INTERVAL_TICKS) {
            return;
        }
        state.ticksSinceBiomeCheck = 0;

        RegistryEntry<Biome> biomeEntry = player.getWorld().getBiomeAccess().getBiome(player.getBlockPos());
        Optional<RegistryKey<Biome>> key = biomeEntry.getKey();
        if (key.isEmpty()) {
            return;
        }
        Identifier biomeId = key.get().getValue();
        if (race.isEmpty()) {
            return;
        }
        // Persisted on KindredData (Task 13) rather than the old in-memory-per-session set: an
        // in-memory set reset on every relog/server restart, which let a relog macro re-farm the
        // same biome's xp indefinitely. KindredData.discoveredBiomes() survives save/load, so
        // add() only returns true the first time this player has ever discovered this biome.
        KindredData data = KindredAttachment.get(player);
        if (!data.discoveredBiomes().add(biomeId)) {
            return; // already discovered (ever, not just this session)
        }
        award(player, race.get(), SURVIVAL, SURVIVAL_NEW_BIOME_XP);
    }

    private static Optional<Identifier> cachedRace(ServerPlayerEntity player, PlayerTickState state) {
        state.ticksSinceRaceCheck++;
        // Distinguish "confirmed present -> cache long" from "still empty -> recheck soon", so a
        // player who has no race yet (e.g. just joined) doesn't get stuck with a stale empty result
        // reused for the full 5-minute window once they do pick a race.
        int interval = (state.cachedRace != null && state.cachedRace.isPresent())
                ? RACE_CACHE_TICKS_PRESENT
                : RACE_CACHE_TICKS_EMPTY;
        if (state.cachedRace == null || state.ticksSinceRaceCheck >= interval) {
            state.cachedRace = RaceAccess.getRace(player);
            state.ticksSinceRaceCheck = 0;
        }
        return state.cachedRace;
    }

    // --- Shared helpers ---------------------------------------------------------------------------

    private static boolean isEligible(ServerPlayerEntity player) {
        return !player.isCreative() && !player.isSpectator();
    }

    /** Resolves the player's race via {@link RaceAccess}, then awards; no-ops (skips) if the
     * player has no race yet — see class javadoc. */
    private static void award(ServerPlayerEntity player, Identifier discipline, long baseXp) {
        RaceAccess.getRace(player).ifPresent(race -> award(player, race, discipline, baseXp));
    }

    /** Awards using an already-resolved race (tick-driven hooks, which cache it — see
     * {@link #cachedRace}), then re-syncs to the client. */
    private static void award(ServerPlayerEntity player, Identifier race, Identifier discipline, long baseXp) {
        KindredData data = KindredAttachment.get(player);
        ProgressionService.awardXp(data, race, discipline, baseXp, Kindreds.CONFIG.xpRateGlobal);
        SyncKindredDataS2C.sendTo(player);
    }

    /** Per-player, in-memory (not persisted — resets on server restart/rejoin) tick bookkeeping
     * for the sneak and biome-discovery hooks. */
    private static final class PlayerTickState {
        int sneakTicks;
        int ticksSinceBiomeCheck;
        int ticksSinceRaceCheck; // cachedRace == null forces a check on first use regardless
        Optional<Identifier> cachedRace;
    }
}
