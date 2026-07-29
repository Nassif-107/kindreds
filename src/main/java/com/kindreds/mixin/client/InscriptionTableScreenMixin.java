package com.kindreds.mixin.client;

import com.kindreds.client.screen.InscriptionsScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.sevenstars.middleearth.gui.inscriptiontable.InscriptionTableScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A way into the reference page from the table itself.
 *
 * <p>Standing at the table is exactly when somebody wants to know what the words make, and until
 * now the answer was to close it, open the hub, find the page, read it and come back. One button
 * removes all of that.
 *
 * <h2>Why a mixin, and why it is this small</h2>
 * The base mod's screen is an ordinary {@code HandledScreen} with no hook for adding a widget, so
 * there is no polite way in. The injection appends one button at the end of {@code init} and
 * touches nothing else: a future base-mod change can move the button or lose it, but cannot alter
 * how the table works.
 *
 * <p>{@code require = 0} is the honest failure mode here. If Middle-earth renames or restructures
 * its screen, the right outcome is a missing button and a working table - not a client that refuses
 * to start - because the page is still reachable from the kindreds hub either way. What catches the
 * rename is the contract test, not a crash in front of a player.
 */
@Mixin(value = InscriptionTableScreen.class, remap = false)
public abstract class InscriptionTableScreenMixin extends Screen {

    private InscriptionTableScreenMixin() {
        // Never called. The superclass is declared only so this mixin can reach addDrawableChild.
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void kindreds$addInscriptionsButton(CallbackInfo info) {
        // Top-right of the window rather than inside the table's frame: the frame is a fixed
        // texture, and anything drawn over it hides something the base mod meant to be seen.
        int buttonWidth = 84;
        addDrawableChild(ButtonWidget
            .builder(Text.translatable("kindreds.inscriptions.open"), button -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) {
                    InscriptionsScreen.open(client);
                }
            })
            .dimensions(this.width - buttonWidth - 6, 6, buttonWidth, 16)
            .tooltip(Tooltip.of(Text.translatable("kindreds.inscriptions.open.tip")))
            .build());
    }
}
