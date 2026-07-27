package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.config.KindredsConfig;
import com.kindreds.config.Menace;
import com.kindreds.config.RuleDial;
import com.kindreds.config.ScalingCurve;
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

/**
 * C2S: set one <b>numeric</b> enemy-difficulty setting from the rules screen - the counterpart to
 * {@link SetConfigFlagC2S}, which can only carry booleans.
 *
 * <p>This is what makes {@code CUSTOM} mean something. The screen's "The world answers" section was
 * render-only, so choosing the hand-tuned preset selected a mode in which nothing was preset-driven
 * and nothing was editable either - the numbers could only be changed from a console.
 *
 * <p>Two guards, both server-side, because this handler is reachable by any crafted packet:
 * <ol>
 *   <li><b>Operator permission</b> - the same level the commands and the sibling handlers require.</li>
 *   <li><b>A key whitelist with per-key bounds</b> - {@link RuleDial} enumerates every field this
 *       endpoint may write and clamps the value on arrival. Deliberately not a generic "set any
 *       config field by name" endpoint, which would let a client rewrite xp rates, the respec item,
 *       or anything added to the config later. {@link ScalingCurve} is the one non-{@code RuleDial}
 *       key, carried as an ordinal and bounds-checked against the enum's own length.</li>
 * </ol>
 *
 * <p>Any successful write switches {@code menace} onto {@link Menace#CUSTOM}: the config no longer
 * matches whatever named preset it claimed, and continuing to display that name would be a lie. The
 * pacing preset ({@code difficulty}) is deliberately left alone - these fields are not part of it.
 */
public record SetConfigValueC2S(String key, double value) implements CustomPayload {
    public static final CustomPayload.Id<SetConfigValueC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "set_config_value"));

    public static final PacketCodec<RegistryByteBuf, SetConfigValueC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SetConfigValueC2S::key,
            PacketCodecs.DOUBLE, SetConfigValueC2S::value,
            SetConfigValueC2S::new);

    /** The one editable setting that is an enum rather than a number; carried as its ordinal. */
    public static final String SCALING_CURVE_KEY = "scalingCurve";

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                handle(context.player(), payload.key(), payload.value()));
    }

    private static void handle(ServerPlayerEntity player, String key, double value) {
        if (!player.hasPermissionLevel(SetDifficultyC2S.OPERATOR_LEVEL)) {
            Kindreds.LOGGER.warn("[Kindreds] {} tried to change rule value '{}' without operator permission",
                    player.getGameProfile().getName(), key);
            player.sendMessage(Text.translatable("kindreds.settings.denied").formatted(Formatting.RED), false);
            SyncConfigS2C.sendTo(player);
            return;
        }
        KindredsConfig c = Kindreds.CONFIG;
        String shown;

        if (SCALING_CURVE_KEY.equals(key)) {
            ScalingCurve[] curves = ScalingCurve.values();
            // Bounds-checked against the enum rather than trusted: an out-of-range ordinal from a
            // crafted packet would otherwise be an ArrayIndexOutOfBoundsException in a network thread.
            int ordinal = (int) Math.round(value);
            if (ordinal < 0 || ordinal >= curves.length) {
                SyncConfigS2C.sendTo(player);
                return;
            }
            c.scalingCurve = curves[ordinal];
            shown = Text.translatable("kindreds.settings.curve." + c.scalingCurve).getString();
        } else {
            RuleDial dial = RuleDial.byKey(key);
            if (dial == null) {
                Kindreds.LOGGER.warn("[Kindreds] rejected write to non-whitelisted rule value '{}'", key);
                SyncConfigS2C.sendTo(player);
                return;
            }
            dial.write(c, value);
            shown = dial.format(dial.read(c));
        }

        // The file no longer matches whatever preset it named - say so rather than keep the label.
        c.menace = Menace.CUSTOM;
        c.save(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
        Kindreds.LOGGER.info("[Kindreds] {} set {}={}", player.getGameProfile().getName(), key, shown);
        player.sendMessage(Text.translatable("kindreds.settings.value_applied",
                        Text.translatable("kindreds.settings." + key), Text.literal(shown))
                .formatted(Formatting.GREEN), false);
        SyncConfigS2C.sendToAll(player.getServer());
    }
}
