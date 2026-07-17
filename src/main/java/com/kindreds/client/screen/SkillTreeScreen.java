package com.kindreds.client.screen;

import com.kindreds.data.Discipline;
import com.kindreds.data.Disciplines;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.Theme;
import com.kindreds.network.OpenTreeC2S;
import com.kindreds.network.RequestUnlockC2S;
import com.kindreds.network.RespecC2S;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.ProgressionService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The lore-themed skill-tree screen: a pannable/zoomable node canvas (left ~75%) over the race's
 * themed background, plus a "character page" side panel (right ~25%) with discipline gauges, the
 * active vision lens, titles, and a respec button. See package javadoc / Task 11 brief for the full
 * design; this class owns layout, input (pan/zoom/click), and pulling state together each frame -
 * actual drawing is delegated to {@link TreeRenderer} (canvas) and {@link NodeTooltip} (hover card).
 *
 * <h2>Live data, not a snapshot</h2>
 * The {@link KindredData} passed to the constructor is only a starting point - every frame re-reads
 * {@link ClientKindredData#INSTANCE} instead (see {@link #currentData()}), so the screen reflects
 * unlocks/respecs the instant the server's {@code SyncKindredDataS2C} lands, with no need to
 * recreate the screen.
 *
 * <h2>No race / no tree</h2>
 * If the player hasn't chosen a race yet (or the base mod isn't installed), {@code tree} is
 * {@code null} and the screen shows a gentle prompt instead of a canvas (see {@link #open}).
 */
public class SkillTreeScreen extends Screen {
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 2.2f;

    private final SkillTree tree;
    private final Theme theme;
    private final KindredData initialData;

    private float panX;
    private float panY;
    private float zoom = 1.0f;

    private boolean dragging;
    private boolean suppressDragThisPress;

    private int canvasX, canvasY, canvasW, canvasH;
    private int panelX, panelY, panelW, panelH;
    private int[] respecButton = new int[4]; // x, y, w, h

    private SkillNode hoveredNode;
    private TreeRenderer.NodeState hoveredNodeState;
    private final List<NodeHit> nodeHits = new ArrayList<>();

    private record NodeHit(SkillNode node, float x, float y, float radius) {
    }

    public SkillTreeScreen(SkillTree tree, KindredData data, Theme theme) {
        super(Text.literal(tree != null ? NodeTooltip.displayName(tree.race().getPath()) + " - Skill Tree"
                : "Skill Tree"));
        this.tree = tree;
        this.theme = theme;
        this.initialData = data;
    }

    /** Resolves the current player's race -&gt; {@link SkillTree} -&gt; {@link Theme} from the
     * client-mirrored synced registries and opens the screen (or a no-race prompt if unresolved).
     * Called by {@code KindredsClient}'s "Open skill tree" keybind, which also fires
     * {@link OpenTreeC2S} alongside this so the server refreshes the client's data. */
    public static void open(MinecraftClient client) {
        KindredData data = ClientKindredData.INSTANCE;
        Identifier race = data.race();
        SkillTree tree = null;
        Theme theme = null;

        if (race != null && client.world != null) {
            try {
                Registry<SkillTree> trees = client.world.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
                for (SkillTree candidate : trees) {
                    if (candidate.race().equals(race)) {
                        tree = candidate;
                        break;
                    }
                }
                if (tree != null) {
                    Registry<Theme> themes = client.world.getRegistryManager().getOrThrow(KindredsRegistries.THEME);
                    theme = themes.get(tree.theme());
                }
            } catch (RuntimeException e) {
                // Registries not synced yet - fall through to the no-tree prompt.
            }
        }

        client.setScreen(new SkillTreeScreen(tree, data, theme));
    }

    private KindredData currentData() {
        KindredData live = ClientKindredData.INSTANCE;
        return live != null ? live : initialData;
    }

    // --- Layout -----------------------------------------------------------------------------------

    @Override
    protected void init() {
        canvasX = 8;
        canvasY = 8;
        canvasW = Math.max(100, (int) (width * 0.75f) - 12);
        canvasH = height - 16;
        panelX = canvasX + canvasW + 8;
        panelY = 8;
        panelW = Math.max(80, width - panelX - 8);
        panelH = height - 16;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // --- Render -------------------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float deltaTicks) {
        this.renderBackground(ctx, mouseX, mouseY, deltaTicks);

        if (tree == null) {
            renderNoRacePrompt(ctx);
            super.render(ctx, mouseX, mouseY, deltaTicks);
            return;
        }

        KindredData data = currentData();

        TreeRenderer.drawBackground(ctx, theme, canvasX, canvasY, canvasW, canvasH);

        ctx.enableScissor(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH);
        TreeRenderer.CanvasTransform t =
                new TreeRenderer.CanvasTransform(canvasX, canvasY, canvasW, canvasH, panX, panY, zoom);
        TreeRenderer.drawEdges(ctx, tree, theme, t);

        nodeHits.clear();
        hoveredNode = null;
        for (SkillNode node : tree.nodes()) {
            float sx = t.screenX(node.pos()[0]);
            float sy = t.screenY(node.pos()[1]);
            float radius = TreeRenderer.radiusFor(node, zoom);
            if (TreeRenderer.isCulled(sx, sy, radius, canvasX, canvasY, canvasW, canvasH)) {
                continue;
            }
            boolean hovered = distance(mouseX, mouseY, sx, sy) <= radius
                    && mouseX >= canvasX && mouseX <= canvasX + canvasW
                    && mouseY >= canvasY && mouseY <= canvasY + canvasH;
            TreeRenderer.NodeState state = TreeRenderer.stateOf(node, data, tree);
            if (hovered) {
                hoveredNode = node;
                hoveredNodeState = state;
            }
            TreeRenderer.drawNode(ctx, node, state, theme, sx, sy, radius, hovered);
            nodeHits.add(new NodeHit(node, sx, sy, radius));
        }
        ctx.disableScissor();

        TreeRenderer.drawFrame(ctx, theme, canvasX, canvasY, canvasW, canvasH);

        renderSidePanel(ctx, data, mouseX, mouseY);

        if (hoveredNode != null) {
            NodeTooltip.render(ctx, MinecraftClient.getInstance(), hoveredNode, hoveredNodeState, tree, theme,
                    mouseX, mouseY, width, height);
        }

        super.render(ctx, mouseX, mouseY, deltaTicks);
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2, dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private void renderNoRacePrompt(DrawContext ctx) {
        Text msg = Text.literal("Choose your people first (use the Player's Book).");
        int w = textRenderer.getWidth(msg);
        ctx.drawText(textRenderer, msg, (width - w) / 2, height / 2, 0xFFDDDDDD, true);
    }

    // --- Side panel -----------------------------------------------------------------------------

    private void renderSidePanel(DrawContext ctx, KindredData data, int mouseX, int mouseY) {
        TreeRenderer.drawBackground(ctx, theme, panelX, panelY, panelW, panelH);
        TreeRenderer.drawFrame(ctx, theme, panelX, panelY, panelW, panelH);

        int accent = ThemeAssets.accent(theme);
        int textX = panelX + 10;
        int y = panelY + 10;

        String raceName = NodeTooltip.displayName(tree.race().getPath());
        ctx.fill(textX, y, textX + 16, y + 16, ThemeAssets.ownedColor(theme));
        ctx.drawBorder(textX, y, 16, 16, accent);
        ctx.drawText(textRenderer, Text.literal(raceName), textX + 22, y + 4, 0xFFFFFFFF, true);
        y += 26;

        ctx.drawText(textRenderer, Text.literal("Disciplines"), textX, y, accent, false);
        y += 12;

        MinecraftClient client = MinecraftClient.getInstance();
        Registry<Discipline> disciplines = null;
        if (client.world != null) {
            try {
                disciplines = client.world.getRegistryManager().getOrThrow(KindredsRegistries.DISCIPLINE);
            } catch (RuntimeException ignored) {
                // Not synced yet - gauges fall back to id-derived names/colors below.
            }
        }

        int gaugeWidth = panelW - 20;
        for (String path : Disciplines.ALL) {
            Identifier disciplineId = Identifier.of("kindreds", path);
            int level = ProgressionService.pointsForLevel(data.xpIn(disciplineId));
            int spent = ProgressionService.pointsSpent(data, tree, disciplineId);
            int available = Math.max(0, level - spent);

            Discipline discipline = disciplines != null ? disciplines.get(disciplineId) : null;
            String name = discipline != null ? discipline.name() : NodeTooltip.displayName(path);
            int color = discipline != null ? ThemeAssets.opaque(discipline.colorInt()) : accent;

            ctx.drawText(textRenderer, Text.literal(name + "  " + spent + "/" + level
                    + (available > 0 ? " (+" + available + ")" : "")), textX, y, 0xFFE0E0E0, false);
            y += 9;
            int barH = 5;
            ctx.fill(textX, y, textX + gaugeWidth, y + barH, 0xFF202020);
            // Filled portion = spent / level (points spent can't exceed points earned in practice,
            // but clamp defensively against desync).
            int filled = level <= 0 ? 0 : Math.round(gaugeWidth * Math.min(1f, spent / (float) level));
            ctx.fill(textX, y, textX + filled, y + barH, color);
            ctx.drawBorder(textX, y, gaugeWidth, barH, ThemeAssets.withAlpha(accent, 140));
            y += barH + 6;
        }

        y += 6;
        ctx.drawText(textRenderer, Text.literal("Vision"), textX, y, accent, false);
        y += 12;
        Identifier lens = data.activeVisionLens();
        String lensName = lens != null ? NodeTooltip.displayName(lens.getPath()) : "None equipped";
        ctx.drawText(textRenderer, Text.literal(lensName), textX, y, 0xFFE0E0E0, false);
        y += 18;

        ctx.drawText(textRenderer, Text.literal("Titles"), textX, y, accent, false);
        y += 12;
        String titles = data.titles().isEmpty() ? "None earned yet" : String.join(", ", data.titles());
        for (var line : textRenderer.wrapLines(Text.literal(titles), gaugeWidth)) {
            ctx.drawText(textRenderer, line, textX, y, 0xFFE0E0E0, false);
            y += 10;
        }

        int btnH = 20;
        int btnY = panelY + panelH - btnH - 10;
        respecButton = new int[]{textX, btnY, gaugeWidth, btnH};
        boolean hovered = isWithin(respecButton, mouseX, mouseY);
        ctx.fill(respecButton[0], respecButton[1], respecButton[0] + respecButton[2], respecButton[1] + respecButton[3],
                hovered ? ThemeAssets.mix(ThemeAssets.secondary(theme), 0xFFFFFFFF, 0.15f) : ThemeAssets.secondary(theme));
        ctx.drawBorder(respecButton[0], respecButton[1], respecButton[2], respecButton[3], accent);
        Text respecText = Text.literal("Unlearn the old ways");
        int tw = textRenderer.getWidth(respecText);
        ctx.drawText(textRenderer, respecText, respecButton[0] + (respecButton[2] - tw) / 2,
                respecButton[1] + (respecButton[3] - 9) / 2, 0xFFFFFFFF, true);
    }

    private static boolean isWithin(int[] bounds, double x, double y) {
        return x >= bounds[0] && x <= bounds[0] + bounds[2] && y >= bounds[1] && y <= bounds[1] + bounds[3];
    }

    // --- Input --------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        suppressDragThisPress = false;

        if (tree == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (button == 0 && isWithin(respecButton, mouseX, mouseY)) {
            suppressDragThisPress = true;
            openRespecConfirm();
            return true;
        }

        if (button == 0) {
            for (NodeHit hit : nodeHits) {
                if (distance(mouseX, mouseY, hit.x(), hit.y()) <= hit.radius()) {
                    suppressDragThisPress = true;
                    onNodeClicked(hit.node());
                    return true;
                }
            }
        }

        if (button == 0 && mouseX >= canvasX && mouseX <= canvasX + canvasW
                && mouseY >= canvasY && mouseY <= canvasY + canvasH) {
            dragging = true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        suppressDragThisPress = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging && !suppressDragThisPress && button == 0) {
            panX += (float) deltaX;
            panY += (float) deltaY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tree == null || mouseX < canvasX || mouseX > canvasX + canvasW || mouseY < canvasY || mouseY > canvasY + canvasH) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        TreeRenderer.CanvasTransform before =
                new TreeRenderer.CanvasTransform(canvasX, canvasY, canvasW, canvasH, panX, panY, zoom);
        float worldX = before.worldXOf(mouseX);
        float worldY = before.worldYOf(mouseY);

        float factor = (float) Math.pow(1.15, verticalAmount);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));

        // Keep the point under the cursor fixed: recompute pan so worldX/worldY still project to
        // (mouseX, mouseY) at the new zoom.
        float centerX = canvasX + canvasW / 2f;
        float centerY = canvasY + canvasH / 2f;
        panX = (float) (mouseX - centerX - worldX * TreeRenderer.GRID_SPACING * zoom);
        panY = (float) (mouseY - centerY - worldY * TreeRenderer.GRID_SPACING * zoom);
        return true;
    }

    private void onNodeClicked(SkillNode node) {
        KindredData data = currentData();
        TreeRenderer.NodeState state = TreeRenderer.stateOf(node, data, tree);
        if (state == TreeRenderer.NodeState.AVAILABLE || state == TreeRenderer.NodeState.SEALED) {
            ClientPlayNetworking.send(new RequestUnlockC2S(node.id()));
        }
    }

    private void openRespecConfirm() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                ClientPlayNetworking.send(new RespecC2S());
            }
            client.setScreen(this);
        }, Text.literal("Unlearn the old ways?"),
                Text.literal("This will remove every unlocked node and refund none of your discipline points. "
                        + "Consumes the configured respec item.")));
    }

    // --- Server feedback (UnlockResultS2C) ------------------------------------------------------

    /** Called by {@code KindredsClient}'s {@code UnlockResultS2C} receiver so the currently-open
     * screen (if any) can react to a rejected/accepted unlock or respec with a toast - the tree
     * itself already re-renders from {@link ClientKindredData#INSTANCE} on success without any extra
     * handling needed here. */
    public static void handleUnlockResult(MinecraftClient client, boolean ok, String reason) {
        if (ok) {
            return; // the next SyncKindredDataS2C already updates the live view; no toast needed.
        }
        String message = switch (reason) {
            case "insufficient_points" -> "Not enough discipline points.";
            case "missing_prereq" -> "You must learn its prerequisite first.";
            case "exclusive_conflict" -> "You have already chosen a rival path.";
            case "deed_not_earned" -> "The deed for this feat has not yet been proven.";
            case "already_unlocked" -> "Already learned.";
            case "respec_insufficient_item" -> "You lack the item required to respec.";
            case "respec_item_invalid" -> "Respec is misconfigured on this server.";
            default -> "That cannot be done right now.";
        };
        SystemToast.add(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION,
                Text.literal("Kindreds"), Text.literal(message));
    }
}
