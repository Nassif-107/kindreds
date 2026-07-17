package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.ability.ActiveAbilityService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: "activate my active ability." Sent by the client's "Use ability" keybind (see
 * {@code KindredsClient}); the server handler ({@link #registerServerHandler()}) dispatches
 * straight to {@link ActiveAbilityService#activate}.
 *
 * <h2>Client-side ability selection (P1)</h2>
 * {@code abilityId} is always sent as {@code ""} (empty string) by the current client keybind
 * handler. Picking a <b>specific</b> active ability client-side would require the client to
 * replicate server-side tree resolution (matching the player's base-mod race to a
 * {@link com.kindreds.data.SkillTree}) just to find "the first unlocked active ability id" - the
 * base-mod race is an optional-dependency concept the client-side {@code ClientKindredData}
 * mirror doesn't carry, so duplicating that resolution client-side would be both more code and
 * another place for the two sides to disagree. Simpler and just as correct to let the
 * already-authoritative server do that resolution once, in
 * {@link ActiveAbilityService#activate}, which treats a blank {@code abilityId} as "activate my
 * first unlocked active ability" rather than requiring an exact match. A later UI that lets a
 * player choose a specific ability/slot can send a real ability id here with no change to this
 * payload's shape.
 */
public record ActivateAbilityC2S(String abilityId) implements CustomPayload {
    public static final CustomPayload.Id<ActivateAbilityC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "activate_ability"));

    public static final PacketCodec<RegistryByteBuf, ActivateAbilityC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ActivateAbilityC2S::abilityId,
            ActivateAbilityC2S::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Registers the payload type and its server-side handler. Call once from
     * {@link Kindreds#onInitialize()} (a common entrypoint, so this also registers the payload
     * type client-side - required for the client to be able to send it at all - matching
     * {@code RequestUnlockC2S}'s pattern). */
    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);

        // Fabric API guarantees global C2S receivers run on the server thread, so it's safe to
        // mutate player/world state directly here (no extra context.server().execute(...) needed).
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                ActiveAbilityService.activate(context.player(), payload.abilityId()));
    }
}
