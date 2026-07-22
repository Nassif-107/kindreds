package com.kindreds.threat;

import com.kindreds.Kindreds;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Whether an {@link Entity} is one of the base Middle-earth mod's hostile mobs, safe to call whether
 * or not that mod is installed.
 *
 * <p>This mod loads standalone (the base mod's jars are {@code modCompileOnly} - compile-time only),
 * so every entry point here is absence-safe by construction, the same two-layer pattern as {@link
 * com.kindreds.playerdata.RaceAccess}:
 * <ol>
 *   <li>{@link FabricLoader#isModLoaded(String)} is checked <b>first</b>. If the base mod ({@code
 *       "middle-earth"}) isn't loaded, this returns {@code false} without ever touching a base-mod
 *       class - no {@link NoClassDefFoundError} risk, since the JVM only verifies/loads a class the
 *       first time a code path referencing it actually executes.</li>
 *   <li>All base-mod class references live in {@link MiddleEarthFoesBridge}, called only after that
 *       check passes, wrapped in {@code catch (Throwable)} here as a second line of defense (covers a
 *       future base-mod release that's "loaded" but has changed/removed the API this relies on).</li>
 * </ol>
 *
 * <p>See {@link MiddleEarthFoesBridge} for exactly which base-mod methods are called and why.
 */
public final class MiddleEarthFoes {
    private MiddleEarthFoes() {
    }

    private static final String MIDDLE_EARTH_MOD_ID = "middle-earth";

    /**
     * Latches the degraded-path warn to once per session: if a future base-mod release breaks this
     * API while still reporting itself as loaded, every combat tick would otherwise re-throw and
     * re-log a full stack trace, which is log spam at combat rate rather than a useful diagnostic
     * past the first occurrence.
     */
    private static volatile boolean warnedOnce = false;

    /**
     * @return whether {@code entity} is a base-mod mob that counts as hostile to {@code player} -
     * always {@code false} if the base mod isn't loaded, or if checking failed unexpectedly.
     */
    public static boolean isHostileBaseMob(Entity entity, ServerPlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded(MIDDLE_EARTH_MOD_ID)) {
            return false;
        }
        try {
            return MiddleEarthFoesBridge.isHostileBaseMob(entity, player);
        } catch (Throwable t) {
            if (!warnedOnce) {
                warnedOnce = true;
                Kindreds.LOGGER.warn(
                        "[Kindreds] failed to check entity {} against the base Middle-earth mod's factions; treating as out of scope",
                        entity.getUuid(), t);
            }
            return false;
        }
    }
}
