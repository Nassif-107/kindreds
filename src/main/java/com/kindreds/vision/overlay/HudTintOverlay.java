package com.kindreds.vision.overlay;

import com.kindreds.Kindreds;
import com.kindreds.vision.VisionManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

/**
 * Full-screen tint element for the vision framework, registered via {@code HudElementRegistry}
 * (never the deprecated {@code HudRenderCallback}) so it draws in the GUI pass - after any Iris
 * shader pipeline has already run, per {@code research/lore/vision-rendering-tech-1.21.8.md}
 * ("HUD overlays are Iris-safe"). Unlike the outline lenses, this never needs to check {@link
 * VisionManager#shaderPackActive()}.
 *
 * <p>P1 usage is deliberately minimal - a faint tint whenever any lens is active, just enough to
 * give the player a persistent cue their vision is altered - and it exists mainly as the seam later
 * HUD-driven lenses (e.g. an Orc daylight vignette, a Hobbit fear desaturation) will hang their own
 * {@code fillGradient}/tint logic off of.
 */
public final class HudTintOverlay {
    private HudTintOverlay() {
    }

    /** ARGB, ~4% white overlay - a bare presence cue, not an obstruction. */
    private static final int TINT_ARGB = 0x0AFFFFFF;

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.of(Kindreds.MOD_ID, "vision_tint"),
                HudTintOverlay::render);
    }

    private static void render(DrawContext ctx, RenderTickCounter tick) {
        if (VisionManager.activeLens() == null) {
            return;
        }
        ctx.fill(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), TINT_ARGB);
    }
}
