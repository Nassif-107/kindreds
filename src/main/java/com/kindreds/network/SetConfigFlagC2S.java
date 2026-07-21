package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.ability.BirthTraitService;
import com.kindreds.config.KindredsConfig;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * C2S: toggle one of the server's <b>rule switches</b> (birth traits, curses, vision, cross-training)
 * from the settings screen.
 *
 * <p>Two guards, both server-side, because this handler is reachable by any crafted packet:
 * <ol>
 *   <li><b>Operator permission</b> - the same level the commands require.</li>
 *   <li><b>A key whitelist</b> - only the four rule switches below can ever be written. This is
 *       deliberately not a generic "set any config field" endpoint, which would let a client rewrite
 *       xp rates, the respec item, or anything else added to the config later.</li>
 * </ol>
 *
 * <p>Flipping birth traits has to re-apply to everyone already online (their modifiers were applied at
 * join), so {@link BirthTraitService#refreshIfChanged} is run for each player. Curses and
 * cross-training are read live each tick / at award time and need no fix-up.
 */
public record SetConfigFlagC2S(String key, boolean value) implements CustomPayload {
    public static final CustomPayload.Id<SetConfigFlagC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "set_config_flag"));

    public static final PacketCodec<RegistryByteBuf, SetConfigFlagC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SetConfigFlagC2S::key,
            PacketCodecs.BOOLEAN, SetConfigFlagC2S::value,
            SetConfigFlagC2S::new);

    /** The only fields this endpoint may ever write. */
    public static final Set<String> ALLOWED = Set.of(
            "enableBirthTraits", "enableCurses", "enableVision", "allowCrossTraining");

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                handle(context.player(), payload.key(), payload.value()));
    }

    private static void handle(ServerPlayerEntity player, String key, boolean value) {
        if (!player.hasPermissionLevel(SetDifficultyC2S.OPERATOR_LEVEL)) {
            Kindreds.LOGGER.warn("[Kindreds] {} tried to change rule '{}' without operator permission",
                    player.getGameProfile().getName(), key);
            player.sendMessage(Text.translatable("kindreds.settings.denied").formatted(Formatting.RED), false);
            SyncConfigS2C.sendTo(player);
            return;
        }
        if (!ALLOWED.contains(key)) {
            Kindreds.LOGGER.warn("[Kindreds] rejected write to non-whitelisted config key '{}'", key);
            SyncConfigS2C.sendTo(player);
            return;
        }
        KindredsConfig c = Kindreds.CONFIG;
        switch (key) {
            case "enableBirthTraits" -> c.enableBirthTraits = value;
            case "enableCurses" -> c.enableCurses = value;
            case "enableVision" -> c.enableVision = value;
            case "allowCrossTraining" -> c.allowCrossTraining = value;
            default -> {
                return;
            }
        }
        // Changing any rule switches the config off its preset onto CUSTOM: the file no longer matches
        // a named difficulty, and silently claiming it still does would be a lie.
        c.difficulty = com.kindreds.config.Difficulty.CUSTOM;
        c.save(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
        Kindreds.LOGGER.info("[Kindreds] {} set {}={}", player.getGameProfile().getName(), key, value);

        MinecraftServer server = player.getServer();
        if (server != null) {
            // Birth traits are applied at join, so an in-flight flip must be pushed to everyone.
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                BirthTraitService.refreshIfChanged(p);
            }
        }
        player.sendMessage(Text.translatable("kindreds.settings.flag_applied",
                Text.translatable("kindreds.settings.flag." + key),
                Text.translatable(value ? "kindreds.settings.on" : "kindreds.settings.off"))
                .formatted(Formatting.GREEN), false);
        SyncConfigS2C.sendToAll(server);
    }
}
