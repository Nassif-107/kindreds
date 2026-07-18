package com.kindreds.client.loadout;

import com.kindreds.playerdata.ClientKindredData;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * The always-on ability bar: a small vertical stack of {@link ClientLoadout#SLOTS} slot boxes on the
 * left edge, each showing the assigned active's name, its live cooldown, and a highlight on the
 * currently-selected slot (the one the "use ability" key fires). Only drawn once the player has
 * unlocked at least one active ability, so it never clutters the screen of someone who hasn't.
 */
public final class LoadoutHud {
    private LoadoutHud() {
    }

    private static final int SLOT_W = 104;
    private static final int SLOT_H = 16;
    private static final int MARGIN = 4;
    private static final int SELECTED_BORDER = 0xFFF0C000; // gold
    private static final int BORDER = 0xFF3A3A3A;
    private static final int BG = 0xB0101014;
    private static final int BG_SELECTED = 0xD0201808;

    public static void register() {
        HudRenderCallback.EVENT.register(LoadoutHud::render);
    }

    private static void render(DrawContext ctx, Object tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) {
            return;
        }
        List<String> unlocked = ClientLoadout.unlockedAbilityIds();
        if (unlocked.isEmpty()) {
            return; // no actives earned yet - keep the HUD clean
        }

        TextRenderer tr = mc.textRenderer;
        int total = SLOT_H * ClientLoadout.SLOTS + 2 * (ClientLoadout.SLOTS - 1);
        int x = MARGIN;
        int y = (ctx.getScaledWindowHeight() - total) / 2;
        long now = mc.world.getTime();

        for (int i = 0; i < ClientLoadout.SLOTS; i++) {
            int slotY = y + i * (SLOT_H + 2);
            boolean isSelected = i == ClientLoadout.selected();
            ctx.fill(x, slotY, x + SLOT_W, slotY + SLOT_H, isSelected ? BG_SELECTED : BG);
            ctx.drawBorder(x, slotY, SLOT_W, SLOT_H, isSelected ? SELECTED_BORDER : BORDER);

            String abilityId = ClientLoadout.slot(i);
            String label = (i + 1) + " " + ClientLoadout.displayName(abilityId);
            int textColor = abilityId.isEmpty() ? 0xFF808080 : 0xFFFFFFFF;

            // Cooldown readout (right-aligned), red while cooling down.
            long end = ClientKindredData.INSTANCE.cooldowns().getLong(abilityId);
            long remaining = end - now;
            if (!abilityId.isEmpty() && remaining > 0) {
                String cd = String.format("%.0fs", Math.ceil(remaining / 20.0));
                ctx.drawText(tr, Text.literal(cd).formatted(Formatting.RED),
                        x + SLOT_W - tr.getWidth(cd) - 3, slotY + 4, 0xFFFF5555, false);
                textColor = 0xFFB0B0B0; // dim the name while on cooldown
            } else if (!abilityId.isEmpty()) {
                ctx.drawText(tr, Text.literal("✔").formatted(Formatting.GREEN),
                        x + SLOT_W - tr.getWidth("✔") - 3, slotY + 4, 0xFF55FF55, false);
            }

            ctx.drawText(tr, label, x + 4, slotY + 4, textColor, false);
        }
    }
}
