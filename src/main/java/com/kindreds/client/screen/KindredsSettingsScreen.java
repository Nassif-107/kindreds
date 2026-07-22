package com.kindreds.client.screen;

import com.kindreds.config.Difficulty;
import com.kindreds.network.SetDifficultyC2S;
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
 * The <b>server rules</b> screen: shows the active difficulty preset and what it implies, and lets an
 * <b>operator</b> change it without touching the console.
 *
 * <p>These are server-wide rules, not personal preferences, so a non-operator sees the same
 * information read-only with a plain explanation of why. That hiding is only courtesy - the authority
 * check that matters lives server-side in {@link SetDifficultyC2S}.
 *
 * <p>Values come from {@link ClientConfigMirror}, pushed by the server on join and after every change,
 * so the screen always reflects the real rules rather than the client's own config file.
 */
public class KindredsSettingsScreen extends Screen {
    private static final Difficulty[] PRESETS = {
            Difficulty.FIRESIDE, Difficulty.ROAD, Difficulty.LONG_DEFEAT, Difficulty.DOOM, Difficulty.CUSTOM
    };

    /** The four rule switches, in display order. Kept separate from the difficulty presets on purpose:
     * these are lore/sandbox switches (a Snaga is sun-weak because it is a Snaga), not difficulty. */
    private static final String[] FLAGS = {
            "enableBirthTraits", "enableCurses", "enableVision", "allowCrossTraining",
            "allowGrantXp"
    };

    /** "The world answers" (spec §6): the enemy-scaling settings, display only - unlike {@link #FLAGS}
     * these have no click handler here. Editing is via {@code /kindreds config}; this row set exists
     * so a non-operator (and an operator without console access) can at least see what is in effect. */
    private static final String[] WORLD_ANSWERS_ROWS = {
            "enemyScaling", "scalingCurve", "maxDamageBonus", "xpBonus"
    };

    private final List<int[]> presetRects = new ArrayList<>();
    private final List<int[]> flagRects = new ArrayList<>();
    private final Screen parent;

    /** Vertical scroll offset (px) applied to the panel body when {@code panelH} exceeds the
     * available window height - the safety net for GUI scales/window sizes where even {@code compact}
     * mode cannot make everything fit. Clamped to {@code [0, maxScroll]} every render/scroll. */
    private int scrollY;

    /** {@code panelH - visibleHeight} as of the last render, i.e. the current scroll clamp ceiling.
     * Kept as a field so {@link #mouseScrolled} can clamp without redoing the layout math. */
    private int maxScroll;

    /** The scissor-clipped body band, {@code [visibleTop, visibleBottom]}, as of the last render -
     * mirrors the local {@code bodyTop}/{@code panelBottom} in {@link #render}. A preset/flag row is
     * only ever added to {@link #presetRects}/{@link #flagRects} when it lies fully inside this band,
     * and {@link #mouseClicked} rejects any click outside it too: a row that has scrolled up under the
     * always-visible header (or down past the panel bottom) must never be clickable there, even though
     * its rect - drawn at a scrolled Y - would otherwise still overlap the header/footer screen area. */
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
        presetRects.clear();
        flagRects.clear();

        int panelW = Math.min(420, this.width - 40);
        int x = (this.width - panelW) / 2;
        // At a small window or a high GUI scale there is far less room than the full layout wants
        // (854x480 at scale 2 leaves 427x240, against 384 needed), and the bottom of the panel -
        // the rule toggles and the last preset - simply fell off the screen. Compact the rows until
        // it fits rather than drawing something the player cannot reach.
        // Compact when the full layout genuinely will not fit, not when the window is merely small:
        // the roomy metrics need head + 5 presets + rules + 5 flags, and at 854x480 that overran the
        // bottom and cut the last toggles off entirely.
        // This must mirror the roomy (non-compact) branch of the panelH formula below - head + 5
        // presets + rules gap + 5 flags + world-answers gap + 4 world-answers rows + footer gap.
        // It previously stopped after FLAGS, so it under-counted the panel by the whole "The world
        // answers" section and never compacted for a screen that, once that section was added, no
        // longer fit - which is exactly why that section and the footer ran off the bottom at scales
        // 2-4: the decision below thought there was room when there wasn't.
        int fullHeight = 56 + PRESETS.length * 38 + 24 + FLAGS.length * 18 + 24
                + WORLD_ANSWERS_ROWS.length * 18 + 26;
        // Conservative on purpose: the roomy layout measured 386 but drew past the bottom of a
        // 480-tall window, cutting the last two toggles. Rather than keep guessing at the true
        // height, the roomy variant is reserved for windows with room to spare - compact loses only
        // the preset descriptions and still shows every control.
        boolean compact = this.height < 560 || fullHeight > this.height - 12;
        int rowH = compact ? 22 : 34;
        int flagH = compact ? 14 : 18;
        // The heading is a title, a "current difficulty" line and a line of numbers. In compact mode
        // the header was only 34px tall while the numbers were drawn at +33, so the first preset row
        // was painted straight over them - which is why the xp/cap/scaling line appeared to sit
        // behind Fireside. The header now reserves what it actually draws.
        int headH = compact ? 48 : 56;
        // bodyH is exactly what the loops below draw (presets, rule flags, world-answers, footer gap)
        // - kept separate from headH so scrolling can be applied to precisely that content without
        // needing to re-derive it from panelH later (headH can still grow by a couple of px below when
        // the numbers line wraps, and bodyH must not drift when that happens).
        int bodyH = PRESETS.length * (rowH + 4) + (compact ? 14 : 24)
                + FLAGS.length * flagH + (compact ? 14 : 24)
                + WORLD_ANSWERS_ROWS.length * flagH + (compact ? 16 : 26);
        int panelH = headH + bodyH;

        // Root cause 2: compact alone can still not be enough at GUI scale 3-4 or a small window -
        // it drops row height/spacing but not rows. When the panel genuinely does not fit even
        // compact, pin its top near the screen top and let the body (everything below the header)
        // scroll, instead of silently drawing "The world answers" and the footer past the bottom
        // edge where they exist on screen but can neither be seen nor clicked.
        boolean overflow = panelH > this.height - 12;
        int y;
        int panelBottom;
        if (!overflow) {
            // Unchanged from before: centered, full content, no scrolling - the common case must not
            // regress.
            y = Math.max(4, (this.height - panelH) / 2);
            panelBottom = y + panelH;
        } else {
            y = 4;
            panelBottom = this.height - 6;
        }

        ctx.fill(x - 8, y - 10, x + panelW + 8, panelBottom, 0xE0120F0A);
        ctx.drawBorder(x - 8, y - 10, panelW + 16, panelBottom - (y - 10), 0xFF4A3D28);

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.settings.title").formatted(Formatting.GOLD),
                this.width / 2, y, 0xFFD8B45F);

        SyncConfigS2C.View v = ClientConfigMirror.get();
        if (v == null) {
            scrollY = 0;
            maxScroll = 0;
            // No body is drawn at all in this branch, so nothing should ever be clickable - collapse
            // the band to empty rather than leave the previous render's bounds lying around.
            visibleTop = 0;
            visibleBottom = 0;
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("kindreds.settings.unknown").formatted(Formatting.GRAY),
                    this.width / 2, y + 30, 0xFF8A7C60);
            return;
        }

        // Current rules, in plain numbers.
        ctx.drawText(this.textRenderer, Text.translatable("kindreds.settings.current",
                        Text.translatable("kindreds.difficulty." + v.difficulty().toLowerCase(Locale.ROOT)))
                .formatted(Formatting.WHITE), x, y + 20, 0xFFFFFFFF, false);
        // Two halves, so a long line can wrap inside the panel instead of running off its edge.
        String left = "xp x" + v.xpRate() + "   ·   " + v.death();
        String right = Text.translatable("kindreds.settings.cap").getString() + " " + capText(v)
                + "   ·   " + Text.translatable("kindreds.settings.scaling").getString() + " "
                + onOff(v.enemyScaling());
        String joined = left + "   ·   " + right;
        if (this.textRenderer.getWidth(joined) <= panelW) {
            ctx.drawText(this.textRenderer, Text.literal(joined).formatted(Formatting.GRAY),
                    x, y + 33, 0xFFB6A888, false);
        } else {
            ctx.drawText(this.textRenderer, Text.literal(left).formatted(Formatting.GRAY),
                    x, y + 30, 0xFFB6A888, false);
            ctx.drawText(this.textRenderer, Text.literal(right).formatted(Formatting.GRAY),
                    x, y + 40, 0xFFB6A888, false);
            headH = Math.max(headH, 58);
        }

        // bodyTop is where the scrollable content (presets onward) actually starts drawing - after
        // the header, which may just have grown by a couple of px if the numbers line wrapped. The
        // title/header above bodyTop is never scrolled, so it can never be the thing that's lost.
        int bodyTop = y + headH;
        maxScroll = overflow ? Math.max(0, bodyH - (panelBottom - bodyTop)) : 0;
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));
        // Stash the band for mouseClicked (see the field javadoc) - must be set together with the
        // presetRects/flagRects population below so the two never disagree about what is visible.
        this.visibleTop = bodyTop;
        this.visibleBottom = panelBottom;

        // Scissor bounds equal the drawable content box in both branches (in the non-overflow branch
        // panelBottom - bodyTop == bodyH exactly, by construction of panelH above), so this clips
        // nothing when everything already fits - only the overflow case actually crops anything.
        ctx.enableScissor(x - 8, bodyTop, x + panelW + 8, panelBottom);

        int by = bodyTop - scrollY;
        int presetIndex = 0;
        for (Difficulty d : PRESETS) {
            boolean active = d.name().equalsIgnoreCase(v.difficulty());
            int h = rowH;
            // {x, y, w, h, PRESETS-index}. The index rides along in the rect itself rather than being
            // inferred from list position, because a row that scrolls fully off the visible band below
            // is skipped from presetRects entirely (see the fully-visible check) - without a carried
            // index, later presets would silently shift down to earlier slots in mouseClicked.
            int[] r = {x, by, panelW, h, presetIndex};
            boolean fullyVisible = r[1] >= bodyTop && r[1] + r[3] <= panelBottom;
            if (fullyVisible) {
                presetRects.add(r);
            }
            presetIndex++;
            boolean hover = isOperator() && within(r, mouseX, mouseY);

            ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], active ? 0xC02A2010 : (hover ? 0x80201810 : 0x60141014));
            ctx.drawBorder(r[0], r[1], r[2], r[3], active ? 0xFFD8B45F : 0xFF3B3122);

            ctx.drawText(this.textRenderer,
                    Text.translatable("kindreds.difficulty." + d.name().toLowerCase(Locale.ROOT))
                            .formatted(active ? Formatting.GOLD : Formatting.WHITE),
                    r[0] + 8, r[1] + 7, active ? 0xFFD8B45F : 0xFFECE3CD, false);
            if (!compact) {
                ctx.drawText(this.textRenderer,
                        Text.translatable("kindreds.difficulty." + d.name().toLowerCase(Locale.ROOT) + ".desc")
                                .formatted(Formatting.GRAY),
                        r[0] + 8, r[1] + 21, 0xFF9A8F76, false);
            }
            by += h + 4;
        }

        // --- Rule switches (lore/sandbox, not difficulty) ---
        by += compact ? 4 : 8;
        ctx.drawText(this.textRenderer, Text.translatable("kindreds.settings.rules")
                .formatted(Formatting.GOLD), x, by, 0xFFD8B45F, false);
        by += compact ? 10 : 13;
        int flagIndex = 0;
        for (String flag : FLAGS) {
            boolean on = flagValue(v, flag);
            // {x, y, w, h, FLAGS-index} - see the presetRects comment above for why the index rides
            // along instead of being read back from list position.
            int[] r = {x, by, panelW, flagH - 2, flagIndex};
            boolean fullyVisible = r[1] >= bodyTop && r[1] + r[3] <= panelBottom;
            if (fullyVisible) {
                flagRects.add(r);
            }
            flagIndex++;
            boolean hover = isOperator() && within(r, mouseX, mouseY);
            if (hover) {
                ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0x50201810);
            }
            ctx.drawText(this.textRenderer, Text.translatable("kindreds.settings.flag." + flag)
                    .formatted(Formatting.WHITE), r[0] + 4, r[1] + 4, 0xFFECE3CD, false);
            Text pill = Text.translatable(on ? "kindreds.settings.on" : "kindreds.settings.off");
            int pw = this.textRenderer.getWidth(pill) + 10;
            int px = r[0] + r[2] - pw - 4;
            ctx.fill(px, r[1] + 2, px + pw, r[1] + 14, on ? 0x804A8036 : 0x80402020);
            ctx.drawBorder(px, r[1] + 2, pw, 12, on ? 0xFF8FCA79 : 0xFF7A5A5A);
            ctx.drawText(this.textRenderer, pill, px + 5, r[1] + 4,
                    on ? 0xFF8FCA79 : 0xFFB08A8A, false);
            by += flagH;
        }

        // --- World answers (enemy scaling, spec §6) - display only ---
        by += compact ? 4 : 8;
        ctx.drawText(this.textRenderer, Text.translatable("kindreds.settings.section.world_answers")
                .formatted(Formatting.GOLD), x, by, 0xFFD8B45F, false);
        by += compact ? 10 : 13;
        for (String row : WORLD_ANSWERS_ROWS) {
            ctx.drawText(this.textRenderer, Text.translatable("kindreds.settings." + row)
                    .formatted(Formatting.WHITE), x + 4, by + 4, 0xFFECE3CD, false);
            Text value = worldAnswerValue(v, row);
            int vw = this.textRenderer.getWidth(value);
            ctx.drawText(this.textRenderer, value, x + panelW - vw - 4, by + 4, 0xFFB6A888, false);
            by += flagH;
        }

        Text foot = isOperator()
                ? Text.translatable("kindreds.settings.op").formatted(Formatting.DARK_GRAY)
                : Text.translatable("kindreds.settings.notop").formatted(Formatting.RED);
        ctx.drawCenteredTextWithShadow(this.textRenderer, foot, this.width / 2, by + 6,
                isOperator() ? 0xFF8A7C60 : 0xFFDD8060);
        ctx.disableScissor();
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

    /** The right-hand value shown for one {@link #WORLD_ANSWERS_ROWS} row. The curve is localized via
     * {@code kindreds.settings.curve.<NAME>} rather than shown as its raw enum name. */
    private static Text worldAnswerValue(SyncConfigS2C.View v, String row) {
        return switch (row) {
            case "enemyScaling" -> Text.translatable(v.enemyScaling() ? "kindreds.settings.on" : "kindreds.settings.off");
            case "scalingCurve" -> Text.translatable("kindreds.settings.curve." + v.scalingCurve());
            case "maxDamageBonus" -> Text.literal(v.maxDamageBonus() + "%");
            case "xpBonus" -> Text.literal(v.xpBonus() + "%");
            default -> Text.literal("");
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
        // Defense-in-depth alongside the fully-visible check that gates what makes it into
        // presetRects/flagRects in the first place (see their population in render()): a click outside
        // the scissor-clipped body band - e.g. in the always-visible header, where a row can appear to
        // have scrolled to once scrollY > 0 - must never be able to hit a rect, even if some future
        // change to the rect-population logic slipped one through.
        boolean inBody = mouseY >= visibleTop && mouseY <= visibleBottom;
        if (isOperator() && inBody) {
            for (int[] r : presetRects) {
                if (within(r, mouseX, mouseY)) {
                    ClientPlayNetworking.send(
                            new SetDifficultyC2S(PRESETS[r[4]].name().toLowerCase(Locale.ROOT)));
                    return true;
                }
            }
            SyncConfigS2C.View v = ClientConfigMirror.get();
            for (int[] r : flagRects) {
                if (within(r, mouseX, mouseY) && v != null) {
                    ClientPlayNetworking.send(new com.kindreds.network.SetConfigFlagC2S(
                            FLAGS[r[4]], !flagValue(v, FLAGS[r[4]])));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Scrolls the body (presets/flags/world-answers/footer) when the panel is taller than the
     * window - the safety net for GUI scales 2-4 / small windows where even {@code compact} still
     * overflows. A no-op (falls through to the superclass) once everything already fits, so this
     * never fights normal Screen scroll handling in the common case. */
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

    private static boolean within(int[] r, double x, double y) {
        return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }
}
