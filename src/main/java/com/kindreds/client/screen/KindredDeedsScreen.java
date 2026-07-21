package com.kindreds.client.screen;

import com.kindreds.client.ClientDeeds;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The Great Deeds of your kindred: what they are, what each asks of you, and which you have done.
 *
 * <p>Deeds were invisible before this page existed. The tree tells a player their ceiling can only
 * be widened by deeds, and the only sign a deed existed at all was the toast when one happened to
 * complete - so the one mechanic that lets a build grow past its cap was something you could not
 * look up. Four per kindred, each worth a twentieth of your own tree.
 *
 * <p>Drawn as a leaf of the same book the traits page is, because a record of what you have done
 * belongs beside the record of what you are.
 */
public class KindredDeedsScreen extends Screen {
    private static final int PAGE_W_MAX = 400;
    private static final int LINE = 11;

    private static final int LEATHER = 0xFF3E2C18;
    private static final int PARCHMENT = 0xFFEBE0C6;
    private static final int INK = 0xFF33281A;
    private static final int INK_HEADER = 0xFF7A2E1A;
    private static final int INK_MUTE = 0xFF6E6046;
    private static final int DONE = 0xFF2F6B2C;
    private static final int GILT = 0xFF9A6E1E;
    private static final int RULE = 0x55000000;

    private final KindredData data;
    private final Screen parent;
    private float scrollY;
    private int contentHeight;
    private int viewHeight = 1;

    public KindredDeedsScreen(KindredData data, Screen parent) {
        super(Text.translatable("kindreds.deeds.title"));
        this.data = data != null ? data : new KindredData();
        this.parent = parent;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int pageWidth() {
        return Math.min(PAGE_W_MAX, Math.max(180, this.width - 40));
    }

    /** One deed as this page needs it: its renown path, and whether it is done. */
    private record Deed(String path, boolean done) {
    }

    /**
     * Your own kindred's four deeds. Renown earned as another people is recorded but not shown here,
     * matching the rule the cap already follows: the renown belongs to the people you earned it as.
     */
    private List<Deed> deeds() {
        List<Deed> out = new ArrayList<>();
        Identifier race = data.race();
        if (race == null) {
            return out;
        }
        String mine = "renown/" + race.getPath() + "/";
        for (String path : ClientDeeds.all().keySet()) {
            if (path.startsWith(mine)) {
                out.add(new Deed(path, data.renown().contains(path)));
            }
        }
        return out;
    }

    private static String titleKey(String path) {
        return "kindreds.advancement." + path.replace('/', '.') + ".title";
    }

    private static String riddleKey(String path) {
        return "kindreds.advancement." + path.replace('/', '.') + ".desc";
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0000000);

        int pw = pageWidth();
        int px = (width - pw) / 2;
        int py = 16;
        int ph = height - 32;
        drawBook(ctx, px, py, pw, ph);

        int cx = px + pw / 2;
        List<Deed> deeds = deeds();
        int done = (int) deeds.stream().filter(Deed::done).count();

        String heading = I18n.translate("kindreds.deeds.title");
        int tw = textRenderer.getWidth(heading);
        ctx.drawText(textRenderer, Text.literal(heading).formatted(Formatting.BOLD),
                cx - tw / 2, py + 10, INK_HEADER, false);

        // The one number a player came here for: what their deeds are worth.
        String tally = deeds.isEmpty()
                ? I18n.translate("kindreds.deeds.norace")
                : I18n.translate("kindreds.deeds.tally", done, deeds.size(), done * 5);
        int sw = textRenderer.getWidth(tally);
        ctx.drawText(textRenderer, Text.literal(tally), cx - sw / 2, py + 22,
                done > 0 ? DONE : INK_MUTE, false);
        ctx.fill(px + 30, py + 34, px + pw - 30, py + 35, RULE);

        int viewTop = py + 40;
        int viewBottom = py + ph - 20;
        viewHeight = Math.max(1, viewBottom - viewTop);
        clampScroll();

        int x = px + 26;
        int wrap = pw - 58;
        ctx.enableScissor(px + 14, viewTop, px + pw - 14, viewBottom);
        int y = viewTop + (int) scrollY;

        if (deeds.isEmpty()) {
            y = ink(ctx, x, y, wrap, I18n.translate("kindreds.deeds.none"), INK);
        }
        for (Deed deed : deeds) {
            y = deed(ctx, x, y, wrap, deed);
        }

        contentHeight = (y - (int) scrollY) - viewTop;
        ctx.disableScissor();
        drawScrollbar(ctx, px + pw - 20, viewTop, viewBottom);

        String hint = I18n.translate("kindreds.deeds.hint");
        int hw = textRenderer.getWidth(hint);
        ctx.drawText(textRenderer, Text.literal(hint), cx - hw / 2, py + ph - 15, INK_MUTE, false);

        super.render(ctx, mouseX, mouseY, delta);
    }

    /** One deed: the name you earn, the riddle, what it actually asks, and what it is worth. */
    private int deed(DrawContext ctx, int x, int y, int wrap, Deed deed) {
        int top = y;
        String mark = deed.done() ? "✦" : "◇";
        ctx.drawText(textRenderer, Text.literal(mark), x, y, deed.done() ? DONE : INK_MUTE, false);

        Text name = Text.translatable(titleKey(deed.path())).copy().formatted(Formatting.BOLD);
        ctx.drawText(textRenderer, name, x + 12, y, deed.done() ? DONE : INK_HEADER, false);
        String worth = I18n.translate("kindreds.deeds.worth", 5);
        ctx.drawText(textRenderer, Text.literal(worth), x + wrap - textRenderer.getWidth(worth), y,
                deed.done() ? DONE : INK_MUTE, false);
        y += LINE + 1;

        // The riddle is the flavour the advancement itself shows; the requirement below it is read
        // from that same advancement's criteria, so the page cannot promise a different errand.
        y = wrapped(ctx, Text.translatable(riddleKey(deed.path())).copy().formatted(Formatting.ITALIC),
                x + 12, y, wrap - 12, INK_MUTE);
        Text requirement = ClientDeeds.requirement(deed.path());
        if (requirement != null) {
            y = wrapped(ctx, Text.translatable("kindreds.deeds.asks", requirement),
                    x + 12, y, wrap - 12, INK);
        }
        if (deed.done()) {
            // Not "Title earned: Khazad-Work" - the name is already the heading an inch above, and
            // repeating it reads as a mistake rather than as emphasis.
            y = wrapped(ctx, Text.translatable("kindreds.deeds.title_held"), x + 12, y, wrap - 12, GILT);
        }

        ctx.fill(x + 4, top + LINE + 1, x + 5, y - 2,
                (deed.done() ? DONE & 0x00FFFFFF : GILT & 0x00FFFFFF) | 0xAA000000);
        return y + 8;
    }

    private int wrapped(DrawContext ctx, Text text, int x, int y, int wrap, int color) {
        for (var line : textRenderer.wrapLines(text, wrap)) {
            ctx.drawText(textRenderer, line, x, y, color, false);
            y += LINE;
        }
        return y + 1;
    }

    private int ink(DrawContext ctx, int x, int y, int wrap, String text, int color) {
        return wrapped(ctx, Text.literal(text), x, y, wrap, color);
    }

    private void drawBook(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x - 4, y - 4, x + w + 4, y + h + 4, LEATHER);
        ctx.fill(x - 4, y - 4, x + w + 4, y - 1, 0xFF5A4026);
        ctx.fill(x, y, x + w, y + h, PARCHMENT);
        ctx.fillGradient(x, y, x + w, y + h, 0x00000000, 0x22000000);
        ctx.fillGradient(x, y, x + 10, y + h, 0x33000000, 0x00000000);
        ctx.drawBorder(x + 5, y + 5, w - 10, h - 10, 0x44000000);
    }

    private void drawScrollbar(DrawContext ctx, int x, int top, int bottom) {
        int track = bottom - top;
        if (contentHeight <= track) {
            return;
        }
        ctx.fill(x, top, x + 3, bottom, 0x22000000);
        int thumb = Math.max(16, track * track / contentHeight);
        float frac = -scrollY / (float) (contentHeight - track);
        int ty = top + (int) ((track - thumb) * Math.max(0f, Math.min(1f, frac)));
        ctx.fill(x, ty, x + 3, ty + thumb, 0x88000000);
    }

    private void clampScroll() {
        scrollY = Math.max(Math.min(0, viewHeight - contentHeight), Math.min(0, scrollY));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollY += (float) verticalAmount * 18;
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 265) {           // GLFW UP
            scrollY += 22;
            clampScroll();
            return true;
        }
        if (keyCode == 264) {           // GLFW DOWN
            scrollY -= 22;
            clampScroll();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    public static void open(MinecraftClient client) {
        client.setScreen(new KindredDeedsScreen(ClientKindredData.INSTANCE, client.currentScreen));
    }
}
