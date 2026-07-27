package com.kindreds.network;

import com.google.gson.Gson;
import com.kindreds.Kindreds;
import com.kindreds.inscription.InscriptionIndex;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * S2C: the inscription reference table, so the client can show a page it could not otherwise build.
 *
 * <p>The client is never told what inscription recipes exist. Middle-earth resolves them in its own
 * screen handler and sends down only which words are lit; Minecraft itself stopped shipping full
 * recipe data to clients in 1.21.2. Reading the live registry server-side and pushing the result is
 * therefore the only way for the page to describe the server it is actually connected to, rather
 * than a table baked in at build time that goes stale the first time a datapack changes.
 *
 * <p>Carried as one JSON string, like {@link SyncConfigS2C}: this is display data that changes only
 * on a datapack reload, and a string keeps the payload trivial to extend without rewriting a codec
 * every time a column is added.
 *
 * <p>Sent on request rather than on join - see {@code RequestInscriptionsC2S}. It is a couple of
 * kilobytes that most players will never open, and paying for it at every login to save a moment
 * for the few who do is the wrong trade.
 */
public record SyncInscriptionsS2C(String json) implements CustomPayload {
    public static final CustomPayload.Id<SyncInscriptionsS2C> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "sync_inscriptions"));

    public static final PacketCodec<RegistryByteBuf, SyncInscriptionsS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SyncInscriptionsS2C::json,
            SyncInscriptionsS2C::new);

    private static final Gson GSON = new Gson();

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void sendTo(ServerPlayerEntity player) {
        List<InscriptionIndex.Entry> rows = InscriptionIndex.build(player.getServer());
        ServerPlayNetworking.send(player, new SyncInscriptionsS2C(GSON.toJson(rows)));
    }

    /** Parses the payload back into rows, or an empty list if it cannot be read. */
    public static List<InscriptionIndex.Entry> parse(String json) {
        try {
            InscriptionIndex.Entry[] rows = GSON.fromJson(json, InscriptionIndex.Entry[].class);
            return rows == null ? List.of() : List.of(rows);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
