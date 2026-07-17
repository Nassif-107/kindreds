package com.kindreds.network;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: "I just opened (or want to refresh) my skill tree screen." Sent by the client's "Open skill
 * tree" keybind ({@code KindredsClient}) right alongside opening {@code SkillTreeScreen} locally -
 * the screen itself renders immediately from whatever {@link com.kindreds.playerdata.ClientKindredData}
 * already has cached (so opening the screen never has to wait on a network round trip), and this
 * packet's only job is to ask the server for a fresh {@link SyncKindredDataS2C} in case the client's
 * mirror is stale (e.g. another session mutated the same attachment, or this is the very first open
 * of the session and the join-time sync raced with something). Carries no fields - it's a pure
 * request marker.
 */
public record OpenTreeC2S() implements CustomPayload {
    public static final CustomPayload.Id<OpenTreeC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "open_tree"));

    public static final PacketCodec<RegistryByteBuf, OpenTreeC2S> CODEC = PacketCodec.unit(new OpenTreeC2S());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Registers the payload type and its server-side handler. Call once from
     * {@link Kindreds#onInitialize()} (a common entrypoint, so this also registers the payload type
     * client-side - required for the client to be able to send it at all - matching
     * {@code RequestUnlockC2S}'s pattern). */
    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);

        // Fabric API guarantees global C2S receivers run on the server thread, so it's safe to
        // touch player/world state directly here (no extra context.server().execute(...) needed).
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                SyncKindredDataS2C.sendTo(context.player()));
    }
}
