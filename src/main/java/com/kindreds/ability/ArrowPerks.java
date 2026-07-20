package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import com.kindreds.mixin.PersistentProjectileEntityAccessor;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * The Elf-archery (and any bow-race) arrow perks that act on the projectile itself, rather than on the
 * damage number. Read from {@code PerkService} for the arrow's owner and applied by {@code
 * PersistentProjectileEntityMixin}: once at spawn ({@link #onSpawn}) and on a living hit ({@link
 * #onHit}). All server-side (owner is a {@link ServerPlayerEntity} only there); a no-op for a player
 * with none of these perks, so ordinary archers pay nothing.
 *
 * <ul>
 *   <li><b>arrow_crit</b> - the shot always deals a critical (even a quick, un-drawn one).</li>
 *   <li><b>arrow_pierce</b> ({@code targets}) - the arrow punches through a file of foes.</li>
 *   <li><b>arrow_velocity</b> ({@code bonus}) - flatter, faster flight (more range/impact).</li>
 *   <li><b>multishot</b> ({@code arrows}) - looses extra arrows in a spread (the rain of arrows).</li>
 *   <li><b>arrow_effect</b> ({@code effect}, {@code foe}) - a venom/barbed/starlight tip: a status
 *       effect on the struck foe.</li>
 * </ul>
 * (Flat/percent bow-damage and long-shot are handled in the damage path, {@code
 * PerkEventHandlers.outgoingDamageMultiplier}.)
 */
public final class ArrowPerks {
    private ArrowPerks() {
    }

    /** Marks arrows spawned BY a multishot so they don't recursively multishot again. */
    private static final String SIBLING_TAG = "kindreds_ms";

    public static void onSpawn(PersistentProjectileEntity arrow, ServerPlayerEntity owner) {
        if (!PerkService.perksOfType(owner, "arrow_crit").isEmpty()) {
            arrow.setCritical(true);
        }

        int pierce = 0;
        for (PerkDef perk : PerkService.perksOfType(owner, "arrow_pierce")) {
            pierce = Math.max(pierce, Math.round(perk.param("targets", 1f)));
        }
        if (pierce > 0) {
            ((PersistentProjectileEntityAccessor) arrow).kindreds$setPierceLevel((byte) Math.min(pierce, 100));
        }

        float velocityBonus = 0f;
        for (PerkDef perk : PerkService.perksOfType(owner, "arrow_velocity")) {
            velocityBonus += perk.param("bonus", 0.3f);
        }
        if (velocityBonus > 0f) {
            arrow.setVelocity(arrow.getVelocity().multiply(1.0 + velocityBonus));
        }

        // Multishot - only for a primary arrow, never for one this method already spawned.
        if (!arrow.getCommandTags().contains(SIBLING_TAG) && owner.getWorld() instanceof ServerWorld world) {
            int extra = 0;
            for (PerkDef perk : PerkService.perksOfType(owner, "multishot")) {
                extra += Math.round(perk.param("arrows", 1f));
            }
            if (extra > 0) {
                Vec3d v = arrow.getVelocity();
                double speed = v.length();
                double dmg = ((PersistentProjectileEntityAccessor) arrow).kindreds$getDamage();
                boolean crit = arrow.isCritical();
                for (int i = 0; i < extra; i++) {
                    ArrowEntity sibling = new ArrowEntity(world, owner, new ItemStack(Items.ARROW), null);
                    sibling.refreshPositionAndAngles(arrow.getX(), arrow.getY(), arrow.getZ(), arrow.getYaw(), arrow.getPitch());
                    Vec3d spread = v.add(
                            (world.random.nextDouble() - 0.5) * 0.16 * speed,
                            (world.random.nextDouble() - 0.5) * 0.10 * speed,
                            (world.random.nextDouble() - 0.5) * 0.16 * speed);
                    sibling.setVelocity(spread);
                    sibling.setCritical(crit);
                    sibling.setDamage(dmg);
                    sibling.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
                    sibling.addCommandTag(SIBLING_TAG);
                    world.spawnEntity(sibling);
                }
            }
        }
    }

    public static void onHit(PersistentProjectileEntity arrow, ServerPlayerEntity owner, LivingEntity target) {
        List<PerkDef> effects = PerkService.perksOfType(owner, "arrow_effect");
        if (effects.isEmpty()) {
            return;
        }
        boolean applied = false;
        for (PerkDef perk : effects) {
            if (perk.foe().isPresent() && !PerkEventHandlers.matchesFoe(target, perk.foe().get())) {
                continue;
            }
            if (perk.effect().isPresent()) {
                var eff = perk.effect().get();
                var registered = Registries.STATUS_EFFECT.getEntry(eff.effect());
                if (registered.isPresent()) {
                    target.addStatusEffect(new StatusEffectInstance(registered.get(),
                            eff.durationTicks() < 0 ? 100 : eff.durationTicks(), eff.amplifier()));
                    applied = true;
                }
            }
        }
        if (applied && owner.getWorld() instanceof ServerWorld world) {
            world.spawnParticles(ParticleTypes.WITCH, target.getX(), target.getBodyY(0.6), target.getZ(),
                    6, 0.3, 0.3, 0.3, 0.02);
        }
    }
}
