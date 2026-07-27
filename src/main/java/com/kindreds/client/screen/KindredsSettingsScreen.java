package com.kindreds.client.screen;

import com.kindreds.config.Difficulty;
import com.kindreds.config.Menace;
import com.kindreds.config.RuleDial;
import com.kindreds.config.ScalingCurve;
import com.kindreds.network.SetConfigFlagC2S;
import com.kindreds.network.SetConfigValueC2S;
import com.kindreds.network.SetDifficultyC2S;
import com.kindreds.network.SetMenaceC2S;
import com.kindreds.network.SyncConfigS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The <b>server rules</b> screen: shows the active difficulty presets and what they imply, and lets an
 * <b>operator</b> change every one of them without touching the console.
 *
 * <h2>Two preset axes</h2>
 * {@link Difficulty} is about the player - xp rate, what a death costs, how much of a tree may be
 * mastered. {@link Menace} is about the enemies - how hard they hit, what they can take, how often one
 * of them is a named champion with an escort. They were one setting, so "gentle pace, terrifying
 * world" was not expressible; now each has its own row set.
 *
 * <h2>Everything here is editable</h2>
 * The enemy-scaling numbers used to be rendered and nothing more - no click handler at any row. That
 * made {@code CUSTOM} a mode in which nothing was preset-driven and nothing was editable either: the
 * one setting whose entire purpose is hand-tuning was the only one that needed a console. Every dial
 * now takes a click (left raises, right lowers, shift for a coarse step), and any edit moves the
 * enemy preset onto {@code CUSTOM} server-side, so the screen never shows a preset name above numbers
 * that no longer match it.
 *
 * <p>A non-operator sees the same information read-only with a plain explanation of why. That hiding
 * is only courtesy - the authority checks that matter live server-side in {@link SetDifficultyC2S},
 * {@link SetMenaceC2S}, {@link SetConfigFlagC2S} and {@link SetConfigValueC2S}.
 *
 * <p>Values come from {@link ClientConfigMirror}, pushed by the server on join and after every change,
 * so the screen always reflects the real rules rather than the client's own config file.
 */
public class KindredsSettingsScreen extends Screen {

    private static final Difficulty[] PACING = {
            Difficulty.FIRESIDE, Difficulty.ROAD, Difficulty.LONG_DEFEAT, Difficulty.DOOM, Difficulty.CUSTOM
    };

    private static final Menace[] MENACE = {
            Menace.SHIRE, Menace.WATCHFUL_PEACE, Menace.GATHERING_DARK, Menace.OPEN_WAR,
            Menace.THE_BLACK_TIDE, Menace.WRATH_OF_SAURON, Menace.CUSTOM
    };

    /** The rule switches, in display order. Kept apart from both preset axes on purpose: these are
     * lore/sandbox switches (a Snaga is sun-weak because it is a Snaga), not difficulty. */
    private static final String[] FLAGS = {
            "enableBirthTraits", "enableCurses", "enableVision", "allowCrossTraining", "allowGrantXp"
    };

    /** Row kinds, so one layout pass can walk a heterogeneous list instead of four hand-aligned loops
     * each re-deriving the same scroll and visibility maths. */
    private enum Kind { HEADER, PACING, MENACE, FLAG, SCALING_TOGGLE, CURVE, DIAL }

    /** One laid-out row: what it is, which entry of its kind, and where it was drawn. */
    private static final class Row {
        final Kind kind;
        final int index;
        final String label;
        int x, y, w, h;

        Row(Kind kind, int index, String label) {
            this.kind = kind;
            this.index = index;
            this.label = label;
        }

        boolean clickable() {
            return kind != Kind.HEADER;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Screen parent;

    /** Vertical scroll offset (px) applied to the panel body when the content exceeds the available
     * window height. Clamped to {@code [0, maxScroll]} every render/scroll. */
    private int scrollY;

    /** {@code bodyHeight - visibleHeight} as of the last render, i.e. the current scroll clamp
     * ceiling. Kept as a field so {@link #mouseScrolled} can clamp without redoing the layout. */
    private int maxScroll;

    /** The scissor-clipped body band as of the last render. A row is only added to {@link #rows} when
     * it lies fully inside this band, and {@link #mouseClicked} rejects any click outside it too: a
     * row scrolled up under the always-visible header must never be clickable there, even though its
     * rect - drawn at a scrolled Y - would otherwise still overlap the header's screen area. */
    private int visibleTop;
    private int visibleBottom;

    public KindredsSettingsScreen(Screen parent) {
        super(Text.translatable("kindreds.settings.title"));
        this.parent = parent;
    }

    public static void open(MinecraftClient client) {
        client.setScreen(new KindredsSettingsScreen(client.currentScreen));
    }

    private boolean isOperator() {
        return this.client != null && this.client.player != null
                && this.client.player.hasPermissionLevel(SetDifficultyC2S.OPERATOR_LEVEL);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        rows.clear();

        int panelW = Math.min(440, this.width - 40);
        int x = (this.width - panelW) / 2;

        SyncConfigS2C.View v = ClientConfigMirror.get();

        // Every row is one line tall now, preset descriptions included as a second line only when
        // there is room. Uniform rows are what let the layout below be a single walk: the old
        // per-section metrics had to be kept in sync by hand across four places and were, twice,
        // not - which is how "The world answers" came to be drawn past the bottom of the screen.
        boolean roomy = this.height >= 620;
        int rowH = roomy ? 24 : 13;
        int lineH = 13;
        int headH = 50;

        int bodyH = 0;
        for (Row r : buildRows(v)) {
            bodyH += heightOf(r, rowH, lineH);
        }
        bodyH += 16; // footer

        int panelH = headH + bodyH;
        boolean overflow = panelH > this.height - 12;
        int y = overflow ? 4 : Math.max(4, (this.height - panelH) / 2);
        int panelBottom = overflow ? this.height - 6 : y + panelH;

        ctx.fill(x - 8, y - 10, x + panelW + 8, panelBottom, 0xE0120F0A);
        ctx.drawBorder(x - 8, y - 10, panelW + 16, panelBottom - (y - 10), 0xFF4A3D28);

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.settings.title").formatted(Formatting.GOLD),
                this.width / 2, y, 0xFFD8B45F);

        if (v == null) {
            scrollY = 0;
            maxScroll = 0;
            visibleTop = 0;
            visibleBottom = 0;
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("kindreds.settings.unknown").formatted(Formatting.GRAY),
                    this.width / 2, y + 30, 0xFF8A7C60);
            return;
        }

        // Current rules, in plain numbers - the one always-visible summary.
        ctx.drawText(this.textRenderer, Text.translatable("kindreds.settings.current",
                        Text.translatable("kindreds.difficulty." + v.difficulty().toLowerCase(Locale.ROOT)),
                        Text.translatable("kindreds.menace." + v.menace().toLowerCase(Locale.ROOT)))
                .formatted(Formatting.WHITE), x, y + 18, 0xFFFFFFFF, false);
        String summary = "xp x" + v.xpRate() + "   ·   " + v.death() + "   ·   "
                + Text.translatable("kindreds.settings.cap").getString() + " " + capText(v)
                + "   ·   " + Text.translatable("kindreds.settings.scaling").getString() + " "
                + onOff(v.enemyScaling());
        ctx.drawText(this.textRenderer, Text.literal(summary).formatted(Formatting.GRAY),
                x, y + 31, 0xFFB6A888, false);

        int bodyTop = y + headH;
        maxScroll = Math.max(0, bodyH - (panelBottom - bodyTop));
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));
        this.visibleTop = bodyTop;
        this.visibleBottom = panelBottom;

        ctx.enableScissor(x - 8, bodyTop, x + panelW + 8, panelBottom);

        int by = bodyTop - scrollY;
        for (Row r : buildRows(v)) {
            int h = heightOf(r, rowH, lineH);
            r.x = x;
            r.y = by;
            r.w = panelW;
            r.h = h - 2;
            boolean fullyVisible = r.y >= bodyTop && r.y + r.h <= panelBottom;
            if (fullyVisible) {
                rows.add(r);
            }
            // Hover is gated on fullyVisible too, matching mouseClicked's own rejection: a
            // partially-scrolled-off row must not light up under the cursor while rejecting the click
            // that highlight promised would land.
            boolean hover = fullyVisible && isOperator() && r.clickable() && within(r, mouseX, mouseY);
            drawRow(ctx, r, v, hover, roomy);
            by += h;
        }

        Text foot = isOperator()
                ? Text.translatable("kindreds.settings.op").formatted(Formatting.DARK_GRAY)
                : Text.translatable("kindreds.settings.notop").formatted(Formatting.RED);
        ctx.drawCenteredTextWithShadow(this.textRenderer, foot, this.width / 2, by + 4,
                isOperator() ? 0xFF8A7C60 : 0xFFDD8060);
        ctx.disableScissor();
    }

    private static int heightOf(Row r, int rowH, int lineH) {
        return switch (r.kind) {
            case HEADER -> lineH + 3;
            case PACING, MENACE -> rowH;
            default -> lineH;
        };
    }

    /** The full row list, rebuilt each frame - it is a few dozen small objects and depends on live
     * config, so caching it would only add an invalidation path to get wrong. */
    private List<Row> buildRows(SyncConfigS2C.View v) {
        List<Row> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        out.add(new Row(Kind.HEADER, 0, "kindreds.settings.section.pacing"));
        for (int i = 0; i < PACING.length; i++) {
            out.add(new Row(Kind.PACING, i, null));
        }
        out.add(new Row(Kind.HEADER, 0, "kindreds.settings.section.menace"));
        for (int i = 0; i < MENACE.length; i++) {
            out.add(new Row(Kind.MENACE, i, null));
        }
        out.add(new Row(Kind.HEADER, 0, "kindreds.settings.rules"));
        for (int i = 0; i < FLAGS.length; i++) {
            out.add(new Row(Kind.FLAG, i, FLAGS[i]));
        }
        out.add(new Row(Kind.HEADER, 0, "kindreds.settings.section.world_answers"));
        out.add(new Row(Kind.SCALING_TOGGLE, 0, "enemyScaling"));
        out.add(new Row(Kind.CURVE, 0, "scalingCurve"));
        RuleDial[] dials = RuleDial.values();
        for (int i = 0; i < dials.length; i++) {
            out.add(new Row(Kind.DIAL, i, dials[i].key));
        }
        return out;
    }

    private void drawRow(DrawContext ctx, Row r, SyncConfigS2C.View v, boolean hover, boolean roomy) {
        switch (r.kind) {
            case HEADER -> ctx.drawText(this.textRenderer,
                    Text.translatable(r.label).formatted(Formatting.GOLD), r.x, r.y + 2, 0xFFD8B45F, false);

            case PACING -> {
                Difficulty d = PACING[r.index];
                boolean active = d.name().equalsIgnoreCase(v.difficulty());
                drawPresetRow(ctx, r, hover, active, roomy,
                        "kindreds.difficulty." + d.name().toLowerCase(Locale.ROOT));
            }

            case MENACE -> {
                Menace m = MENACE[r.index];
                boolean active = m.name().equalsIgnoreCase(v.menace());
                drawPresetRow(ctx, r, hover, active, roomy,
                        "kindreds.menace." + m.name().toLowerCase(Locale.ROOT));
            }

            case FLAG -> drawToggleRow(ctx, r, hover,
                    Text.translatable("kindreds.settings.flag." + r.label), flagValue(v, r.label));

            case SCALING_TOGGLE -> drawToggleRow(ctx, r, hover,
                    Text.translatable("kindreds.settings.enemyScaling"), v.enemyScaling());

            case CURVE -> drawValueRow(ctx, r, hover,
                    Text.translatable("kindreds.settings.scalingCurve"),
                    Text.translatable("kindreds.settings.curve." + v.scalingCurve()));

            case DIAL -> {
                RuleDial dial = RuleDial.values()[r.index];
                drawValueRow(ctx, r, hover, Text.translatable("kindreds.settings." + dial.key),
                        Text.literal(dial.format(SyncConfigS2C.dialValue(v, dial))));
            }
        }
    }

    private void drawPresetRow(DrawContext ctx, Row r, boolean hover, boolean active, boolean roomy,
                               String langKey) {
        ctx.fill(r.x, r.y, r.x + r.w, r.y + r.h,
                active ? 0xC02A2010 : (hover ? 0x80201810 : 0x60141014));
        ctx.drawBorder(r.x, r.y, r.w, r.h, active ? 0xFFD8B45F : 0xFF3B3122);
        ctx.drawText(this.textRenderer, Text.translatable(langKey)
                        .formatted(active ? Formatting.GOLD : Formatting.WHITE),
                r.x + 8, r.y + 3, active ? 0xFFD8B45F : 0xFFECE3CD, false);
        if (roomy) {
            ctx.drawText(this.textRenderer, Text.translatable(langKey + ".desc").formatted(Formatting.GRAY),
                    r.x + 8, r.y + 14, 0xFF9A8F76, false);
        }
    }

    private void drawToggleRow(DrawContext ctx, Row r, boolean hover, Text label, boolean on) {
        if (hover) {
            ctx.fill(r.x, r.y, r.x + r.w, r.y + r.h, 0x50201810);
        }
        ctx.drawText(this.textRenderer, label.copy().formatted(Formatting.WHITE),
                r.x + 4, r.y + 2, 0xFFECE3CD, false);
        Text pill = Text.translatable(on ? "kindreds.settings.on" : "kindreds.settings.off");
        int pw = this.textRenderer.getWidth(pill) + 10;
        int px = r.x + r.w - pw - 4;
        ctx.fill(px, r.y, px + pw, r.y + 11, on ? 0x804A8036 : 0x80402020);
        ctx.drawBorder(px, r.y, pw, 11, on ? 0xFF8FCA79 : 0xFF7A5A5A);
        ctx.drawText(this.textRenderer, pill, px + 5, r.y + 2, on ? 0xFF8FCA79 : 0xFFB08A8A, false);
    }

    /** A label, its value, and - for an operator - the two arrows that say the value can be moved.
     * The arrows are the whole point: without them a row that happens to be clickable is
     * indistinguishable from the read-only text this section used to be. */
    private void drawValueRow(DrawContext ctx, Row r, boolean hover, Text label, Text value) {
        if (hover) {
            ctx.fill(r.x, r.y, r.x + r.w, r.y + r.h, 0x50201810);
        }
        ctx.drawText(this.textRenderer, label.copy().formatted(Formatting.WHITE),
                r.x + 4, r.y + 2, 0xFFECE3CD, false);
        Text shown = isOperator()
                ? Text.literal("- ").append(value).append(Text.literal(" +"))
                : value;
        int vw = this.textRenderer.getWidth(shown);
        ctx.drawText(this.textRenderer, shown.copy().formatted(hover ? Formatting.YELLOW : Formatting.GRAY),
                r.x + r.w - vw - 4, r.y + 2, hover ? 0xFFF2D68A : 0xFFB6A888, false);
    }

    private static boolean flagValue(SyncConfigS2C.View v, String flag) {
        return switch (flag) {
            case "enableBirthTraits" -> v.birthTraits();
            case "enableCurses" -> v.curses();
            case "enableVision" -> v.vision();
            case "allowCrossTraining" -> v.crossTraining();
            case "allowGrantXp" -> v.grantXp();
            default -> false;
        };
    }

    /** The cap as the player experiences it: "75% of your tree", a flat number, or off. */
    private static String capText(SyncConfigS2C.View v) {
        if (v.capPercent() >= 100) {
            return Text.translatable("kindreds.settings.off").getString();
        }
        if (v.capPercent() > 0) {
            return v.capPercent() + "%";
        }
        return v.softCap() > 0 ? String.valueOf(v.softCap())
                : Text.translatable("kindreds.settings.off").getString();
    }

    private static String onOff(boolean b) {
        return Text.translatable(b ? "kindreds.settings.on" : "kindreds.settings.off").getString();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Defence in depth alongside the fully-visible check that gates what enters `rows` at all: a
        // click outside the scissor-clipped band - e.g. in the always-visible header, where a row can
        // appear to have scrolled to once scrollY > 0 - must never reach a row.
        boolean inBody = mouseY >= visibleTop && mouseY <= visibleBottom;
        if (!isOperator() || !inBody) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        SyncConfigS2C.View v = ClientConfigMirror.get();
        if (v == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        for (Row r : rows) {
            if (!r.clickable() || !within(r, mouseX, mouseY)) {
                continue;
            }
            // Right-click lowers, left-click raises. Toggles ignore the distinction - there is only
            // one other state to be in - so both buttons flip them.
            boolean down = button == 1;
            boolean coarse = hasShiftDown();
            switch (r.kind) {
                case PACING -> ClientPlayNetworking.send(
                        new SetDifficultyC2S(PACING[r.index].name().toLowerCase(Locale.ROOT)));
                case MENACE -> ClientPlayNetworking.send(
                        new SetMenaceC2S(MENACE[r.index].name().toLowerCase(Locale.ROOT)));
                case FLAG -> ClientPlayNetworking.send(
                        new SetConfigFlagC2S(FLAGS[r.index], !flagValue(v, FLAGS[r.index])));
                case SCALING_TOGGLE -> ClientPlayNetworking.send(
                        new SetConfigFlagC2S("enableEnemyScaling", !v.enemyScaling()));
                case CURVE -> ClientPlayNetworking.send(
                        new SetConfigValueC2S(SetConfigValueC2S.SCALING_CURVE_KEY, nextCurve(v, down)));
                case DIAL -> {
                    RuleDial dial = RuleDial.values()[r.index];
                    double stepSize = coarse ? dial.bigStep : dial.step;
                    double next = SyncConfigS2C.dialValue(v, dial) + (down ? -stepSize : stepSize);
                    ClientPlayNetworking.send(new SetConfigValueC2S(dial.key, dial.clamp(next)));
                }
                default -> {
                    return super.mouseClicked(mouseX, mouseY, button);
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** The next curve in either direction, wrapping. Resolved by name rather than kept as an ordinal
     * on the view, so an unrecognised value (an older server, a hand-edited file) lands on the first
     * curve instead of throwing in the middle of a click handler. */
    private static int nextCurve(SyncConfigS2C.View v, boolean backwards) {
        ScalingCurve[] curves = ScalingCurve.values();
        int current = 0;
        for (int i = 0; i < curves.length; i++) {
            if (curves[i].name().equalsIgnoreCase(v.scalingCurve())) {
                current = i;
                break;
            }
        }
        return (current + (backwards ? curves.length - 1 : 1)) % curves.length;
    }

    /** Scrolls the body when the content is taller than the window. A no-op once everything fits, so
     * this never fights normal Screen scroll handling in the common case. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        scrollY -= (int) Math.round(verticalAmount * 16);
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
            return;
        }
        super.close();
    }

    private static boolean within(Row r, double x, double y) {
        return x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h;
    }
}
