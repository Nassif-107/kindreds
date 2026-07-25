package com.kindreds.ability;

import com.kindreds.data.BirthTrait;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.PerkDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import com.kindreds.progression.UnlockService;
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

    /**
     * How many times the player owns {@code perkId} - its <b>rank</b>.
     *
     * <p>Several perks used to be read as a yes/no ({@code perksOfType(..).isEmpty()}), which quietly
     * threw away everything a lane granted after its first node: a Goblin owns {@code camouflage}
     * seven times over and felt it once. Rank turns that authored depth into real depth - the second
     * and seventh node of a stealth lane are supposed to differ.
     */
    public static int rankOf(ServerPlayerEntity player, String perkId) {
        int rank = 0;
        for (PerkDef perk : ownedPerks(player)) {
            if (perk.perk().equals(perkId)) {
                rank++;
            }
        }
        return rank;
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
        birthTraitFor(server, race.get()).ifPresent(bt -> addPerks(perks, bt.traits(), 1));
        UnlockService.treeFor(player).ifPresent(tree -> {
            KindredData data = KindredAttachment.get(player);
            for (String nodeId : data.unlockedNodes()) {
                int rank = data.rankOf(nodeId);
                tree.node(nodeId).ifPresent(node -> addPerks(perks, node.abilities(), rank));
            }
        });
        return List.copyOf(perks);
    }

    /**
     * Adds every perk in {@code abilities}, with its numbers scaled to {@code rank}.
     *
     * <p>This is where a rank stops being bookkeeping and becomes strength. Most perk handlers fold
     * their owned copies together with {@code Math.max}, so handing them the same perk twice changed
     * nothing - which is exactly why a second node granting an identical perk used to be worth
     * nothing at all. Scaling the numbers instead means rank 2 genuinely reads as twice the perk to
     * every handler, whether that handler maxes, sums or rolls.
     */
    private static void addPerks(List<PerkDef> out, List<AbilityDef> abilities, int rank) {
        for (AbilityDef ability : abilities) {
            if (ability instanceof PerkDef perk) {
                out.add(rank <= 1 ? perk : scaled(perk, rank));
            }
        }
    }

    /**
     * A copy of {@code perk} with its tunables multiplied by {@code rank}.
     *
     * <p>Probability-shaped params are deliberately excluded from the multiplication and raised on a
     * curve that cannot pass certainty instead: tripling a {@code chance} of 0.4 would read as 1.2 and
     * silently become "always", which is a different perk rather than a deeper one. Each rank closes
     * half of the remaining gap to 1.0, so more ranks always help and never guarantee.
     */
    private static PerkDef scaled(PerkDef perk, int rank) {
        Map<String, Float> scaledParams = new java.util.HashMap<>(perk.params().size());
        perk.params().forEach((key, value) -> scaledParams.put(key, scaleParam(key, value, rank)));
        return new PerkDef(perk.perk(), scaledParams, perk.foe(), perk.effect());
    }

    /** Param names that read as a probability in {@code [0,1]} rather than a magnitude. */
    private static boolean isChanceLike(String key) {
        return key.equals("chance") || key.endsWith("_chance") || key.equals("share");
    }

    private static float scaleParam(String key, float value, int rank) {
        if (!isChanceLike(key)) {
            return value * rank;
        }
        if (value <= 0f || value >= 1f) {
            return value;   // a ban or a certainty is a statement, not something to deepen
        }
        float chance = value;
        for (int i = 1; i < rank; i++) {
            chance += (1f - chance) * 0.5f;   // halve the remaining gap, never reach it
        }
        return chance;
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
}
