package com.kindreds.client.loadout;

import com.kindreds.playerdata.ClientKindredData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * The <b>ability radial</b>: a quick-select wheel over the {@link ClientLoadout#SLOTS} equipped
 * actives. Opened with a tap of the "cycle ability" key, it is the comfortable way to switch without
 * mashing a cycle key blind - the pattern proven by Ars Nouveau's and Iron's Spells' spell wheels.
 *
 * <p>Every reasonable input works, so nobody has to learn a special gesture:
 * <ul>
 *   <li><b>Move the mouse</b> toward a wedge to highlight it, then <b>click</b> (or Enter/Space).</li>
 *   <li><b>Number keys 1-{@value ClientLoadout#SLOTS}</b> pick a slot outright.</li>
 *   <li><b>Scroll</b> to move the highlight.</li>
 *   <li><b>Esc</b> cancels and keeps the previous slot.</li>
 * </ul>
 * The world keeps running while it is open ({@link #shouldPause()} is {@code false}) - this is a
 * combat control, not a menu.
 */
public class AbilityRadialScreen extends Screen {
    private static final int RADIUS = 78;       // distance from centre to each wedge label
    private static final int DEAD_ZONE = 26;    // inside this, nothing is highlighted
    private static final int BOX_W = 116;
    private static final int BOX_H = 30;

    private int highlighted;

    public AbilityRadialScreen() {
        super(Text.translatable("kindreds.radial.title"));
        this.highlighted = ClientLoadout.selected();
    }

    /** A combat control: the world must keep running behind it. */
    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Mouse direction picks the wedge, but only once the cursor leaves the dead zone, so a tiny
        // twitch near the centre never changes the selection.
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        if (Math.sqrt(dx * dx + dy * dy) > DEAD_ZONE) {
            double deg = (Math.toDegrees(Math.atan2(dy, dx)) + 90.0 + 360.0) % 360.0;
            highlighted = (int) Math.floor(deg / (360.0 / ClientLoadout.SLOTS)) % ClientLoadout.SLOTS;
        }

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.radial.title").formatted(Formatting.GOLD), cx, cy - 6, 0xFFFFD86B);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("kindreds.radial.hint").formatted(Formatting.DARK_GRAY), cx, cy + 6, 0xFF8A7C60);

        // Dead end guard: unlocked actives but nothing assigned means four empty wedges and no clue
        // what to do. Say where to assign them, using the player's actual loadout key.
        boolean anyAssigned = false;
        for (int i = 0; i < ClientLoadout.SLOTS; i++) {
            if (!ClientLoadout.slot(i).isEmpty()) {
                anyAssigned = true;
                break;
            }
        }
        if (!anyAssigned) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("kindreds.radial.assign",
                            com.kindreds.KindredsClient.openLoadoutKeyName()).formatted(Formatting.YELLOW),
                    cx, cy + RADIUS + 34, 0xFFFFD86B);
        }

        for (int i = 0; i < ClientLoadout.SLOTS; i++) {
            double angle = Math.toRadians(i * (360.0 / ClientLoadout.SLOTS) - 90.0);
            int bx = cx + (int) Math.round(Math.cos(angle) * RADIUS) - BOX_W / 2;
            int by = cy + (int) Math.round(Math.sin(angle) * RADIUS) - BOX_H / 2;
            boolean on = i == highlighted;

            String id = ClientLoadout.slot(i);
            boolean empty = id == null || id.isEmpty();
            ctx.fill(bx, by, bx + BOX_W, by + BOX_H, on ? 0xF01F1A12 : 0xC0120F0A);
            ctx.drawBorder(bx, by, BOX_W, BOX_H, on ? 0xFFD8B45F : 0xFF3B3122);

            Text label = empty
                    ? Text.translatable("kindreds.radial.empty").formatted(Formatting.DARK_GRAY)
                    : Text.literal(ClientLoadout.displayName(id))
                            .formatted(on ? Formatting.WHITE : Formatting.GRAY);
            ctx.drawCenteredTextWithShadow(this.textRenderer, label, bx + BOX_W / 2, by + 5,
                    on ? 0xFFFFFFFF : 0xFFB6A888);

            // Slot number, plus the live cooldown so you can pick something that is actually ready.
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(String.valueOf(i + 1)).formatted(Formatting.GOLD), bx + 4, by + 5, 0xFFD8B45F);
            String cd = cooldownLabel(id);
            if (!cd.isEmpty()) {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal(cd).formatted(Formatting.RED), bx + BOX_W / 2, by + 17, 0xFFFF6B6B);
            } else if (!empty) {
                ctx.drawCenteredTextWithShadow(this.textRenderer,
                        Text.translatable("kindreds.radial.ready").formatted(Formatting.GREEN),
                        bx + BOX_W / 2, by + 17, 0xFF7FD46B);
            }
        }
    }

    /** Remaining cooldown on {@code id} as e.g. {@code "12.5s"}, or "" when ready/empty. */
    private String cooldownLabel(String id) {
        if (id == null || id.isEmpty() || this.client == null || this.client.world == null) {
            return "";
        }
        long end = ClientKindredData.INSTANCE.cooldowns().getLong(id);
        long left = end - this.client.world.getTime();
        return left > 0 ? String.format("%.1fs", left / 20.0) : "";
    }

    private void choose(int slot) {
        ClientLoadout.setSelected(slot);
        MinecraftClient c = this.client != null ? this.client : MinecraftClient.getInstance();
        if (c.player != null) {
            String id = ClientLoadout.slot(slot);
            c.player.sendMessage(Text.translatable("kindreds.hud.selected", slot + 1,
                    ClientLoadout.displayName(id)).formatted(Formatting.AQUA), true);
        }
        this.close();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        choose(highlighted);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int step = vertical > 0 ? -1 : 1;
        highlighted = (highlighted + step + ClientLoadout.SLOTS) % ClientLoadout.SLOTS;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode < GLFW.GLFW_KEY_1 + ClientLoadout.SLOTS) {
            choose(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE
                || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            choose(highlighted);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
