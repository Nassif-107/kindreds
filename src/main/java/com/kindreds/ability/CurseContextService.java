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
 * (not cached), so a node lost to a future respec is naturally cleaned up within one tick interval
 * without any extra wiring - see {@link #tickPlayer}.
 *
 * <p>Not persisted - {@link #ACTIVE} resets on server restart, which is harmless: the very next
 * tick re-evaluates every online player's context from scratch and re-applies whatever should be
 * active.
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
            if (!data.hasNode(node.id())) {
                // Not (or no longer) owned - if it was previously active, drop the bookkeeping too
                // so a respec doesn't leave a stale "active" entry a future re-unlock would treat as
                // already-on.
                active.remove(node.id());
                continue;
            }
            for (AbilityDef ability : node.abilities()) {
                if (!(ability instanceof CurseDef curse) || curse.when().isEmpty()) {
                    continue;
                }
                boolean matches = matchesContext(curse.when(), player);
                boolean wasActive = active.contains(node.id());
                if (matches && !wasActive) {
                    applyContextualCurse(player, curse, node.id());
                    active.add(node.id());
                } else if (!matches && wasActive) {
                    removeContextualCurse(player, curse, node.id());
                    active.remove(node.id());
                }
            }
        }
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
