package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * The <b>orc-kin signature</b> passives, refreshed on the shared aura cadence (see
 * {@link PerkEventHandlers}). Each is what makes one of the three lesser orc-races play differently
 * from the others - built on real, synced status effects, not render tricks:
 * <ul>
 *   <li><b>prey_sense</b> (Snaga) - nearby living foes are marked with the true vanilla <b>Glowing</b>
 *       effect, so the tracker (and its pack) can see the prey through the dark and the stone.</li>
 *   <li><b>dread_aura</b> (Orc) - the Nameless Fear of Mordor: nearby foes are steadily <b>Weakened</b>
 *       (and, at the deeper dread, slowed) just for standing near this orc.</li>
 * </ul>
 * (The Uruk's <b>unyielding</b> slowness-immunity is a true immunity handled in
 * {@code LivingEntityStatusEffectMixin}, not here.)
 */
public final class RacialSignatures {
    private RacialSignatures() {
    }

    private static final int HOLD = 60; // > the 10-tick cadence, so the mark/debuff never flickers

    public static void tick(ServerPlayerEntity player) {
        if (!(player.getWorld() instanceof ServerWorld world)) {
            return;
        }
        preySense(player, world);
        dreadAura(player, world);
    }

    /** Snaga: mark nearby living hostiles with a glowing outline (the real {@link StatusEffects#GLOWING}
     * effect - visible to every player, through walls, exactly as spectral arrows work). */
    private static void preySense(ServerPlayerEntity player, ServerWorld world) {
        List<PerkDef> perks = PerkService.perksOfType(player, "prey_sense");
        if (perks.isEmpty()) {
            return;
        }
        double radius = 0;
        for (PerkDef p : perks) {
            radius = Math.max(radius, p.param("radius", 16f));
        }
        double r2 = radius * radius;
        Box box = player.getBoundingBox().expand(radius);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> Allegiance.isFoe(player, x) && x.squaredDistanceTo(player) <= r2)) {
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, HOLD, 0, false, false, false));
        }
    }

    /** Orc: nearby foes are cowed by the dread of the Eye - a steady Weakness (and Slowness at the
     * deeper dread). */
    private static void dreadAura(ServerPlayerEntity player, ServerWorld world) {
        List<PerkDef> perks = PerkService.perksOfType(player, "dread_aura");
        if (perks.isEmpty()) {
            return;
        }
        int amp = 0;
        double radius = 0;
        for (PerkDef p : perks) {
            amp = Math.max(amp, Math.round(p.param("amplifier", 0f)));
            radius = Math.max(radius, p.param("radius", 8f));
        }
        double r2 = radius * radius;
        Box box = player.getBoundingBox().expand(radius);
        boolean any = false;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                x -> Allegiance.isFoe(player, x) && x.squaredDistanceTo(player) <= r2)) {
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, HOLD, amp, false, false, true));
            if (amp >= 1) {
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, HOLD, 0, false, false, true));
            }
            any = true;
        }
        if (any) {
            world.spawnParticles(ParticleTypes.SMOKE, player.getX(), player.getBodyY(1.0), player.getZ(),
                    6, radius / 3, 0.4, radius / 3, 0.0);
        }
    }
}
