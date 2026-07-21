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

    private final List<int[]> presetRects = new ArrayList<>();
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

        int panelW = Math.min(420, this.width - 40);
        int x = (this.width - panelW) / 2;
        int y = Math.max(20, this.height / 2 - 150);

        ctx.fill(x - 8, y - 10, x + panelW + 8, y + 292, 0xE0120F0A);
        ctx.drawBorder(x - 8, y - 10, panelW + 16, 302, 0xFF4A3D28);

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
                                + (v.softCap() > 0 ? v.softCap() : Text.translatable("kindreds.settings.off").getString())
                                + "   ·   " + Text.translatable("kindreds.settings.scaling").getString() + " "
                                + onOff(v.enemyScaling()))
                .formatted(Formatting.GRAY), x, y + 33, 0xFFB6A888, false);

        int by = y + 54;
        for (Difficulty d : PRESETS) {
            boolean active = d.name().equalsIgnoreCase(v.difficulty());
            int h = 40;
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

        Text foot = isOperator()
                ? Text.translatable("kindreds.settings.op").formatted(Formatting.DARK_GRAY)
                : Text.translatable("kindreds.settings.notop").formatted(Formatting.RED);
        ctx.drawCenteredTextWithShadow(this.textRenderer, foot, this.width / 2, by + 6,
                isOperator() ? 0xFF8A7C60 : 0xFFDD8060);
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
