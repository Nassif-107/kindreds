package com.kindreds.mixin;

import net.minecraft.entity.projectile.PersistentProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the two arrow internals the arrow-perk engine ({@link com.kindreds.ability.ArrowPerks})
 * needs but vanilla keeps protected/private: {@code setPierceLevel} (for the pierce perk) and the
 * {@code damage} field (to copy a bow shot's power onto multishot siblings). Accessor-only - no
 * behaviour, so no injecting-mixin risk.
 */
@Mixin(PersistentProjectileEntity.class)
public interface PersistentProjectileEntityAccessor {
    @Invoker("setPierceLevel")
    void kindreds$setPierceLevel(byte level);

    @Accessor("damage")
    double kindreds$getDamage();
}
