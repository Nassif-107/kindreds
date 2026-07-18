package com.kindreds.client;

import com.kindreds.client.screen.KindredCodexScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Overlays a "Kindred Traits" button on the base Middle-earth mod's onboarding / race-selection
 * screens, opening the {@link KindredCodexScreen} almanac so a player can read every race's boons and
 * banes before committing.
 *
 * <p>Decoupled from the base mod: the target screen is matched by its class name containing
 * {@code "onboarding"} (no compile-time reference, no reflection into its state). The button is drawn
 * via {@link ScreenEvents#afterRender} (so it renders on top of the base screen's own widgets, which
 * may not render buttons we add through the widget list) and its click handled via
 * {@link ScreenMouseEvents#afterMouseClick}. Worst case on a base-mod change: the button stops
 * appearing - it can never crash their screen.
 */
public final class OnboardingCodexButton {
    private OnboardingCodexButton() {
    }

    private static final int BW = 118;
    private static final int BH = 18;
    private static final int MARGIN = 6;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!screen.getClass().getName().toLowerCase(Locale.ROOT).contains("onboarding")) {
                return;
            }
            int bx = scaledWidth - BW - MARGIN;
            int by = MARGIN;

            ScreenEvents.afterRender(screen).register((scr, ctx, mouseX, mouseY, delta) -> {
                boolean hover = mouseX >= bx && mouseX <= bx + BW && mouseY >= by && mouseY <= by + BH;
                ctx.fill(bx, by, bx + BW, by + BH, hover ? 0xFF4A3A1A : 0xE01E1710);
                ctx.drawBorder(bx, by, BW, BH, 0xFFC8A24A);
                Text label = Text.literal("⚔ Kindred Traits");
                int lw = client.textRenderer.getWidth(label);
                ctx.drawText(client.textRenderer, label, bx + (BW - lw) / 2, by + 5, 0xFFF0E0B0, false);
            });

            ScreenMouseEvents.afterMouseClick(screen).register((scr, mouseX, mouseY, button) -> {
                if (button == 0 && mouseX >= bx && mouseX <= bx + BW && mouseY >= by && mouseY <= by + BH) {
                    KindredCodexScreen.open(client);
                }
            });
        });
    }
}
