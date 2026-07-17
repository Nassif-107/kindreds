package com.kindreds.vision.lens;

import com.kindreds.Kindreds;
import com.kindreds.vision.SeeThroughLayer;
import com.kindreds.vision.VisionManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/**
 * Elf's "keen-sight" lens: while active, outlines nearby living entities (see-through, via {@link
 * SeeThroughLayer}) within a radius of the player - hostile mobs tinted red, everything else
 * (players, passive/neutral mobs) tinted blue-green - plus a gentle starlight gamma lift at night.
 *
 * <p>P1 threat tint is deliberately coarse: {@code entity instanceof} {@link Monster} (vanilla's own
 * "is a hostile mob" marker interface) is the only signal. True faction/diplomacy-aware tinting
 * (reading the base mod's reputation system through {@code RaceAccess}) is a later phase per the
 * task brief - the scan/render framework here doesn't need to change for that, only the color
 * lookup in {@link #drawOutlines}.
 */
public final class KeenSightLens {
    private KeenSightLens() {
    }

    public static final Identifier ID = Identifier.of(Kindreds.MOD_ID, "keen_sight");

    private static final int DEFAULT_RADIUS = 32;
    private static final int MAX_OUTLINES = 48;
    private static final float FADE_NEAR = 4f;
    private static final float FADE_FAR = 40f;
    private static final double BOOST_GAMMA = 1.0;
    private static final long NIGHT_START_TICKS = 13000L;
    private static final long NIGHT_END_TICKS = 23000L;

    private static Double savedGamma = null;

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
            return;
        }
        drawOutlines(ctx, mc);
    }

    private static boolean isNight(MinecraftClient mc) {
        long time = mc.world.getTimeOfDay() % 24000L;
        return time >= NIGHT_START_TICKS && time <= NIGHT_END_TICKS;
    }

    /** Nudges {@code mc.options}'s gamma toward {@link #BOOST_GAMMA} while {@code shouldBoost}, and
     * restores the value it saved once {@code shouldBoost} goes false again - but only if the
     * option still holds the boosted value, so a manual brightness change made mid-boost by the
     * player isn't silently clobbered on restore. Same pattern as {@code StoneSenseLens}'s
     * underground boost. */
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

    /** Force-restores gamma to the value saved before this lens boosted it, regardless of whether
     * the option still holds the boosted value, and clears the saved state - a no-op if this lens
     * never boosted gamma. Unlike {@link #applyNightGammaBoost}, this doesn't depend on {@code
     * render()} running again to fire: it exists specifically for paths where it can't (e.g. {@code
     * MinecraftClient.world} has already gone {@code null} on disconnect, so {@link
     * WorldRenderEvents#AFTER_TRANSLUCENT} stops firing entirely). See {@code
     * VisionManager#onWorldLeave()}. */
    public static void resetGamma(MinecraftClient mc) {
        if (savedGamma != null) {
            mc.options.getGamma().setValue(savedGamma);
            savedGamma = null;
        }
    }

    /** Cached {@link VisionManager#radiusFor(Identifier, int)} result, recomputed at most once per
     * game tick rather than every render frame - matches {@code StoneSenseLens}'s scan throttle in
     * spirit (registry lookup + unlocked-node walk is comparatively expensive and this lens, unlike
     * stone-sense, isn't otherwise throttled since its entity scan runs every frame). */
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

    private static void drawOutlines(WorldRenderContext ctx, MinecraftClient mc) {
        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null) {
            return;
        }
        int radius = radius(mc);
        ClientWorld world = mc.world;
        Vec3d eye = mc.player.getEyePos();
        double radiusSq = (double) radius * radius;
        Box searchBox = mc.player.getBoundingBox().expand(radius);

        List<LivingEntity> nearby = world.getEntitiesByClass(LivingEntity.class, searchBox,
                e -> e != mc.player && e.isAlive() && e.squaredDistanceTo(eye) <= radiusSq);
        if (nearby.isEmpty()) {
            return;
        }
        nearby.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(eye)));
        if (nearby.size() > MAX_OUTLINES) {
            nearby = nearby.subList(0, MAX_OUTLINES);
        }

        Vec3d cam = ctx.camera().getPos();
        VertexConsumerProvider.Immediate vcp = mc.getBufferBuilders().getEntityVertexConsumers();
        RenderLayer layer = SeeThroughLayer.getSeeThroughLines();
        VertexConsumer lines = vcp.getBuffer(layer);

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        for (LivingEntity entity : nearby) {
            Box box = entity.getBoundingBox().expand(0.05);
            if (ctx.frustum() != null && !ctx.frustum().isVisible(box)) {
                continue; // frustum cull: skip boxes behind/outside the camera view
            }
            double dist = Math.sqrt(entity.squaredDistanceTo(eye));
            float alpha = MathHelper.clamp((float) (1.0 - (dist - FADE_NEAR) / (FADE_FAR - FADE_NEAR)), 0.15f, 1.0f);
            if (entity instanceof Monster) {
                VertexRendering.drawBox(matrices, lines, box, 0.95f, 0.2f, 0.2f, alpha); // hostile: red
            } else {
                VertexRendering.drawBox(matrices, lines, box, 0.25f, 0.9f, 0.7f, alpha); // friend/neutral: blue-green
            }
        }
        matrices.pop();
        vcp.draw(layer);
    }
}
