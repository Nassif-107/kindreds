package com.kindreds.playerdata;

import com.kindreds.Kindreds;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Reads a player's selected race from the base Middle-earth mod, if it's installed.
 *
 * <p>This mod loads standalone (the base mod's jars are {@code modCompileOnly} - compile-time
 * only), so every entry point here is absence-safe by construction:
 * <ol>
 *   <li>{@link net.fabricmc.loader.api.FabricLoader#isModLoaded(String)} is checked <b>first</b>.
 *       If the base mod ({@code "middle-earth"}) isn't loaded, this returns {@link
 *       Optional#empty()} without ever touching a base-mod class - no {@link NoClassDefFoundError}
 *       risk, since the JVM only verifies/loads a class the first time a code path referencing it
 *       actually executes.</li>
 *   <li>All base-mod class references live in {@link MiddleEarthRaceBridge}, called only after
 *       that check passes, wrapped in {@code catch (Throwable)} here as a second line of defense
 *       (covers a future base-mod release that's "loaded" but has changed/removed the API this
 *       relies on).</li>
 * </ol>
 *
 * <p>See {@link MiddleEarthRaceBridge} for exactly which base-mod method is called.
 */
public final class RaceAccess {
    private RaceAccess() {
    }

    private static final String MIDDLE_EARTH_MOD_ID = "middle-earth";

    /**
     * @return the player's {@code middle-earth:*} race id, or {@link Optional#empty()} if the base
     * mod isn't loaded, the player hasn't picked a race yet, or reading it failed unexpectedly.
     */
    public static Optional<Identifier> getRace(ServerPlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded(MIDDLE_EARTH_MOD_ID)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(MiddleEarthRaceBridge.getRace(player));
        } catch (Throwable t) {
            Kindreds.LOGGER.warn(
                    "[Kindreds] failed to read player {}'s race from the base Middle-earth mod; treating as none",
                    player.getGameProfile().getName(), t);
            return Optional.empty();
        }
    }
}
