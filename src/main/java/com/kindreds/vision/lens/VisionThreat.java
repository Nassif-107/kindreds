package com.kindreds.vision.lens;

import com.kindreds.Kindreds;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;

/**
 * Client-side "is this creature a threat worth revealing" test for {@link KeenSightLens}, safe to
 * call whether or not the base Middle-earth mod is installed - the same two-layer absence-safe
 * pattern as {@link com.kindreds.playerdata.RaceAccess} and {@link com.kindreds.threat.MiddleEarthFoes}:
 * <ol>
 *   <li>A vanilla {@link Monster} is a threat with no base-mod involvement at all.</li>
 *   <li>{@link FabricLoader#isModLoaded(String)} is checked before any base-mod class is touched, so
 *       there is no {@link NoClassDefFoundError} risk when the base mod is absent.</li>
 *   <li>All base-mod class references live in {@link VisionThreatBridge}, reached only past that gate
 *       and wrapped in {@code catch (Throwable)} (second line of defence against a future base-mod
 *       API change).</li>
 * </ol>
 *
 * <p>A deliberately separate, client-only sibling of {@link com.kindreds.threat.MiddleEarthFoes}:
 * that class's coarse spawn-time check needs the server-side player-faction lookup and lives in the
 * scaling package (which diverges between branches), whereas this one needs only the entity's own
 * faction <b>disposition</b> - resolvable from a {@code ClientWorld} - so it stays self-contained.
 */
public final class VisionThreat {
    private VisionThreat() {
    }

    private static final String MIDDLE_EARTH_MOD_ID = "middle-earth";

    /** Latched to one warn per session so a broken base-mod API can't spam the log at frame rate. */
    private static volatile boolean warnedOnce = false;

    /** @return whether this creature should be lit by keen-sight: a vanilla hostile, or a base-mod
     * EVIL-faction NPC / warg / troll. Never true for animals, allies or neutral folk. */
    public static boolean isThreat(LivingEntity entity) {
        if (entity instanceof Monster) {
            return true;
        }
        if (!FabricLoader.getInstance().isModLoaded(MIDDLE_EARTH_MOD_ID)) {
            return false;
        }
        try {
            return VisionThreatBridge.isHostileFactionMob(entity);
        } catch (Throwable t) {
            if (!warnedOnce) {
                warnedOnce = true;
                Kindreds.LOGGER.warn(
                        "[Kindreds] keen-sight threat check failed for entity {}; not highlighting it",
                        entity.getUuid(), t);
            }
            return false;
        }
    }
}
