package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * The <b>Beast-lore</b> tick perks - the Elf as friend of all living things. Three passive arts,
 * refreshed on the shared aura cadence (see {@link PerkEventHandlers}):
 * <ul>
 *   <li><b>beast_calm</b> - wild animals will not raise tooth or claw against you (Elven kinship
 *       with beasts; wolves and bears break off the hunt).</li>
 *   <li><b>pack_bond</b> - your own tamed beasts fight the fiercer at your side (the pack-lord).</li>
 *   <li><b>elven_steed</b> - your mount runs tireless and swift, as Asfaloth bore Frodo.</li>
 * </ul>
 * The buff durations outlast the cadence so they never flicker between refreshes.
 */
public final class BeastPerks {
    private BeastPerks() {
    }

    private static final int HOLD = 40; // ticks a refreshed buff is held (> the 10-tick cadence)

    /** Called once per player each aura cadence from {@link PerkEventHandlers}. */
    public static void tick(ServerPlayerEntity player) {
        beastCalm(player);
        packBond(player);
        elvenSteed(player);
    }

    /** Wild animals within range that have taken you for prey lose interest - the Firstborn walk the
     * wild unmolested. */
    private static void beastCalm(ServerPlayerEntity player) {
        List<PerkDef> perks = PerkService.perksOfType(player, "beast_calm");
        if (perks.isEmpty()) {
            return;
        }
        double radius = 0;
        for (PerkDef p : perks) {
            radius = Math.max(radius, p.param("radius", 12f));
        }
        Box box = player.getBoundingBox().expand(radius);
        for (AnimalEntity beast : player.getWorld().getEntitiesByClass(AnimalEntity.class, box,
                b -> b.isAlive() && b.getTarget() == player)) {
            beast.setTarget(null);
            beast.setAttacker(null);
            if (beast instanceof Angerable angry) {
                angry.stopAnger();
            }
        }
    }

    /** Your own tamed beasts nearby are heartened - Strength, and at the deeper bond Resistance and a
     * slow mending too. */
    private static void packBond(ServerPlayerEntity player) {
        List<PerkDef> perks = PerkService.perksOfType(player, "pack_bond");
        if (perks.isEmpty()) {
            return;
        }
        int amp = 0;
        double radius = 12;
        for (PerkDef p : perks) {
            amp = Math.max(amp, Math.round(p.param("amplifier", 0f)));
            radius = Math.max(radius, p.param("radius", 12f));
        }
        Box box = player.getBoundingBox().expand(radius);
        for (TameableEntity pet : player.getWorld().getEntitiesByClass(TameableEntity.class, box,
                t -> t.isAlive() && t.isTamed() && player.equals(t.getOwner()))) {
            pet.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, HOLD, amp, false, false, false));
            if (amp >= 1) {
                pet.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, HOLD, 0, false, false, false));
                pet.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, HOLD, 0, false, false, false));
            }
        }
    }

    /** While you ride a living mount it runs swift and tireless (Speed, Jump, and at the deeper art a
     * slow mending), and you sit the saddle sure (Resistance). */
    private static void elvenSteed(ServerPlayerEntity player) {
        // The same mount-craft under two names: the Elf's elven_steed and the Rohirrim's war_steed.
        List<PerkDef> perks = new java.util.ArrayList<>(PerkService.perksOfType(player, "elven_steed"));
        perks.addAll(PerkService.perksOfType(player, "war_steed"));
        if (perks.isEmpty() || !(player.getVehicle() instanceof LivingEntity mount)) {
            return;
        }
        int speed = 0;
        boolean regen = false;
        int riderResist = -1;
        for (PerkDef p : perks) {
            speed = Math.max(speed, Math.round(p.param("speed", 0f)));
            regen |= p.param("regen", 0f) > 0.5f;
            riderResist = Math.max(riderResist, Math.round(p.param("rider_resistance", -1f)));
        }
        mount.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, HOLD, speed, false, false, false));
        mount.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, HOLD, speed, false, false, false));
        if (regen) {
            mount.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, HOLD, 0, false, false, false));
        }
        if (riderResist >= 0) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, HOLD, riderResist, false, false, false));
        }
    }
}
