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

    /** Extra {@code itemUseTimeLeft} decrement per tick while drawing a bow: 0 = normal, 1 = 2x, 2 = 3x.
     * Elves draw fast by nature; the {@code swift_draw} skill-tree perk stacks on top (so a Wood-elf
     * who trains it draws faster still). Read on both sides so the local player's animation matches the
     * server's shot charge. */
    public static int extraTicks(PlayerEntity player) {
        Identifier race;
        boolean swiftDrawPerk;
        if (player.getWorld().isClient) {
            // Lazily hops to client-only classes; never loaded on a dedicated server (isClient=false there).
            race = com.kindreds.client.ClientRaceAccess.localRace(player);
            swiftDrawPerk = com.kindreds.client.ClientPerkAccess.localHasPerk(player, "swift_draw");
        } else if (player instanceof ServerPlayerEntity serverPlayer) {
            race = KindredAttachment.get(serverPlayer).race();
            swiftDrawPerk = !PerkService.perksOfType(serverPlayer, "swift_draw").isEmpty();
        } else {
            return 0;
        }
        int extra = 0;
        if (race != null && race.getPath().equals("elf")) {
            extra += 1; // the Eldar: innately swift of hand
        }
        if (swiftDrawPerk) {
            extra += 1; // trained swift-draw
        }
        return extra;
    }
}
