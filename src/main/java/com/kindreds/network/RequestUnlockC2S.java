package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.ability.AbilityApplier;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
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
 * <h2>Resolving the player's tree (interim, pending Task 8)</h2>
 * The brief describes resolving "the player's race, then the race's tree" from the
 * {@code SKILL_TREE} registry. Reading a player's actual race from the base Middle-earth mod is
 * Task 8's job ({@code RaceAccess}) and doesn't exist yet as of this task. As an interim stand-in
 * that needs no assumptions about that future API, {@link #resolveTree} instead scans every
 * {@link SkillTree} in the {@code SKILL_TREE} dynamic registry for the one containing the
 * requested node id ({@link SkillNode#id()} is authored unique per its own tree - see its
 * javadoc - and P1's trees are authored with disjoint id namespaces per race, so this is safe in
 * practice). Once Task 8 lands, the natural follow-up is to resolve the tree via
 * {@code SKILL_TREE registry entry for RaceAccess.raceOf(player)} instead (and optionally assert
 * the resolved node's tree matches that race, as a sanity check against a malicious/buggy client
 * requesting a foreign-race node id that happens to collide).
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
        Optional<SkillTree> maybeTree = resolveTree(server, nodeId);
        if (maybeTree.isEmpty()) {
            UnlockResultS2C.sendTo(player, false, "unknown_node");
            return;
        }
        SkillTree tree = maybeTree.get();
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
        for (AbilityDef ability : node.abilities()) {
            AbilityApplier.apply(player, ability, nodeId);
        }

        SyncKindredDataS2C.sendTo(player);
        UnlockResultS2C.sendTo(player, true, "ok");
    }

    /** See the "Resolving the player's tree" section of this class's javadoc. */
    private static Optional<SkillTree> resolveTree(MinecraftServer server, String nodeId) {
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        for (SkillTree tree : trees) {
            if (tree.node(nodeId).isPresent()) {
                return Optional.of(tree);
            }
        }
        return Optional.empty();
    }

    private static boolean isDeedEarned(MinecraftServer server, ServerPlayerEntity player, Identifier deedId) {
        AdvancementEntry advancement = server.getAdvancementLoader().get(deedId);
        if (advancement == null) {
            return false;
        }
        return player.getAdvancementTracker().getProgress(advancement).isDone();
    }
}
