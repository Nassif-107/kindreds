package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
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

    /**
     * How much faster than the rider's own sprint a mount should feel, by perk depth.
     *
     * Index is the perk's {@code speed} param (0 when unbought). A mount is meant to be the faster
     * way to travel; these say by how much, and nothing else does.
     */
    private static final double[] TARGET_ADVANTAGE = { 1.25, 1.40, 1.55, 1.70 };

    /** Sprinting is roughly 30% quicker than walking, which is the speed a rider compares against. */
    private static final double SPRINT_FACTOR = 1.3;

    /** One Speed amplifier level is +20% movement. */
    private static final double SPEED_PER_AMPLIFIER = 0.20;

    /**
     * Hard ceiling on a mount's resulting speed, in raw movement-speed units.
     *
     * Capping the amplifier instead was wrong, and the arithmetic shows why: an amplifier is only
     * large because the base is small. A 0.1125 horse - the slow end of vanilla's threefold spread -
     * needs Speed XIX to reach 0.54, which sounds absurd and is merely arithmetic. Capping the
     * amplifier at 7 left that horse at 0.27 while a maxed Elf sprinted at 0.43, so the rider was
     * still faster on foot: exactly the complaint, reintroduced by the safety valve.
     *
     * 0.65 is a little under twice the fastest vanilla horse and comfortably above a fully invested
     * Elf's sprint, so nothing outruns it while nothing blinks across the landscape either.
     */
    private static final double MAX_MOUNT_SPEED_ABSOLUTE = 0.65;

    /**
     * The Speed amplifier a mount needs to sit a set margin ahead of its rider's sprint.
     *
     * Targeting a speed rather than applying a multiplier is the whole point, because both ends of
     * the sum vary independently. A rider's foot speed scales with investment - an Elf can reach
     * +231% from survival, lore and song nodes and outrun a Speed II horse outright - while a horse's
     * own base speed is randomised across a threefold range, 0.1125 to 0.3375. A flat buff is
     * therefore never right twice: the same +40% leaves a slow horse behind a sprinting Elf and makes
     * an already-fast horse alarming under a Human.
     *
     * Measuring what the mount actually needs fixes both at once. A fast horse under a modest rider
     * gets little or nothing, because it is already ahead; a slow horse under a swift rider gets a
     * great deal. Returns -1 when no help is needed at all.
     */
    private static int neededSpeedAmplifier(ServerPlayerEntity player, LivingEntity mount, int perkSpeed) {
        var riderAttr = player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        var mountAttr = mount.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        if (riderAttr == null || mountAttr == null) {
            return -1;
        }
        // The mount's inherent speed, before whatever this method granted it last tick.
        double mountBase = mountAttr.getBaseValue();
        if (mountBase <= 0.0) {
            return -1;
        }
        int tier = Math.max(0, Math.min(perkSpeed, TARGET_ADVANTAGE.length - 1));
        double target = Math.min(
                riderAttr.getValue() * SPRINT_FACTOR * TARGET_ADVANTAGE[tier],
                MAX_MOUNT_SPEED_ABSOLUTE);

        double needed = target / mountBase;
        if (needed <= 1.0) {
            return -1; // already quick enough on its own
        }
        int amplifier = (int) Math.round((needed - 1.0) / SPEED_PER_AMPLIFIER) - 1;
        return Math.max(amplifier, 0);
    }

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
        if (!(player.getVehicle() instanceof LivingEntity mount)) {
            return;
        }
        // The same mount-craft under two names: the Elf's elven_steed and the Rohirrim's war_steed.
        List<PerkDef> perks = new java.util.ArrayList<>(PerkService.perksOfType(player, "elven_steed"));
        perks.addAll(PerkService.perksOfType(player, "war_steed"));

        int speed = 0;
        boolean regen = false;
        int riderResist = -1;
        for (PerkDef p : perks) {
            speed = Math.max(speed, Math.round(p.param("speed", 0f)));
            regen |= p.param("regen", 0f) > 0.5f;
            riderResist = Math.max(riderResist, Math.round(p.param("rider_resistance", -1f)));
        }

        // Applied whether or not a mount perk was bought. Keeping a horse ahead of the rider who is
        // sitting on it is not a reward for a beast-lore purchase - and the nodes that make a rider
        // outpace their own mount live in entirely different disciplines.
        int amplifier = neededSpeedAmplifier(player, mount, speed);
        if (amplifier >= 0) {
            mount.addStatusEffect(
                    new StatusEffectInstance(StatusEffects.SPEED, HOLD, amplifier, false, false, false));
        }

        if (perks.isEmpty()) {
            return;
        }
        // A steady seat, not a catapult: one level of spring at the deeper ranks, never scaling with speed.
        if (speed >= 2) {
            mount.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, HOLD, 0, false, false, false));
        }
        if (regen) {
            mount.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, HOLD, 0, false, false, false));
        }
        if (riderResist >= 0) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, HOLD, riderResist, false, false, false));
        }
    }
}
