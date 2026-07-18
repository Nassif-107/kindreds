package com.kindreds.vision.lens;

import com.kindreds.Kindreds;
import com.kindreds.vision.VisionManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Elf's "keen-sight" lens. While active, nearby living creatures are lit with vanilla's glowing
 * entity outline (the same see-through silhouette a spectral arrow paints) so the Eldar sense the
 * living around them even through stone - plus a gentle starlight gamma lift at night.
 *
 * <h2>Why glow, not boxes</h2>
 * The earlier P1 version drew an axis-aligned wireframe {@code VertexRendering.drawBox} per entity -
 * a literal cube that read as programmer-art, not sight. This replaces it with {@link
 * Entity#setGlowing(boolean)}: the client sets the local glow flag on each in-range creature every
 * frame, and vanilla's outline post-pass renders a clean, creature-shaped silhouette that composites
 * over terrain (visible through walls, exactly the intent). We track which entities we forced on so
 * we can switch them back off the instant they leave range or the lens is dropped - never leaving a
 * creature stuck glowing.
 *
 * <p>Threat-colour tinting (hostiles red, friends teal) needs a scoreboard team colour, which is
 * server-owned; the white silhouette is the honest client-only result for now. Faction-aware colour
 * is a later phase and only changes the team lookup, not this scan/toggle framework.
 */
public final class KeenSightLens {
    private KeenSightLens() {
    }

    public static final Identifier ID = Identifier.of(Kindreds.MOD_ID, "keen_sight");

    private static final int DEFAULT_RADIUS = 32;
    private static final int MAX_GLOWING = 48;
    private static final double BOOST_GAMMA = 1.0;
    private static final long NIGHT_START_TICKS = 13000L;
    private static final long NIGHT_END_TICKS = 23000L;

    private static Double savedGamma = null;
    /** Entity ids this lens currently holds the glow flag on, so it can release exactly those again. */
    private static final Set<Integer> GLOWING = new HashSet<>();

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(KeenSightLens::render);
    }

    private static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean live = VisionManager.isLensLive(ID) && mc.player != null && mc.world != null;

        // Gamma boost transitions must be evaluated regardless of `live` so a saved value is always
        // restored (e.g. the lens got deactivated, or Iris just kicked in) rather than left boosted.
        applyNightGammaBoost(mc, live && isNight(mc));

        if (!live) {
            releaseAll(mc);
            return;
        }
        updateGlow(mc);
    }

    private static boolean isNight(MinecraftClient mc) {
        long time = mc.world.getTimeOfDay() % 24000L;
        return time >= NIGHT_START_TICKS && time <= NIGHT_END_TICKS;
    }

    /** Nudges gamma toward {@link #BOOST_GAMMA} while boosting and restores the saved value after -
     * but only if the option still holds the boosted value, so a manual brightness change made
     * mid-boost isn't clobbered on restore. Same pattern as {@code StoneSenseLens}. */
    private static void applyNightGammaBoost(MinecraftClient mc, boolean shouldBoost) {
        SimpleOption<Double> gamma = mc.options.getGamma();
        if (shouldBoost) {
            if (savedGamma == null) {
                savedGamma = gamma.getValue();
                gamma.setValue(BOOST_GAMMA);
            }
        } else if (savedGamma != null) {
            if (gamma.getValue() == BOOST_GAMMA) {
                gamma.setValue(savedGamma);
            }
            savedGamma = null;
        }
    }

    /** Force-restores gamma and drops every forced glow - for paths where {@code render()} can't run
     * again to clean up (e.g. {@code MinecraftClient.world} already went null on disconnect). See
     * {@code VisionManager#onWorldLeave()}. */
    public static void resetGamma(MinecraftClient mc) {
        if (savedGamma != null) {
            mc.options.getGamma().setValue(savedGamma);
            savedGamma = null;
        }
        releaseAll(mc);
    }

    private static int cachedRadius = DEFAULT_RADIUS;
    private static long lastRadiusTick = Long.MIN_VALUE;

    private static int radius(MinecraftClient mc) {
        long time = mc.world.getTime();
        if (time != lastRadiusTick) {
            lastRadiusTick = time;
            cachedRadius = VisionManager.radiusFor(ID, DEFAULT_RADIUS);
        }
        return cachedRadius;
    }

    /** Sets the glow flag on the nearest living creatures within range and clears it on any entity
     * that has since left the set - so the outline tracks the lens exactly. */
    private static void updateGlow(MinecraftClient mc) {
        int radius = radius(mc);
        ClientWorld world = mc.world;
        Vec3d eye = mc.player.getEyePos();
        double radiusSq = (double) radius * radius;
        Box searchBox = mc.player.getBoundingBox().expand(radius);

        List<LivingEntity> nearby = world.getEntitiesByClass(LivingEntity.class, searchBox,
                e -> e != mc.player && e.isAlive() && e.squaredDistanceTo(eye) <= radiusSq);
        nearby.sort((a, b) -> Double.compare(a.squaredDistanceTo(eye), b.squaredDistanceTo(eye)));

        Set<Integer> next = new HashSet<>();
        int count = 0;
        for (LivingEntity entity : nearby) {
            if (count >= MAX_GLOWING) {
                break;
            }
            entity.setGlowing(true);
            next.add(entity.getId());
            count++;
        }

        // Release anyone we were glowing last frame who isn't in range now.
        for (int id : GLOWING) {
            if (!next.contains(id)) {
                Entity e = world.getEntityById(id);
                if (e != null) {
                    e.setGlowing(false);
                }
            }
        }
        GLOWING.clear();
        GLOWING.addAll(next);
    }

    /** Clears the glow flag on every entity this lens forced on. Safe to call when the world is
     * present; ids that no longer resolve are simply dropped. */
    private static void releaseAll(MinecraftClient mc) {
        if (GLOWING.isEmpty()) {
            return;
        }
        ClientWorld world = mc.world;
        if (world != null) {
            for (int id : GLOWING) {
                Entity e = world.getEntityById(id);
                if (e != null) {
                    e.setGlowing(false);
                }
            }
        }
        GLOWING.clear();
    }
}
