package com.kindreds.client.loadout;

import com.kindreds.playerdata.ClientKindredData;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The always-on <b>ability bar</b>: a row of {@link ClientLoadout#SLOTS} hotbar-sized slots in the
 * bottom-right, styled to read at a glance the way the vanilla hotbar does.
 *
 * <p>Per slot: its number, a <b>vanilla-style cooldown sweep</b> (a dark overlay draining from the
 * bottom) with the remaining seconds, and a gold frame on the selected slot. When a cooldown ends the
 * slot <b>flashes</b> briefly so you notice an ability came back up without watching the bar. Only the
 * <i>selected</i> ability is named (above the row), which keeps four long Elvish names from becoming
 * wallpaper.
 *
 * <p>Hidden entirely until the player has actually unlocked an active, so it never clutters a new
 * player's screen.
 */
public final class LoadoutHud {
    private LoadoutHud() {
    }

    // Sized against the window rather than fixed: at 22px the slots crowded the corner and pushed
    // the key hint off the bottom of a small screen, and a size that suits a 1080p window is wrong
    // for someone playing at GUI scale 4 in a 480px-tall one.
    private static final int SLOT_LARGE = 20;
    private static final int SLOT_SMALL = 15;
    private static final int GAP = 2;
    private static final int MARGIN = 5;

    /** Slot size for the current window - compact when there is little vertical room to spare. */
    private static int slotSize(DrawContext ctx) {
        return ctx.getScaledWindowHeight() < 260 ? SLOT_SMALL : SLOT_LARGE;
    }
    private static final int FLASH_TICKS = 12;

    /** ability id -> the full cooldown length of the current cycle, so the sweep has a denominator
     * (the synced data only carries the end tick). */
    private static final Map<String, Long> TOTALS = new HashMap<>();
    /** ability id -> world time at which it last became ready, for the flash. */
    private static final Map<String, Long> READY_AT = new HashMap<>();

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
        long now = mc.world.getTime();
        final int SLOT = slotSize(ctx);
        int barW = ClientLoadout.SLOTS * SLOT + (ClientLoadout.SLOTS - 1) * GAP;
        int x0 = ctx.getScaledWindowWidth() - barW - MARGIN;
        int y = ctx.getScaledWindowHeight() - SLOT - MARGIN;

        for (int i = 0; i < ClientLoadout.SLOTS; i++) {
            int x = x0 + i * (SLOT + GAP);
            boolean sel = i == ClientLoadout.selected();
            String id = ClientLoadout.slot(i);
            boolean empty = id == null || id.isEmpty();

            ctx.fill(x, y, x + SLOT, y + SLOT, sel ? 0xD02A2010 : 0xA0101014);

            long remaining = 0L;
            if (!empty) {
                remaining = Math.max(0L, ClientKindredData.INSTANCE.cooldowns().getLong(id) - now);
                trackCooldown(id, remaining, now);
                if (remaining > 0) {
                    // Vanilla-style sweep: a dark overlay covering the fraction still on cooldown.
                    long total = Math.max(1L, TOTALS.getOrDefault(id, remaining));
                    int h = (int) Math.round(SLOT * Math.min(1.0, remaining / (double) total));
                    ctx.fill(x, y + SLOT - h, x + SLOT, y + SLOT, 0x99000000);
                }
            }

            // Ready-flash: a brief warm pulse right after an ability comes off cooldown.
            Long readyAt = empty ? null : READY_AT.get(id);
            if (readyAt != null && now - readyAt < FLASH_TICKS) {
                int a = (int) (0x88 * (1.0 - (now - readyAt) / (double) FLASH_TICKS));
                ctx.fill(x, y, x + SLOT, y + SLOT, (a << 24) | 0x00FFD86B);
            }

            ctx.drawBorder(x, y, SLOT, SLOT, sel ? 0xFFD8B45F : (empty ? 0xFF2A2A2A : 0xFF4A4030));
            if (sel) { // a second, inset frame reads as "raised" without moving the slot
                ctx.drawBorder(x - 1, y - 1, SLOT + 2, SLOT + 2, 0x66D8B45F);
            }

            // Slot number, dim when empty.
            ctx.drawText(tr, Text.literal(String.valueOf(i + 1)),
                    x + 2, y + 2, empty ? 0xFF5A5A5A : 0xFFD8B45F, false);

            if (!empty && remaining > 0) {
                String cd = remaining >= 20 ? String.valueOf((int) Math.ceil(remaining / 20.0))
                        : String.format("%.1f", remaining / 20.0);
                ctx.drawText(tr, Text.literal(cd).formatted(Formatting.RED),
                        x + SLOT - tr.getWidth(cd) - 2, y + SLOT - 10, 0xFFFF6B6B, true);
            }
        }

        // No name above the row. It was the widest thing on screen, ran off to the left, and told
        // you what the highlighted slot already shows - the radial and the tree are where names
        // belong. The bar is just the bar.

        // "You have points to spend" pip - the single best nudge back into the skill tree. Sits above
        // the row, gently pulsing so it reads as new without nagging.
        int unspent = com.kindreds.client.ClientProgress.unspentTotal();
        if (unspent > 0) {
            // At the tree-wide cap those points cannot be spent at all, so the nudge becomes a calm
            // statement of fact instead: a pulsing "go spend these" that can never be obeyed is nagging.
            boolean capped = com.kindreds.client.ClientProgress.atCap();
            // "2 ready to learn" is something to act on; "2 points" is only a number. Falls back to
            // the point count when the points cannot buy anything yet (prerequisites unmet).
            int ready = com.kindreds.client.ClientProgress.readyTotal();
            // Kept terse on purpose: this sits over the world, so it says the number and the key
            // and nothing else. "3 ready - K", not a sentence.
            Text pip = capped
                    ? Text.translatable("kindreds.hud.capped.short")
                    : ready > 0
                        ? Text.translatable("kindreds.hud.ready.short", ready,
                            com.kindreds.KindredsClient.openTreeKeyName())
                        : Text.translatable("kindreds.hud.points.short", unspent,
                            com.kindreds.KindredsClient.openTreeKeyName());
            int w = tr.getWidth(pip) + 8;
            int px = x0 + barW - w;
            int py = y - 24;
            double pulse = !capped && com.kindreds.Kindreds.CONFIG.hudAnimations
                    ? 0.5 + 0.5 * Math.sin(now / 6.0) : 1.0;
            int alpha = (int) (0x60 + 0x40 * pulse) << 24;
            ctx.fill(px, py, px + w, py + 11, alpha | (capped ? 0x00201A14 : 0x00332200));
            ctx.drawBorder(px, py, w, 11, capped ? 0xFF6E6250 : 0xFFD8B45F);
            ctx.drawText(tr, pip, px + 4, py + 2, capped ? 0xFFA9997C : 0xFFFFD86B, false);
        }

        // The key hint is onboarding, not permanent furniture: it ran under the slots every second of
        // play, was the first thing clipped off the bottom at small window sizes, and told a veteran
        // nothing. Shown only while no ability is bound - the one time it is actually needed.
        boolean nothingBound = true;
        for (int i = 0; i < ClientLoadout.SLOTS; i++) {
            String bound = ClientLoadout.slot(i);
            if (bound != null && !bound.isEmpty()) {
                nothingBound = false;
                break;
            }
        }
        if (nothingBound) {
            Text hint = Text.literal(com.kindreds.KindredsClient.cycleAbilityKeyName().getString() + " "
                    + Text.translatable("kindreds.hud.switch").getString() + "  ·  "
                    + com.kindreds.KindredsClient.useAbilityKeyName().getString() + " "
                    + Text.translatable("kindreds.hud.use").getString());
            ctx.drawText(tr, hint, x0 + barW - tr.getWidth(hint), y - 22, 0xFF8A7C60, false);
        }
    }

    /** Remembers each cooldown's full length (for the sweep) and when it ended (for the flash). */
    private static void trackCooldown(String id, long remaining, long now) {
        if (remaining > 0) {
            long prev = TOTALS.getOrDefault(id, 0L);
            if (remaining > prev) {
                TOTALS.put(id, remaining); // a fresh cast: this is the full length
            }
        } else if (TOTALS.remove(id) != null) {
            READY_AT.put(id, now); // just came off cooldown - flash it
        }
    }
}
