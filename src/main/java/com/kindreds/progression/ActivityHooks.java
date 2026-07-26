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
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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
    // Chunk 2 disciplines.
    private static final Identifier SONG = Identifier.of(Kindreds.MOD_ID, "song");
    private static final Identifier BEAST_LORE = Identifier.of(Kindreds.MOD_ID, "beast_lore");
    private static final Identifier RUNECRAFT = Identifier.of(Kindreds.MOD_ID, "runecraft");
    private static final Identifier LEADERSHIP = Identifier.of(Kindreds.MOD_ID, "leadership");
    private static final Identifier SHADOW = Identifier.of(Kindreds.MOD_ID, "shadow");

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
    private static final long LORE_STUDY_XP = 3;         // pore over a lectern, book-shelf or map
    private static final long LORE_STUDY_CD = 60;
    private static final long SURVIVAL_HARVEST_XP = 2;   // bring in a ripe crop

    // Chunk 2 discipline tuning.
    private static final long SONG_PLAY_XP = 4;          // sound a drum, tend a bonfire or campfire
    private static final long SONG_RESTED_XP = 25;       // a night's rest ended in song
    private static final long SONG_PIPE_XP = 5;          // a pipe at ease, the Shire's own art
    private static final long SONG_PIPE_CD = 300;
    /** Ticks abed before waking counts as a night's rest (a bed skips ~5s of real time). */
    private static final int SLEEP_TICKS_FOR_SONG = 60;
    private static final long RUNECRAFT_USE_XP = 3;      // use an enchant/anvil/lectern/etc. station
    private static final long BEAST_INTERACT_XP = 2;     // handle an animal
    private static final long BEAST_RIDE_XP = 1;         // per interval while mounted on a beast
    private static final long BEAST_TAME_XP = 20;        // one-time: successfully tame a wolf or horse
    private static final long LEADERSHIP_CHAMPION_XP = 12; // fell a mighty foe (max health >= 30)
    private static final long LEADERSHIP_LEAD_XP = 1;    // per interval with allies at your side
    private static final long SHADOW_INNOCENT_XP = 10;   // a dark deed: slay the innocent
    /** Cooldowns (game ticks) so "use"-style hooks can't be spam-farmed. */
    private static final long SONG_CD = 200, RUNECRAFT_CD = 60, BEAST_CD = 40;
    private static final int RIDE_AWARD_INTERVAL = 100;   // 5s mounted
    private static final int LEAD_AWARD_INTERVAL = 200;   // 10s leading
    private static final int LEAD_RADIUS = 16;
    private static final float CHAMPION_HEALTH = 30f;

    /**
     * Blocks that count as making music or keeping a hearth, for Song.
     *
     * <p>A tag rather than a hardcoded list because the vanilla answers are largely unreachable in
     * Middle-earth: a note block wants redstone and a jukebox wants a diamond, and the mod generates
     * neither ore in its own dimension, while a bell has no crafting recipe in vanilla at all. The
     * tag leads instead with Middle-earth's own {@code bonfire} (a campfire and logs) and
     * {@code orcish_drum} (leather, sticks and logs) - both craftable from what the world actually
     * provides - and keeps the vanilla blocks as optional entries so they still count for anyone who
     * has carried one back from the old world.
     */
    private static final net.minecraft.registry.tag.TagKey<Block> SONG_HEARTHS =
            net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.BLOCK,
                    Identifier.of(Kindreds.MOD_ID, "song_hearths"));

    /**
     * Where runes are cut and metal is worked.
     *
     * <p>A tag, and led by Middle-earth's own smithing rather than vanilla's: the mod ships a working
     * {@code forge} and {@code bellows} and five shaping anvils - stone, treated, and the dwarven,
     * elven and orcish patterns - and those, not a vanilla anvil, are where a smith in this world
     * actually stands. The vanilla stations stay in the tag behind them.
     */
    private static final net.minecraft.registry.tag.TagKey<Block> RUNECRAFT_STATIONS =
            net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.BLOCK,
                    Identifier.of(Kindreds.MOD_ID, "runecraft_stations"));

    /** Instruments and pipes - Song earned from what you carry rather than what you stand beside.
     * Middle-earth ships five pipes and pipeweed to fill them; a pipe at ease by the road is as much
     * the art of the hall as a drum is, and it is the one Song source that travels with you. */
    private static final net.minecraft.registry.tag.TagKey<net.minecraft.item.Item> SONG_INSTRUMENTS =
            net.minecraft.registry.tag.TagKey.of(net.minecraft.registry.RegistryKeys.ITEM,
                    Identifier.of(Kindreds.MOD_ID, "song_instruments"));

    /** Per-player, per-key last-award game time, for the cooldown-gated "use" hooks. */
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();

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
        UseBlockCallback.EVENT.register(ActivityHooks::onUseBlock);
        UseEntityCallback.EVENT.register(ActivityHooks::onUseEntity);
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(ActivityHooks::onUseItem);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            TICK_STATE.remove(handler.player.getUuid());
            COOLDOWNS.remove(handler.player.getUuid());
        });
    }

    // --- Song (note blocks / jukebox) + Runecraft (stations): UseBlockCallback ------------------

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (world.isClient() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity sp) || !isEligible(sp)) {
            return ActionResult.PASS;
        }
        Block block = world.getBlockState(hit.getBlockPos()).getBlock();
        if (world.getBlockState(hit.getBlockPos()).isIn(SONG_HEARTHS) && offCooldown(sp, "song", SONG_CD)) {
            award(sp, SONG, SONG_PLAY_XP);
        } else if (isLoreStation(block) && offCooldown(sp, "lore_study", LORE_STUDY_CD)) {
            // Books and maps are study; the forge and the enchanting-rune are craft. Splitting them
            // gives Lore the one thing it never had - a source you can return to. Until now its only
            // income was completing advancements, which are finite and mostly unrelated to a scholar.
            award(sp, LORE, LORE_STUDY_XP);
        } else if (world.getBlockState(hit.getBlockPos()).isIn(RUNECRAFT_STATIONS)
                && offCooldown(sp, "runecraft", RUNECRAFT_CD)) {
            award(sp, RUNECRAFT, RUNECRAFT_USE_XP);
        }
        return ActionResult.PASS;
    }

    // --- Song (pipes and instruments): UseItemCallback -------------------------------------------

    private static ActionResult onUseItem(PlayerEntity player, World world, Hand hand) {
        if (world.isClient() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity sp) || !isEligible(sp)) {
            return ActionResult.PASS;
        }
        if (sp.getStackInHand(hand).isIn(SONG_INSTRUMENTS) && offCooldown(sp, "song_pipe", SONG_PIPE_CD)) {
            award(sp, SONG, SONG_PIPE_XP);
        }
        return ActionResult.PASS;
    }

    // --- Beast-lore (handling animals): UseEntityCallback ---------------------------------------

    private static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity,
                                            net.minecraft.util.hit.EntityHitResult hit) {
        if (world.isClient() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity sp) || !isEligible(sp)) {
            return ActionResult.PASS;
        }
        if (entity instanceof net.minecraft.entity.passive.AnimalEntity && offCooldown(sp, "beast", BEAST_CD)) {
            award(sp, BEAST_LORE, BEAST_INTERACT_XP);
        }
        return ActionResult.PASS;
    }

    // --- Beast-lore (taming milestone): called from WolfTameActivityMixin / HorseTameActivityMixin

    /** Called by {@code com.kindreds.mixin.WolfTameActivityMixin} / {@code
     * com.kindreds.mixin.HorseTameActivityMixin} the instant a wolf or horse transitions from
     * untamed to tamed as the direct, synchronous result of this player's {@code interactMob}
     * call. The mixins themselves guard the untamed->tamed transition (see their javadocs for why
     * that's exploit-safe against chunk-reload xp farming), so this method just applies the
     * eligibility gate and awards — heavier than the repeatable {@link #BEAST_INTERACT_XP}/
     * {@link #BEAST_RIDE_XP} sources since taming isn't spammable. */
    public static void onAnimalTamed(ServerPlayerEntity player) {
        if (!isEligible(player)) {
            return;
        }
        award(player, BEAST_LORE, BEAST_TAME_XP);
    }

    /** Where a thing is read or charted rather than forged. */
    private static boolean isLoreStation(Block block) {
        return block == Blocks.LECTERN || block == Blocks.CHISELED_BOOKSHELF
                || block == Blocks.CARTOGRAPHY_TABLE || block == Blocks.BOOKSHELF;
    }



    // --- Mining: PlayerBlockBreakEvents.AFTER ---------------------------------------------------

    private static void onBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || !isEligible(serverPlayer)) {
            return;
        }
        // A ripe crop brought in is survival, not mining - and it is the source that matters most to
        // the peoples who barely eat. Survival previously paid mainly for eating, so an Elf, who
        // endures hunger by nature, could hardly earn it at all.
        if (state.getBlock() instanceof net.minecraft.block.CropBlock crop && crop.isMature(state)) {
            award(serverPlayer, SURVIVAL, SURVIVAL_HARVEST_XP);
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
        // Leadership: felling a mighty foe (a "champion" - warden, ravager, golem, etc.).
        if (killedEntity.getMaxHealth() >= CHAMPION_HEALTH) {
            race.ifPresent(r -> award(player, r, LEADERSHIP, LEADERSHIP_CHAMPION_XP));
        }
        // Shadow: a dark deed - slaying the innocent (villagers, their golems, wandering traders).
        if (isInnocent(killedEntity)) {
            race.ifPresent(r -> award(player, r, SHADOW, SHADOW_INNOCENT_XP));
        }
    }

    private static boolean isInnocent(LivingEntity entity) {
        return entity instanceof net.minecraft.entity.passive.VillagerEntity
                || entity instanceof net.minecraft.entity.passive.WanderingTraderEntity
                || entity instanceof net.minecraft.entity.passive.IronGolemEntity;
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
        // Checked before the eligibility gate: a Great Deed is renown, not discipline xp, and it counts
        // for every kindred regardless of whether this player can currently earn xp.
        RenownService.onAdvancementCompleted(player, advancement);
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
            tickBeastRide(player, state, race);
            tickLeadership(player, state, race);
            tickRestedSong(player, state, race);
        }
    }

    /**
     * Song: a night actually slept through ends in one, and pays properly for it.
     *
     * <p>Song had exactly one source - striking a note block or a jukebox, two xp on a one-second
     * cooldown - which made it the one discipline a player could not realistically level at all
     * without standing in a room hitting a block. Sleeping is the opposite kind of source: it happens
     * on its own rhythm, cannot be farmed faster than the night comes, and is where the songs in the
     * books are actually sung. Awarded on waking, and only after a real sleep rather than a moment in
     * the bed, so climbing in and straight out earns nothing.
     */
    private static void tickRestedSong(ServerPlayerEntity player, PlayerTickState state, Optional<Identifier> race) {
        if (player.isSleeping()) {
            state.ticksSleeping++;
            return;
        }
        int slept = state.ticksSleeping;
        state.ticksSleeping = 0;
        if (slept >= SLEEP_TICKS_FOR_SONG) {
            race.ifPresent(r -> award(player, r, SONG, SONG_RESTED_XP));
        }
    }

    /** Beast-lore: riding a tamed beast builds the bond. */
    private static void tickBeastRide(ServerPlayerEntity player, PlayerTickState state, Optional<Identifier> race) {
        if (!(player.getVehicle() instanceof net.minecraft.entity.passive.AnimalEntity)) {
            state.ticksMounted = 0;
            return;
        }
        if (++state.ticksMounted < RIDE_AWARD_INTERVAL) {
            return;
        }
        state.ticksMounted = 0;
        race.ifPresent(r -> award(player, r, BEAST_LORE, BEAST_RIDE_XP));
    }

    /** Leadership: standing with allies (other players nearby) grows a captain's renown. */
    private static void tickLeadership(ServerPlayerEntity player, PlayerTickState state, Optional<Identifier> race) {
        if (++state.ticksLeading < LEAD_AWARD_INTERVAL) {
            return;
        }
        state.ticksLeading = 0;
        boolean hasAlly = false;
        for (ServerPlayerEntity other : player.getWorld().getServer().getPlayerManager().getPlayerList()) {
            if (other != player && other.getWorld() == player.getWorld()
                    && other.squaredDistanceTo(player) <= (double) LEAD_RADIUS * LEAD_RADIUS) {
                hasAlly = true;
                break;
            }
        }
        if (hasAlly) {
            race.ifPresent(r -> award(player, r, LEADERSHIP, LEADERSHIP_LEAD_XP));
        }
    }

    /** True (and stamps "now") if {@code key} is off cooldown for this player; false while cooling. */
    private static boolean offCooldown(ServerPlayerEntity player, String key, long cooldownTicks) {
        long now = player.getWorld().getTime();
        Map<String, Long> map = COOLDOWNS.computeIfAbsent(player.getUuid(), id -> new HashMap<>());
        Long last = map.get(key);
        if (last != null && now - last < cooldownTicks) {
            return false;
        }
        map.put(key, now);
        return true;
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
    /**
     * Awards xp, redirecting it when this race could never spend it where it landed.
     *
     * <p>Not every race has every discipline - a Dwarf's tree holds combat, mining, smithing and
     * runecraft and nothing else - so an activity that pays into Lore paid a Dwarf in a currency with
     * nothing to buy. The old guard only skipped that when {@code allowCrossTraining} was <b>off</b>,
     * and it defaults to on, so in practice the xp was banked and quietly wasted: one Dwarf reached
     * <i>fifteen levels</i> of Lore, every point of it unspendable, while wondering why the tree said
     * he had points he could not use.
     *
     * <p>Both settings now do something useful with it rather than losing it:
     * <ul>
     *   <li><b>on</b> - the xp is redirected into the nearest discipline this race actually has (see
     *       {@link #NEIGHBOURS}), so reading a book still teaches a Dwarf <i>something</i>.</li>
     *   <li><b>off</b> - the xp is not awarded at all, the strict reading: only your own people's
     *       pursuits advance you. Nothing is banked that cannot be spent either way.</li>
     * </ul>
     */
    private static void award(ServerPlayerEntity player, Identifier race, Identifier discipline, long baseXp) {
        Identifier target = discipline;
        if (!raceCanSpendIn(player, race, discipline)) {
            if (!Kindreds.CONFIG.allowCrossTraining) {
                return;
            }
            target = nearestSpendable(player, race, discipline);
            if (target == null) {
                return;     // no tree, or a tree with no disciplines at all - nothing to redirect into
            }
        }
        ProgressionService.awardXp(player, race, target, baseXp, Kindreds.CONFIG.xpRateGlobal);
        SyncKindredDataS2C.sendTo(player);
    }

    /**
     * Where a discipline's xp goes when the race has no nodes for it: the first neighbour below that
     * this race can actually spend in, else any discipline it has (lowest id, so the choice is stable
     * rather than depending on registry order).
     *
     * <p>The pairings are by kinship of subject, not by power - lore and runecraft are both the study
     * of hidden things, stealth and shadow are the same art under two names, smithing and mining are
     * two halves of one craft - so a redirect still feels like it belongs to what you were doing.
     */
    private static final Map<String, List<String>> NEIGHBOURS = Map.ofEntries(
            Map.entry("lore", List.of("runecraft", "song", "survival", "combat")),
            Map.entry("runecraft", List.of("lore", "smithing", "song", "combat")),
            Map.entry("song", List.of("lore", "leadership", "survival", "combat")),
            Map.entry("leadership", List.of("combat", "song", "survival")),
            Map.entry("stealth", List.of("shadow", "survival", "archery", "combat")),
            Map.entry("shadow", List.of("stealth", "survival", "combat")),
            Map.entry("survival", List.of("beast_lore", "stealth", "mining", "combat")),
            Map.entry("beast_lore", List.of("survival", "leadership", "combat")),
            Map.entry("archery", List.of("combat", "stealth", "survival")),
            Map.entry("combat", List.of("leadership", "archery", "survival")),
            Map.entry("mining", List.of("smithing", "survival", "combat")),
            Map.entry("smithing", List.of("mining", "runecraft", "combat")));

    private static Identifier nearestSpendable(ServerPlayerEntity player, Identifier race, Identifier discipline) {
        for (String neighbour : NEIGHBOURS.getOrDefault(discipline.getPath(), List.of())) {
            Identifier candidate = Identifier.of(Kindreds.MOD_ID, neighbour);
            if (raceCanSpendIn(player, race, candidate)) {
                return candidate;
            }
        }
        // Nothing kindred left: fall back to any discipline this race owns, chosen deterministically.
        return spendableDisciplines(player, race).stream().sorted(Comparator.comparing(Identifier::toString))
                .findFirst().orElse(null);
    }

    /** Every discipline this race's tree actually has nodes in. */
    private static java.util.Set<Identifier> spendableDisciplines(ServerPlayerEntity player, Identifier race) {
        java.util.Set<Identifier> out = new java.util.HashSet<>();
        if (player.getServer() == null) {
            return out;
        }
        try {
            for (com.kindreds.data.SkillTree tree : player.getServer().getRegistryManager()
                    .getOrThrow(com.kindreds.data.KindredsRegistries.SKILL_TREE)) {
                if (tree.race().equals(race)) {
                    for (com.kindreds.data.SkillNode n : tree.nodes()) {
                        out.add(n.cost().disciplineId());
                    }
                    return out;
                }
            }
        } catch (RuntimeException ignored) {
            // registries unavailable - caller falls back to not redirecting
        }
        return out;
    }

    /** Whether {@code race}'s tree actually has nodes in {@code discipline} - i.e. whether points
     * earned there could ever be spent. Only consulted when cross-training is disabled. */
    private static boolean raceCanSpendIn(ServerPlayerEntity player, Identifier race, Identifier discipline) {
        if (player.getServer() == null) {
            return true;
        }
        try {
            for (com.kindreds.data.SkillTree tree : player.getServer().getRegistryManager()
                    .getOrThrow(com.kindreds.data.KindredsRegistries.SKILL_TREE)) {
                if (tree.race().equals(race)) {
                    for (com.kindreds.data.SkillNode n : tree.nodes()) {
                        if (n.cost().disciplineId().equals(discipline)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        } catch (RuntimeException ignored) {
            // registries unavailable - fail open rather than silently eat progression
        }
        return true;
    }

    /** Per-player, in-memory (not persisted — resets on server restart/rejoin) tick bookkeeping
     * for the sneak and biome-discovery hooks. */
    private static final class PlayerTickState {
        int sneakTicks;
        int ticksSinceBiomeCheck;
        int ticksMounted;
        int ticksLeading;
        int ticksSleeping;
        int ticksSinceRaceCheck; // cachedRace == null forces a check on first use regardless
        Optional<Identifier> cachedRace;
    }
}
