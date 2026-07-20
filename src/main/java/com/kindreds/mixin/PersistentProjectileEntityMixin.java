package com.kindreds.mixin;

import com.kindreds.ability.ArrowPerks;
import com.kindreds.progression.ActivityHooks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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

    /** One-shot guard so the arrow's spawn-time perks (crit/pierce/velocity/multishot) apply exactly
     * once, on its first ticked frame, rather than every tick. */
    @Unique
    private boolean kindreds$perksApplied;

    @Inject(method = "onEntityHit", at = @At("HEAD"))
    private void kindreds$onEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        Entity owner = ((ProjectileEntity) (Object) this).getOwner();
        if (owner instanceof ServerPlayerEntity player) {
            ActivityHooks.onArrowHitEntity(player);
            if (hitResult.getEntity() instanceof LivingEntity target) {
                ArrowPerks.onHit((PersistentProjectileEntity) (Object) this, player, target);
            }
        }
    }

    @Inject(method = "onBlockHit", at = @At("HEAD"))
    private void kindreds$onBlockHit(BlockHitResult hitResult, CallbackInfo ci) {
        Entity owner = ((ProjectileEntity) (Object) this).getOwner();
        if (owner instanceof ServerPlayerEntity player) {
            ActivityHooks.onArrowHitBlock(player);
        }
    }

    /** Keen-eye aim-assist: nudge an in-flight arrow toward a nearby foe if its owner has the
     * {@code true_flight} perk (a no-op otherwise). See {@link com.kindreds.ability.ArcheryAssist}. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kindreds$arrowTick(CallbackInfo ci) {
        Entity owner = ((ProjectileEntity) (Object) this).getOwner();
        if (owner instanceof ServerPlayerEntity player) {
            PersistentProjectileEntity self = (PersistentProjectileEntity) (Object) this;
            if (!kindreds$perksApplied) {
                kindreds$perksApplied = true;
                ArrowPerks.onSpawn(self, player); // crit / pierce / velocity / multishot, once
            }
            com.kindreds.ability.ArcheryAssist.steer(self, player); // keen-eye aim-assist
        }
    }
}
