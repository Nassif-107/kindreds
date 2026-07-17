package com.kindreds.network;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * S2C: reports the outcome of a {@link RequestUnlockC2S} back to the requesting player's client
 * (e.g. so the tree UI can show a toast/error rather than silently doing nothing on failure).
 *
 * @param ok     whether the unlock succeeded
 * @param reason {@code "ok"} on success, otherwise one of {@code UnlockService.UnlockResult}'s
 *               failure reasons (e.g. {@code "insufficient_points"}, {@code "missing_prereq"}).
 */
public record UnlockResultS2C(boolean ok, String reason) implements CustomPayload {
    public static final CustomPayload.Id<UnlockResultS2C> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "unlock_result"));

    public static final PacketCodec<RegistryByteBuf, UnlockResultS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, UnlockResultS2C::ok,
            PacketCodecs.STRING, UnlockResultS2C::reason,
            UnlockResultS2C::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void sendTo(ServerPlayerEntity player, boolean ok, String reason) {
        ServerPlayNetworking.send(player, new UnlockResultS2C(ok, reason));
    }
}
