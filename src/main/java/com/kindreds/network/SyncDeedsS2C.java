package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.progression.DeedIndex;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.Identifier;

import java.util.Map;

/**
 * S2C: what each Great Deed asks of you, so the Deeds page can tell the player how to earn one.
 *
 * <p>Sent once, on join. The deed criteria are datapack data and do not change while a player is
 * connected; whether a deed is <em>done</em> is a different question, and the client already knows
 * that from {@link com.kindreds.playerdata.KindredData#renown()} in the ordinary data sync.
 *
 * <p>The lines travel as {@link Text}, not as strings: they are built from translatable parts on the
 * server and resolved into words by the client, so a Russian client reads Russian off an English
 * server.
 */
public record SyncDeedsS2C(Map<String, Text> requirements) implements CustomPayload {
    public static final CustomPayload.Id<SyncDeedsS2C> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "sync_deeds"));

    public static final PacketCodec<RegistryByteBuf, SyncDeedsS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.map(java.util.LinkedHashMap::new, PacketCodecs.STRING, TextCodecs.PACKET_CODEC),
            SyncDeedsS2C::requirements,
            SyncDeedsS2C::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void sendTo(ServerPlayerEntity player) {
        if (player == null || player.getServer() == null) {
            return;
        }
        Map<String, Text> lines = DeedIndex.requirements(player.getServer());
        if (!lines.isEmpty()) {
            ServerPlayNetworking.send(player, new SyncDeedsS2C(lines));
        }
    }
}
