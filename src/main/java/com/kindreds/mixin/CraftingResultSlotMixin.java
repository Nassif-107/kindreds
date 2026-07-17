package com.kindreds.mixin;

import com.kindreds.progression.ActivityHooks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smithing/Crafting activity hook. Fabric-api has no generic "item crafted" event, so this mixes
 * into {@link CraftingResultSlot} directly.
 *
 * <p>Target ({@code javap} on 1.21.8 yarn 1.21.8+build.1, verified against
 * {@code minecraft-merged-*.jar}): {@code public void onTakeItem(PlayerEntity, ItemStack)} —
 * {@code CraftingResultSlot}'s own override, called exactly when the player removes the crafted
 * result from the output slot (it's also the method that internally triggers recipe-unlock/stat
 * bookkeeping for that craft), covering both the 2x2 player-inventory grid and the 3x3 crafting
 * table (both use this slot class).
 */
@Mixin(CraftingResultSlot.class)
public abstract class CraftingResultSlotMixin {
    @Inject(method = "onTakeItem", at = @At("HEAD"))
    private void kindreds$onTakeItem(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ActivityHooks.onCraftedItemTaken(serverPlayer, stack);
        }
    }
}
