package com.kindreds.playerdata;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Registers the persistent per-player {@link KindredData} attachment and provides typed
 * get/set helpers over it.
 *
 * <p>Uses {@code AttachmentRegistry.createPersistent(Identifier, Codec)} (fabric-api 0.130.0,
 * {@code fabric-data-attachment-api-v1}), which persists the attachment to the player's saved NBT
 * across server restarts but does <b>not</b> sync it to the client and does <b>not</b> copy it on
 * death — sync is handled manually via {@link com.kindreds.network.SyncKindredDataS2C} (per this
 * task's brief, rather than the builder's own {@code syncWith(...)}), and death-copy behavior is
 * config-driven and deferred to a later task (Task 11).
 */
public final class KindredAttachment {
    private KindredAttachment() {
    }

    public static final AttachmentType<KindredData> TYPE =
            AttachmentRegistry.createPersistent(Identifier.of(Kindreds.MOD_ID, "player"), KindredData.CODEC);

    /**
     * Forces this class to load (and thus registers {@link #TYPE}) during mod initialization.
     * {@code TYPE} is a lazy {@code static final}, so without an explicit touch here the attachment
     * type isn't registered until the first {@link #get}/{@link #set} at runtime - which is AFTER
     * the server loads player NBT on join, producing "unknown attachment type kindreds:player" and
     * silently dropping the player's saved progress. Call once from {@link Kindreds#onInitialize()}.
     */
    public static AttachmentType<KindredData> init() {
        // Returning TYPE forces this class to load (running the static initializer that registers
        // the attachment). Callers ignore the return; it exists only to make the reference a
        // genuine use the compiler/JIT can't elide.
        return TYPE;
    }

    /** Returns {@code player}'s {@link KindredData}, creating (and attaching) a fresh default
     * instance if none exists yet. */
    public static KindredData get(PlayerEntity player) {
        return player.getAttachedOrCreate(TYPE, KindredData::new);
    }

    public static void set(ServerPlayerEntity player, KindredData data) {
        player.setAttached(TYPE, data);
    }
}
