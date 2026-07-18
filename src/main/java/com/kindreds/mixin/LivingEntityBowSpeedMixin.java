package com.kindreds.mixin;

import com.kindreds.ability.BowDrawSpeed;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.RangedWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Swift draw": while a player is drawing a bow/crossbow, advance their {@code itemUseTimeLeft} by an
 * extra {@link BowDrawSpeed#extraTicks} per tick. Because both the shot's charge (server) and the
 * pull animation (client) read this same counter, speeding it makes the whole draw - power AND
 * animation - faster and in-sync for the local player. A no-op for anyone with no bonus (returns 0),
 * so ordinary archers are untouched. Target verified against 1.21.8 yarn: {@code
 * LivingEntity.tickActiveItemStack()} (method_6076), field {@code itemUseTimeLeft} (field_6222).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityBowSpeedMixin {

    @Shadow
    private int itemUseTimeLeft;

    @Shadow
    public abstract boolean isUsingItem();

    @Shadow
    public abstract ItemStack getActiveItem();

    @Inject(method = "tickActiveItemStack", at = @At("TAIL"))
    private void kindreds$swiftDraw(CallbackInfo ci) {
        if (!(((Object) this) instanceof PlayerEntity player) || !isUsingItem()) {
            return;
        }
        if (!(getActiveItem().getItem() instanceof RangedWeaponItem)) {
            return;
        }
        int extra = BowDrawSpeed.extraTicks(player);
        if (extra > 0 && itemUseTimeLeft > extra + 1) {
            itemUseTimeLeft -= extra;
        }
    }
}
