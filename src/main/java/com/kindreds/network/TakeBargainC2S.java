package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.ability.CorruptionService;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import com.kindreds.progression.UnlockService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * C2S: "I accept" - the player taking the Bargain from the skill-tree screen (which shows its own
 * confirm dialog first, since this is irreversible).
 *
 * <p>Carries no fields. Every precondition is re-checked here rather than trusted from the client:
 * the button is only <i>drawn</i> when eligible, but a hand-crafted packet must not be able to buy
 * the cap widening early, twice, or with the cap switched off entirely.
 *
 * <p>Reports its outcome through {@link UnlockResultS2C} (reasons: {@code "bargain_ok"},
 * {@code "bargain_already"}, {@code "bargain_not_at_cap"}, {@code "bargain_unavailable"}).
 */
public record TakeBargainC2S() implements CustomPayload {
    public static final CustomPayload.Id<TakeBargainC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "take_bargain"));

    public static final PacketCodec<RegistryByteBuf, TakeBargainC2S> CODEC =
            PacketCodec.unit(new TakeBargainC2S());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) -> handle(context.player()));
    }

    private static void handle(ServerPlayerEntity player) {
        KindredData data = KindredAttachment.get(player);
        if (CorruptionService.hasBargained(data)) {
            UnlockResultS2C.sendTo(player, false, "bargain_already");
            return;
        }
        SkillTree tree = resolveTree(player);
        if (tree == null || UnlockService.effectiveCap(tree, data) <= 0) {
            // No tree, or the cap is switched off on this server - there is nothing to widen, so there
            // is nothing worth paying two hearts for.
            UnlockResultS2C.sendTo(player, false, "bargain_unavailable");
            return;
        }
        if (!atCap(tree, data)) {
            UnlockResultS2C.sendTo(player, false, "bargain_not_at_cap");
            return;
        }

        CorruptionService.takeBargain(player);
        SyncKindredDataS2C.sendTo(player);
        UnlockResultS2C.sendTo(player, true, "bargain_ok");
    }

    /** True when the cheapest node the player doesn't own no longer fits under their ceiling - i.e.
     * they have genuinely hit the wall, which is the only moment the Bargain is offered. */
    private static boolean atCap(SkillTree tree, KindredData data) {
        int cap = UnlockService.effectiveCap(tree, data);
        int spent = UnlockService.totalPointsSpent(data, tree);
        int cheapest = Integer.MAX_VALUE;
        for (SkillNode node : tree.nodes()) {
            if (!data.hasNode(node.id())) {
                cheapest = Math.min(cheapest, node.cost().points());
            }
        }
        return cheapest != Integer.MAX_VALUE && spent + cheapest > cap;
    }

    private static SkillTree resolveTree(ServerPlayerEntity player) {
        if (player.getServer() == null) {
            return null;
        }
        Identifier race = RaceAccess.getRace(player).orElse(null);
        if (race == null) {
            return null;
        }
        Registry<SkillTree> trees = player.getServer().getRegistryManager()
                .getOrThrow(KindredsRegistries.SKILL_TREE);
        return SkillTreeResolver.byRace(trees, race).tree().orElse(null);
    }
}
