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
import net.minecraft.client.resource.language.I18n;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The "Kindred Codex" - a browsable, book-styled almanac of every race's innate boons and banes,
 * plus (for the player's own race) their disciplines, vision, and titles. Drawn as an aged-parchment
 * tome (leather cover, ink text) rather than the race theme's background. Purely client-side.
 *
 * <p>The traits are read far more often than they are browsed, so the page is built around reading
 * them. They are grouped by <em>kind of fact</em> - what is always true of your body, what wakes
 * only in some place or hour, what you carry as a burden - because a flat list of twelve lines gives
 * a player no way to tell a permanent gift from one that needs open night sky. The grouping comes
 * from the data ({@link BirthTrait#conditionalPluses()}), not from guessing at the prose.
 *
 * <p>Everything that is not a trait sits below them: the disciplines, then the guide to how learning
 * works, which used to open the page and pushed the actual traits under the fold on every visit.
 */
public class KindredCodexScreen extends Screen {
    /** The page never wants to be wider than this - long lines of prose are tiring to read. */
    private static final int PAGE_W_MAX = 400;
    private static final int LINE = 11;

    // Parchment palette (dark ink on aged paper - readable, book-like).
    private static final int LEATHER = 0xFF3E2C18;
    private static final int PARCHMENT = 0xFFEBE0C6;
    private static final int INK = 0xFF33281A;
    private static final int INK_HEADER = 0xFF7A2E1A;
    private static final int INK_MUTE = 0xFF6E6046;
    private static final int BOON = 0xFF2F6B2C;
    private static final int BANE = 0xFF9A2B2B;
    /** Illumination: a gold dark enough to hold its own against parchment, as the hub's is against leather. */
    private static final int GILT = 0xFF9A6E1E;
    private static final int RULE = 0x55000000;

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
    /** One hit rect per race tab, rebuilt each frame beside the tabs it describes. */
    private final java.util.List<int[]> tabs = new java.util.ArrayList<>();
    private float scrollY;
    private int contentHeight;
    /** Height of the scrolling viewport, kept from the last frame so scrolling can clamp to it. */
    private int viewHeight = 1;
    private String hoverTip;

    public KindredCodexScreen(KindredData data, Screen parent) {
        super(Text.translatable("kindreds.codex.title"));
        this.data = data != null ? data : new KindredData();
        this.parent = parent;
    }

    public static void open(MinecraftClient client) {
        client.setScreen(new KindredCodexScreen(ClientKindredData.INSTANCE, client.currentScreen));
    }

    private static Identifier id(String race) {
        return Identifier.of("middle-earth", race);
    }

    /** The page as actually drawn: capped for readability, but shrunk when the window is narrower
     * than the cap, which a fixed 400 could not do - it simply ran off both edges at small sizes
     * and at high GUI scales, taking the margin and the scrollbar with it. */
    private int pageWidth() {
        return Math.min(PAGE_W_MAX, Math.max(180, this.width - 40));
    }

    /** Short windows lose the ornament first, then the padding - never the text. */
    private boolean compact() {
        return this.height < 300;
    }

    @Override
    protected void init() {
        playerRace = data.race();
        int idx = playerRace == null ? -1 : ALL_RACES.indexOf(playerRace);
        browseIndex = idx < 0 ? 0 : idx;
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

    private static String raceName(Identifier race) {
        return I18n.translate("kindreds.race." + race.getPath());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0000000);
        hoverTip = null;

        int pw = pageWidth();
        int px = (width - pw) / 2;
        int py = 16;
        int ph = height - 32;
        drawBook(ctx, px, py, pw, ph);

        boolean isOwn = browseRace().equals(playerRace);
        int cx = px + pw / 2;

        // Eight races, one click each. Paging ◄ ► alone meant up to seven clicks to reach a kindred,
        // and the initials are taken from the localised name so the row never shows English letters
        // over a translated page; where two share a letter the hover name tells them apart.
        int tabTop = py + (compact() ? 6 : 9);
        int tabW = Math.min(22, (pw - 36) / ALL_RACES.size());
        int tabH = 13;
        int tabX = cx - (tabW * ALL_RACES.size() + (ALL_RACES.size() - 1) * 2) / 2;
        tabs.clear();
        for (int i = 0; i < ALL_RACES.size(); i++) {
            int[] r = {tabX + i * (tabW + 2), tabTop, tabW, tabH};
            tabs.add(r);
            drawTab(ctx, r, ALL_RACES.get(i), i == browseIndex,
                    ALL_RACES.get(i).equals(playerRace), within(r, mouseX, mouseY));
        }

        int titleY = tabTop + tabH + (compact() ? 5 : 8);
        String heading = raceName(browseRace());
        int tw = textRenderer.getWidth(heading);
        ctx.drawText(textRenderer, Text.literal(heading).formatted(Formatting.BOLD),
                cx - tw / 2, titleY, INK_HEADER, false);
        if (isOwn) {
            String mine = I18n.translate("kindreds.codex.your_people");
            int mw = textRenderer.getWidth(mine);
            ctx.drawText(textRenderer, Text.literal(mine).formatted(Formatting.ITALIC),
                    cx - mw / 2, titleY + 11, GILT, false);
        }
        int ruleY = titleY + (isOwn ? 23 : 12);
        ctx.fill(px + 30, ruleY, px + pw - 30, ruleY + 1, RULE);

        int viewTop = ruleY + 6;
        int viewBottom = py + ph - 22;
        viewHeight = Math.max(1, viewBottom - viewTop);
        clampScroll();

        int x = px + 26;
        int wrap = pw - 58;                        // room kept on the right for the scrollbar
        ctx.enableScissor(px + 14, viewTop, px + pw - 14, viewBottom);
        int y = viewTop + (int) scrollY;

        if (birth == null) {
            y = ink(ctx, x, y, wrap, I18n.translate("kindreds.codex.gifts.none"));
        } else {
            y = group(ctx, x, y, wrap, "kindreds.codex.section.born", GILT,
                    birth.pluses(), List.of());
            y = group(ctx, x, y, wrap, "kindreds.codex.section.conditional", GILT,
                    birth.conditionalPluses(), birth.conditionalMinuses());
            y = group(ctx, x, y, wrap, "kindreds.codex.section.burdens", BANE,
                    List.of(), birth.minuses());
        }

        if (isOwn) {
            y = sectionHead(ctx, x, y, wrap, "kindreds.codex.section.disciplines", GILT);
            for (String disc : Disciplines.ALL) {
                y = discipline(ctx, x + 10, y, wrap - 10, disc);
            }
            y += 10;

            y = sectionHead(ctx, x, y, wrap, "kindreds.codex.section.vision_titles", GILT);
            String lens;
            if (data.activeVisionLens() != null) {
                String vp = data.activeVisionLens().getPath();
                lens = I18n.hasTranslation("kindreds.vision." + vp) ? I18n.translate("kindreds.vision." + vp) : titleCase(vp);
            } else {
                lens = I18n.translate("kindreds.codex.vision.none");
            }
            y = ink(ctx, x + 10, y, wrap - 10, I18n.translate("kindreds.codex.vision", lens));
            java.util.List<String> titleKeys =
                    com.kindreds.progression.RenownService.titleKeys(data);
            String titles = titleKeys.isEmpty()
                    ? I18n.translate("kindreds.codex.titles.none")
                    : titleKeys.stream().map(I18n::translate).collect(java.util.stream.Collectors.joining(", "));
            y = ink(ctx, x + 10, y, wrap - 10, I18n.translate("kindreds.codex.titles", titles));
            y = ink(ctx, x + 10, y, wrap - 10,
                    I18n.translate("kindreds.codex.skills_learned", data.unlockedNodes().size()));
            y += 10;

            // The guide sits last. It is read once and the traits are read forever; opening with it
            // put four paragraphs of instruction above the thing the page is named for.
            y = sectionHead(ctx, x, y, wrap, "kindreds.codex.section.ways", INK_MUTE);
            y = ink(ctx, x + 10, y, wrap - 10, I18n.translate("kindreds.codex.guide.tree"));
            y = ink(ctx, x + 10, y, wrap - 10, I18n.translate("kindreds.codex.guide.earn"));
            y = ink(ctx, x + 10, y, wrap - 10, I18n.translate("kindreds.codex.guide.skills"));
            y = ink(ctx, x + 10, y, wrap - 10, I18n.translate("kindreds.codex.guide.gifts"));
        } else {
            y = ink(ctx, x, y, wrap, I18n.translate("kindreds.codex.choose"));
        }

        contentHeight = (y - (int) scrollY) - viewTop;
        ctx.disableScissor();
        drawScrollbar(ctx, px + pw - 20, viewTop, viewBottom);

        prevBtn = new int[]{px + 14, py + ph - 20, 58, 15};
        nextBtn = new int[]{px + pw - 72, py + ph - 20, 58, 15};
        drawTurn(ctx, prevBtn, "◄ " + I18n.translate("kindreds.codex.prev"), within(prevBtn, mouseX, mouseY));
        drawTurn(ctx, nextBtn, I18n.translate("kindreds.codex.next") + " ►", within(nextBtn, mouseX, mouseY));
        String pageNo = (browseIndex + 1) + " / " + ALL_RACES.size();
        int nw = textRenderer.getWidth(pageNo);
        ctx.drawText(textRenderer, Text.literal(pageNo), cx - nw / 2, py + ph - 17, INK_MUTE, false);

        super.render(ctx, mouseX, mouseY, delta);
        if (hoverTip != null) {
            ctx.drawTooltip(textRenderer, Text.literal(hoverTip), mouseX, mouseY);
        }
    }

    // --- pieces of the page ----------------------------------------------------------------------

    private void drawBook(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x - 4, y - 4, x + w + 4, y + h + 4, LEATHER);          // leather cover
        ctx.fill(x - 4, y - 4, x + w + 4, y - 1, 0xFF5A4026);           // top edge highlight
        ctx.fill(x, y, x + w, y + h, PARCHMENT);                        // page
        ctx.fillGradient(x, y, x + w, y + h, 0x00000000, 0x22000000);   // gentle age-shade
        ctx.fillGradient(x, y, x + 10, y + h, 0x33000000, 0x00000000);  // inner-margin shadow
        ctx.drawBorder(x + 5, y + 5, w - 10, h - 10, 0x44000000);       // keyline
    }

    private void drawTab(DrawContext ctx, int[] r, Identifier race, boolean current, boolean own, boolean hover) {
        String name = raceName(race);
        ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3],
                current ? 0x33000000 : hover ? 0x1E000000 : 0x0E000000);
        ctx.drawBorder(r[0], r[1], r[2], r[3], current ? 0xFF9A6E1E : 0x33000000);
        if (own) { // a gilt foot marks your own people wherever they sit in the row
            ctx.fill(r[0] + 2, r[1] + r[3] - 2, r[0] + r[2] - 2, r[1] + r[3] - 1, GILT);
        }
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        int iw = textRenderer.getWidth(initial);
        ctx.drawText(textRenderer, Text.literal(initial), r[0] + (r[2] - iw) / 2, r[1] + 3,
                current ? INK_HEADER : hover ? INK : INK_MUTE, false);
        if (hover) {
            hoverTip = name;
        }
    }

    /**
     * One illuminated group of trait lines, with a gilt rail down its left margin so the eye can see
     * where a group begins and ends without counting bullets. Drawn only when it has something in it -
     * an Elf has no unconditional bane, and an empty "Burdens" heading reads as a missing entry.
     */
    private int group(DrawContext ctx, int x, int y, int wrap, String labelKey, int accent,
                      List<String> boons, List<String> banes) {
        if (boons.isEmpty() && banes.isEmpty()) {
            return y;
        }
        y = sectionHead(ctx, x, y, wrap, labelKey, accent);
        int top = y;
        for (String boon : boons) {
            y = bullet(ctx, x + 10, y, wrap - 10, "✦", BOON, I18n.translate(boon));
        }
        for (String bane : banes) {
            y = bullet(ctx, x + 10, y, wrap - 10, "✖", BANE, I18n.translate(bane));
        }
        ctx.fill(x + 3, top, x + 4, y - 3, (accent & 0x00FFFFFF) | 0xAA000000);
        return y + 10;
    }

    private int sectionHead(DrawContext ctx, int x, int y, int wrap, String labelKey, int accent) {
        String label = I18n.translate(labelKey);
        ctx.drawText(textRenderer, Text.literal(label).formatted(Formatting.BOLD), x, y, accent, false);
        int lw = textRenderer.getWidth(label);
        // the rule runs from the end of the label to the margin, as a chapter head does in a book
        ctx.fill(x + lw + 6, y + 4, x + wrap, y + 5, RULE);
        return y + 14;
    }

    private int discipline(DrawContext ctx, int x, int y, int wrap, String disc) {
        Identifier discId = Identifier.of("kindreds", disc);
        int level = ProgressionService.pointsForLevel(data.xpIn(discId));
        int spent = tree != null ? ProgressionService.pointsSpent(data, tree, discId) : 0;
        int avail = level - spent;

        String name = I18n.translate("kindreds.discipline." + disc);
        ctx.drawText(textRenderer, Text.literal(name), x, y, avail > 0 ? BOON : INK, false);
        String right = I18n.translate("kindreds.codex.disc.line", level, spent)
                + (avail > 0 ? "   " + I18n.translate("kindreds.codex.disc.to_spend", avail) : "");
        // right-aligned so the numbers form a column instead of drifting with the name's length,
        // which a fixed-width %-12s could not do in a proportional font
        ctx.drawText(textRenderer, Text.literal(right), x + wrap - textRenderer.getWidth(right), y,
                avail > 0 ? BOON : INK_MUTE, false);
        y += LINE;

        long xp = data.xpIn(discId);
        long lo = LevelCurve.xpForLevel(level);
        long hi = LevelCurve.xpForLevel(level + 1);
        float frac = hi > lo ? Math.max(0f, Math.min(1f, (float) (xp - lo) / (float) (hi - lo))) : 0f;
        ctx.fill(x, y, x + wrap, y + 3, 0x33000000);
        ctx.fill(x, y, x + (int) (wrap * frac), y + 3, 0xFF7A5A2E);
        return y + 8;
    }

    private int ink(DrawContext ctx, int x, int y, int wrap, String text) {
        for (var line : textRenderer.wrapLines(Text.literal(text), wrap)) {
            ctx.drawText(textRenderer, line, x, y, INK, false);
            y += LINE;
        }
        return y + 2;
    }

    private int bullet(DrawContext ctx, int x, int y, int wrap, String glyph, int color, String text) {
        ctx.drawText(textRenderer, Text.literal(glyph), x, y, color, false);
        boolean first = true;
        for (var line : textRenderer.wrapLines(Text.literal(text), wrap - 12)) {
            ctx.drawText(textRenderer, line, x + 12, y, first ? color : INK, false);
            y += LINE;
            first = false;
        }
        return y + 3;             // a little air between entries; twelve lines run together without it
    }

    /** Shows there is more page below - the old view gave no sign that it scrolled at all. */
    private void drawScrollbar(DrawContext ctx, int x, int top, int bottom) {
        int track = bottom - top;
        if (contentHeight <= track) {
            return;
        }
        ctx.fill(x, top, x + 3, bottom, 0x22000000);
        int thumb = Math.max(16, track * track / contentHeight);
        int travel = track - thumb;
        float frac = -scrollY / (float) (contentHeight - track);
        int ty = top + (int) (travel * Math.max(0f, Math.min(1f, frac)));
        ctx.fill(x, ty, x + 3, ty + thumb, 0x88000000);
    }

    private void drawTurn(DrawContext ctx, int[] r, String label, boolean hover) {
        ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0x33000000 : 0x18000000);
        ctx.drawBorder(r[0], r[1], r[2], r[3], 0x66000000);
        int lw = textRenderer.getWidth(label);
        ctx.drawText(textRenderer, Text.literal(label), r[0] + (r[2] - lw) / 2, r[1] + 4, INK_HEADER, false);
    }

    // --- input ------------------------------------------------------------------------------------

    private void showRace(int index) {
        browseIndex = (index + ALL_RACES.size()) % ALL_RACES.size();
        scrollY = 0;
        resolveBrowse();
    }

    private void clampScroll() {
        float minScroll = Math.min(0, viewHeight - contentHeight);
        scrollY = Math.max(minScroll, Math.min(0, scrollY));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int i = 0; i < tabs.size(); i++) {
                if (within(tabs.get(i), mouseX, mouseY)) {
                    showRace(i);
                    return true;
                }
            }
            if (within(prevBtn, mouseX, mouseY)) {
                showRace(browseIndex - 1);
                return true;
            }
            if (within(nextBtn, mouseX, mouseY)) {
                showRace(browseIndex + 1);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case 263 -> { // GLFW LEFT
                showRace(browseIndex - 1);
                return true;
            }
            case 262 -> { // GLFW RIGHT
                showRace(browseIndex + 1);
                return true;
            }
            case 265 -> { // GLFW UP
                scrollY += 22;
                clampScroll();
                return true;
            }
            case 264 -> { // GLFW DOWN
                scrollY -= 22;
                clampScroll();
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollY += (float) verticalAmount * 18;
        clampScroll();
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
