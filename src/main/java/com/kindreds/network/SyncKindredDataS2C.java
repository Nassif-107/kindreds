package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
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

    /**
     * Serializes {@code player}'s current {@link KindredData} and sends it to just that player.
     *
     * <p>Refreshes {@link KindredData#race()} from {@link RaceAccess} first (rather than trusting
     * whatever was last stored) - this is the one place every sync path (join, unlock, respec,
     * {@code OpenTreeC2S}) funnels through, so it's the simplest spot to guarantee the client's
     * mirrored race id never goes stale, without every call site needing its own
     * {@code RaceAccess.getRace} call.
     */
    public static void sendTo(ServerPlayerEntity player) {
        KindredData data = KindredAttachment.get(player);
        data.setRace(RaceAccess.getRace(player).orElse(null));
        ServerPlayNetworking.send(player, new SyncKindredDataS2C(data));
    }
}
