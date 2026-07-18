package com.kindreds.ability;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * The Elf's "keen-eye" archery aim-assist ({@code true_flight} perk): each tick, an in-flight arrow
 * loosed by a player who owns the perk is nudged toward the nearest hostile within a forward cone -
 * a gentle homing, not a snap, so the shot still needs pointing roughly right. Called from
 * {@code PersistentProjectileEntityMixin#tick}. Server-side only (the owner is only a {@link
 * ServerPlayerEntity} there), and a no-op for anyone without the perk, so it costs nothing for
 * ordinary archers.
 */
public final class ArcheryAssist {
    private ArcheryAssist() {
    }

    private static final double RANGE = 24.0;
    /** cos of the half-angle of the forward cone we're willing to curve toward (~60 degrees). */
    private static final double CONE = 0.5;
    /** How hard each tick pulls the arrow's heading toward the target (0..1). Subtle on purpose. */
    private static final double PULL = 0.22;

    public static void steer(PersistentProjectileEntity arrow, ServerPlayerEntity owner) {
        if (!(arrow.getWorld() instanceof ServerWorld world) || PerkService.perksOfType(owner, "true_flight").isEmpty()) {
            return;
        }
        Vec3d velocity = arrow.getVelocity();
        double speed = velocity.length();
        if (speed < 0.15) {
            return; // stuck in a block, or spent - nothing to steer
        }
        Vec3d pos = arrow.getPos();
        Vec3d heading = velocity.multiply(1.0 / speed);

        LivingEntity best = null;
        double bestDot = CONE;
        Box box = arrow.getBoundingBox().expand(RANGE);
        List<LivingEntity> candidates = world.getEntitiesByClass(LivingEntity.class, box,
                e -> e != owner && e.isAlive() && e instanceof Monster);
        for (LivingEntity e : candidates) {
            Vec3d toEye = e.getEyePos().subtract(pos);
            double dist = toEye.length();
            if (dist < 1.0 || dist > RANGE) {
                continue;
            }
            double dot = toEye.multiply(1.0 / dist).dotProduct(heading);
            if (dot > bestDot) {
                bestDot = dot;
                best = e;
            }
        }
        if (best == null) {
            return;
        }
        Vec3d desired = best.getPos().add(0, best.getHeight() * 0.5, 0).subtract(pos).normalize();
        Vec3d newHeading = heading.multiply(1.0 - PULL).add(desired.multiply(PULL)).normalize();
        arrow.setVelocity(newHeading.multiply(speed));
        arrow.velocityModified = true;
    }
}
