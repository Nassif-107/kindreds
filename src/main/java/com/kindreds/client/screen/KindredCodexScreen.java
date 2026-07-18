package com.kindreds.client.screen;

import com.kindreds.data.BirthTrait;
import com.kindreds.data.Disciplines;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillTree;
import com.kindreds.data.Theme;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.LevelCurve;
import com.kindreds.progression.ProgressionService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The "Kindred Codex" - a browsable almanac of every race's innate birth boons and banes, plus (for
 * the player's own race) their disciplines, vision, and titles. Replaces the old {@code /kindreds
 * inspect} chat dump, and doubles as the race-choosing reference (◄ ► to browse all peoples), so it
 * works both in-world and from the onboarding screen where the player has no race yet. Purely
 * client-side: reads {@link ClientKindredData} and the synced registries.
 */
public class KindredCodexScreen extends Screen {
    private static final int PANEL_W = 380;
    private static final int LINE = 11;

    /** The eight base-mod races, in Codex browse order. */
    private static final List<Identifier> ALL_RACES = List.of(
            id("elf"), id("dwarf"), id("human"), id("hobbit"), id("orc"), id("uruk"), id("goblin"), id("snaga"));

    private final KindredData data;
    private final Screen parent;

    private Identifier playerRace;
    private int browseIndex;
    private Theme theme;
    private BirthTrait birth;
    private SkillTree tree;

    private int[] prevBtn = new int[4];
    private int[] nextBtn = new int[4];
    private float scrollY;
    private int contentHeight;

    public KindredCodexScreen(KindredData data, Screen parent) {
        super(Text.literal("Kindred Codex"));
        this.data = data != null ? data : new KindredData();
        this.parent = parent;
    }

    public static void open(MinecraftClient client) {
        client.setScreen(new KindredCodexScreen(ClientKindredData.INSTANCE, client.currentScreen));
    }

    private static Identifier id(String race) {
        return Identifier.of("middle-earth", race);
    }

    @Override
    protected void init() {
        playerRace = data.race();
        browseIndex = Math.max(0, ALL_RACES.indexOf(playerRace));
        resolveBrowse();
    }

    private Identifier browseRace() {
        return ALL_RACES.get(browseIndex);
    }

    private void resolveBrowse() {
        birth = null;
        tree = null;
        theme = null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        Identifier race = browseRace();
        try {
            Registry<BirthTrait> births = client.world.getRegistryManager().getOrThrow(KindredsRegistries.BIRTH_TRAIT);
            for (BirthTrait bt : births) {
                if (bt.race().equals(race)) {
                    birth = bt;
                    break;
                }
            }
            Registry<SkillTree> trees = client.world.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
            for (SkillTree t : trees) {
                if (t.race().equals(race)) {
                    tree = t;
                    break;
                }
            }
            if (tree != null) {
                Registry<Theme> themes = client.world.getRegistryManager().getOrThrow(KindredsRegistries.THEME);
                theme = themes.get(tree.theme());
            }
        } catch (RuntimeException ignored) {
            // registries not synced yet
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xDC080808);

        int panelX = (width - PANEL_W) / 2;
        int panelY = 18;
        int panelH = height - 36;
        TreeRenderer.drawBackground(ctx, theme, panelX, panelY, PANEL_W, panelH);
        TreeRenderer.drawFrame(ctx, theme, panelX, panelY, PANEL_W, panelH);

        int accent = theme != null ? ThemeAssets.accent(theme) : 0xFFC8B884;
        boolean isOwn = browseRace().equals(playerRace);

        // Header with ◄ race ► navigation.
        String raceName = titleCase(browseRace().getPath()) + (isOwn ? "  (your people)" : "");
        int tw = textRenderer.getWidth(raceName);
        int cx = panelX + PANEL_W / 2;
        ctx.drawText(textRenderer, Text.literal(raceName).formatted(Formatting.BOLD), cx - tw / 2, panelY + 9, accent, true);
        prevBtn = new int[]{panelX + 10, panelY + 6, 18, 16};
        nextBtn = new int[]{panelX + PANEL_W - 28, panelY + 6, 18, 16};
        drawNav(ctx, prevBtn, "◄", within(prevBtn, mouseX, mouseY), accent);
        drawNav(ctx, nextBtn, "►", within(nextBtn, mouseX, mouseY), accent);

        int viewTop = panelY + 26;
        int viewBottom = panelY + panelH - 16;
        ctx.enableScissor(panelX + 1, viewTop, panelX + PANEL_W - 1, viewBottom);
        int x = panelX + 16;
        int y = viewTop + (int) scrollY;
        int wrap = PANEL_W - 44;

        y = section(ctx, x, y, accent, "The Ways of the Kindreds");
        y = wrapLine(ctx, x + 6, y, wrap, "§7• Open your skill tree with §fK§7, then spend discipline points earned through your deeds.");
        y = wrapLine(ctx, x + 6, y, wrap, "§7• Earn points by living your people's life — fight, mine, sneak, craft, explore.");
        y = wrapLine(ctx, x + 6, y, wrap, "§7• Passive skills are always on. Active skills fire with §fG§7. Vision toggles with §fV§7 (or Equip it in the tree).");
        y = wrapLine(ctx, x + 6, y, wrap, "§7• Birth traits below are innate. Some wake with place & time — starlight, the deep places, the open Sun.");
        y += 10;

        y = section(ctx, x, y, accent, "Born of your blood");
        if (birth != null) {
            for (String plus : birth.pluses()) {
                y = wrapLine(ctx, x + 6, y, wrap, "§a✦ " + plus);
            }
            for (String minus : birth.minuses()) {
                y = wrapLine(ctx, x + 6, y, wrap, "§c✖ " + minus);
            }
        } else {
            y = wrapLine(ctx, x + 6, y, wrap, "§7(no birth traits recorded for this people)");
        }
        y += 10;

        if (isOwn) {
            y = section(ctx, x, y, accent, "Disciplines");
            for (String disc : Disciplines.ALL) {
                Identifier discId = Identifier.of("kindreds", disc);
                int level = ProgressionService.pointsForLevel(data.xpIn(discId));
                int spent = tree != null ? ProgressionService.pointsSpent(data, tree, discId) : 0;
                int avail = level - spent;
                String head = String.format("%-10s Lv %-2d   %d spent   %s", titleCase(disc), level, spent,
                        avail > 0 ? "§a+" + avail + " to spend§r" : "");
                ctx.drawText(textRenderer, Text.literal(head), x + 6, y, 0xFFE6E0D0, false);
                y += LINE;
                long xp = data.xpIn(discId);
                long lo = LevelCurve.xpForLevel(level);
                long hi = LevelCurve.xpForLevel(level + 1);
                float frac = hi > lo ? Math.max(0f, Math.min(1f, (float) (xp - lo) / (float) (hi - lo))) : 0f;
                ctx.fill(x + 6, y, x + 6 + wrap, y + 4, 0x60000000);
                ctx.fill(x + 6, y, x + 6 + (int) (wrap * frac), y + 4, theme != null ? ThemeAssets.ownedColor(theme) : 0xFF7FB0FF);
                y += 9;
            }
            y += 10;

            y = section(ctx, x, y, accent, "Vision & Titles");
            String lens = data.activeVisionLens() != null ? titleCase(data.activeVisionLens().getPath()) : "None equipped";
            ctx.drawText(textRenderer, Text.literal("Vision: " + lens), x + 6, y, 0xFFD8D2C0, false);
            y += LINE;
            String titles = data.titles().isEmpty() ? "None earned yet" : String.join(", ", data.titles());
            y = wrapLine(ctx, x + 6, y, wrap, "Titles: " + titles);
            ctx.drawText(textRenderer, Text.literal("Skills learned: " + data.unlockedNodes().size()), x + 6, y, 0xFFD8D2C0, false);
            y += LINE;
        } else {
            y = wrapLine(ctx, x + 6, y, wrap,
                    "§8Choose this people (in the Player's Book) to walk their path and earn their skills.");
        }

        contentHeight = (y - (int) scrollY) - viewTop;
        ctx.disableScissor();

        String hint = "◄ ► browse peoples · scroll to read · Esc to close";
        int hw = textRenderer.getWidth(hint);
        ctx.drawText(textRenderer, Text.literal(hint).formatted(Formatting.DARK_GRAY),
                cx - hw / 2, panelY + panelH - 11, 0xFF6E6A60, false);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawNav(DrawContext ctx, int[] r, String glyph, boolean hover, int accent) {
        ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? ThemeAssets.withAlpha(accent, 90) : 0x50000000);
        ctx.drawBorder(r[0], r[1], r[2], r[3], accent);
        int gw = textRenderer.getWidth(glyph);
        ctx.drawText(textRenderer, Text.literal(glyph), r[0] + (r[2] - gw) / 2, r[1] + 4, 0xFFFFFFFF, false);
    }

    private int section(DrawContext ctx, int x, int y, int accent, String label) {
        ctx.drawText(textRenderer, Text.literal(label).formatted(Formatting.BOLD), x, y, accent, true);
        ctx.fill(x, y + 10, x + PANEL_W - 32, y + 11, ThemeAssets.withAlpha(accent, 120));
        return y + 15;
    }

    private int wrapLine(DrawContext ctx, int x, int y, int wrap, String text) {
        for (var line : textRenderer.wrapLines(Text.literal(text), wrap)) {
            ctx.drawText(textRenderer, line, x, y, 0xFFCEC8B8, false);
            y += LINE;
        }
        return y;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && within(prevBtn, mouseX, mouseY)) {
            browseIndex = (browseIndex - 1 + ALL_RACES.size()) % ALL_RACES.size();
            scrollY = 0;
            resolveBrowse();
            return true;
        }
        if (button == 0 && within(nextBtn, mouseX, mouseY)) {
            browseIndex = (browseIndex + 1) % ALL_RACES.size();
            scrollY = 0;
            resolveBrowse();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int viewH = height - 36 - 42;
        float minScroll = Math.min(0, viewH - contentHeight);
        scrollY = Math.max(minScroll, Math.min(0, scrollY + (float) verticalAmount * 18));
        return true;
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    private static boolean within(int[] r, double x, double y) {
        return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
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
