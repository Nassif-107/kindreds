package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * S2C: pushes a player's server-authoritative {@link KindredData} down to their own client, so
 * client-side UI (skill tree screen, HUD) can read it via
 * {@link com.kindreds.playerdata.ClientKindredData}. Sent on {@code ServerPlayConnectionEvents.JOIN}
 * (see {@link com.kindreds.Kindreds}) and whenever server-side logic mutates a player's data.
 */
public record SyncKindredDataS2C(KindredData data) implements CustomPayload {
    public static final CustomPayload.Id<SyncKindredDataS2C> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "sync_kindred_data"));

    public static final PacketCodec<RegistryByteBuf, SyncKindredDataS2C> CODEC = PacketCodec.tuple(
            KindredData.PACKET_CODEC, SyncKindredDataS2C::data,
            SyncKindredDataS2C::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Serializes {@code player}'s current {@link KindredData} and sends it to just that player. */
    public static void sendTo(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new SyncKindredDataS2C(KindredAttachment.get(player)));
    }
}
