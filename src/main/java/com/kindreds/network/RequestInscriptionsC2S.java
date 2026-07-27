package com.kindreds.network;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: "send me the inscription table", raised when the player opens the page.
 *
 * <p>Pull rather than push. The table is a couple of kilobytes that most players will never look at,
 * and sending it to everybody at every login to save a moment for the few who do is the wrong trade.
 * It also means the page always reflects the server as it stands when it is opened, so a datapack
 * reload between opening it twice is visible rather than cached.
 *
 * <p>Carries no fields: the request is the whole message, and anything the handler needs it can read
 * off the connection. A payload with no data still needs a codec, hence the unit-shaped tuple below.
 *
 * <p>No permission check, deliberately - unlike its neighbours in this package this grants nothing
 * and changes nothing. It returns a reference table about recipes the player can already discover by
 * standing at the table and trying words, which is exactly the tedium the page exists to spare them.
 */
public record RequestInscriptionsC2S(boolean unused) implements CustomPayload {
    public static final CustomPayload.Id<RequestInscriptionsC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "request_inscriptions"));

    public static final PacketCodec<RegistryByteBuf, RequestInscriptionsC2S> CODEC =
            PacketCodec.tuple(PacketCodecs.BOOLEAN, RequestInscriptionsC2S::unused,
                    RequestInscriptionsC2S::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                SyncInscriptionsS2C.sendTo(context.player()));
    }
}
