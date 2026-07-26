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
    /**
     * Sends the player their own state.
     *
     * <p>Sends a <b>snapshot</b>, not the live attachment. Netty encodes the payload on its own
     * thread, some ticks after this call, walking the very sets the server thread is still editing -
     * unlock a node or earn a deed in that window and the encode dies with a
     * {@link java.util.ConcurrentModificationException}, which drops the packet and can take the
     * connection with it. Copying costs a few small collections per sync and removes the race
     * entirely. (Found by a functional test that unlocked nodes while syncs were in flight.)
     */
    public static void sendTo(ServerPlayerEntity player) {
        KindredData live = KindredAttachment.get(player);
        live.setRace(RaceAccess.getRace(player).orElse(null));
        ServerPlayNetworking.send(player, new SyncKindredDataS2C(snapshot(live)));
    }

    /**
     * A detached copy safe to hand to another thread.
     *
     * Package-private rather than private so a test can assert it carries every field. It grew a
     * field-copying bug once already - see the nodeRanks line below - and the failure was silent
     * at this layer, so it is worth being able to check directly.
     */
    static KindredData snapshot(KindredData live) {
        KindredData copy = new KindredData(
                new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(live.disciplineXp()),
                new java.util.HashSet<>(live.unlockedNodes()),
                live.activeVisionLens(),
                live.corruption(),
                new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(live.cooldowns()),
                new java.util.HashSet<>(live.discoveredBiomes()),
                new java.util.HashSet<>(live.renown()));
        copy.setRace(live.race());
        // ThreatState.copy() deep-copies familyCompetence rather than sharing the reference - the
        // same ConcurrentModificationException hazard this whole method exists to avoid (see the
        // class javadoc) applies equally to that map. copy() is also where familyVoiceKeys (spec
        // §3a's Deeds-page voice lines) is freshly derived from that same live map, right before it
        // is thrown away here - see ThreatState#copy()'s javadoc for the full §3a/§7 reconciliation.
        copy.setThreat(live.threat().copy());
        // Ranks are not a constructor parameter, so they must be copied explicitly - and omitting
        // that had no visible symptom at this layer, because the packet codec carried the map
        // faithfully. It was simply always empty by the time it arrived, so every owned node read
        // as rank 1 on the client. That understated spent points, which surfaced as points the
        // tree offered and the server then refused to spend, and made a rank-3 node's tooltip say
        // "Rank 1 of 3".
        copy.nodeRanks().putAll(live.nodeRanks());
        return copy;
    }
}
