package com.kindreds.client.screen;

import com.kindreds.data.Disciplines;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.Theme;
import com.kindreds.network.RequestUnlockC2S;
import com.kindreds.network.RespecC2S;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.LevelCurve;
import com.kindreds.progression.ProgressionService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The lore-themed skill-tree screen, redesigned as a <b>discipline-focused</b> view:
 * <ul>
 *   <li><b>Left rail</b> - one tab per discipline that has nodes in this race's tree, each showing
 *       its level and available points. Click to focus that discipline.</li>
 *   <li><b>Center canvas</b> - only the focused discipline's branch, auto-centered at a fixed,
 *       readable spacing (independent of the raw authored {@code pos} scale) with node names drawn
 *       inline, vertical scroll when the branch is tall.</li>
 *   <li><b>Right panel</b> - the selected node's full detail (effects in plain language, cost,
 *       prereqs, deed) with an Unlock action, or the focused discipline's summary + XP bar when no
 *       node is selected; plus vision, titles, and respec.</li>
 * </ul>
 *
 * <p>Race -&gt; tree -&gt; theme is resolved live each frame from the client-mirrored synced data
 * (see {@link #refreshTreeForRace}), so the screen self-heals if the join-time race sync lands after
 * it opens. Unlock attempts get audible + visual feedback via {@link #onUnlockResult}.
 */
public class SkillTreeScreen extends Screen {
    private static final int TAB_W = 152;
    private static final int PANEL_W = 214;
    private static final int MARGIN = 8;
    private static final int GAP = 8;

    private static final int COL_SPACING = 132;
    private static final int ROW_SPACING = 86;
    private static final int NODE_R = 15;
    private static final int CANVAS_TOP_PAD = 44;   // room for the discipline title band
    private static final int CANVAS_BOTTOM_PAD = 20;

    // Not final: resolved lazily from the live client-synced race (see refreshTreeForRace()).
    private SkillTree tree;
    private Theme theme;
    private Identifier resolvedForRace;
    private final KindredData initialData;

    private List<String> tabDisciplines = List.of();   // discipline paths that have nodes, in order
    private String selectedDiscipline;                 // e.g. "combat"
    private SkillNode selectedNode;

    private float scrollY;
    private float contentHeight;

    // Layout rects (x, y, w, h), recomputed in init().
    private int[] rail = new int[4];
    private int[] canvas = new int[4];
    private int[] panel = new int[4];
    private int[] unlockButton = new int[4];  // only valid while an unlockable node is selected
    private int[] respecButton = new int[4];

    private final List<int[]> tabRects = new ArrayList<>();   // parallel to tabDisciplines: x,y,w,h
    private final List<Placed> placed = new ArrayList<>();
    private Placed hovered;

    // Unlock feedback.
    private String pendingUnlockNodeId;
    private String flashNodeId;
    private float flashTimer;

    private record Placed(SkillNode node, float x, float y, float r, TreeRenderer.NodeState state) {
    }

    public SkillTreeScreen(KindredData data) {
        super(Text.literal("Skill Tree"));
        this.initialData = data;
    }

    /** Opens the screen; it resolves its own race/tree/theme each frame from live synced data. */
    public static void open(MinecraftClient client) {
        client.setScreen(new SkillTreeScreen(ClientKindredData.INSTANCE));
    }

    private KindredData currentData() {
        KindredData live = ClientKindredData.INSTANCE;
        return live != null ? live : initialData;
    }

    // --- Layout / lifecycle ----------------------------------------------------------------------

    @Override
    protected void init() {
        rail = new int[]{MARGIN, MARGIN, TAB_W, height - 2 * MARGIN};
        panel = new int[]{width - PANEL_W - MARGIN, MARGIN, PANEL_W, height - 2 * MARGIN};
        int cx = rail[0] + rail[2] + GAP;
        canvas = new int[]{cx, MARGIN, panel[0] - GAP - cx, height - 2 * MARGIN};
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Re-resolves {@link #tree}/{@link #theme} from the client-mirrored synced registries when the
     * race first becomes known (or changes) after this screen opened, and (re)builds the discipline
     * tab list. Cheap and idempotent; retries next frame while the race/registries aren't ready. */
    private void refreshTreeForRace() {
        Identifier race = currentData().race();
        if (tree != null && Objects.equals(race, resolvedForRace)) {
            return;
        }
        resolvedForRace = race;
        tree = null;
        theme = null;
        tabDisciplines = List.of();
        selectedDiscipline = null;
        selectedNode = null;
        if (race == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
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
                buildTabs();
            }
        } catch (RuntimeException ignored) {
            // Registries not synced yet - stay on the prompt; retried next frame.
        }
    }

    /** Builds the discipline tab list from the disciplines that actually have nodes in this tree,
     * in the canonical {@link Disciplines#ALL} order, and picks a sensible default focus. */
    private void buildTabs() {
        List<String> withNodes = new ArrayList<>();
        for (String disc : Disciplines.ALL) {
            for (SkillNode node : tree.nodes()) {
                if (node.cost().disciplineId().getPath().equals(disc)) {
                    withNodes.add(disc);
                    break;
                }
            }
        }
        tabDisciplines = withNodes;
        // Default focus: the first discipline that already has points to spend, else the first tab.
        KindredData data = currentData();
        selectedDiscipline = withNodes.isEmpty() ? null : withNodes.get(0);
        for (String disc : withNodes) {
            if (available(data, disc) > 0) {
                selectedDiscipline = disc;
                break;
            }
        }
        scrollY = 0;
        selectedNode = null;
    }

    // --- Discipline point helpers ----------------------------------------------------------------

    private static Identifier disciplineId(String path) {
        return Identifier.of("kindreds", path);
    }

    private int level(KindredData d, String disc) {
        return ProgressionService.pointsForLevel(d.xpIn(disciplineId(disc)));
    }

    private int spent(KindredData d, String disc) {
        return ProgressionService.pointsSpent(d, tree, disciplineId(disc));
    }

    private int available(KindredData d, String disc) {
        return ProgressionService.pointsAvailable(d, tree, disciplineId(disc));
    }

    // --- Render ----------------------------------------------------------------------------------

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float deltaTicks) {
        // Flat darkened backdrop (avoids Screen#renderBackground's blur, which crashes non-pausing
        // screens under this pack's Sodium/Iris pipeline).
        ctx.fill(0, 0, this.width, this.height, 0xC80A0A0A);

        refreshTreeForRace();

        if (tree == null) {
            renderNoRacePrompt(ctx);
            super.render(ctx, mouseX, mouseY, deltaTicks);
            return;
        }
        if (flashTimer > 0) {
            flashTimer = Math.max(0, flashTimer - deltaTicks);
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        KindredData data = currentData();
        renderTabRail(ctx, data, mouseX, mouseY);
        renderCanvas(ctx, data, mouseX, mouseY);
        renderPanel(ctx, data, mouseX, mouseY);

        if (hovered != null) {
            NodeTooltip.render(ctx, MinecraftClient.getInstance(), hovered.node(), hovered.state(),
                    tree, theme, mouseX, mouseY, width, height);
        }
        super.render(ctx, mouseX, mouseY, deltaTicks);
    }

    private void renderNoRacePrompt(DrawContext ctx) {
        Text msg = Text.literal("Choose your people first (use the Player's Book).");
        int w = textRenderer.getWidth(msg);
        ctx.drawText(textRenderer, msg, (width - w) / 2, height / 2, 0xFFDDDDDD, true);
    }

    // --- Tab rail --------------------------------------------------------------------------------

    private void renderTabRail(DrawContext ctx, KindredData data, int mouseX, int mouseY) {
        TreeRenderer.drawBackground(ctx, theme, rail[0], rail[1], rail[2], rail[3]);
        TreeRenderer.drawFrame(ctx, theme, rail[0], rail[1], rail[2], rail[3]);
        int accent = ThemeAssets.accent(theme);
        int x = rail[0] + 10;
        int y = rail[1] + 10;
        ctx.drawText(textRenderer, Text.literal("Disciplines").formatted(Formatting.BOLD), x, y, accent, false);
        y += 16;

        tabRects.clear();
        int rowH = 34;
        for (String disc : tabDisciplines) {
            int[] r = {rail[0] + 6, y, rail[2] - 12, rowH - 4};
            tabRects.add(r);
            boolean sel = disc.equals(selectedDiscipline);
            boolean hover = within(r, mouseX, mouseY);
            int avail = available(data, disc);

            if (sel) {
                ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], ThemeAssets.withAlpha(accent, 70));
                ctx.drawBorder(r[0], r[1], r[2], r[3], accent);
            } else if (hover) {
                ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x40FFFFFF);
            }

            int textColor = sel ? 0xFFFFFFFF : 0xFFD8D2C0;
            ctx.drawText(textRenderer, Text.literal(titleCase(disc)).formatted(sel ? Formatting.BOLD : Formatting.RESET),
                    r[0] + 8, r[1] + 5, textColor, false);
            String sub = "Lv " + level(data, disc) + "   " + spent(data, disc) + " spent";
            ctx.drawText(textRenderer, Text.literal(sub), r[0] + 8, r[1] + 17, 0xFF9A9484, false);
            if (avail > 0) {
                String pts = "+" + avail;
                int pw = textRenderer.getWidth(pts);
                ctx.drawText(textRenderer, Text.literal(pts).formatted(Formatting.BOLD),
                        r[0] + r[2] - pw - 8, r[1] + 11, 0xFF66DD66, true);
            }
            y += rowH;
        }
    }

    // --- Canvas (focused discipline branch) ------------------------------------------------------

    private void renderCanvas(DrawContext ctx, KindredData data, int mouseX, int mouseY) {
        TreeRenderer.drawBackground(ctx, theme, canvas[0], canvas[1], canvas[2], canvas[3]);
        TreeRenderer.drawFrame(ctx, theme, canvas[0], canvas[1], canvas[2], canvas[3]);

        int accent = ThemeAssets.accent(theme);
        String title = selectedDiscipline == null ? "" : titleCase(selectedDiscipline) + " Path";
        int tw = textRenderer.getWidth(title);
        ctx.drawText(textRenderer, Text.literal(title).formatted(Formatting.BOLD),
                canvas[0] + (canvas[2] - tw) / 2, canvas[1] + 14, accent, true);

        layout(data);

        int top = canvas[1] + 1;
        int bottom = canvas[1] + canvas[3] - 1;
        ctx.enableScissor(canvas[0] + 1, top + CANVAS_TOP_PAD - 12, canvas[0] + canvas[2] - 1, bottom);

        // Edges first (only within this discipline's placed set).
        for (Placed p : placed) {
            for (String prereqId : p.node().prereqs()) {
                findPlaced(prereqId).ifPresent(pre ->
                        drawEdge(ctx, pre.x(), pre.y(), p.x(), p.y(), ThemeAssets.edgeColor(theme)));
            }
        }

        hovered = null;
        for (Placed p : placed) {
            boolean hover = dist(mouseX, mouseY, p.x(), p.y()) <= p.r() + 4
                    && mouseY >= top + CANVAS_TOP_PAD - 12 && mouseY <= bottom;
            if (hover) {
                hovered = p;
            }
            boolean selected = selectedNode != null && selectedNode.id().equals(p.node().id());
            if (flashNodeId != null && flashNodeId.equals(p.node().id()) && flashTimer > 0) {
                float g = (8f - flashTimer) / 8f;               // 0 -> 1 as it settles
                int ring = (int) (p.r() + 6 + g * 14);
                int alpha = (int) (200 * (1 - g));
                ctx.drawBorder((int) p.x() - ring, (int) p.y() - ring, ring * 2, ring * 2,
                        ThemeAssets.withAlpha(0xFFFFE070, Math.max(0, alpha)));
            }
            TreeRenderer.drawNode(ctx, p.node(), p.state(), theme, p.x(), p.y(), p.r(), hover || selected);

            // Inline node name so the branch is readable at a glance.
            String name = NodeTooltip.displayName(p.node().id());
            int nw = textRenderer.getWidth(name);
            int nameColor = switch (p.state()) {
                case OWNED -> 0xFFB9F2B0;
                case AVAILABLE -> 0xFFFFF3C0;
                case SEALED -> ThemeAssets.WARNING_COLOR;
                case LOCKED -> 0xFF8A8478;
            };
            ctx.drawText(textRenderer, Text.literal(name), (int) p.x() - nw / 2, (int) (p.y() + p.r() + 4),
                    nameColor, true);
            String costTag = p.state() == TreeRenderer.NodeState.OWNED ? "✔"
                    : p.node().cost().points() + " pt";
            int cw = textRenderer.getWidth(costTag);
            ctx.drawText(textRenderer, Text.literal(costTag), (int) p.x() - cw / 2, (int) (p.y() + p.r() + 15),
                    0xFF9A9484, true);
        }
        ctx.disableScissor();

        if (placed.isEmpty()) {
            String none = "No path here yet.";
            int w = textRenderer.getWidth(none);
            ctx.drawText(textRenderer, Text.literal(none), canvas[0] + (canvas[2] - w) / 2,
                    canvas[1] + canvas[3] / 2, 0xFF9A9484, true);
        }
    }

    /** Places the focused discipline's nodes at fixed, readable spacing, centered horizontally, so
     * layout is independent of the raw authored {@code pos} magnitudes (which differ per tree). Column
     * order comes from distinct x values, row order from distinct y values. */
    private void layout(KindredData data) {
        placed.clear();
        if (selectedDiscipline == null) {
            contentHeight = 0;
            return;
        }
        List<SkillNode> nodes = new ArrayList<>();
        TreeMap<Integer, Integer> xIndex = new TreeMap<>();
        TreeMap<Integer, Integer> yIndex = new TreeMap<>();
        for (SkillNode node : tree.nodes()) {
            if (node.cost().disciplineId().getPath().equals(selectedDiscipline)) {
                nodes.add(node);
                xIndex.put(node.pos()[0], 0);
                yIndex.put(node.pos()[1], 0);
            }
        }
        indexKeys(xIndex);
        indexKeys(yIndex);

        int cols = xIndex.size();
        int rows = yIndex.size();
        contentHeight = Math.max(0, (rows - 1) * ROW_SPACING) + NODE_R * 2 + 40;

        float startX = canvas[0] + canvas[2] / 2f - (cols - 1) * COL_SPACING / 2f;
        float startY = canvas[1] + CANVAS_TOP_PAD + NODE_R + scrollY;

        for (SkillNode node : nodes) {
            int col = xIndex.get(node.pos()[0]);
            int row = yIndex.get(node.pos()[1]);
            float sx = startX + col * COL_SPACING;
            float sy = startY + row * ROW_SPACING;
            float r = node.deedAdvancement().isPresent() ? NODE_R * 1.4f : NODE_R;
            placed.add(new Placed(node, sx, sy, r, TreeRenderer.stateOf(node, data, tree)));
        }
    }

    /** Replaces each key's placeholder value with its rank (0-based) among the sorted keys. */
    private static void indexKeys(TreeMap<Integer, Integer> map) {
        int i = 0;
        for (Integer key : map.keySet()) {
            map.put(key, i++);
        }
    }

    private Optional<Placed> findPlaced(String nodeId) {
        for (Placed p : placed) {
            if (p.node().id().equals(nodeId)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private static void drawEdge(DrawContext ctx, float x1, float y1, float x2, float y2, int color) {
        // Simple orthogonal-ish connector: vertical then horizontal, drawn as thin fills.
        int thick = 2;
        int midY = (int) ((y1 + y2) / 2);
        ctx.fill((int) x1 - thick / 2, (int) Math.min(y1, midY), (int) x1 + thick / 2 + 1, (int) Math.max(y1, midY), color);
        ctx.fill((int) Math.min(x1, x2), midY - thick / 2, (int) Math.max(x1, x2), midY + thick / 2 + 1, color);
        ctx.fill((int) x2 - thick / 2, (int) Math.min(midY, y2), (int) x2 + thick / 2 + 1, (int) Math.max(midY, y2), color);
    }

    // --- Detail panel ----------------------------------------------------------------------------

    private void renderPanel(DrawContext ctx, KindredData data, int mouseX, int mouseY) {
        TreeRenderer.drawBackground(ctx, theme, panel[0], panel[1], panel[2], panel[3]);
        TreeRenderer.drawFrame(ctx, theme, panel[0], panel[1], panel[2], panel[3]);

        int accent = ThemeAssets.accent(theme);
        int x = panel[0] + 10;
        int y = panel[1] + 10;

        // Race header.
        String raceName = titleCase(tree.race().getPath());
        ctx.fill(x, y, x + 16, y + 16, ThemeAssets.ownedColor(theme));
        ctx.drawBorder(x, y, 16, 16, accent);
        ctx.drawText(textRenderer, Text.literal(raceName).formatted(Formatting.BOLD), x + 22, y + 4, 0xFFFFFFFF, true);
        y += 26;
        ctx.fill(x, y, panel[0] + panel[2] - 10, y + 1, ThemeAssets.withAlpha(accent, 120));
        y += 6;

        unlockButton = new int[]{0, 0, 0, 0};
        if (selectedNode != null) {
            y = renderNodeDetail(ctx, data, x, y);
        } else {
            y = renderDisciplineSummary(ctx, data, x, y);
        }

        // Vision + titles, anchored lower.
        int vy = panel[1] + panel[3] - 96;
        ctx.drawText(textRenderer, Text.literal("Vision").formatted(Formatting.BOLD), x, vy, accent, false);
        Identifier lensId = data.activeVisionLens();
        String lens = lensId != null ? titleCase(lensId.getPath()) : "None equipped";
        ctx.drawText(textRenderer, Text.literal(lens), x, vy + 12, 0xFFD8D2C0, false);
        ctx.drawText(textRenderer, Text.literal("Titles").formatted(Formatting.BOLD), x, vy + 30, accent, false);
        String titles = data.titles().isEmpty() ? "None earned yet" : String.join(", ", data.titles());
        for (var line : textRenderer.wrapLines(Text.literal(titles), panel[2] - 20)) {
            ctx.drawText(textRenderer, line, x, vy + 42, 0xFFD8D2C0, false);
            break;
        }

        // Respec button.
        respecButton = new int[]{panel[0] + 10, panel[1] + panel[3] - 30, panel[2] - 20, 22};
        boolean rHover = within(respecButton, mouseX, mouseY);
        ctx.fill(respecButton[0], respecButton[1], respecButton[0] + respecButton[2], respecButton[1] + respecButton[3],
                rHover ? ThemeAssets.withAlpha(accent, 80) : 0x50000000);
        ctx.drawBorder(respecButton[0], respecButton[1], respecButton[2], respecButton[3], accent);
        Text rt = Text.literal("Unlearn the old ways");
        int rtw = textRenderer.getWidth(rt);
        ctx.drawText(textRenderer, rt, respecButton[0] + (respecButton[2] - rtw) / 2, respecButton[1] + 7, 0xFFFFFFFF, true);
    }

    private int renderDisciplineSummary(DrawContext ctx, KindredData data, int x, int y) {
        if (selectedDiscipline == null) {
            ctx.drawText(textRenderer, Text.literal("Select a discipline.").formatted(Formatting.GRAY), x, y, 0xFFB0AAA0, false);
            return y + 16;
        }
        int accent = ThemeAssets.accent(theme);
        int lvl = level(data, selectedDiscipline);
        int sp = spent(data, selectedDiscipline);
        int av = available(data, selectedDiscipline);
        ctx.drawText(textRenderer, Text.literal(titleCase(selectedDiscipline)).formatted(Formatting.BOLD), x, y, accent, false);
        y += 14;
        ctx.drawText(textRenderer, Text.literal("Level " + lvl + "  ·  " + sp + " spent  ·  "), x, y, 0xFFD8D2C0, false);
        y += 12;
        ctx.drawText(textRenderer, Text.literal(av + " point(s) to spend").formatted(av > 0 ? Formatting.GREEN : Formatting.GRAY),
                x, y, av > 0 ? 0xFF66DD66 : 0xFF9A9484, false);
        y += 16;

        // XP-to-next-level bar.
        long xp = data.xpIn(disciplineId(selectedDiscipline));
        long cur = LevelCurve.xpForLevel(lvl);
        long next = LevelCurve.xpForLevel(lvl + 1);
        float frac = next > cur ? (float) (xp - cur) / (float) (next - cur) : 0f;
        frac = Math.max(0f, Math.min(1f, frac));
        int barW = panel[2] - 20;
        ctx.fill(x, y, x + barW, y + 6, 0x60000000);
        ctx.fill(x, y, x + (int) (barW * frac), y + 6, ThemeAssets.ownedColor(theme));
        ctx.drawBorder(x, y, barW, 6, ThemeAssets.withAlpha(accent, 160));
        y += 12;
        ctx.drawText(textRenderer, Text.literal("XP " + xp + " / " + next).formatted(Formatting.DARK_GRAY), x, y, 0xFF9A9484, false);
        y += 18;
        ctx.drawText(textRenderer, Text.literal("Click a node to inspect it.").formatted(Formatting.ITALIC),
                x, y, 0xFF9A9484, false);
        return y + 16;
    }

    private int renderNodeDetail(DrawContext ctx, KindredData data, int x, int y) {
        int accent = ThemeAssets.accent(theme);
        SkillNode node = selectedNode;
        TreeRenderer.NodeState state = TreeRenderer.stateOf(node, data, tree);
        int wrap = panel[2] - 20;

        ctx.drawText(textRenderer, Text.literal(NodeTooltip.displayName(node.id())).formatted(Formatting.BOLD),
                x, y, 0xFFFFFFFF, true);
        y += 13;
        String status = switch (state) {
            case OWNED -> "Owned";
            case AVAILABLE -> "Available";
            case SEALED -> "Sealed - needs a deed";
            case LOCKED -> "Locked";
        };
        int statusColor = switch (state) {
            case OWNED -> 0xFF66DD66;
            case AVAILABLE -> 0xFFFFE070;
            case SEALED -> ThemeAssets.WARNING_COLOR;
            case LOCKED -> 0xFF9A9484;
        };
        ctx.drawText(textRenderer, Text.literal(status), x, y, statusColor, false);
        y += 14;

        for (var line : textRenderer.wrapLines(Text.literal(NodeTooltip.flavor(node)).formatted(Formatting.GRAY), wrap)) {
            ctx.drawText(textRenderer, line, x, y, 0xFFB6B0A2, false);
            y += 10;
        }
        y += 4;
        ctx.drawText(textRenderer, Text.literal("Effects").formatted(Formatting.BOLD), x, y, accent, false);
        y += 12;
        for (var ability : node.abilities()) {
            // describe() embeds legacy color codes (e.g. curses); the String draw path honors them.
            for (var line : textRenderer.wrapLines(Text.literal(NodeTooltip.describe(ability)), wrap)) {
                ctx.drawText(textRenderer, line, x + 4, y, 0xFFE6E0D0, false);
                y += 10;
            }
        }
        y += 4;
        ctx.drawText(textRenderer, Text.literal("Cost: " + node.cost().points() + " " + titleCase(selectedDiscipline) + " pt"),
                x, y, 0xFF7FD0E0, false);
        y += 12;
        if (!node.prereqs().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String pid : node.prereqs()) {
                names.add(tree.node(pid).map(n -> NodeTooltip.displayName(n.id())).orElse(pid));
            }
            for (var line : textRenderer.wrapLines(Text.literal("Requires: " + String.join(", ", names)).formatted(Formatting.DARK_GRAY), wrap)) {
                ctx.drawText(textRenderer, line, x, y, 0xFF9A9484, false);
                y += 10;
            }
        }
        node.deedAdvancement().ifPresent(deed -> { /* shown via status line above */ });

        // Unlock action for an unlockable node.
        if (state == TreeRenderer.NodeState.AVAILABLE || state == TreeRenderer.NodeState.SEALED) {
            y += 6;
            unlockButton = new int[]{x, y, wrap, 20};
            boolean hover = within(unlockButton, lastMouseX, lastMouseY);
            int fill = state == TreeRenderer.NodeState.SEALED ? ThemeAssets.withAlpha(ThemeAssets.WARNING_COLOR, 90)
                    : (hover ? 0xC0225522 : 0x80183018);
            ctx.fill(unlockButton[0], unlockButton[1], unlockButton[0] + unlockButton[2], unlockButton[1] + unlockButton[3], fill);
            ctx.drawBorder(unlockButton[0], unlockButton[1], unlockButton[2], unlockButton[3],
                    state == TreeRenderer.NodeState.SEALED ? ThemeAssets.WARNING_COLOR : 0xFF66DD66);
            String label = state == TreeRenderer.NodeState.SEALED
                    ? "Attempt (deed-sealed)"
                    : "Learn  (" + node.cost().points() + " pt)";
            int lw = textRenderer.getWidth(label);
            ctx.drawText(textRenderer, Text.literal(label), unlockButton[0] + (unlockButton[2] - lw) / 2, unlockButton[1] + 6, 0xFFFFFFFF, true);
            y += 24;
        }
        return y;
    }

    // --- Input -----------------------------------------------------------------------------------

    private int lastMouseX, lastMouseY;

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tree == null || button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // Respec.
        if (within(respecButton, mouseX, mouseY)) {
            openRespecConfirm();
            return true;
        }
        // Unlock button.
        if (selectedNode != null && within(unlockButton, mouseX, mouseY)) {
            attemptUnlock(selectedNode);
            return true;
        }
        // Discipline tabs.
        for (int i = 0; i < tabRects.size(); i++) {
            if (within(tabRects.get(i), mouseX, mouseY)) {
                selectedDiscipline = tabDisciplines.get(i);
                selectedNode = null;
                scrollY = 0;
                return true;
            }
        }
        // Canvas nodes.
        for (Placed p : placed) {
            if (dist(mouseX, mouseY, p.x(), p.y()) <= p.r() + 4) {
                selectedNode = p.node();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tree != null && within(canvas, mouseX, mouseY)) {
            float viewH = canvas[3] - CANVAS_TOP_PAD - CANVAS_BOTTOM_PAD;
            float minScroll = Math.min(0, viewH - contentHeight);
            scrollY = Math.max(minScroll, Math.min(0, scrollY + (float) verticalAmount * 24));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void attemptUnlock(SkillNode node) {
        pendingUnlockNodeId = node.id();
        ClientPlayNetworking.send(new RequestUnlockC2S(node.id()));
    }

    private void openRespecConfirm() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                ClientPlayNetworking.send(new RespecC2S());
            }
            client.setScreen(this);
        }, Text.literal("Unlearn the old ways?"),
                Text.literal("This removes every unlocked node and refunds none of your discipline points.")));
    }

    // --- Unlock feedback -------------------------------------------------------------------------

    /** Entry point from {@code KindredsClient}'s {@code UnlockResultS2C} receiver; routes to the open
     * tree screen (if any) for sound + node flash, and always surfaces a toast. */
    public static void handleUnlockResult(MinecraftClient client, boolean ok, String reason) {
        if (client.currentScreen instanceof SkillTreeScreen screen) {
            screen.onUnlockResult(client, ok, reason);
            return;
        }
        toast(client, ok, reason);
    }

    private void onUnlockResult(MinecraftClient client, boolean ok, String reason) {
        if (ok) {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.3f));
            flashNodeId = pendingUnlockNodeId;
            flashTimer = 8f;
        } else {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_VILLAGER_NO, 1.0f));
        }
        pendingUnlockNodeId = null;
        toast(client, ok, reason);
    }

    private static void toast(MinecraftClient client, boolean ok, String reason) {
        Text title = Text.literal(ok ? "Skill learned" : "Cannot learn");
        Text body = Text.literal(ok ? "A new strength awakens." : humanizeReason(reason));
        SystemToast.add(client.getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION, title, body);
    }

    private static String humanizeReason(String reason) {
        if (reason == null) {
            return "Not allowed right now.";
        }
        return switch (reason) {
            case "insufficient_points" -> "Not enough points in that discipline.";
            case "missing_prereq" -> "Unlock its prerequisite first.";
            case "already_owned" -> "You already know this.";
            case "deed_not_earned" -> "Earn its deed first to break the seal.";
            case "no_tree_for_race", "no_such_node", "ambiguous_node" -> "This skill isn't available.";
            default -> titleCase(reason);
        };
    }

    // --- Small helpers ---------------------------------------------------------------------------

    private static boolean within(int[] r, double x, double y) {
        return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2, dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static String titleCase(String path) {
        String[] words = path.replace('_', ' ').replace('/', ' ').replace('.', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
