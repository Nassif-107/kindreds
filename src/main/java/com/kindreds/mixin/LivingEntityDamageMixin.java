package com.kindreds.mixin;

import com.kindreds.ability.PerkEventHandlers;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales the damage a player deals so the {@code bane} and {@code arrow_slaying} perks can add real
 * bonus damage against a foe category - the one place a perk needs to change a vanilla number rather
 * than react after the fact, so it's done here in the damage pipeline instead of via a Fabric event
 * (which can only cancel or observe damage, not rescale it). Doing it on the <b>victim's</b> {@code
 * damage} at HEAD, before mitigation, means one hook covers both melee and arrows, and avoids the
 * i-frame breakage a "deal a second hit" approach would cause.
 *
 * <p>Target ({@code javap} on 1.21.8 yarn 1.21.8+build.1): {@code LivingEntity#damage(ServerWorld,
 * DamageSource, float)boolean} - the 1.21 signature that takes the {@link ServerWorld}. The full
 * descriptor is spelled out in {@code method} so the injector can never bind the wrong overload. The
 * handler captures the target's whole argument list (value + all three params) to satisfy strict
 * argument-capture matching. When the attacker isn't a player, or the player owns no relevant perk,
 * {@link PerkEventHandlers#outgoingDamageMultiplier} returns {@code 1.0} and the amount is unchanged.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

    @ModifyVariable(
            method = "damage(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/damage/DamageSource;F)Z",
            at = @At("HEAD"),
            argsOnly = true)
    private float kindreds$scalePerkDamage(float value, ServerWorld world, DamageSource source, float rawAmount) {
        float amount = value;
        LivingEntity self = (LivingEntity) (Object) this;
        // Attacker-side perks (bane / arrow-slaying) scale damage this player DEALS.
        if (source.getAttacker() instanceof ServerPlayerEntity attacker) {
            boolean projectile = source.isIn(DamageTypeTags.IS_PROJECTILE);
            amount *= PerkEventHandlers.outgoingDamageMultiplier(attacker, self, projectile);
        }
        // Defender-side perks (evasion) scale damage this player TAKES.
        if (self instanceof ServerPlayerEntity victim) {
            amount *= PerkEventHandlers.incomingDamageMultiplier(victim, source);
        }
        return amount;
    }
}
