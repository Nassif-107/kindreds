package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.config.Difficulty;
import com.kindreds.config.KindredsConfig;
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
 * C2S: "set the server difficulty preset", sent by the in-game settings screen.
 *
 * <p><b>Authority lives here, not in the UI.</b> The screen hides its buttons from non-operators as a
 * courtesy, but that is cosmetic - a crafted packet from any client still lands in this handler, so
 * operator permission ({@code level >= 2}, the same bar the {@code /kindreds difficulty} command uses)
 * is re-checked before anything is touched. A non-operator gets a refusal and no state changes.
 *
 * <p>On success the new settings are persisted and pushed to <i>every</i> online player, since these
 * are server-wide rules rather than a per-player preference.
 */
public record SetDifficultyC2S(String preset) implements CustomPayload {
    public static final CustomPayload.Id<SetDifficultyC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "set_difficulty"));

    public static final PacketCodec<RegistryByteBuf, SetDifficultyC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SetDifficultyC2S::preset,
            SetDifficultyC2S::new);

    /** Permission level required to change server rules - matches the command's {@code requires}. */
    public static final int OPERATOR_LEVEL = 2;

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
        if (!player.hasPermissionLevel(OPERATOR_LEVEL)) {
            Kindreds.LOGGER.warn("[Kindreds] {} tried to change difficulty without operator permission",
                    player.getGameProfile().getName());
            player.sendMessage(Text.translatable("kindreds.settings.denied").formatted(Formatting.RED), false);
            SyncConfigS2C.sendTo(player); // snap their UI back to the real values
            return;
        }
        Difficulty d;
        try {
            d = Difficulty.valueOf(preset.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            SyncConfigS2C.sendTo(player);
            return;
        }
        KindredsConfig config = Kindreds.CONFIG;
        config.difficulty = d;
        d.applyTo(config);
        config.save(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
        Kindreds.LOGGER.info("[Kindreds] difficulty set to {} by {}", d, player.getGameProfile().getName());
        player.sendMessage(Text.translatable("kindreds.settings.applied",
                Text.translatable("kindreds.difficulty." + d.name().toLowerCase(Locale.ROOT)))
                .formatted(Formatting.GREEN), false);
        SyncConfigS2C.sendToAll(player.getServer());
    }
}
