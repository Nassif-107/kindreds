package com.kindreds.ability;

import com.kindreds.data.BirthTrait;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.PerkDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves and caches the {@link PerkDef}s a player currently owns - from both their race's
 * {@link BirthTrait} and every unlocked skill-tree node - and hands them to the perk event handlers
 * (Task 3: mining/kill/melee/arrow/aura) that read them live on the relevant game event.
 *
 * <h2>Why a cache</h2>
 * Combat and mining events fire many times per second; re-walking the birth-trait registry and the
 * unlocked-node set each time would be wasteful. Owned perks change only on a handful of discrete
 * events, so the per-player list is cached and {@linkplain #invalidate(UUID) invalidated} at exactly
 * those points: node unlock ({@code RequestUnlockC2S}), respec ({@code RespecService}), race change /
 * birth reconcile ({@code BirthTraitService}), and login/logout (here). Perk ownership is read
 * straight from data (the registries + {@link KindredData#unlockedNodes()}), independent of whether
 * {@code AbilityApplier}'s attribute modifiers have finished their deferred post-base-mod apply - so
 * a perk is queryable the instant its node/birth-trait is owned.
 */
public final class PerkService {
    private PerkService() {
    }

    private static final Map<UUID, List<PerkDef>> CACHE = new ConcurrentHashMap<>();

    /** Lifecycle invalidation: drop a player's cached perks on login (rebuild fresh) and logout. */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> invalidate(handler.player.getUuid()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> invalidate(handler.player.getUuid()));
    }

    /** Forget {@code uuid}'s cached perks; the next {@link #ownedPerks} call rebuilds them. */
    public static void invalidate(UUID uuid) {
        CACHE.remove(uuid);
    }

    /** Every perk the player owns right now (birth traits + unlocked nodes), cached until invalidated. */
    public static List<PerkDef> ownedPerks(ServerPlayerEntity player) {
        return CACHE.computeIfAbsent(player.getUuid(), id -> resolve(player));
    }

    /** Owned perks whose id equals {@code perkId} - the common query shape for an event handler.
     * Returns an empty list (never null) when the player owns none. */
    public static List<PerkDef> perksOfType(ServerPlayerEntity player, String perkId) {
        List<PerkDef> out = new ArrayList<>();
        for (PerkDef perk : ownedPerks(player)) {
            if (perk.perk().equals(perkId)) {
                out.add(perk);
            }
        }
        return out;
    }

    private static List<PerkDef> resolve(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return List.of();
        }
        Optional<Identifier> race = RaceAccess.getRace(player);
        if (race.isEmpty()) {
            return List.of();
        }
        List<PerkDef> perks = new ArrayList<>();
        birthTraitFor(server, race.get()).ifPresent(bt -> addPerks(perks, bt.traits()));
        resolveTree(server, race.get()).ifPresent(tree -> {
            KindredData data = KindredAttachment.get(player);
            for (String nodeId : data.unlockedNodes()) {
                tree.node(nodeId).ifPresent(node -> addPerks(perks, node.abilities()));
            }
        });
        return List.copyOf(perks);
    }

    private static void addPerks(List<PerkDef> out, List<AbilityDef> abilities) {
        for (AbilityDef ability : abilities) {
            if (ability instanceof PerkDef perk) {
                out.add(perk);
            }
        }
    }

    private static Optional<BirthTrait> birthTraitFor(MinecraftServer server, Identifier race) {
        Registry<BirthTrait> registry = server.getRegistryManager().getOrThrow(KindredsRegistries.BIRTH_TRAIT);
        for (BirthTrait trait : registry) {
            if (trait.race().equals(race)) {
                return Optional.of(trait);
            }
        }
        return Optional.empty();
    }

    private static Optional<SkillTree> resolveTree(MinecraftServer server, Identifier race) {
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        return SkillTreeResolver.byRace(trees, race).tree();
    }
}
