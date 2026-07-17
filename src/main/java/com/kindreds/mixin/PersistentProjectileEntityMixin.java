package com.kindreds.mixin;

import com.kindreds.progression.ActivityHooks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Archery activity hook. Fabric-api has no "projectile hit" event, so this mixes into
 * {@link PersistentProjectileEntity} (the arrow/trident/etc. base class) directly.
 *
 * <p>Targets ({@code javap} on 1.21.8 yarn 1.21.8+build.1, verified against
 * {@code minecraft-merged-*.jar}):
 * <ul>
 *   <li>{@code protected void onEntityHit(EntityHitResult)} — fired when the projectile hits a
 *   living/entity target.</li>
 *   <li>{@code protected void onBlockHit(BlockHitResult)} — fired when it lands in a block
 *   instead.</li>
 * </ul>
 * {@code getOwner()} is declared {@code public} on the {@code ProjectileEntity} superclass, so
 * it's called via a plain cast rather than {@code @Shadow} (which — correctly — warns at compile
 * time that it can't find a same-named member declared directly on {@code
 * PersistentProjectileEntity} itself; a public inherited method doesn't need shadowing).
 */
@Mixin(PersistentProjectileEntity.class)
public abstract class PersistentProjectileEntityMixin {
    @Inject(method = "onEntityHit", at = @At("HEAD"))
    private void kindreds$onEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        Entity owner = ((ProjectileEntity) (Object) this).getOwner();
        if (owner instanceof ServerPlayerEntity player) {
            ActivityHooks.onArrowHitEntity(player);
        }
    }

    @Inject(method = "onBlockHit", at = @At("HEAD"))
    private void kindreds$onBlockHit(BlockHitResult hitResult, CallbackInfo ci) {
        Entity owner = ((ProjectileEntity) (Object) this).getOwner();
        if (owner instanceof ServerPlayerEntity player) {
            ActivityHooks.onArrowHitBlock(player);
        }
    }
}
