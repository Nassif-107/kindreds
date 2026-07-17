package com.kindreds.vision;

import com.kindreds.Kindreds;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import java.util.OptionalDouble;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.util.Identifier;

/**
 * A custom "see-through" line {@link RenderLayer}, used by the vision lenses ({@code
 * StoneSenseLens}, {@code KeenSightLens}) to draw outlines that are visible THROUGH occluding
 * terrain, exactly like the sibling Mithril Locator mod's proven {@code LocatorRenderLayers} - this
 * class is a straight copy of it under the {@code kindreds} namespace, since both mods target the
 * same MC/Fabric API version and the underlying Blaze3D pipeline shape hasn't changed.
 *
 * <p>Vanilla's {@link RenderLayer#getLines()} pipeline ({@link RenderPipelines#LINES}) defaults to
 * {@link DepthTestFunction#LEQUAL_DEPTH_TEST}, so lines drawn with it are occluded by opaque blocks
 * in front of the camera. The whole point of stone-sense/keen-sight is to reveal ore/entities
 * through terrain without a literal X-ray of the world, so they must render regardless of what's in
 * front of them (comparable to how F3+B entity hitboxes / vanilla's own "see through" text
 * pipelines ignore depth).
 *
 * <p>This mirrors the exact pattern vanilla itself uses for {@code
 * RenderPipelines.RENDERTYPE_TEXT_SEETHROUGH} (see {@code net.minecraft.client.gl.RenderPipelines}):
 * start from the normal snippet, disable depth writes, and set the depth test function to {@link
 * DepthTestFunction#NO_DEPTH_TEST}.
 */
public final class SeeThroughLayer {
    private SeeThroughLayer() {
    }

    /**
     * Copy of {@link RenderPipelines#LINES} (built from the same {@code RENDERTYPE_LINES_SNIPPET}:
     * same shaders, blend, cull, and vertex format) but with depth testing disabled and depth writes
     * turned off, so the geometry is always drawn on top regardless of occluding terrain.
     */
    private static final RenderPipeline LINES_SEE_THROUGH_PIPELINE = RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
            .withLocation(Identifier.of(Kindreds.MOD_ID, "lines_see_through"))
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .build();

    /**
     * Matches the vanilla {@code RenderLayer.LINES} {@code MultiPhase} definition (buffer size,
     * {@code VIEW_OFFSET_Z_LAYERING} layering phase, line-width phase, {@code ITEM_ENTITY_TARGET})
     * but points at {@link #LINES_SEE_THROUGH_PIPELINE} instead of {@link RenderPipelines#LINES}.
     */
    private static final RenderLayer.MultiPhase LINES_SEE_THROUGH = RenderLayer.of(
            "kindreds_lines_see_through",
            1536,
            LINES_SEE_THROUGH_PIPELINE,
            RenderLayer.MultiPhaseParameters.builder()
                    .lineWidth(new RenderPhase.LineWidth(OptionalDouble.empty()))
                    .layering(RenderPhase.VIEW_OFFSET_Z_LAYERING)
                    .target(RenderPhase.ITEM_ENTITY_TARGET)
                    .build(false)
    );

    /** The see-through line layer to use in place of {@link RenderLayer#getLines()}. Registered
     * once, statically, on class load. */
    public static RenderLayer getSeeThroughLines() {
        return LINES_SEE_THROUGH;
    }
}
