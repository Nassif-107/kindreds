package com.kindreds.client;

import com.kindreds.client.screen.KindredCodexScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Adds a "Kindred Traits" button onto the base Middle-earth mod's onboarding / race-selection
 * screens, opening the {@link KindredCodexScreen} almanac so a player can read every race's innate
 * boons and banes <b>before</b> they commit to a people.
 *
 * <p>Deliberately decoupled from the base mod: the target screen is matched by its class name
 * containing {@code "onboarding"} (no compile-time reference to a base-mod screen class, no reflection
 * into its private selection state), and the button is added through Fabric's public
 * {@link Screens#getButtons} API. If the base mod renames or restructures its onboarding screens, the
 * worst case is the button simply stops appearing - it can never crash their screen.
 */
public final class OnboardingCodexButton {
    private OnboardingCodexButton() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!screen.getClass().getName().toLowerCase(Locale.ROOT).contains("onboarding")) {
                return;
            }
            ButtonWidget button = ButtonWidget.builder(
                            Text.literal("⚔ Kindred Traits"),
                            btn -> KindredCodexScreen.open(client))
                    .dimensions(scaledWidth / 2 - 70, scaledHeight - 26, 140, 20)
                    .build();
            Screens.getButtons(screen).add(button);
        });
    }
}
