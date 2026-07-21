package com.kindreds.client.screen;

import com.kindreds.KindredsClient;
import com.kindreds.data.Disciplines;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.Theme;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.data.ability.CurseDef;
import com.kindreds.data.ability.VisionUnlock;
import com.kindreds.network.RequestUnlockC2S;
import com.kindreds.network.RespecC2S;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.LevelCurve;
import com.kindreds.progression.ProgressionService;
import com.kindreds.vision.VisionManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.resource.language.I18n;
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
import java.util.Map;
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

    // World-space spacing for the whole-tree constellation map (screen size = these * zoom).
    private static final float COL_SPACING = 78f;
    private static final float ROW_SPACING = 74f;
    private static final float LANE_GAP = 70f;      // gap between discipline regions
    private static final int NODE_R = 15;
    private static final int CANVAS_TOP_PAD = 20;
    private static final float MIN_ZOOM = 0.30f;
    private static final float MAX_ZOOM = 1.60f;
    /** Below this zoom, node labels/badges are hidden to keep the overview clean. */
    private static final float LABEL_ZOOM = 0.62f;

    // Not final: resolved lazily from the live client-synced race (see refreshTreeForRace()).
    private SkillTree tree;
    private Theme theme;
    private Identifier resolvedForRace;
    private final KindredData initialData;

    private List<String> tabDisciplines = List.of();   // all 7 discipline paths, in canonical order
    private java.util.Set<String> disciplinesWithNodes = java.util.Set.of();
    private String selectedDiscipline;                 // e.g. "combat"
    private SkillNode selectedNode;

    /** Two ways to read the tree: MAP = whole constellation (all disciplines at once, pan/zoom);
     * BRANCH = one discipline, focused and readable (pick via the tabs). Both share the camera. */
    private enum ViewMode { MAP, BRANCH }
    private ViewMode viewMode = ViewMode.MAP;
    private int[] viewToggleButton = new int[4];

    // Whole-tree map camera.
    private float panX, panY, zoom = 1f;
    private float worldCenterX, worldCenterY;
    private boolean fitted;
    private boolean dragging;
    private double dragPrevX, dragPrevY;
    private float railScroll;
    private int railContentHeight;

    private final Map<SkillNode, float[]> nodeWorld = new java.util.HashMap<>();   // node -> world x,y
    private final Map<String, float[]> laneBounds = new java.util.HashMap<>();      // discipline -> world x0,x1

    // Layout rects (x, y, w, h), recomputed in init().
    private int[] rail = new int[4];
    private int[] canvas = new int[4];
    private int[] panel = new int[4];
    private int[] unlockButton = new int[4];  // only valid while an unlockable node is selected

    /** Live node search. Hand-rolled rather than a TextFieldWidget because this screen is entirely
     * custom-drawn with manual hit-testing and no other widgets - typing straight into it keeps that
     * model (and avoids a widget fighting the canvas for render order and focus). */
    private String query = "";
    private int searchMatches;
    private int[] visionButton = new int[4];  // only valid while an owned vision node is selected
    // Assign-to-slot buttons, only valid while an owned active-ability node is selected.
    private final int[][] slotButtons = new int[com.kindreds.client.loadout.ClientLoadout.SLOTS][4];
    private int[] codexButton = new int[4];
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
        super(Text.translatable("kindreds.tree.title"));
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

    /** Typing anywhere in the tree filters it - no need to click into a box first. */
    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (chr >= ' ' && chr != 127) {
            query += chr;
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    /** Backspace edits the query; Esc clears it before it closes the screen, so an active search is
     * never a trap. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
            query = query.substring(0, query.length() - 1);
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && !query.isEmpty()) {
            query = "";
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
        // Only show disciplines this race actually trains (in canonical order) - no dead "no path" tabs.
        java.util.Set<String> withNodes = new java.util.HashSet<>();
        for (SkillNode node : tree.nodes()) {
            withNodes.add(node.cost().disciplineId().getPath());
        }
        List<String> present = new ArrayList<>();
        for (String disc : Disciplines.ALL) {
            if (withNodes.contains(disc)) {
                present.add(disc);
            }
        }
        tabDisciplines = present;
        disciplinesWithNodes = withNodes;

        // Default focus: first discipline with points to spend, else the first present one.
        KindredData data = currentData();
        selectedDiscipline = present.isEmpty() ? null : present.get(0);
        for (String disc : present) {
            if (available(data, disc) > 0) {
                selectedDiscipline = disc;
                break;
            }
        }
        selectedNode = null;
        fitted = false;
    }

    /** Short human label + color for a node's primary kind, so passive/active/vision/curse skills
     * are distinguishable at a glance. */
    private static NodeKind kindOf(SkillNode node) {
        boolean vision = false, active = false, curse = false;
        for (AbilityDef a : node.abilities()) {
            if (a instanceof ActiveAbilityDef) {
                active = true;
            } else if (a instanceof VisionUnlock) {
                vision = true;
            } else if (a instanceof CurseDef) {
                curse = true;
            }
        }
        if (active) {
            return NodeKind.ACTIVE;
        }
        if (vision) {
            return NodeKind.VISION;
        }
        if (curse) {
            return NodeKind.CURSE;
        }
        return NodeKind.PASSIVE;
    }

    private enum NodeKind {
        PASSIVE("Passive", "P", 0xFF8FC7FF),
        ACTIVE("Active", "A", 0xFFFFC24A),
        VISION("Vision", "V", 0xFF7CE0C0),
        CURSE("Curse", "!", ThemeAssets.WARNING_COLOR);

        final String label;
        final String badge;
        final int color;

        NodeKind(String label, String badge, int color) {
            this.label = label;
            this.badge = badge;
            this.color = color;
        }
    }

    private static int activeCooldownSeconds(SkillNode node) {
        for (AbilityDef a : node.abilities()) {
            if (a instanceof ActiveAbilityDef act) {
                return act.cooldownTicks() / 20;
            }
        }
        return 0;
    }

    private static String curseWhenText(SkillNode node) {
        for (AbilityDef a : node.abilities()) {
            if (a instanceof CurseDef c && !c.when().isEmpty()) {
                return " in the " + titleCase(c.when());
            }
        }
        return "";
    }

    private static Identifier visionLensId(SkillNode node) {
        for (AbilityDef a : node.abilities()) {
            if (a instanceof VisionUnlock v) {
                return Identifier.of("kindreds", v.visionId());
            }
        }
        return null;
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
        Text msg = Text.translatable("kindreds.tree.choose_people");
        int w = textRenderer.getWidth(msg);
        ctx.drawText(textRenderer, msg, (width - w) / 2, height / 2, 0xFFDDDDDD, true);
    }

    // --- Tab rail --------------------------------------------------------------------------------

    private void renderTabRail(DrawContext ctx, KindredData data, int mouseX, int mouseY) {
        TreeRenderer.drawBackground(ctx, theme, rail[0], rail[1], rail[2], rail[3]);
        TreeRenderer.drawFrame(ctx, theme, rail[0], rail[1], rail[2], rail[3]);
        int accent = ThemeAssets.accent(theme);
        int x = rail[0] + 10;
        ctx.drawText(textRenderer, Text.translatable("kindreds.tree.disciplines").formatted(Formatting.BOLD), x, rail[1] + 10, accent, false);

        int rowH = 34;
        int listTop = rail[1] + 26;
        int listBottom = rail[1] + rail[3] - 4;
        railContentHeight = tabDisciplines.size() * rowH;
        // Clamp scroll (needed since there can now be up to 12 disciplines - more than fit on a tall
        // GUI scale) then clip the list so rows never spill past the rail frame.
        railScroll = Math.max(Math.min(0, (listBottom - listTop) - railContentHeight), Math.min(0, railScroll));
        ctx.enableScissor(rail[0] + 1, listTop, rail[0] + rail[2] - 1, listBottom);

        tabRects.clear();
        int y = listTop + (int) railScroll;
        for (String disc : tabDisciplines) {
            int[] r = {rail[0] + 6, y, rail[2] - 12, rowH - 4};
            tabRects.add(r);
            boolean sel = disc.equals(selectedDiscipline);
            boolean hover = within(r, mouseX, mouseY);
            boolean hasNodes = disciplinesWithNodes.contains(disc);
            int avail = available(data, disc);

            if (sel) {
                ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], ThemeAssets.withAlpha(accent, 70));
                ctx.drawBorder(r[0], r[1], r[2], r[3], accent);
            } else if (hover) {
                ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x40FFFFFF);
            }

            int textColor = !hasNodes ? 0xFF6E6A60 : (sel ? 0xFFFFFFFF : 0xFFD8D2C0);
            ctx.drawText(textRenderer, Text.literal(titleCase(disc)).formatted(sel ? Formatting.BOLD : Formatting.RESET),
                    r[0] + 8, r[1] + 5, textColor, false);
            String sub = hasNodes
                    ? Text.translatable("kindreds.tree.tab.sub", level(data, disc), spent(data, disc)).getString()
                    : Text.translatable("kindreds.tree.tab.nopath").getString();
            ctx.drawText(textRenderer, Text.literal(sub), r[0] + 8, r[1] + 17, 0xFF7A756A, false);
            if (hasNodes && avail > 0) {
                String pts = "+" + avail;
                int pw = textRenderer.getWidth(pts);
                ctx.drawText(textRenderer, Text.literal(pts).formatted(Formatting.BOLD),
                        r[0] + r[2] - pw - 8, r[1] + 5, 0xFF66DD66, true);
            }
            // "N nodes you could take right now" - answers "what do I actually do?" without making
            // the player open every discipline to find out. Pulses in step with the node halos.
            if (hasNodes && tree != null) {
                int ready = TreeRenderer.availableCount(tree, data, disciplineId(disc));
                if (ready > 0) {
                    Text badge = Text.translatable("kindreds.tree.ready", ready);
                    int bw = textRenderer.getWidth(badge);
                    double pulse = com.kindreds.Kindreds.CONFIG.hudAnimations
                            ? 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 260.0) : 1.0;
                    int a = (int) (170 + 85 * pulse) << 24;
                    ctx.drawText(textRenderer, badge, r[0] + r[2] - bw - 8, r[1] + 17,
                            a | 0x00FFD86B, false);
                }
            }
            y += rowH;
        }
        ctx.disableScissor();
    }

    // --- Canvas (focused discipline branch) ------------------------------------------------------

    private void renderCanvas(DrawContext ctx, KindredData data, int mouseX, int mouseY) {
        TreeRenderer.drawBackground(ctx, theme, canvas[0], canvas[1], canvas[2], canvas[3]);
        TreeRenderer.drawFrame(ctx, theme, canvas[0], canvas[1], canvas[2], canvas[3]);
        int accent = ThemeAssets.accent(theme);

        layout(data);

        int left = canvas[0] + 1, right = canvas[0] + canvas[2] - 1;
        int top = canvas[1] + 1, bottom = canvas[1] + canvas[3] - 1;
        ctx.enableScissor(left, top, right, bottom);

        float cx = canvas[0] + canvas[2] / 2f;
        // Discipline regions: a faint colored band + a label at the top.
        for (Map.Entry<String, float[]> e : laneBounds.entrySet()) {
            String disc = e.getKey();
            int dc = disciplineColor(disc);
            float x0 = cx + panX + (e.getValue()[0] - worldCenterX) * zoom - COL_SPACING * zoom * 0.5f;
            float x1 = cx + panX + (e.getValue()[1] - worldCenterX) * zoom + COL_SPACING * zoom * 0.5f;
            boolean sel = disc.equals(selectedDiscipline);
            ctx.fill((int) x0, top, (int) x1, bottom, ThemeAssets.withAlpha(dc, sel ? 30 : 12));
            String label = titleCase(disc);
            int lw = textRenderer.getWidth(label);
            ctx.drawText(textRenderer, Text.literal(label).formatted(Formatting.BOLD),
                    (int) ((x0 + x1) / 2 - lw / 2f), canvas[1] + 5, dc, true);
        }

        // Edges (all prereqs, including cross-discipline convergences shown in MAP view).
        for (Placed p : placed) {
            for (String prereqId : p.node().prereqs()) {
                findPlaced(prereqId).ifPresent(pre ->
                        drawEdge(ctx, pre.x(), pre.y(), p.x(), p.y(), ThemeAssets.edgeColor(theme)));
            }
        }

        boolean showLabels = zoom >= LABEL_ZOOM;
        hovered = null;
        searchMatches = 0;
        for (Placed p : placed) {
            boolean hover = within(canvas, mouseX, mouseY) && dist(mouseX, mouseY, p.x(), p.y()) <= p.r() + 3;
            if (hover) {
                hovered = p;
            }
            boolean selected = selectedNode != null && selectedNode.id().equals(p.node().id());
            if (flashNodeId != null && flashNodeId.equals(p.node().id()) && flashTimer > 0) {
                float g = (8f - flashTimer) / 8f;
                int ring = (int) (p.r() + 6 + g * 14);
                ctx.drawBorder((int) p.x() - ring, (int) p.y() - ring, ring * 2, ring * 2,
                        ThemeAssets.withAlpha(0xFFFFE070, Math.max(0, (int) (200 * (1 - g)))));
            }
            // Search hit: ring it brightly so matches stand out even in the zoomed-out map view.
            if (!query.isEmpty() && NodeTooltip.displayName(p.node().id())
                    .toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) {
                searchMatches++;
                int ring = (int) (p.r() + 5);
                ctx.drawBorder((int) p.x() - ring, (int) p.y() - ring, ring * 2, ring * 2, 0xFFFFE070);
            }
            TreeRenderer.drawNode(ctx, p.node(), p.state(), theme, p.x(), p.y(), p.r(), hover || selected);

            if (showLabels) {
                NodeKind kind = kindOf(p.node());
                int bx = (int) (p.x() + p.r() - 3);
                int by = (int) (p.y() - p.r() - 7);
                ctx.fill(bx - 2, by - 1, bx + 9, by + 9, 0xD0101014);
                ctx.drawText(textRenderer, Text.literal(kind.badge), bx, by, kind.color, false);
                String name = NodeTooltip.displayName(p.node().id());
                int nw = textRenderer.getWidth(name);
                int nameColor = switch (p.state()) {
                    case OWNED -> 0xFFB9F2B0;
                    case AVAILABLE -> 0xFFFFF3C0;
                    case SEALED -> ThemeAssets.WARNING_COLOR;
                    case LOCKED -> 0xFF8A8478;
                };
                int nx = (int) p.x() - nw / 2;
                int ny = (int) (p.y() + p.r() + 3);
                // A dark backing pill so a long name stays legible where it overlaps a neighbouring
                // node or edge (they used to blur together). Drawn only for the selected/hovered node
                // at full opacity, lighter for the rest, so the focused node's name always reads clearly.
                boolean focus = hover || selected;
                ctx.fill(nx - 3, ny - 2, nx + nw + 3, ny + 10, focus ? 0xF0141018 : 0xB0101014);
                ctx.drawText(textRenderer, Text.literal(name), nx, ny, nameColor, true);
            }
        }
        ctx.disableScissor();

        // Search box, drawn outside the canvas scissor so it never gets clipped. Shows the hint until
        // you type, then the query plus a live match count.
        String label = query.isEmpty()
                ? Text.translatable("kindreds.tree.search.hint").getString()
                : query + "  (" + searchMatches + ")";
        int sw = Math.max(96, textRenderer.getWidth(label) + 12);
        int sx = canvas[0] + canvas[2] - sw - 6;
        int sy = canvas[1] + 6;
        ctx.fill(sx, sy, sx + sw, sy + 14, 0xC0101014);
        ctx.drawBorder(sx, sy, sw, 14, query.isEmpty() ? 0xFF4A4030 : 0xFFD8B45F);
        ctx.drawText(textRenderer, Text.literal(label), sx + 5, sy + 3,
                query.isEmpty() ? 0xFF6E6A60 : 0xFFFFD86B, false);

        if (placed.isEmpty()) {
            String none = Text.translatable("kindreds.tree.empty").getString();
            int w = textRenderer.getWidth(none);
            ctx.drawText(textRenderer, Text.literal(none), canvas[0] + (canvas[2] - w) / 2, canvas[1] + canvas[3] / 2, 0xFF9A9484, true);
        }

        // View toggle (top-right) + control hint (bottom-left).
        String toggle = viewMode == ViewMode.MAP ? "◇ Whole map" : "▤ One branch";
        int tw = textRenderer.getWidth(toggle) + 12;
        viewToggleButton = new int[]{canvas[0] + canvas[2] - tw - 6, canvas[1] + 4, tw, 14};
        boolean th = within(viewToggleButton, mouseX, mouseY);
        ctx.fill(viewToggleButton[0], viewToggleButton[1], viewToggleButton[0] + tw, viewToggleButton[1] + 14,
                th ? ThemeAssets.withAlpha(accent, 90) : 0x80000000);
        ctx.drawBorder(viewToggleButton[0], viewToggleButton[1], tw, 14, accent);
        ctx.drawText(textRenderer, Text.literal(toggle), viewToggleButton[0] + 6, viewToggleButton[1] + 3, 0xFFFFFFFF, false);
        ctx.drawText(textRenderer, Text.translatable("kindreds.tree.pan_hint").formatted(Formatting.DARK_GRAY),
                canvas[0] + 8, canvas[1] + canvas[3] - 11, 0xFF6E6A60, false);
    }

    /** Places the focused discipline's nodes at fixed, readable spacing, centered horizontally, so
     * layout is independent of the raw authored {@code pos} magnitudes (which differ per tree). Column
     * order comes from distinct x values, row order from distinct y values. */
    private void layout(KindredData data) {
        placed.clear();
        nodeWorld.clear();
        laneBounds.clear();
        if (tree == null) {
            return;
        }
        // Each discipline is a vertical region; MAP lays out every region side by side, BRANCH lays
        // out only the selected discipline's region. Same world->screen transform for both.
        float laneX = 0f;
        float maxRowY = 0f;
        for (String disc : tabDisciplines) {
            if (viewMode == ViewMode.BRANCH && !disc.equals(selectedDiscipline)) {
                continue;
            }
            List<SkillNode> lane = new ArrayList<>();
            TreeMap<Integer, Integer> xs = new TreeMap<>();
            TreeMap<Integer, Integer> ys = new TreeMap<>();
            for (SkillNode node : tree.nodes()) {
                if (node.cost().disciplineId().getPath().equals(disc)) {
                    lane.add(node);
                    xs.put(node.pos()[0], 0);
                    ys.put(node.pos()[1], 0);
                }
            }
            if (lane.isEmpty()) {
                continue;
            }
            indexKeys(xs);
            indexKeys(ys);
            for (SkillNode node : lane) {
                float wx = laneX + xs.get(node.pos()[0]) * COL_SPACING;
                float wy = ys.get(node.pos()[1]) * ROW_SPACING;
                nodeWorld.put(node, new float[]{wx, wy});
                maxRowY = Math.max(maxRowY, wy);
            }
            float laneW = (xs.size() - 1) * COL_SPACING;
            laneBounds.put(disc, new float[]{laneX, laneX + laneW});
            laneX += laneW + LANE_GAP;
        }
        float totalW = Math.max(1f, laneX - LANE_GAP);
        worldCenterX = totalW / 2f;
        worldCenterY = maxRowY / 2f;
        if (!fitted && !nodeWorld.isEmpty()) {
            autoFit(totalW, maxRowY);
            fitted = true;
        }
        float cx = canvas[0] + canvas[2] / 2f;
        float cy = canvas[1] + CANVAS_TOP_PAD + (canvas[3] - CANVAS_TOP_PAD) / 2f;
        for (Map.Entry<SkillNode, float[]> e : nodeWorld.entrySet()) {
            SkillNode node = e.getKey();
            float sx = cx + panX + (e.getValue()[0] - worldCenterX) * zoom;
            float sy = cy + panY + (e.getValue()[1] - worldCenterY) * zoom;
            float r = (node.deedAdvancement().isPresent() ? NODE_R * 1.4f : NODE_R) * zoom;
            placed.add(new Placed(node, sx, sy, r, TreeRenderer.stateOf(node, data, tree)));
        }
    }

    /** Fit the current world extent into the canvas with padding. */
    private void autoFit(float worldW, float worldH) {
        float availW = canvas[2] - 44f;
        float availH = canvas[3] - CANVAS_TOP_PAD - 44f;
        float z = Math.min(availW / (worldW + 2 * NODE_R), availH / (worldH + 2 * NODE_R));
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        panX = 0f;
        panY = 0f;
    }

    private int disciplineColor(String disc) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) {
            try {
                var reg = client.world.getRegistryManager().getOrThrow(KindredsRegistries.DISCIPLINE);
                var d = reg.get(Identifier.of("kindreds", disc));
                if (d != null) {
                    return 0xFF000000 | d.colorInt();
                }
            } catch (RuntimeException ignored) {
                // registry not ready
            }
        }
        return ThemeAssets.accent(theme);
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
        visionButton = new int[]{0, 0, 0, 0};
        for (int[] sb : slotButtons) {
            sb[0] = sb[1] = sb[2] = sb[3] = 0;
        }
        if (selectedNode != null) {
            y = renderNodeDetail(ctx, data, x, y);
        } else {
            y = renderDisciplineSummary(ctx, data, x, y);
        }

        // Vision + titles, anchored lower.
        int vy = panel[1] + panel[3] - 96;
        ctx.drawText(textRenderer, Text.translatable("kindreds.tree.section.vision").formatted(Formatting.BOLD), x, vy, accent, false);
        Identifier lensId = data.activeVisionLens();
        String lens = lensId != null ? titleCase(lensId.getPath()) : "None equipped";
        ctx.drawText(textRenderer, Text.literal(lens), x, vy + 12, 0xFFD8D2C0, false);
        ctx.drawText(textRenderer, Text.translatable("kindreds.tree.section.titles").formatted(Formatting.BOLD), x, vy + 30, accent, false);
        String titles = data.titles().isEmpty() ? "None earned yet" : String.join(", ", data.titles());
        for (var line : textRenderer.wrapLines(Text.literal(titles), panel[2] - 20)) {
            ctx.drawText(textRenderer, line, x, vy + 42, 0xFFD8D2C0, false);
            break;
        }

        // Codex button (opens the full character/traits menu).
        codexButton = new int[]{panel[0] + 10, panel[1] + panel[3] - 56, panel[2] - 20, 20};
        boolean cHover = within(codexButton, mouseX, mouseY);
        ctx.fill(codexButton[0], codexButton[1], codexButton[0] + codexButton[2], codexButton[1] + codexButton[3],
                cHover ? ThemeAssets.withAlpha(accent, 80) : 0x50000000);
        ctx.drawBorder(codexButton[0], codexButton[1], codexButton[2], codexButton[3], accent);
        Text ct = Text.translatable("kindreds.tree.open_codex");
        int ctw = textRenderer.getWidth(ct);
        ctx.drawText(textRenderer, ct, codexButton[0] + (codexButton[2] - ctw) / 2, codexButton[1] + 6, 0xFFFFFFFF, true);

        // Respec button.
        respecButton = new int[]{panel[0] + 10, panel[1] + panel[3] - 30, panel[2] - 20, 22};
        boolean rHover = within(respecButton, mouseX, mouseY);
        ctx.fill(respecButton[0], respecButton[1], respecButton[0] + respecButton[2], respecButton[1] + respecButton[3],
                rHover ? ThemeAssets.withAlpha(accent, 80) : 0x50000000);
        ctx.drawBorder(respecButton[0], respecButton[1], respecButton[2], respecButton[3], accent);
        Text rt = Text.translatable("kindreds.tree.respec");
        int rtw = textRenderer.getWidth(rt);
        ctx.drawText(textRenderer, rt, respecButton[0] + (respecButton[2] - rtw) / 2, respecButton[1] + 7, 0xFFFFFFFF, true);
    }

    private int renderDisciplineSummary(DrawContext ctx, KindredData data, int x, int y) {
        if (selectedDiscipline == null) {
            ctx.drawText(textRenderer, Text.translatable("kindreds.tree.select_discipline").formatted(Formatting.GRAY), x, y, 0xFFB0AAA0, false);
            return y + 16;
        }
        int accent = ThemeAssets.accent(theme);
        int lvl = level(data, selectedDiscipline);
        int sp = spent(data, selectedDiscipline);
        int av = available(data, selectedDiscipline);
        ctx.drawText(textRenderer, Text.literal(titleCase(selectedDiscipline)).formatted(Formatting.BOLD), x, y, accent, false);
        y += 14;
        ctx.drawText(textRenderer, Text.translatable("kindreds.tree.level_line", lvl, sp), x, y, 0xFFD8D2C0, false);
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
        ctx.drawText(textRenderer, Text.translatable("kindreds.tree.xp_line", xp, next).formatted(Formatting.DARK_GRAY), x, y, 0xFF9A9484, false);
        y += 18;
        ctx.drawText(textRenderer, Text.translatable("kindreds.tree.click_node").formatted(Formatting.ITALIC),
                x, y, 0xFF9A9484, false);
        return y + 16;
    }

    /** The id of the first active ability on {@code node}, or {@code null} if it has none - used to
     * offer (and target) loadout-slot assignment from the tree panel. */
    private static String activeAbilityId(SkillNode node) {
        for (var ability : node.abilities()) {
            if (ability instanceof ActiveAbilityDef active) {
                return active.abilityId().toString();
            }
        }
        return null;
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
            case OWNED -> I18n.translate("kindreds.tree.status.owned");
            case AVAILABLE -> I18n.translate("kindreds.tree.status.available");
            case SEALED -> I18n.translate("kindreds.tree.status.sealed");
            case LOCKED -> I18n.translate("kindreds.tree.status.locked");
        };
        int statusColor = switch (state) {
            case OWNED -> 0xFF66DD66;
            case AVAILABLE -> 0xFFFFE070;
            case SEALED -> ThemeAssets.WARNING_COLOR;
            case LOCKED -> 0xFF9A9484;
        };
        ctx.drawText(textRenderer, Text.literal(status), x, y, statusColor, false);
        NodeKind kind = kindOf(node);
        String kindLabel = "[" + I18n.translate("kindreds.tree.kind." + kind.name().toLowerCase(Locale.ROOT)) + "]";
        ctx.drawText(textRenderer, Text.literal(kindLabel), panel[0] + panel[2] - 10 - textRenderer.getWidth(kindLabel), y, kind.color, false);
        y += 14;

        for (var line : textRenderer.wrapLines(Text.literal(NodeTooltip.flavor(node)).formatted(Formatting.GRAY), wrap)) {
            ctx.drawText(textRenderer, line, x, y, 0xFFB6B0A2, false);
            y += 10;
        }
        y += 4;
        ctx.drawText(textRenderer, Text.literal(I18n.translate("kindreds.tree.effects")).formatted(Formatting.BOLD), x, y, accent, false);
        y += 12;
        for (var ability : node.abilities()) {
            // describe() embeds legacy color codes (e.g. curses); the String draw path honors them.
            for (var line : textRenderer.wrapLines(Text.literal(NodeTooltip.describe(ability)), wrap)) {
                ctx.drawText(textRenderer, line, x + 4, y, 0xFFE6E0D0, false);
                y += 10;
            }
        }
        y += 4;
        ctx.drawText(textRenderer, Text.literal(I18n.translate("kindreds.tree.cost", node.cost().points(),
                I18n.translate("kindreds.discipline." + selectedDiscipline))), x, y, 0xFF7FD0E0, false);
        y += 12;
        if (!node.prereqs().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String pid : node.prereqs()) {
                names.add(tree.node(pid).map(n -> NodeTooltip.displayName(n.id())).orElse(pid));
            }
            for (var line : textRenderer.wrapLines(Text.literal(I18n.translate("kindreds.tree.requires", String.join(", ", names))).formatted(Formatting.DARK_GRAY), wrap)) {
                ctx.drawText(textRenderer, line, x, y, 0xFF9A9484, false);
                y += 10;
            }
        }
        node.deedAdvancement().ifPresent(deed -> { /* shown via status line above */ });

        // How to use, by kind - so the player knows whether a skill just works or needs a key/equip.
        y += 5;
        String use = switch (kind) {
            case PASSIVE -> I18n.translate("kindreds.tree.use.passive");
            case ACTIVE -> {
                int cd = activeCooldownSeconds(node);
                String keyName = KindredsClient.useAbilityKeyName().getString();
                yield cd > 0 ? I18n.translate("kindreds.tree.use.active_cd", keyName, cd)
                        : I18n.translate("kindreds.tree.use.active", keyName);
            }
            case VISION -> {
                String keyName = KindredsClient.cycleVisionKeyName().getString();
                yield state == TreeRenderer.NodeState.OWNED
                        ? I18n.translate("kindreds.tree.use.vision_owned", keyName)
                        : I18n.translate("kindreds.tree.use.vision", keyName);
            }
            case CURSE -> I18n.translate("kindreds.tree.use.curse", curseWhenText(node));
        };
        for (var line : textRenderer.wrapLines(Text.literal(use).formatted(Formatting.ITALIC), wrap)) {
            ctx.drawText(textRenderer, line, x, y, kind.color, false);
            y += 10;
        }

        // Equip/Unequip button for an owned vision node.
        if (state == TreeRenderer.NodeState.OWNED && kind == NodeKind.VISION) {
            Identifier lensId = visionLensId(node);
            boolean activeLens = lensId != null && lensId.equals(VisionManager.activeLens());
            y += 4;
            visionButton = new int[]{x, y, wrap, 20};
            boolean hover = within(visionButton, lastMouseX, lastMouseY);
            ctx.fill(visionButton[0], visionButton[1], visionButton[0] + visionButton[2], visionButton[1] + visionButton[3],
                    activeLens ? 0xC03A2E10 : (hover ? 0xC0104834 : 0x80183028));
            ctx.drawBorder(visionButton[0], visionButton[1], visionButton[2], visionButton[3],
                    activeLens ? ThemeAssets.WARNING_COLOR : 0xFF7CE0C0);
            String label = activeLens ? I18n.translate("kindreds.tree.unequip_vision") : I18n.translate("kindreds.tree.equip_vision");
            int lw = textRenderer.getWidth(label);
            ctx.drawText(textRenderer, Text.literal(label), visionButton[0] + (visionButton[2] - lw) / 2, visionButton[1] + 6, 0xFFFFFFFF, true);
            y += 24;
        }

        // Assign-to-slot row for an owned active ability - equip it straight from the tree.
        if (state == TreeRenderer.NodeState.OWNED && kind == NodeKind.ACTIVE) {
            String abilityId = activeAbilityId(node);
            if (abilityId != null) {
                y += 4;
                ctx.drawText(textRenderer, Text.literal(I18n.translate("kindreds.tree.equip_slot")).formatted(Formatting.BOLD),
                        x, y, accent, false);
                y += 12;
                int n = com.kindreds.client.loadout.ClientLoadout.SLOTS;
                int bw = (wrap - (n - 1) * 4) / n;
                for (int i = 0; i < n; i++) {
                    int bx = x + i * (bw + 4);
                    slotButtons[i] = new int[]{bx, y, bw, 20};
                    boolean holds = abilityId.equals(com.kindreds.client.loadout.ClientLoadout.slot(i));
                    boolean hover = within(slotButtons[i], lastMouseX, lastMouseY);
                    ctx.fill(bx, y, bx + bw, y + 20, holds ? 0xC03A2E10 : (hover ? 0xC0104834 : 0x80183028));
                    ctx.drawBorder(bx, y, bw, 20, holds ? 0xFFF0C000 : 0xFF7CE0C0);
                    String lbl = String.valueOf(i + 1);
                    ctx.drawText(textRenderer, Text.literal(lbl), bx + (bw - textRenderer.getWidth(lbl)) / 2,
                            y + 6, holds ? 0xFFF0C000 : 0xFFFFFFFF, true);
                }
                y += 24;
                ctx.drawText(textRenderer, Text.literal(I18n.translate("kindreds.tree.equip_hint")).formatted(Formatting.DARK_GRAY),
                        x, y, 0xFF8A8478, false);
                y += 11;
            }
        }

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
                    ? I18n.translate("kindreds.tree.attempt_sealed")
                    : I18n.translate("kindreds.tree.learn", node.cost().points());
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
        // Codex.
        if (within(codexButton, mouseX, mouseY)) {
            KindredCodexScreen.open(MinecraftClient.getInstance());
            return true;
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
        // Equip/unequip vision button.
        if (selectedNode != null && within(visionButton, mouseX, mouseY)) {
            Identifier lensId = visionLensId(selectedNode);
            if (lensId != null) {
                boolean activeLens = lensId.equals(VisionManager.activeLens());
                VisionManager.equip(activeLens ? null : lensId);
            }
            return true;
        }
        // View toggle (whole map <-> one branch).
        if (within(viewToggleButton, mouseX, mouseY)) {
            viewMode = viewMode == ViewMode.MAP ? ViewMode.BRANCH : ViewMode.MAP;
            fitted = false;
            return true;
        }
        // Discipline tabs.
        for (int i = 0; i < tabRects.size(); i++) {
            if (within(tabRects.get(i), mouseX, mouseY)) {
                selectedDiscipline = tabDisciplines.get(i);
                selectedNode = null;
                if (viewMode == ViewMode.BRANCH) {
                    fitted = false; // re-fit to the newly focused discipline
                } else {
                    float[] lb = laneBounds.get(selectedDiscipline);
                    if (lb != null) {
                        panX = -((lb[0] + lb[1]) / 2f - worldCenterX) * zoom; // pan the map to that region
                    }
                }
                return true;
            }
        }
        // Equip an owned active ability into a loadout slot, straight from the tree panel.
        if (selectedNode != null) {
            String abilityId = activeAbilityId(selectedNode);
            if (abilityId != null) {
                for (int i = 0; i < slotButtons.length; i++) {
                    if (within(slotButtons[i], mouseX, mouseY)) {
                        boolean holds = abilityId.equals(com.kindreds.client.loadout.ClientLoadout.slot(i));
                        com.kindreds.client.loadout.ClientLoadout.setSlot(i, holds ? "" : abilityId);
                        return true;
                    }
                }
            }
        }
        // Canvas nodes.
        for (Placed p : placed) {
            if (dist(mouseX, mouseY, p.x(), p.y()) <= p.r() + 3) {
                selectedNode = p.node();
                return true;
            }
        }
        // Empty canvas: begin dragging the map.
        if (within(canvas, mouseX, mouseY)) {
            dragging = true;
            dragPrevX = mouseX;
            dragPrevY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging && button == 0) {
            panX += (float) (mouseX - dragPrevX);
            panY += (float) (mouseY - dragPrevY);
            dragPrevX = mouseX;
            dragPrevY = mouseY;
            fitted = true;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tree != null && within(rail, mouseX, mouseY)) {
            railScroll += (float) verticalAmount * 18;   // clamped in renderTabRail
            return true;
        }
        if (tree != null && within(canvas, mouseX, mouseY)) {
            float old = zoom;
            zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (verticalAmount > 0 ? 1.12f : 0.89f)));
            float cx = canvas[0] + canvas[2] / 2f;
            float cy = canvas[1] + CANVAS_TOP_PAD + (canvas[3] - CANVAS_TOP_PAD) / 2f;
            // Keep the world point under the cursor fixed while zooming.
            float wx = (float) ((mouseX - cx - panX) / old) + worldCenterX;
            float wy = (float) ((mouseY - cy - panY) / old) + worldCenterY;
            panX = (float) mouseX - cx - (wx - worldCenterX) * zoom;
            panY = (float) mouseY - cy - (wy - worldCenterY) * zoom;
            fitted = true;
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
        }, Text.translatable("kindreds.tree.respec_confirm_title"),
                Text.translatable("kindreds.tree.respec_confirm_body")));
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
            case "exclusive_conflict" -> "You've committed to a different path — this one is closed.";
            case "already_owned" -> "You already know this.";
            case "deed_not_earned" -> "Earn its deed first to break the seal.";
            case "soft_cap" -> "You have committed all you can — respec to spend elsewhere.";
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
