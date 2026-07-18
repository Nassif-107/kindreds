package com.kindreds.ability;

import com.kindreds.playerdata.KindredAttachment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * How many EXTRA ticks a player's bow draw advances per game tick - the "swift draw" mechanic that
 * makes the master bow-races charge (and animate) a shot faster. Read by {@code
 * LivingEntityBowSpeedMixin} on both sides so the client animation and the server-authoritative shot
 * power stay in lockstep for the local player: the client reads its own synced race, the server reads
 * the attachment. Elves are the peerless archers of Middle-earth, so they draw at double speed.
 */
public final class BowDrawSpeed {
    private BowDrawSpeed() {
    }

    /** Extra {@code itemUseTimeLeft} decrement per tick while drawing a bow: 0 = normal, 1 = 2x, ... */
    public static int extraTicks(PlayerEntity player) {
        Identifier race;
        if (player.getWorld().isClient) {
            // Lazily hops to a client-only class; never loaded on a dedicated server (isClient=false there).
            race = com.kindreds.client.ClientRaceAccess.localRace(player);
        } else if (player instanceof ServerPlayerEntity serverPlayer) {
            race = KindredAttachment.get(serverPlayer).race();
        } else {
            return 0;
        }
        if (race == null) {
            return 0;
        }
        return switch (race.getPath()) {
            case "elf" -> 1;   // the Eldar: double draw speed
            default -> 0;
        };
    }
}
