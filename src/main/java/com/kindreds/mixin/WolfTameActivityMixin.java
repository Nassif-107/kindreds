package com.kindreds.mixin;

import com.kindreds.progression.ActivityHooks;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Beast-lore taming-milestone activity hook for wolves.
 *
 * <p>Target ({@code javap} on 1.21.8 yarn 1.21.8+build.1, verified against
 * {@code minecraft-merged-*.jar}): {@code WolfEntity} overrides
 * {@code public ActionResult interactMob(PlayerEntity, Hand)} directly (not merely inherited).
 *
 * <p>Deliberately does NOT hook {@code TameableEntity.setTamed} — that method also fires from
 * {@code readCustomData} on every chunk/world load of an already-tamed wolf, which would award xp
 * on every reload instead of once at the moment of taming (a chunk-reload xp-farming exploit).
 * Hooking {@code interactMob} instead and comparing {@link TameableEntity#isTamed()} before vs.
 * after the vanilla call avoids this entirely: {@code interactMob} is never part of the NBT-load
 * path, so the {@code false -> true} transition this mixin looks for can only happen as the
 * direct, synchronous result of this specific player's click. {@code isOwner(player)} at TAIL
 * confirms it was this player's tame roll, not some other concurrent effect.
 */
@Mixin(WolfEntity.class)
public abstract class WolfTameActivityMixin {

    @Unique
    private boolean kindreds$wasTamedBeforeInteract;

    @Inject(method = "interactMob", at = @At("HEAD"))
    private void kindreds$captureTameStateBefore(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        kindreds$wasTamedBeforeInteract = ((TameableEntity) (Object) this).isTamed();
    }

    @Inject(method = "interactMob", at = @At("TAIL"))
    private void kindreds$creditTameOnSuccess(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        TameableEntity self = (TameableEntity) (Object) this;
        if (!kindreds$wasTamedBeforeInteract && self.isTamed() && self.isOwner(player)
                && player instanceof ServerPlayerEntity sp) {
            ActivityHooks.onAnimalTamed(sp);
        }
    }
}
