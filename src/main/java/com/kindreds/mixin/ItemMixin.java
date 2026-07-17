package com.kindreds.mixin;

import com.kindreds.progression.ActivityHooks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Survival (eating) activity hook. 1.21+ food is data-component-driven ({@code ConsumableComponent}
 * / {@code FoodComponent}), and the single place every item's "use finished" completion funnels
 * through — regardless of whether it's food, a potion, milk, etc. — is {@link Item#finishUsing}.
 *
 * <p>Target ({@code javap} on 1.21.8 yarn 1.21.8+build.1, verified against
 * {@code minecraft-merged-*.jar}): {@code public ItemStack finishUsing(ItemStack, World,
 * LivingEntity)}. Injected at {@code HEAD} so the passed-in {@code stack} parameter still carries
 * its original components (in particular {@code DataComponentTypes.FOOD}) regardless of what the
 * (possibly stack-shrinking/replacing) vanilla body does afterwards; {@code stack.contains(FOOD)}
 * is used to only credit Survival for actual food items, not every consumable (e.g. not potions).
 */
@Mixin(Item.class)
public abstract class ItemMixin {
    @Inject(method = "finishUsing", at = @At("HEAD"))
    private void kindreds$onFinishUsing(ItemStack stack, World world, LivingEntity user, CallbackInfoReturnable<ItemStack> cir) {
        if (user instanceof ServerPlayerEntity player && stack.contains(DataComponentTypes.FOOD)) {
            ActivityHooks.onFoodEaten(player);
        }
    }
}
