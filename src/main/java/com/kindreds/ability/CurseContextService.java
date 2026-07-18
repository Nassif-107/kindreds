package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.CurseDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Drives {@link CurseDef}'s <b>contextual</b> drawbacks (a nonblank {@link CurseDef#when()}) -
 * unlike an unconditional curse (blank {@code when}, applied once at unlock by {@link
 * CurseService#apply}), a contextual curse's effect is applied/removed here, polled on a
 * lightweight interval, based on whether the owning player currently matches the named context.
 *
 * <h2>Supported contexts (Phase 1, per the Task 12 brief)</h2>
 * <ul>
 *     <li>{@code "deep_dark"} - below open sky, in near-total darkness (block light &lt;= {@link
 *     #MIN_DARK_LIGHT}). Backs Elf's "Deep-Dark Unease".</li>
 *     <li>{@code "daylight"} - direct, unobstructed skylight during the day. Backs Orc/Goblin/
 *     Snaga's "Dread of the Sun".</li>
 * </ul>
 * Any other {@code when} value is treated as never-active - logged once total (not once per tick,
 * to avoid log spam), matching the brief's "no-op-with-log for now" allowance for contexts beyond
 * these two (e.g. a future {@code "in_water"}).
 *
 * <h2>Idempotent apply/remove</h2>
 * {@link #ACTIVE} tracks, per player, which contextual-curse node ids are currently "on", so a
 * repeated tick where the context hasn't changed does nothing - important since {@link
 * AbilityApplier}'s attribute modifiers throw if the same modifier id is added twice. Only a true
 * off-to-on or on-to-off transition calls {@link AbilityApplier#apply}/{@link
 * AbilityApplier#removeNode}. Ownership is re-derived from {@link KindredData#hasNode} every tick
 * (not cached) and folded into the same "matches" boolean the context check produces, so a node
 * lost to a future respec drives the identical on-to-off transition a context ending would - the
 * applied effect is actually stripped (not just the {@link #ACTIVE} bookkeeping) within one tick
 * interval, without any extra wiring - see {@link #tickPlayer}. This matters because at least one
 * contextual effect (Elf's Deep-Dark Unease) is a <b>persistent</b> attribute modifier that would
 * never otherwise self-expire, and re-unlocking + re-entering the context after an unremoved
 * respec would throw on the duplicate persistent-modifier id.
 *
 * <p>Not persisted - {@link #ACTIVE} resets on server restart, which is harmless: the very next
 * tick re-evaluates every online player's context from scratch and re-applies whatever should be
 * active. It is also cleared per-player on a real death via {@link #resetActive} - see that
 * method's javadoc for why a death (unlike a disconnect or dimension change) needs an explicit
 * clear rather than relying on the tick loop alone.
 */
public final class CurseContextService {
    private CurseContextService() {
    }

    /** Re-checked once per second (20 ticks) - the contexts implemented here (sky exposure, time
     * of day) don't need anything faster, and this keeps the per-tick scan cheap. */
    private static final int TICK_INTERVAL = 20;

    /** Block-light ceiling (inclusive) below which a sky-less position counts as "deep dark". */
    private static final int MIN_DARK_LIGHT = 3;

    private static final Map<UUID, Set<String>> ACTIVE = new HashMap<>();
    private static final Set<String> LOGGED_UNKNOWN_WHEN = new HashSet<>();
    private static int tickCounter;

    /** Registers the tick hook and disconnect cleanup. Call once from {@code Kindreds#onInitialize()}. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(CurseContextService::onEndServerTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ACTIVE.remove(handler.player.getUuid()));
    }

    /**
     * Clears {@code uuid}'s {@link #ACTIVE} bookkeeping without touching any in-world attribute
     * modifier/status effect - called by {@code DeathHandler}'s real-death branch, where vanilla
     * has already dropped every persistent modifier this mod installed (see that class's javadoc's
     * "Attribute re-apply on respawn" section) but {@link #ACTIVE} would otherwise keep claiming a
     * contextual curse is still applied. Left stale, the next {@link #tickPlayer} would see {@code
     * wasActive=true} for a still-owned, still-in-context curse and {@link #decideTransition} would
     * return {@link Transition#NONE} - never re-applying the (actually absent) effect. Forcing
     * {@code wasActive=false} here makes that same tick take {@link Transition#APPLY} instead,
     * matching reality. Not needed on a plain dimension change: vanilla keeps the persistent
     * modifiers there, so {@link #ACTIVE} is already correct and clearing it would just cause a
     * redundant, harmless-but-wasteful re-apply next tick.
     */
    public static void resetActive(UUID uuid) {
        ACTIVE.remove(uuid);
    }

    /** Test seam: reports whether {@code nodeId} is currently tracked as active for {@code uuid} -
     * lets {@code CurseContextServiceTest} verify {@link #resetActive} without a live {@code
     * ServerPlayerEntity}/{@code tickPlayer} call. */
    static boolean isActiveForTest(UUID uuid, String nodeId) {
        return ACTIVE.getOrDefault(uuid, Set.of()).contains(nodeId);
    }

    /** Test seam: marks {@code nodeId} active for {@code uuid} directly, bypassing {@link
     * #tickPlayer}/{@link AbilityApplier}, so {@code CurseContextServiceTest} can set up a
     * "currently active" state for {@link #resetActive} to clear. */
    static void markActiveForTest(UUID uuid, String nodeId) {
        ACTIVE.computeIfAbsent(uuid, id -> new HashSet<>()).add(nodeId);
    }

    private static void onEndServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < TICK_INTERVAL) {
            return;
        }
        tickCounter = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            tickPlayer(server, player);
        }
    }

    private static void tickPlayer(MinecraftServer server, ServerPlayerEntity player) {
        Optional<SkillTree> treeOpt = resolveTree(server, player);
        if (treeOpt.isEmpty()) {
            return;
        }
        KindredData data = KindredAttachment.get(player);
        Set<String> active = ACTIVE.computeIfAbsent(player.getUuid(), id -> new HashSet<>());

        for (SkillNode node : treeOpt.get().nodes()) {
            // Re-derived every tick (not cached) rather than short-circuited away when unowned: the
            // invariant is "effect present iff owned AND in-context", so losing ownership (a respec)
            // must go through the very same off-transition below that context-ending does, or the
            // applied effect (e.g. Elf's persistent delvers_fear_strength modifier) would be orphaned
            // - it doesn't self-expire and nothing else would ever strip it.
            boolean owned = data.hasNode(node.id());
            for (AbilityDef ability : node.abilities()) {
                if (!(ability instanceof CurseDef curse) || curse.when().isEmpty()) {
                    continue;
                }
                boolean wasActive = active.contains(node.id());
                // Short-circuits before matchesContext() when unowned, same as the old early-continue
                // did - no spurious "unknown when" log noise for nodes the player doesn't even have.
                // Also short-circuits when enableCurses is disabled server-side: treating that as
                // "never in context" (rather than skipping the tick entirely) routes an already-
                // active contextual curse through decideTransition's REMOVE branch below, so
                // flipping the config off doesn't strand a persistent modifier that was applied
                // while curses were still enabled.
                boolean contextMatches = owned && Kindreds.CONFIG.enableCurses && matchesContext(curse.when(), player);
                switch (decideTransition(owned, contextMatches, wasActive)) {
                    case APPLY -> {
                        applyContextualCurse(player, curse, node.id());
                        active.add(node.id());
                    }
                    case REMOVE -> {
                        removeContextualCurse(player, curse, node.id());
                        active.remove(node.id());
                    }
                    case NONE -> {
                        // No transition this tick - already in the right state.
                    }
                }
            }
        }

        tickBirthCurses(server, player, active);
    }

    /**
     * Drives the <b>innate</b> contextual curses a player has from their race's {@link
     * com.kindreds.data.BirthTrait} (e.g. Orc/Goblin/Snaga "Dread of the Sun" - Weakness/Slowness
     * under open daylight, which the Uruk-hai alone do not suffer). Same apply/remove-on-transition
     * discipline as the node-curse loop above, keyed by {@code birthcurse/<race>/<index>}, and gated
     * on {@code enableBirthTraits}. Birth traits are always "owned" for the player's current race,
     * so ownership is fixed true and only the context (and the config flag) drives the transition.
     */
    private static void tickBirthCurses(MinecraftServer server, ServerPlayerEntity player, Set<String> active) {
        if (!Kindreds.CONFIG.enableBirthTraits) {
            // Config off: transition any active birth curse out (context treated as never-matching).
            Optional<Identifier> race = RaceAccess.getRace(player);
            race.flatMap(r -> birthTraitFor(server, r)).ifPresent(bt ->
                    reconcileBirthCurses(player, bt.traits(), race.get(), active, false));
            return;
        }
        Optional<Identifier> race = RaceAccess.getRace(player);
        if (race.isEmpty()) {
            return;
        }
        birthTraitFor(server, race.get()).ifPresent(bt ->
                reconcileBirthCurses(player, bt.traits(), race.get(), active, true));
    }

    private static void reconcileBirthCurses(ServerPlayerEntity player, List<AbilityDef> traits, Identifier race,
                                              Set<String> active, boolean enabled) {
        for (int i = 0; i < traits.size(); i++) {
            if (!(traits.get(i) instanceof CurseDef curse) || curse.when().isEmpty()) {
                continue;
            }
            String key = "birthcurse/" + race.getPath() + "/" + i;
            boolean wasActive = active.contains(key);
            boolean contextMatches = enabled && matchesContext(curse.when(), player);
            switch (decideTransition(true, contextMatches, wasActive)) {
                case APPLY -> {
                    applyContextualCurse(player, curse, key);
                    active.add(key);
                }
                case REMOVE -> {
                    removeContextualCurse(player, curse, key);
                    active.remove(key);
                }
                case NONE -> {
                    // Already in the right state.
                }
            }
        }
    }

    private static Optional<com.kindreds.data.BirthTrait> birthTraitFor(MinecraftServer server, Identifier race) {
        Registry<com.kindreds.data.BirthTrait> registry =
                server.getRegistryManager().getOrThrow(KindredsRegistries.BIRTH_TRAIT);
        for (com.kindreds.data.BirthTrait trait : registry) {
            if (trait.race().equals(race)) {
                return Optional.of(trait);
            }
        }
        return Optional.empty();
    }

    /** The three outcomes {@link #decideTransition} can produce for a single contextual curse on a
     * single tick - see that method's javadoc for the decision rule. */
    enum Transition { APPLY, REMOVE, NONE }

    /**
     * Pure decision rule behind {@link #tickPlayer}'s apply/remove bookkeeping, split out so it's
     * unit-testable without a running Minecraft server. The invariant: a contextual curse's effect
     * should be present exactly when {@code owned && contextMatches} - this method independently
     * re-derives that AND (rather than trusting a caller to have pre-folded it), so a currently-
     * active curse whose owned-and-in-context state has gone false - whether because the node was
     * respec'd away ({@code !owned}) or the context itself ended ({@code owned && !contextMatches})
     * - always transitions to {@link Transition#REMOVE}, never silently to {@link Transition#NONE}.
     * That's precisely Task 12's fix: a contextual curse's effect (e.g. a persistent attribute
     * modifier) does not self-expire and would otherwise be orphaned by a respec.
     *
     * @param owned          whether the node granting this curse is currently unlocked
     * @param contextMatches whether the player currently matches the curse's {@code when} context
     * @param wasActive      whether this curse's effect is currently tracked as applied
     */
    static Transition decideTransition(boolean owned, boolean contextMatches, boolean wasActive) {
        boolean shouldBeActive = owned && contextMatches;
        if (shouldBeActive && !wasActive) {
            return Transition.APPLY;
        }
        if (!shouldBeActive && wasActive) {
            return Transition.REMOVE;
        }
        return Transition.NONE;
    }

    private static void applyContextualCurse(ServerPlayerEntity player, CurseDef curse, String nodeId) {
        curse.effect().ifPresentOrElse(
                effect -> AbilityApplier.apply(player, effect, nodeId),
                () -> Kindreds.LOGGER.warn(
                        "[Kindreds] contextual curse on node {} has no effect payload; nothing to apply", nodeId));
    }

    private static void removeContextualCurse(ServerPlayerEntity player, CurseDef curse, String nodeId) {
        curse.effect().ifPresent(effect -> AbilityApplier.removeNode(player, List.of(effect), nodeId));
    }

    // --- Context evaluation ------------------------------------------------------------------------

    private static boolean matchesContext(String when, ServerPlayerEntity player) {
        return switch (when) {
            case "deep_dark" -> isDeepDark(player);
            case "daylight" -> isDaylight(player);
            default -> {
                if (LOGGED_UNKNOWN_WHEN.add(when)) {
                    Kindreds.LOGGER.warn(
                            "[Kindreds] curse context '{}' isn't implemented yet; treating it as never active", when);
                }
                yield false;
            }
        };
    }

    /** Below open sky, in near-total darkness - backs Elf's "Deep-Dark Unease". */
    private static boolean isDeepDark(ServerPlayerEntity player) {
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();
        if (world.isSkyVisible(pos)) {
            return false;
        }
        return world.getLightLevel(LightType.BLOCK, pos) <= MIN_DARK_LIGHT;
    }

    /** Direct, unobstructed skylight during the day - backs Orc/Goblin/Snaga's "Dread of the Sun". */
    private static boolean isDaylight(ServerPlayerEntity player) {
        World world = player.getWorld();
        if (!world.isDay()) {
            return false;
        }
        BlockPos pos = player.getBlockPos();
        return world.isSkyVisible(pos) && world.getLightLevel(LightType.SKY, pos) >= 15;
    }

    // --- Tree resolution ---------------------------------------------------------------------------

    /** Mirrors {@code ActiveAbilityService}'s race-based tree resolution, including Task 12 Stage
     * B's ambiguous-race guard via {@link SkillTreeResolver}. */
    private static Optional<SkillTree> resolveTree(MinecraftServer server, ServerPlayerEntity player) {
        Optional<Identifier> race = RaceAccess.getRace(player);
        if (race.isEmpty()) {
            return Optional.empty();
        }
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        SkillTreeResolver.Resolution resolution = SkillTreeResolver.byRace(trees, race.get());
        if (resolution.matchCount() > 1) {
            Kindreds.LOGGER.warn(
                    "[Kindreds] race {} matches {} different skill trees; contextual curses can't be resolved "
                            + "unambiguously (fix the duplicate race authoring)", race.get(), resolution.matchCount());
        }
        return resolution.tree();
    }
}
