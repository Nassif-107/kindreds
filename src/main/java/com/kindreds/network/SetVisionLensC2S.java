package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillTree;
import com.kindreds.data.VisionLenses;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * C2S: "make this vision lens my active one" (an empty {@code lensId} means "turn my active lens
 * off"). Sent by the client's {@code VisionManager} cycle keybind.
 *
 * <p>Re-validates the request server-side rather than trusting the client: {@link #handle} computes
 * which lenses the requesting player has actually unlocked (via {@link VisionLenses#unlockedLenses},
 * scanning the synced {@code SKILL_TREE} registry against their owned node set - the exact same
 * source of truth the client-side {@code VisionManager} reads to decide what's cyclable in the first
 * place) and ignores the request (logging a warning) if the requested lens isn't among them. This is
 * the only real enforcement point for {@code activeVisionLens}: a modified/desynced client could
 * otherwise claim any lens is active with no server-side consequence, since the field itself is just
 * cosmetic client render state.
 *
 * <p><b>Config gating:</b> also the enforcement point for the server's {@code enableVision} config
 * flag ({@link Kindreds#CONFIG}) - a nonempty request is ignored while it's disabled, so a disabled
 * server never grants an active lens no matter what the client optimistically set locally; the next
 * {@code SyncKindredDataS2C} corrects the client back to {@code null}. Turning a lens off (empty
 * {@code lensId}) is never gated, so a lens active from before the flag was disabled can still be
 * cleared.
 */
public record SetVisionLensC2S(Optional<Identifier> lensId) implements CustomPayload {
    public static final CustomPayload.Id<SetVisionLensC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "set_vision_lens"));

    public static final PacketCodec<RegistryByteBuf, SetVisionLensC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.optional(Identifier.PACKET_CODEC), SetVisionLensC2S::lensId,
            SetVisionLensC2S::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Registers the payload type and its server-side handler. Call once from
     * {@link Kindreds#onInitialize()} (a common entrypoint, so this also registers the payload type
     * client-side - required for the client to be able to send it at all - matching {@code
     * ActivateAbilityC2S}'s pattern). */
    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);

        // Fabric API guarantees global C2S receivers run on the server thread, so it's safe to
        // mutate player/attachment state directly here (no extra context.server().execute(...) needed).
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                handle(context.player(), payload.lensId()));
    }

    private static void handle(ServerPlayerEntity player, Optional<Identifier> lensId) {
        KindredData data = KindredAttachment.get(player);
        if (lensId.isEmpty()) {
            data.setActiveVisionLens(null);
            return;
        }

        if (!Kindreds.CONFIG.enableVision) {
            Kindreds.LOGGER.warn(
                    "[Kindreds] player {} requested vision lens '{}' but vision is disabled server-side; ignoring",
                    player.getGameProfile().getName(), lensId.get());
            return;
        }

        Identifier requested = lensId.get();
        Registry<SkillTree> trees = player.getServer().getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        Map<Identifier, Integer> unlocked = VisionLenses.unlockedLenses(data, trees);
        if (!unlocked.containsKey(requested)) {
            Kindreds.LOGGER.warn(
                    "[Kindreds] player {} requested vision lens '{}' without an unlocking node; ignoring",
                    player.getGameProfile().getName(), requested);
            return;
        }
        data.setActiveVisionLens(requested);
    }
}
