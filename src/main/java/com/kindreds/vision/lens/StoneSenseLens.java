package com.kindreds.vision.lens;

import com.kindreds.Kindreds;
import com.kindreds.vision.SeeThroughLayer;
import com.kindreds.vision.VisionManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dwarf's "stone-sense" lens: while active, outlines nearby ore blocks (see-through, via {@link
 * SeeThroughLayer}) within a radius of the player, and lifts render gamma while underground so the
 * cavity/ore reveal is actually legible in the dark.
 *
 * <h2>Scan strategy</h2>
 * Copies the palette-prescreen trick the Mithril Locator mod's {@code OreScanner} uses: for every
 * loaded {@link ChunkSection} in range, the section's block palette is cheaply tested via {@code
 * PalettedContainer#hasAny} for whether it contains anything matching {@link #ORE_TAG} at all,
 * before paying for the 4096-block inner loop - most stone/dirt sections skip out immediately.
 * Unlike {@code OreScanner}, this doesn't need a multi-tick resume cursor: the scan radius here is
 * small (a handful of chunks) and it only runs once every {@link #SCAN_INTERVAL_TICKS} ticks, so a
 * full pass is cheap enough to always finish within a single frame.
 */
public final class StoneSenseLens {
    private StoneSenseLens() {
    }

    public static final Identifier ID = Identifier.of(Kindreds.MOD_ID, "stone_sense");

    private static final TagKey<Block> ORE_TAG = TagKey.of(RegistryKeys.BLOCK, Identifier.of("c", "ores"));
    private static final int DEFAULT_RADIUS = 24;
    private static final int MAX_OUTLINES = 64;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final float FADE_NEAR = 4f;
    private static final float FADE_FAR = 28f;
    private static final double BOOST_GAMMA = 1.0;

    private static List<BlockPos> matches = List.of();
    private static long lastScanTick = Long.MIN_VALUE;
    private static Double savedGamma = null;

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(StoneSenseLens::render);
    }

    private static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean live = VisionManager.isLensLive(ID) && mc.player != null && mc.world != null;

        // Gamma boost transitions must be evaluated regardless of `live` so a saved value is always
        // restored (e.g. the lens got deactivated, or Iris just kicked in) rather than left boosted.
        applyUndergroundGammaBoost(mc, live && isUnderground(mc));

        if (!live) {
            return;
        }
        maybeRescan(mc);
        drawOutlines(ctx, mc);
    }

    private static boolean isUnderground(MinecraftClient mc) {
        BlockPos pos = mc.player.getBlockPos();
        return mc.world.getLightLevel(LightType.SKY, pos) <= 0 && pos.getY() < mc.world.getSeaLevel();
    }

    /** Nudges {@code mc.options}'s gamma toward {@link #BOOST_GAMMA} while {@code shouldBoost}, and
     * restores the value it saved once {@code shouldBoost} goes false again - but only if the
     * option still holds the boosted value, so a manual brightness change made mid-boost by the
     * player isn't silently clobbered on restore. */
    private static void applyUndergroundGammaBoost(MinecraftClient mc, boolean shouldBoost) {
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

    private static void maybeRescan(MinecraftClient mc) {
        long time = mc.world.getTime();
        if (time - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }
        lastScanTick = time;
        matches = scan(mc);
    }

    private static List<BlockPos> scan(MinecraftClient mc) {
        ClientWorld world = mc.world;
        BlockPos center = mc.player.getBlockPos();
        int radius = VisionManager.radiusFor(ID, DEFAULT_RADIUS);
        double radiusSq = (double) radius * radius;
        int chunkRadius = (radius >> 4) + 1;
        ChunkPos centerChunk = new ChunkPos(center);

        List<BlockPos> found = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                scanChunk(world, centerChunk.x + dx, centerChunk.z + dz, center, radiusSq, found);
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        if (found.size() > MAX_OUTLINES) {
            found = found.subList(0, MAX_OUTLINES);
        }
        return found;
    }

    private static void scanChunk(ClientWorld world, int chunkX, int chunkZ, BlockPos center, double radiusSq,
                                   List<BlockPos> found) {
        if (!(world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) instanceof WorldChunk chunk)) {
            return;
        }
        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) {
                continue;
            }
            if (!section.getBlockStateContainer().hasAny(state -> state.isIn(ORE_TAG))) {
                continue;
            }
            int baseY = chunk.getBottomY() + (i << 4);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!state.isIn(ORE_TAG)) {
                            continue;
                        }
                        BlockPos pos = new BlockPos((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);
                        if (pos.getSquaredDistance(center) <= radiusSq) {
                            found.add(pos);
                        }
                    }
                }
            }
        }
    }

    private static void drawOutlines(WorldRenderContext ctx, MinecraftClient mc) {
        if (matches.isEmpty()) {
            return;
        }
        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null) {
            return;
        }
        Vec3d cam = ctx.camera().getPos();
        BlockPos playerPos = mc.player.getBlockPos();
        VertexConsumerProvider.Immediate vcp = mc.getBufferBuilders().getEntityVertexConsumers();
        RenderLayer layer = SeeThroughLayer.getSeeThroughLines();
        VertexConsumer lines = vcp.getBuffer(layer);

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        for (BlockPos pos : matches) {
            Box box = new Box(pos).expand(0.02);
            if (ctx.frustum() != null && !ctx.frustum().isVisible(box)) {
                continue; // frustum cull: skip boxes behind/outside the camera view
            }
            double dist = Math.sqrt(pos.getSquaredDistance(playerPos));
            float alpha = MathHelper.clamp((float) (1.0 - (dist - FADE_NEAR) / (FADE_FAR - FADE_NEAR)), 0.15f, 1.0f);
            VertexRendering.drawBox(matrices, lines, box, 0.2f, 0.85f, 0.75f, alpha); // teal outline
        }
        matrices.pop();
        vcp.draw(layer);
    }
}
