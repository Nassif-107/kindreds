package com.kindreds.mixin.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An invisible player keeps their name floating above them in vanilla, which makes the Hobbit's
 * whole stealth lane worthless against other players: they cannot see you, but they can read your
 * name and walk to it.
 *
 * <p>Suppresses the label whenever the rendered entity is invisible <i>to the viewer</i>.
 * {@code EntityRenderState.invisible} is set per-viewer by vanilla (it already accounts for
 * spectators and team-mates seeing through invisibility), so allies with the right to see you still
 * do, and the check costs one field read.
 *
 * <p><b>Scope, deliberately:</b> this covers every source of invisibility, not only camouflage -
 * an invisibility potion now hides your name too. Splitting the two would need a per-player flag on
 * the wire for something the eye already agrees on: if you cannot be seen, you cannot be labelled.
 */
@Mixin(EntityRenderer.class)
public abstract class InvisibleLabelMixin {
    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"), cancellable = true)
    private void kindreds$hideLabelWhenUnseen(EntityRenderState state, Text text, MatrixStack matrices,
                                              VertexConsumerProvider vertexConsumers, int light,
                                              CallbackInfo ci) {
        if (state != null && state.invisible) {
            ci.cancel();
        }
    }
}
