package com.kindreds.mixin;

import net.minecraft.entity.player.HungerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessor for {@link HungerManager}'s private {@code exhaustion} field. Vanilla exposes
 * {@code getFoodLevel()} and {@code getSaturationLevel()} but no getter for exhaustion, which
 * {@link com.kindreds.ability.RacialNatureService}'s "endure hunger" nature needs to read so it can
 * bleed most of it off each second (draining food far slower for Dwarves/Uruk-hai across sprinting,
 * combat, and regeneration alike). An {@code @Accessor} adds no behaviour - it only surfaces the
 * existing field - so it carries none of the load/version risk of an injecting mixin.
 */
@Mixin(HungerManager.class)
public interface HungerManagerAccessor {
    @Accessor("exhaustion")
    float kindreds$getExhaustion();
}
