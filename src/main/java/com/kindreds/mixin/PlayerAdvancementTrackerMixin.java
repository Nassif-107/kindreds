package com.kindreds.mixin;

import com.kindreds.progression.ActivityHooks;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lore (advancement) activity hook. Fabric-api has no advancement-grant event (its own
 * {@code PlayerAdvancementTrackerMixin}, in {@code fabric-events-interaction-v0}, only cancels
 * fake-player criterion grants — confirmed by decompiling its class file), so this mixes into
 * {@link PlayerAdvancementTracker} directly.
 *
 * <p>Target ({@code javap} on 1.21.8 yarn 1.21.8+build.1, verified against
 * {@code minecraft-merged-*.jar}): {@code public boolean grantCriterion(AdvancementEntry,
 * String)}, injected at {@code RETURN}. {@code grantCriterion} returns {@code true} only when that
 * specific criterion was newly granted (it returns {@code false} if the player already had it, or
 * if the advancement doesn't have that criterion) — so gating on
 * {@code cir.getReturnValue() == true} <b>and</b> {@code getProgress(advancement).isDone()} fires
 * exactly once, on the call that completes the last remaining criterion (not on every criterion
 * grant along the way, and not repeatedly for an already-completed advancement).
 */
@Mixin(PlayerAdvancementTracker.class)
public abstract class PlayerAdvancementTrackerMixin {
    @Shadow
    private ServerPlayerEntity owner;

    @Shadow
    public abstract AdvancementProgress getProgress(AdvancementEntry advancement);

    @Inject(method = "grantCriterion", at = @At("RETURN"))
    private void kindreds$onGrantCriterion(AdvancementEntry advancement, String criterionName, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        AdvancementProgress progress = this.getProgress(advancement);
        if (progress != null && progress.isDone()) {
            ActivityHooks.onAdvancementCompleted(this.owner, advancement);
        }
    }
}
