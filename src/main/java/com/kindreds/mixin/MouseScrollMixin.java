package com.kindreds.mixin;

import com.kindreds.KindredsClient;
import com.kindreds.client.loadout.ClientLoadout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>Scroll to switch abilities.</b> While the "ability wheel" key is held, the mouse wheel cycles the
 * selected ability slot instead of the vanilla hotbar - the fastest possible switch, with no menu at
 * all. Vanilla scrolling is only cancelled while that key is down, so normal hotbar scrolling is
 * untouched the rest of the time.
 */
@Mixin(net.minecraft.client.Mouse.class)
public class MouseScrollMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void kindreds$abilityScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.currentScreen != null || client.player == null || vertical == 0.0) {
            return;
        }
        if (!KindredsClient.cycleAbilityHeld()) {
            return;
        }
        int slots = ClientLoadout.SLOTS;
        int dir = vertical > 0 ? -1 : 1; // wheel-up walks up the bar
        KindredsClient.selectSlot(client, (ClientLoadout.selected() + dir + slots) % slots);
        ci.cancel();
    }
}
