package com.kindreds.mixin;

import com.kindreds.progression.ActivityHooks;
import net.minecraft.entity.passive.AbstractHorseEntity;
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
 * Beast-lore taming-milestone activity hook for horses (and donkeys/mules, which share this base
 * class).
 *
 * <p>Target ({@code javap} on 1.21.8 yarn 1.21.8+build.1, verified against
 * {@code minecraft-merged-*.jar}): {@code AbstractHorseEntity} overrides
 * {@code public ActionResult interactMob(PlayerEntity, Hand)} directly, and exposes
 * {@code public boolean isTame()}. No owner-check method equivalent to
 * {@code TameableEntity.isOwner} is exposed on this class, so the before/after {@code isTame()}
 * transition alone is the signal — see {@link WolfTameActivityMixin}'s javadoc for why that
 * transition is exploit-safe on its own: {@code interactMob} never runs on chunk/world load, so
 * the {@code false -> true} flip this mixin looks for can only happen as the direct, synchronous
 * result of this specific player's click.
 */
@Mixin(AbstractHorseEntity.class)
public abstract class HorseTameActivityMixin {

    @Unique
    private boolean kindreds$wasTameBeforeInteract;

    @Inject(method = "interactMob", at = @At("HEAD"))
    private void kindreds$captureTameStateBefore(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        kindreds$wasTameBeforeInteract = ((AbstractHorseEntity) (Object) this).isTame();
    }

    @Inject(method = "interactMob", at = @At("TAIL"))
    private void kindreds$creditTameOnSuccess(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        AbstractHorseEntity self = (AbstractHorseEntity) (Object) this;
        if (!kindreds$wasTameBeforeInteract && self.isTame() && player instanceof ServerPlayerEntity sp) {
            ActivityHooks.onAnimalTamed(sp);
        }
    }
}
