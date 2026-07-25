package com.kindreds.threat;

import com.kindreds.Kindreds;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;

/**
 * Makes a scaling {@link MobScaler} escort a true copy of its base Middle-earth mod NPC leader
 * (same faction, rank and gear), safe to call whether or not the base mod is installed - the same
 * two-layer absence-safe pattern as {@link MiddleEarthFoes}:
 * <ol>
 *   <li>{@link FabricLoader#isModLoaded(String)} is checked before any base-mod class is touched.</li>
 *   <li>All base-mod references live in {@link EscortNpcBridge}, reached only past that gate and
 *       wrapped in {@code catch (Throwable)} here.</li>
 * </ol>
 * A no-op for vanilla-mob leaders (they need no special copy) and when the base mod is absent.
 */
public final class EscortNpcSupport {
    private EscortNpcSupport() {
    }

    private static final String MIDDLE_EARTH_MOD_ID = "middle-earth";

    /** Latched to one warn per session so a broken base-mod API can't spam the log at spawn rate. */
    private static volatile boolean warnedOnce = false;

    /** Stamp {@code leader}'s NPC identity onto {@code escort} so the escort matches its leader's
     * faction/rank/gear instead of re-rolling by location or spawning blank. No-op unless both are
     * the base mod's NPCs and that mod is loaded. */
    public static void copyNpcIdentity(Entity escort, Entity leader) {
        if (!FabricLoader.getInstance().isModLoaded(MIDDLE_EARTH_MOD_ID)) {
            return;
        }
        try {
            EscortNpcBridge.copyNpcIdentity(escort, leader);
        } catch (Throwable t) {
            if (!warnedOnce) {
                warnedOnce = true;
                Kindreds.LOGGER.warn(
                        "[Kindreds] failed to copy NPC identity onto an escort; it may spawn generic", t);
            }
        }
    }
}
