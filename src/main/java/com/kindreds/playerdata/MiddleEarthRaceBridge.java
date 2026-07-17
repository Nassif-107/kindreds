package com.kindreds.playerdata;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.sevenstars.middleearth.resources.StateSaverAndLoader;
import net.sevenstars.middleearth.resources.persistent_datas.PlayerData;

/**
 * The <b>only</b> class in this mod allowed to reference base Middle-earth mod types
 * ({@code net.sevenstars.middleearth.*}).
 *
 * <p>The base mod's jars are {@code modCompileOnly} (see {@code build.gradle}): present at compile
 * time (from the dev instance's {@code mods/} folder) but not guaranteed present at runtime, since
 * this mod must also load standalone. Referencing base-mod types triggers JVM class verification
 * for whatever method they appear in the moment that method (not just this class) is first
 * touched, which would throw {@link NoClassDefFoundError} if the base mod's jar isn't on the
 * runtime classpath. Isolating every such reference in this one class - and only ever calling into
 * it after {@link RaceAccess} has confirmed the base mod is loaded - means the JVM never attempts
 * to load {@code net.sevenstars.middleearth.*} classes at all when the base mod is absent, so it
 * never gets the chance to throw. {@link RaceAccess} additionally wraps every call here in
 * {@code catch (Throwable)} as a second line of defense (e.g. against a future base-mod release
 * that renames/removes this API but is still technically "loaded").
 *
 * <h2>What's called, exactly</h2>
 * Both members used below are <b>public</b> base-mod API - no mixin or reflection needed:
 * <ul>
 *   <li>{@code net.sevenstars.middleearth.resources.StateSaverAndLoader.getPlayerState(PlayerEntity)}
 *       - {@code public static}, returns the player's {@code PlayerData} (creating a fresh/empty
 *       one server-side if this is the first time it's been requested for that player).</li>
 *   <li>{@code net.sevenstars.middleearth.resources.persistent_datas.PlayerData.getRace()} -
 *       {@code public}, returns the {@code middle-earth:*} race {@link Identifier} the player
 *       selected during onboarding, or {@code null} if they haven't picked one yet.</li>
 * </ul>
 * Decompiled source consulted (not shipped, base mod is compile-only): both classes and their
 * plain-Identifier-returning {@code getRace()} on {@code PlayerData} are public, so this reads the
 * race directly rather than going through {@code PlayerDataService.getPlayerRace} (which resolves
 * to the base mod's own {@code Race} object and additionally requires a {@code World} - overkill
 * here, since all Task 8 needs is the race id).
 */
final class MiddleEarthRaceBridge {
    private MiddleEarthRaceBridge() {
    }

    /** @return the player's race id, or {@code null} if they have none yet. Never throws on its
     * own account beyond what the base mod's API itself might throw - {@link RaceAccess} is
     * responsible for the try/catch boundary. */
    static Identifier getRace(ServerPlayerEntity player) {
        PlayerData data = StateSaverAndLoader.getPlayerState(player);
        if (data == null) {
            return null;
        }
        return data.getRace();
    }
}
