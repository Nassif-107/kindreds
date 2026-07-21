package com.kindreds.mixin.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla keeps drawing worn armour on an invisible entity, so a "vanished" player is a suit of
 * boots and a helmet walking around on its own. For a mod whose stealth branches end at the floor of
 * what the game allows, that is the difference between hidden and obvious.
 *
 * <p>Cancels the armour layer whenever the entity is invisible to the viewer. Same per-viewer flag,
 * and the same deliberate scope, as {@link InvisibleLabelMixin}.
 */
@Mixin(ArmorFeatureRenderer.class)
public abstract class InvisibleEquipmentMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/client/render/entity/state/BipedEntityRenderState;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void kindreds$hideArmourWhenUnseen(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                               int light, BipedEntityRenderState state, float limbAngle,
                                               float limbDistance, CallbackInfo ci) {
        if (state != null && state.invisible) {
            ci.cancel();
        }
    }
}
