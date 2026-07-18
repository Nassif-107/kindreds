package com.kindreds.client;

import com.kindreds.playerdata.ClientKindredData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Client-only bridge to the local player's synced race. Referenced ONLY from the {@code isClient}
 * branch of {@link com.kindreds.ability.BowDrawSpeed}, so this class (and the client-only {@link
 * MinecraftClient}/{@link ClientKindredData} it touches) is never loaded server-side - the JVM loads
 * it lazily, on first execution of that branch, which never runs on a dedicated server. Returns the
 * race only for the local player: other players' client-side draw prediction isn't ours to speed.
 */
@Environment(EnvType.CLIENT)
public final class ClientRaceAccess {
    private ClientRaceAccess() {
    }

    public static Identifier localRace(PlayerEntity player) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player == mc.player) {
            return ClientKindredData.INSTANCE.race();
        }
        return null;
    }
}
