package com.kindreds.mixin;

import com.kindreds.ability.PerkService;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Uruk-hai <b>unyielding</b> signature: a true immunity to being slowed. Rather than stripping the
 * effect each tick (a visible hack), this refuses the effect at the source - {@code canHaveStatusEffect}
 * returns {@code false} for Slowness / Mining Fatigue when the entity is a player who owns the
 * {@code unyielding} perk, so the effect is simply never applied. "They ran Rohan end to end and did
 * not tire."
 */
@Mixin(LivingEntity.class)
public class LivingEntityStatusEffectMixin {

    @Inject(method = "canHaveStatusEffect", at = @At("HEAD"), cancellable = true)
    private void kindreds$unyielding(StatusEffectInstance effect, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayerEntity player)) {
            return;
        }
        var type = effect.getEffectType();
        int rank = PerkService.rankOf(player, "unyielding");
        if (rank <= 0) {
            return;
        }
        boolean impairing = type.value() == StatusEffects.SLOWNESS.value()
                || type.value() == StatusEffects.MINING_FATIGUE.value()
                // Rank 2 is the Uruk who cannot be worn down at all: even a weakening blow slides off.
                || (rank >= 2 && type.value() == StatusEffects.WEAKNESS.value());
        if (impairing) {
            cir.setReturnValue(false);
        }
    }
}
