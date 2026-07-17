package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.ability.AbilityApplier;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import com.kindreds.progression.ProgressionService;
import com.kindreds.progression.UnlockService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * C2S: "I want to unlock this node." Sent by the tree UI when a player clicks an unlockable node.
 *
 * <p>The server handler ({@link #registerServerHandler()}) resolves the requesting player's
 * {@link SkillTree}, validates the request through {@link UnlockService#canUnlock} (pure rules,
 * unit-tested), and on success mutates {@link KindredData}, applies each of the node's abilities
 * via {@link AbilityApplier}, and re-syncs the player's data - {@link SyncKindredDataS2C} is only
 * ever sent on join otherwise (Task 4), so any mutation here <b>must</b> re-send it or the client
 * silently goes stale. On failure it reports the reason via {@link UnlockResultS2C} instead.
 *
 * <h2>Resolving the player's tree</h2>
 * {@link #resolveTree} first asks {@link RaceAccess#getRace} for the requesting player's base-mod
 * race, then looks up the {@link SkillTree} in the {@code SKILL_TREE} dynamic registry whose
 * {@link SkillTree#race()} matches it. This is the primary path: it's correct even if two trees
 * ever authored a colliding node id, since the player's race pins down exactly one tree.
 *
 * <p>If {@code RaceAccess.getRace} comes back empty - the base Middle-earth mod isn't loaded, or
 * the player hasn't picked a race yet (e.g. standalone testing, or mid-onboarding) - {@link
 * #resolveTree} falls back to its original Task-6-era behavior: scanning every {@link SkillTree}
 * for the one containing the requested node id ({@link SkillNode#id()} is authored unique per its
 * own tree - see its javadoc - and P1's trees are authored with disjoint id namespaces per race,
 * so this is safe in practice). Since disjoint id namespaces are only a convention (not enforced
 * anywhere), the fallback scan still guards against a duplicate node id accidentally authored
 * across two trees: if it finds more than one match it logs a warning and rejects the unlock with
 * reason {@code "ambiguous_node"} rather than silently picking one (which could validate - and
 * apply abilities from - the wrong tree). This is safety net for Task 12's tree authoring, kept
 * around for the no-race fallback case.
 */
public record RequestUnlockC2S(String nodeId) implements CustomPayload {
    public static final CustomPayload.Id<RequestUnlockC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "request_unlock"));

    public static final PacketCodec<RegistryByteBuf, RequestUnlockC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, RequestUnlockC2S::nodeId,
            RequestUnlockC2S::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Registers the payload type and its server-side handler. Call once from
     * {@link Kindreds#onInitialize()}. */
    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);

        // Fabric API guarantees global C2S receivers run on the server thread, so it's safe to
        // mutate player/world state directly here (no extra context.server().execute(...) needed).
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            handle(context.server(), player, payload.nodeId());
        });
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, String nodeId) {
        TreeResolution resolution = resolveTree(server, player, nodeId);
        if (resolution.tree().isEmpty()) {
            UnlockResultS2C.sendTo(player, false, resolution.failureReason());
            return;
        }
        SkillTree tree = resolution.tree().get();
        KindredData data = KindredAttachment.get(player);

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data,
                tree,
                nodeId,
                discipline -> ProgressionService.pointsAvailable(data, tree, discipline),
                deedId -> isDeedEarned(server, player, deedId));

        if (!result.ok()) {
            UnlockResultS2C.sendTo(player, false, result.reason());
            return;
        }

        UnlockService.applyUnlock(data, nodeId);
        SkillNode node = tree.node(nodeId).orElseThrow(); // present: canUnlock already found it
        try {
            for (AbilityDef ability : node.abilities()) {
                AbilityApplier.apply(player, ability, nodeId);
            }
        } catch (RuntimeException e) {
            // Belt-and-suspenders: AbilityApplier.apply is designed to never throw on untrusted
            // JSON data (bad operations/ids are logged and skipped), but if something unexpected
            // slips through anyway, the node is already marked unlocked above - we must still
            // reach the sync below so the client doesn't silently diverge from server state.
            Kindreds.LOGGER.error("[Kindreds] unexpected exception applying abilities for node {} on player {}",
                    nodeId, player.getGameProfile().getName(), e);
        }

        SyncKindredDataS2C.sendTo(player);
        UnlockResultS2C.sendTo(player, true, "ok");
    }

    /** See the "Resolving the player's tree" section of this class's javadoc: prefers resolving by
     * the player's base-mod race, falling back to the Task-6-era node-id scan (with its
     * {@code "ambiguous_node"} guard) only when the race is unknown. */
    private static TreeResolution resolveTree(MinecraftServer server, ServerPlayerEntity player, String nodeId) {
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);

        Optional<Identifier> race = RaceAccess.getRace(player);
        if (race.isPresent()) {
            for (SkillTree tree : trees) {
                if (tree.race().equals(race.get())) {
                    return TreeResolution.found(tree);
                }
            }
            Kindreds.LOGGER.warn(
                    "[Kindreds] player {}'s race {} has no matching skill tree in the SKILL_TREE registry",
                    player.getGameProfile().getName(), race.get());
            return TreeResolution.failure("no_tree_for_race");
        }

        return resolveTreeByNodeScan(trees, nodeId);
    }

    /** Fallback used only when the player's race is unknown (base mod absent, or race not yet
     * chosen) - see {@link #resolveTree}. Carries a distinct {@code "ambiguous_node"} failure
     * reason (as opposed to {@code "unknown_node"}) when the id is found in more than one tree, so
     * the caller can report the real cause. */
    private static TreeResolution resolveTreeByNodeScan(Registry<SkillTree> trees, String nodeId) {
        SkillTree match = null;
        int matchCount = 0;
        for (SkillTree tree : trees) {
            if (tree.node(nodeId).isPresent()) {
                match = tree;
                matchCount++;
            }
        }
        if (matchCount == 0) {
            return TreeResolution.failure("unknown_node");
        }
        if (matchCount > 1) {
            Kindreds.LOGGER.warn(
                    "[Kindreds] node id '{}' is present in {} different skill trees; rejecting unlock as ambiguous "
                            + "(fix the duplicate id)", nodeId, matchCount);
            return TreeResolution.failure("ambiguous_node");
        }
        return TreeResolution.found(match);
    }

    /** Result of {@link #resolveTree}: exactly one of {@link #tree} or {@link #failureReason} is
     * present. */
    private record TreeResolution(Optional<SkillTree> tree, String failureReason) {
        static TreeResolution found(SkillTree tree) {
            return new TreeResolution(Optional.of(tree), null);
        }

        static TreeResolution failure(String reason) {
            return new TreeResolution(Optional.empty(), reason);
        }
    }

    private static boolean isDeedEarned(MinecraftServer server, ServerPlayerEntity player, Identifier deedId) {
        AdvancementEntry advancement = server.getAdvancementLoader().get(deedId);
        if (advancement == null) {
            return false;
        }
        return player.getAdvancementTracker().getProgress(advancement).isDone();
    }
}
