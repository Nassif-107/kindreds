package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.config.KindredsConfig;
import com.kindreds.config.Menace;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Locale;

/**
 * C2S: "set the enemy-difficulty preset", sent by the rules screen - the {@link Menace} sibling of
 * {@link SetDifficultyC2S}.
 *
 * <p>A separate payload rather than a mode flag on the existing one, because the two are separate
 * settings that happen to share a screen: bundling them would mean every future change to one axis
 * had to reason about the other's handler.
 *
 * <p><b>Authority lives here, not in the UI</b>, exactly as it does for its sibling: the screen hides
 * its controls from non-operators as a courtesy, but a crafted packet from any client still lands
 * here, so operator permission is re-checked before anything is touched.
 */
public record SetMenaceC2S(String preset) implements CustomPayload {
    public static final CustomPayload.Id<SetMenaceC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "set_menace"));

    public static final PacketCodec<RegistryByteBuf, SetMenaceC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SetMenaceC2S::preset,
            SetMenaceC2S::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                handle(context.player(), payload.preset()));
    }

    private static void handle(ServerPlayerEntity player, String preset) {
        if (!player.hasPermissionLevel(SetDifficultyC2S.OPERATOR_LEVEL)) {
            Kindreds.LOGGER.warn("[Kindreds] {} tried to change the menace preset without operator permission",
                    player.getGameProfile().getName());
            player.sendMessage(Text.translatable("kindreds.settings.denied").formatted(Formatting.RED), false);
            SyncConfigS2C.sendTo(player); // snap their UI back to the real values
            return;
        }
        Menace m;
        try {
            m = Menace.valueOf(preset.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            SyncConfigS2C.sendTo(player);
            return;
        }
        KindredsConfig config = Kindreds.CONFIG;
        config.menace = m;
        m.applyTo(config);
        config.save(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
        Kindreds.LOGGER.info("[Kindreds] menace set to {} by {}", m, player.getGameProfile().getName());
        player.sendMessage(Text.translatable("kindreds.settings.menace_applied",
                Text.translatable("kindreds.menace." + m.name().toLowerCase(Locale.ROOT)))
                .formatted(Formatting.GREEN), false);
        SyncConfigS2C.sendToAll(player.getServer());
    }
}
