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
 * The "Kindred Codex" - a browsable, book-styled almanac of every race's innate boons and banes,
 * plus (for the player's own race) their disciplines, vision, and titles. Drawn as an aged-parchment
 * tome (leather cover, ink text) rather than the race theme's background, and paged with ◄ ► like
 * turning leaves. Purely client-side.
 */
public class KindredCodexScreen extends Screen {
    private static final int PAGE_W = 400;
    private static final int LINE = 11;

    // Parchment palette (dark ink on aged paper - readable, book-like).
    private static final int LEATHER = 0xFF3E2C18;
    private static final int PARCHMENT = 0xFFEBE0C6;
    private static final int INK = 0xFF33281A;
    private static final int INK_HEADER = 0xFF7A2E1A;
    private static final int INK_MUTE = 0xFF6E6046;
    private static final int BOON = 0xFF2F6B2C;
    private static final int BANE = 0xFF9A2B2B;
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

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0000000);

        int px = (width - PAGE_W) / 2;
        int py = 16;
        int ph = height - 32;
        drawBook(ctx, px, py, PAGE_W, ph);

        boolean isOwn = browseRace().equals(playerRace);
        int cx = px + PAGE_W / 2;

        // Title.
        String raceName = titleCase(browseRace().getPath()) + (isOwn ? "  —  your people" : "");
        int tw = textRenderer.getWidth(raceName);
        ctx.drawText(textRenderer, Text.literal(raceName).formatted(Formatting.BOLD, Formatting.UNDERLINE),
                cx - tw / 2, py + 14, INK_HEADER, false);
        ctx.fill(px + 30, py + 26, px + PAGE_W - 30, py + 27, RULE);

        int viewTop = py + 32;
        int viewBottom = py + ph - 22;
        ctx.enableScissor(px + 14, viewTop, px + PAGE_W - 14, viewBottom);
        int x = px + 26;
        int y = viewTop + (int) scrollY;
        int wrap = PAGE_W - 52;

        y = section(ctx, x, y, "The Ways of the Kindreds");
        y = ink(ctx, x, y, wrap, "Open your skill tree with [K], then spend discipline points earned through your deeds.");
        y = ink(ctx, x, y, wrap, "Earn points by living your people's life — fight, mine, sneak, craft, explore.");
        y = ink(ctx, x, y, wrap, "Passive skills are always on. Active skills fire with [G]. Vision toggles with [V], or Equip it in the tree.");
        y = ink(ctx, x, y, wrap, "The birth-gifts below are innate. Some wake with place and hour — starlight, the deep places, the open Sun.");
        y += 8;

        y = section(ctx, x, y, "Birth-gifts of this people");
        if (birth != null) {
            for (String plus : birth.pluses()) {
                y = bullet(ctx, x, y, wrap, "✦", BOON, plus);
            }
            for (String minus : birth.minuses()) {
                y = bullet(ctx, x, y, wrap, "✖", BANE, minus);
            }
        } else {
            y = ink(ctx, x, y, wrap, "(no birth-gifts recorded for this people)");
        }
        y += 8;

        if (isOwn) {
            y = section(ctx, x, y, "Your disciplines");
            for (String disc : Disciplines.ALL) {
                Identifier discId = Identifier.of("kindreds", disc);
                int level = ProgressionService.pointsForLevel(data.xpIn(discId));
                int spent = tree != null ? ProgressionService.pointsSpent(data, tree, discId) : 0;
                int avail = level - spent;
                String head = String.format("%-10s  Lv %-2d   %d spent%s", titleCase(disc), level, spent,
                        avail > 0 ? "   (+" + avail + " to spend)" : "");
                ctx.drawText(textRenderer, Text.literal(head), x, y, avail > 0 ? BOON : INK, false);
                y += LINE;
                long xp = data.xpIn(discId);
                long lo = LevelCurve.xpForLevel(level);
                long hi = LevelCurve.xpForLevel(level + 1);
                float frac = hi > lo ? Math.max(0f, Math.min(1f, (float) (xp - lo) / (float) (hi - lo))) : 0f;
                ctx.fill(x, y, x + wrap, y + 3, 0x33000000);
                ctx.fill(x, y, x + (int) (wrap * frac), y + 3, 0xFF7A5A2E);
                y += 8;
            }
            y += 8;

            y = section(ctx, x, y, "Vision & titles");
            String lens = data.activeVisionLens() != null ? titleCase(data.activeVisionLens().getPath()) : "None equipped";
            y = ink(ctx, x, y, wrap, "Vision: " + lens);
            String titles = data.titles().isEmpty() ? "None earned yet" : String.join(", ", data.titles());
            y = ink(ctx, x, y, wrap, "Titles: " + titles);
            y = ink(ctx, x, y, wrap, "Skills learned: " + data.unlockedNodes().size());
        } else {
            y = ink(ctx, x, y, wrap, "Choose this people in the Player's Book to walk their path and earn their skills.");
        }

        contentHeight = (y - (int) scrollY) - viewTop;
        ctx.disableScissor();

        // Page-turn corners + hint.
        prevBtn = new int[]{px + 14, py + ph - 20, 58, 15};
        nextBtn = new int[]{px + PAGE_W - 72, py + ph - 20, 58, 15};
        drawTurn(ctx, prevBtn, "◄ Prev", within(prevBtn, mouseX, mouseY));
        drawTurn(ctx, nextBtn, "Next ►", within(nextBtn, mouseX, mouseY));
        String pageNo = (browseIndex + 1) + " / " + ALL_RACES.size();
        int pw = textRenderer.getWidth(pageNo);
        ctx.drawText(textRenderer, Text.literal(pageNo), cx - pw / 2, py + ph - 17, INK_MUTE, false);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawBook(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x - 4, y - 4, x + w + 4, y + h + 4, LEATHER);          // leather cover
        ctx.fill(x - 4, y - 4, x + w + 4, y - 1, 0xFF5A4026);           // top edge highlight
        ctx.fill(x, y, x + w, y + h, PARCHMENT);                        // page
        ctx.fillGradient(x, y, x + w, y + h, 0x00000000, 0x22000000);   // gentle age-shade
        ctx.fillGradient(x, y, x + 10, y + h, 0x33000000, 0x00000000);  // inner-margin shadow
        ctx.drawBorder(x + 5, y + 5, w - 10, h - 10, 0x44000000);       // keyline
    }

    private int section(DrawContext ctx, int x, int y, String label) {
        ctx.drawText(textRenderer, Text.literal(label).formatted(Formatting.BOLD), x - 4, y, INK_HEADER, false);
        ctx.fill(x - 4, y + 10, x + PAGE_W - 52, y + 11, RULE);
        return y + 15;
    }

    private int ink(DrawContext ctx, int x, int y, int wrap, String text) {
        for (var line : textRenderer.wrapLines(Text.literal(text), wrap)) {
            ctx.drawText(textRenderer, line, x, y, INK, false);
            y += LINE;
        }
        return y;
    }

    private int bullet(DrawContext ctx, int x, int y, int wrap, String glyph, int color, String text) {
        ctx.drawText(textRenderer, Text.literal(glyph), x, y, color, false);
        boolean first = true;
        for (var line : textRenderer.wrapLines(Text.literal(text), wrap - 12)) {
            ctx.drawText(textRenderer, line, x + 12, y, first ? color : INK, false);
            y += LINE;
            first = false;
        }
        return y;
    }

    private void drawTurn(DrawContext ctx, int[] r, String label, boolean hover) {
        ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0x33000000 : 0x18000000);
        ctx.drawBorder(r[0], r[1], r[2], r[3], 0x66000000);
        int lw = textRenderer.getWidth(label);
        ctx.drawText(textRenderer, Text.literal(label), r[0] + (r[2] - lw) / 2, r[1] + 4, INK_HEADER, false);
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
        int viewH = height - 32 - 54;
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
