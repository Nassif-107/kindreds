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
            "enableBirthTraits", "enableCurses", "enableVision", "allowCrossTraining"
    };

    private final List<int[]> presetRects = new ArrayList<>();
    private final List<int[]> flagRects = new ArrayList<>();
    private final Screen parent;

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
        int panelH = 54 + PRESETS.length * 38 + 24 + FLAGS.length * 18 + 26;
        int y = Math.max(12, (this.height - panelH) / 2);

        ctx.fill(x - 8, y - 10, x + panelW + 8, y + panelH, 0xE0120F0A);
        ctx.drawBorder(x - 8, y - 10, panelW + 16, panelH + 10, 0xFF4A3D28);

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.settings.title").formatted(Formatting.GOLD),
                this.width / 2, y, 0xFFD8B45F);

        SyncConfigS2C.View v = ClientConfigMirror.get();
        if (v == null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("kindreds.settings.unknown").formatted(Formatting.GRAY),
                    this.width / 2, y + 30, 0xFF8A7C60);
            return;
        }

        // Current rules, in plain numbers.
        ctx.drawText(this.textRenderer, Text.translatable("kindreds.settings.current",
                        Text.translatable("kindreds.difficulty." + v.difficulty().toLowerCase(Locale.ROOT)))
                .formatted(Formatting.WHITE), x, y + 20, 0xFFFFFFFF, false);
        ctx.drawText(this.textRenderer, Text.literal(
                        "xp x" + v.xpRate() + "   ·   " + v.death()
                                + "   ·   " + Text.translatable("kindreds.settings.cap").getString() + " "
                                + capText(v)
                                + "   ·   " + Text.translatable("kindreds.settings.scaling").getString() + " "
                                + onOff(v.enemyScaling()))
                .formatted(Formatting.GRAY), x, y + 33, 0xFFB6A888, false);

        int by = y + 54;
        for (Difficulty d : PRESETS) {
            boolean active = d.name().equalsIgnoreCase(v.difficulty());
            int h = 34;
            int[] r = {x, by, panelW, h};
            presetRects.add(r);
            boolean hover = isOperator() && within(r, mouseX, mouseY);

            ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], active ? 0xC02A2010 : (hover ? 0x80201810 : 0x60141014));
            ctx.drawBorder(r[0], r[1], r[2], r[3], active ? 0xFFD8B45F : 0xFF3B3122);

            ctx.drawText(this.textRenderer,
                    Text.translatable("kindreds.difficulty." + d.name().toLowerCase(Locale.ROOT))
                            .formatted(active ? Formatting.GOLD : Formatting.WHITE),
                    r[0] + 8, r[1] + 7, active ? 0xFFD8B45F : 0xFFECE3CD, false);
            ctx.drawText(this.textRenderer,
                    Text.translatable("kindreds.difficulty." + d.name().toLowerCase(Locale.ROOT) + ".desc")
                            .formatted(Formatting.GRAY),
                    r[0] + 8, r[1] + 21, 0xFF9A8F76, false);
            by += h + 4;
        }

        // --- Rule switches (lore/sandbox, not difficulty) ---
        by += 8;
        ctx.drawText(this.textRenderer, Text.translatable("kindreds.settings.rules")
                .formatted(Formatting.GOLD), x, by, 0xFFD8B45F, false);
        by += 13;
        for (String flag : FLAGS) {
            boolean on = flagValue(v, flag);
            int[] r = {x, by, panelW, 16};
            flagRects.add(r);
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
            by += 18;
        }

        Text foot = isOperator()
                ? Text.translatable("kindreds.settings.op").formatted(Formatting.DARK_GRAY)
                : Text.translatable("kindreds.settings.notop").formatted(Formatting.RED);
        ctx.drawCenteredTextWithShadow(this.textRenderer, foot, this.width / 2, by + 6,
                isOperator() ? 0xFF8A7C60 : 0xFFDD8060);
    }

    private static boolean flagValue(SyncConfigS2C.View v, String flag) {
        return switch (flag) {
            case "enableBirthTraits" -> v.birthTraits();
            case "enableCurses" -> v.curses();
            case "enableVision" -> v.vision();
            case "allowCrossTraining" -> v.crossTraining();
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
        if (isOperator()) {
            for (int i = 0; i < presetRects.size() && i < PRESETS.length; i++) {
                if (within(presetRects.get(i), mouseX, mouseY)) {
                    ClientPlayNetworking.send(
                            new SetDifficultyC2S(PRESETS[i].name().toLowerCase(Locale.ROOT)));
                    return true;
                }
            }
            SyncConfigS2C.View v = ClientConfigMirror.get();
            for (int i = 0; i < flagRects.size() && i < FLAGS.length; i++) {
                if (within(flagRects.get(i), mouseX, mouseY) && v != null) {
                    ClientPlayNetworking.send(new com.kindreds.network.SetConfigFlagC2S(
                            FLAGS[i], !flagValue(v, FLAGS[i])));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
