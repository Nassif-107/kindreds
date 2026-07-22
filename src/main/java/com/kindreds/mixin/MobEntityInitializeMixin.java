package com.kindreds.mixin;

import com.kindreds.threat.MobMark;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code SpawnReason} exists for exactly one call and is never stored by vanilla or surfaced by any
 * Fabric event in 0.136.1 (verified against every fabric-api module jar). This is the only moment a
 * mob can be told apart as naturally spawned vs. spawner/egg/summon vs. merely reloaded - so it is
 * captured here into the persistent {@link MobMark}, and {@code MobScaler} reads it from
 * {@code ENTITY_LOAD}, which fires in both cases but has no reason of its own.
 */
@Mixin(MobEntity.class)
public abstract class MobEntityInitializeMixin {

    @Inject(method = "initialize(Lnet/minecraft/world/ServerWorldAccess;Lnet/minecraft/world/LocalDifficulty;Lnet/minecraft/entity/SpawnReason;Lnet/minecraft/entity/EntityData;)Lnet/minecraft/entity/EntityData;",
            at = @At("TAIL"))
    private void kindreds$captureSpawnReason(ServerWorldAccess world, LocalDifficulty difficulty,
                                             SpawnReason reason, EntityData data,
                                             CallbackInfoReturnable<EntityData> cir) {
        MobEntity self = (MobEntity) (Object) this;
        MobMark.set(self, MobMark.of(self).withSpawnReason(reason.name()));
    }
}
