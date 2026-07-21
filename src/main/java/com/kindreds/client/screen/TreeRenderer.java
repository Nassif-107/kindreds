package com.kindreds.client.screen;

import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.Theme;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.ProgressionService;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * Draws the themed background/frame/edges/nodes of the tree canvas. Pure rendering + a couple of
 * small pure helpers (node state, world-&gt;screen transform); all player/tree state is read from
 * the arguments passed in by {@link SkillTreeScreen} - this class holds no state of its own.
 *
 * <h2>DrawContext 1.21.8</h2>
 * Every draw here goes through {@link DrawContext}'s 1.21.8 API (verified against the shipped
 * Mithril Locator HUD): {@code ctx.getMatrices()} returns a 2D {@link Matrix3x2fStack} (push/pop/
 * translate/scale/rotate), {@code ctx.fill}/{@code fillGradient} for flat shapes, and
 * {@code ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, ...)} for textures. "Lines" (edges) and
 * "diamonds" (nodes) have no native primitive, so both are built from a rotated {@code fill()} quad
 * via a push/translate/rotate/pop around the matrix stack, exactly like the compass arrow's rotation
 * in {@code CompassRenderer}.
 */
public final class TreeRenderer {
    private TreeRenderer() {
    }

    /** World-space pixels per {@link SkillNode#pos()} grid unit, before {@link CanvasTransform#zoom}
     * is applied. Public so {@link SkillTreeScreen}'s cursor-anchored zoom math (which needs to
     * invert the same transform outside of {@link CanvasTransform}) uses the identical constant. */
    public static final int GRID_SPACING = 56;

    /** Base node draw radius (half-width of its diamond), before zoom. */
    private static final int NODE_RADIUS = 14;

    /** Capstone nodes (a {@code deedAdvancement} present) draw this much larger - "larger, ornate". */
    private static final float CAPSTONE_SCALE = 1.45f;

    public enum NodeState {
        LOCKED, AVAILABLE, SEALED, OWNED
    }

    /** Maps world node-grid coordinates to screen pixels for the current pan/zoom, and back. Pan is
     * stored in screen pixels (so dragging feels the same regardless of zoom level); zoom scales
     * distance from the canvas's own center point. */
    public record CanvasTransform(int canvasX, int canvasY, int canvasW, int canvasH, float panX, float panY,
                                   float zoom) {
        public float centerX() {
            return canvasX + canvasW / 2f;
        }

        public float centerY() {
            return canvasY + canvasH / 2f;
        }

        public float screenX(int worldX) {
            return centerX() + panX + worldX * GRID_SPACING * zoom;
        }

        public float screenY(int worldY) {
            return centerY() + panY + worldY * GRID_SPACING * zoom;
        }

        /** Inverse of {@link #screenX}/{@link #screenY} - used to keep the point under the cursor
         * fixed while zooming. */
        public float worldXOf(double screenX) {
            return (float) ((screenX - centerX() - panX) / (GRID_SPACING * zoom));
        }

        public float worldYOf(double screenY) {
            return (float) ((screenY - centerY() - panY) / (GRID_SPACING * zoom));
        }
    }

    /** Whether {@code node} is unlockable right now, ignoring its deed (see {@link #stateOf}). */
    private static boolean prereqsAndPointsMet(SkillNode node, KindredData data, SkillTree tree) {
        for (String prereq : node.prereqs()) {
            if (!data.hasNode(prereq)) {
                return false;
            }
        }
        SkillNode.Cost cost = node.cost();
        int available = ProgressionService.pointsAvailable(data, tree, cost.disciplineId());
        if (available < cost.points()) {
            return false;
        }
        // Mirror the server's total-points soft cap so the halo / "N ready" badge can never promise a
        // node the server will refuse. (In multiplayer the client's own config may differ; the server
        // stays authoritative and its rejection toast explains.)
        int cap = com.kindreds.progression.UnlockService.effectiveCap(tree, data);
        return cap <= 0
                || com.kindreds.progression.UnlockService.totalPointsSpent(data, tree) + cost.points() <= cap;
    }

    /**
     * Client-side approximation of a node's unlock state, for rendering only - the server
     * ({@code UnlockService}) remains the sole authority on whether a click actually succeeds.
     * Deliberately does <b>not</b> attempt to determine whether a capstone's deed has actually been
     * earned (the client has no clean, low-risk API to query arbitrary advancement progress from
     * here) - a node with a {@code deedAdvancement} that's otherwise unlockable renders as
     * {@link NodeState#SEALED} regardless of whether the deed is in fact already done; clicking it
     * still attempts {@code RequestUnlockC2S} and the server's real answer comes back via
     * {@code UnlockResultS2C} (shown as a toast by {@code KindredsClient}).
     */
    public static NodeState stateOf(SkillNode node, KindredData data, SkillTree tree) {
        if (data.hasNode(node.id())) {
            return NodeState.OWNED;
        }
        // A specialization whose mutually-exclusive rival is already owned is closed off - the player
        // chose the other path. Shown LOCKED (matching the server's exclusive_conflict rule) so the
        // road not taken visibly shuts, which is the point of a dynamic tree of choices.
        if (isExclusiveClosed(node, data, tree)) {
            return NodeState.LOCKED;
        }
        if (!prereqsAndPointsMet(node, data, tree)) {
            return NodeState.LOCKED;
        }
        return node.deedAdvancement().isPresent() ? NodeState.SEALED : NodeState.AVAILABLE;
    }

    /** Whether {@code node} shares an {@code exclusive_group} with an already-owned node (mirrors
     * {@code UnlockService}'s {@code exclusive_conflict}). */
    private static boolean isExclusiveClosed(SkillNode node, KindredData data, SkillTree tree) {
        if (node.exclusiveGroup().isEmpty()) {
            return false;
        }
        String group = node.exclusiveGroup().get();
        for (SkillNode other : tree.nodes()) {
            if (!other.id().equals(node.id())
                    && other.exclusiveGroup().filter(group::equals).isPresent()
                    && data.hasNode(other.id())) {
                return true;
            }
        }
        return false;
    }

    // --- Background / frame ---------------------------------------------------------------------

    /** Draws the theme's background texture (stretched to fill the canvas) plus a translucent
     * accent-tinted overlay so the same flat texture still reads as "this race's colors" even with
     * placeholder art. Falls back to a flat secondary-tone fill if there's no theme/texture. */
    public static void drawBackground(DrawContext ctx, Theme theme, int x, int y, int w, int h) {
        // Fully procedural (no texture) so there is never a missing-texture fallback: a deep stone/
        // leather base darkened from the race's muted tone, a soft warm accent glow toward the centre,
        // and an edge vignette on all four sides so nodes and text read clearly against it.
        int accent = ThemeAssets.accent(theme);
        int base = ThemeAssets.mix(ThemeAssets.secondary(theme), 0xFF0B0A08, 0.62f);
        ctx.fill(x, y, x + w, y + h, base);

        int glow = ThemeAssets.withAlpha(ThemeAssets.mix(accent, ThemeAssets.secondary(theme), 0.5f), 38);
        ctx.fillGradient(x, y, x + w, y + h / 2, 0x00000000, glow);
        ctx.fillGradient(x, y + h / 2, x + w, y + h, glow, 0x00000000);

        int vy = Math.min(30, h / 4);
        ctx.fillGradient(x, y, x + w, y + vy, 0x99000000, 0x00000000);
        ctx.fillGradient(x, y + h - vy, x + w, y + h, 0x00000000, 0x99000000);

        // Left/right vignette: fillGradient is vertical-only, so emulate the horizontal fade with a
        // few thin translucent strips of decreasing alpha (cheap - a couple dozen fills per side).
        int vx = Math.min(26, w / 4);
        for (int i = 0; i < vx; i++) {
            int a = (int) (0x88 * (1f - (float) i / vx));
            int col = a << 24;
            ctx.fill(x + i, y, x + i + 1, y + h, col);
            ctx.fill(x + w - i - 1, y, x + w - i, y + h, col);
        }
    }

    /** A simple carved/ornate-reading border: an outer accent line, an inner highlight line, and
     * four corner accent dashes - deliberately not a flat single-pixel vanilla panel border. */
    public static void drawFrame(DrawContext ctx, Theme theme, int x, int y, int w, int h) {
        int accent = ThemeAssets.accent(theme);
        ctx.drawBorder(x, y, w, h, accent);
        ctx.drawBorder(x + 2, y + 2, w - 4, h - 4, ThemeAssets.withAlpha(accent, 90));
        int dash = 10;
        int corner = ThemeAssets.ownedColor(theme);
        ctx.fill(x, y, x + dash, y + 2, corner);
        ctx.fill(x, y, x + 2, y + dash, corner);
        ctx.fill(x + w - dash, y, x + w, y + 2, corner);
        ctx.fill(x + w - 2, y, x + w, y + dash, corner);
        ctx.fill(x, y + h - 2, x + dash, y + h, corner);
        ctx.fill(x, y + h - dash, x + 2, y + h, corner);
        ctx.fill(x + w - dash, y + h - 2, x + w, y + h, corner);
        ctx.fill(x + w - 2, y + h - dash, x + w, y + h, corner);
    }

    // --- Edges ----------------------------------------------------------------------------------

    /** Draws every prerequisite edge for {@code tree}, in the theme's edge color, as a chain of
     * rotated {@code fill()} quads (see class javadoc). Edges to/from a node currently off-screen
     * are still drawn (cheap - a handful of fills), only node icons themselves are culled. */
    public static void drawEdges(DrawContext ctx, SkillTree tree, Theme theme, CanvasTransform t) {
        int color = ThemeAssets.edgeColor(theme);
        for (SkillNode node : tree.nodes()) {
            for (String prereqId : node.prereqs()) {
                tree.node(prereqId).ifPresent(prereq ->
                        drawLine(ctx,
                                t.screenX(prereq.pos()[0]), t.screenY(prereq.pos()[1]),
                                t.screenX(node.pos()[0]), t.screenY(node.pos()[1]),
                                Math.max(1.5f, 2f * t.zoom()), color));
            }
        }
    }

    private static void drawLine(DrawContext ctx, float x1, float y1, float x2, float y2, float width, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.5f) {
            return;
        }
        float angle = (float) Math.atan2(dy, dx);

        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x1, y1);
        m.rotate(angle);
        ctx.fill(0, (int) (-width / 2), (int) length, (int) (width / 2) + 1, color);
        m.popMatrix();
    }

    // --- Nodes ------------------------------------------------------------------------------------

    /** On-screen half-width a node's diamond will be drawn at, before culling. Exposed separately
     * from {@link #drawNode} so {@link SkillTreeScreen} can hit-test against the exact same radius
     * the draw call used, computed only once per node per frame. */
    public static float radiusFor(SkillNode node, float zoom) {
        boolean capstone = node.deedAdvancement().isPresent();
        return NODE_RADIUS * zoom * (capstone ? CAPSTONE_SCALE : 1f);
    }

    /** Whether a node at {@code (screenX, screenY)} with the given {@code radius} is entirely
     * outside the canvas rect - i.e. safe to skip drawing/hit-testing entirely. */
    public static boolean isCulled(float screenX, float screenY, float radius,
                                    int canvasX, int canvasY, int canvasW, int canvasH) {
        return screenX + radius < canvasX || screenX - radius > canvasX + canvasW
                || screenY + radius < canvasY || screenY - radius > canvasY + canvasH;
    }

    /** Draws one node as a rotated-square ("diamond") glyph, colored by {@code state}; capstones
     * draw a second, larger outer diamond ring ("seal") behind it. Caller is expected to have
     * already culled via {@link #isCulled} - this always draws. */
    public static void drawNode(DrawContext ctx, SkillNode node, NodeState state, Theme theme,
                                 float screenX, float screenY, float radius, boolean hovered) {
        // A capstone only renders its warning-tinted "seal" ring while still locked/sealed/available -
        // once unlocked (OWNED) it's fully lit like any other owned node, per "owned = fully lit".
        boolean sealed = node.deedAdvancement().isPresent() && state != NodeState.OWNED;

        int color = switch (state) {
            case LOCKED -> ThemeAssets.lockedColor(theme);
            case AVAILABLE -> ThemeAssets.availableColor(theme);
            case SEALED -> ThemeAssets.sealedColor(theme);
            case OWNED -> ThemeAssets.ownedColor(theme);
        };

        if (sealed) {
            drawDiamond(ctx, screenX, screenY, radius * 1.25f, ThemeAssets.withAlpha(ThemeAssets.WARNING_COLOR, 160));
        }
        // "You can take this right now": AVAILABLE nodes breathe a soft outer halo so the eye lands on
        // them immediately. A colour difference alone was too easy to miss across a whole branch.
        if (state == NodeState.AVAILABLE) {
            double pulse = com.kindreds.Kindreds.CONFIG.hudAnimations
                    ? 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 260.0) : 1.0;
            int alpha = (int) (60 + 80 * pulse);
            drawDiamondOutline(ctx, screenX, screenY, radius * (1.30f + 0.10f * (float) pulse),
                    ThemeAssets.withAlpha(ThemeAssets.availableColor(theme), alpha));
        }
        drawDiamond(ctx, screenX, screenY, radius, color);
        int outline = hovered ? 0xFFFFFFFF : ThemeAssets.withAlpha(ThemeAssets.accent(theme), 200);
        drawDiamondOutline(ctx, screenX, screenY, radius, outline);
    }

    /** How many nodes of {@code discipline} the player could unlock right now - drives the
     * "N available" badge on each discipline tab. */
    public static int availableCount(SkillTree tree, KindredData data, net.minecraft.util.Identifier discipline) {
        int n = 0;
        for (SkillNode node : tree.nodes()) {
            if (node.cost().disciplineId().equals(discipline) && stateOf(node, data, tree) == NodeState.AVAILABLE) {
                n++;
            }
        }
        return n;
    }

    private static void drawDiamond(DrawContext ctx, float cx, float cy, float r, int color) {
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.rotate((float) Math.toRadians(45));
        float half = r * 0.6f; // rotated-square half-side roughly matches a circle of radius r
        ctx.fill((int) -half, (int) -half, (int) half, (int) half, color);
        m.popMatrix();
    }

    private static void drawDiamondOutline(DrawContext ctx, float cx, float cy, float r, int color) {
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.rotate((float) Math.toRadians(45));
        float side = r * 1.2f;
        int half = (int) (side / 2);
        ctx.drawBorder(-half, -half, half * 2, half * 2, color);
        m.popMatrix();
    }
}
