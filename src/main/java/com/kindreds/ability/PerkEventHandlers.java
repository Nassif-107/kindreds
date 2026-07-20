package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.DamageTypeTags;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.Block;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * The perk handlers: they read a player's owned {@link PerkDef}s (via {@link PerkService}) on the
 * relevant game event and enact the mechanic. This class carries the <b>mixin-free</b> handlers wired
 * to Fabric events - the outgoing-damage perks (bane / arrow-slaying) instead scale damage in {@code
 * LivingEntityDamageMixin}, which calls {@link #outgoingDamageMultiplier} here so the foe logic lives
 * in one place.
 *
 * <ul>
 *   <li><b>mining_fortune</b> ({@code chance}, {@code amount}) - breaking an ore has {@code chance} to
 *       re-roll its drops {@code amount} extra times (Dwarf "Delver's Fortune").</li>
 *   <li><b>heal_on_kill</b> ({@code health}, {@code food}) - a kill restores health/food (Orc/Uruk
 *       "Bloodlust").</li>
 *   <li><b>strike_effect</b> ({@code chance}, {@code foe}, {@code effect}) - a melee hit inflicts a
 *       status effect on the struck foe (cruel/poisoned blades).</li>
 * </ul>
 */
public final class PerkEventHandlers {
    private PerkEventHandlers() {
    }

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerWorld serverWorld) || !(player instanceof ServerPlayerEntity sp)) {
                return;
            }
            DwarfMining.onBlockBroken(serverWorld, sp, pos, state);
            List<PerkDef> perks = PerkService.perksOfType(sp, "mining_fortune");
            if (perks.isEmpty() || !state.isIn(ConventionalBlockTags.ORES)) {
                return;
            }
            for (PerkDef perk : perks) {
                float chance = perk.param("chance", 0.25f);
                if (serverWorld.random.nextFloat() >= chance) {
                    continue;
                }
                int extraRolls = Math.max(1, Math.round(perk.param("amount", 1f)));
                for (int i = 0; i < extraRolls; i++) {
                    for (ItemStack drop : Block.getDroppedStacks(state, serverWorld, pos, blockEntity, sp,
                            sp.getMainHandStack())) {
                        Block.dropStack(serverWorld, pos, drop.copy());
                    }
                }
                // Delver's Fortune proc: a green sparkle + a chime so the bonus is unmistakable.
                double bx = pos.getX() + 0.5, by = pos.getY() + 0.5, bz = pos.getZ() + 0.5;
                flash(serverWorld, bx, by, bz, ParticleTypes.HAPPY_VILLAGER, 8, 0.45);
                sound(serverWorld, bx, by, bz, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
            }
        });

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killed) -> {
            if (!(entity instanceof ServerPlayerEntity sp)) {
                return;
            }
            List<PerkDef> perks = PerkService.perksOfType(sp, "heal_on_kill");
            boolean procced = false;
            for (PerkDef perk : perks) {
                float heal = perk.param("health", 2f);
                if (heal > 0f) {
                    sp.heal(heal);
                    procced = true;
                }
                int food = Math.round(perk.param("food", 0f));
                if (food > 0) {
                    sp.getHungerManager().add(food, 0.2f);
                    procced = true;
                }
            }
            if (procced) {
                // Bloodlust: heart particles well up as the kill mends the slayer.
                flash(world, sp.getX(), sp.getBodyY(0.6), sp.getZ(), ParticleTypes.HEART, 5, 0.4);
            }
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity sp) || !(entity instanceof LivingEntity target)) {
                return ActionResult.PASS;
            }
            for (PerkDef perk : PerkService.perksOfType(sp, "strike_effect")) {
                if (perk.foe().isPresent() && !matchesFoe(target, perk.foe().get())) {
                    continue;
                }
                if (sp.getRandom().nextFloat() >= perk.param("chance", 1f)) {
                    continue;
                }
                perk.effect().ifPresent(eff -> Registries.STATUS_EFFECT.getEntry(eff.effect()).ifPresent(registered ->
                        target.addStatusEffect(new StatusEffectInstance(registered,
                                eff.durationTicks() < 0 ? 200 : eff.durationTicks(), eff.amplifier()))));
                // Cruel/poisoned blade lands: a puff of foul particles marks the affliction.
                flash((ServerWorld) world, target.getX(), target.getBodyY(0.6), target.getZ(), ParticleTypes.WITCH, 8, 0.3);
            }
            return ActionResult.PASS;
        });

        // After-damage perks: lifesteal (heal the striking player) and thorns (reflect to attacker).
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (damageTaken <= 0f) {
                return;
            }
            // Lifesteal: the player who dealt this melee blow heals a fraction of it.
            if (source.getAttacker() instanceof ServerPlayerEntity attacker && !source.isIn(DamageTypeTags.IS_PROJECTILE)) {
                float pct = 0f;
                for (PerkDef perk : PerkService.perksOfType(attacker, "lifesteal")) {
                    pct += perk.param("percent", 0.1f);
                }
                if (pct > 0f) {
                    attacker.heal(damageTaken * pct);
                    if (attacker.getWorld() instanceof ServerWorld w) {
                        flash(w, attacker.getX(), attacker.getBodyY(0.6), attacker.getZ(), ParticleTypes.HEART, 3, 0.3);
                    }
                }
            }
            // Thorns: a struck player reflects a fraction of the blow back onto a living attacker.
            if (entity instanceof ServerPlayerEntity victim && source.getAttacker() instanceof LivingEntity foe
                    && foe != victim) {
                float pct = 0f;
                for (PerkDef perk : PerkService.perksOfType(victim, "thorns")) {
                    pct += perk.param("percent", 0.25f);
                }
                if (pct > 0f && victim.getWorld() instanceof ServerWorld w) {
                    foe.damage(w, victim.getDamageSources().thorns(victim), damageTaken * pct);
                    flash(w, foe.getX(), foe.getBodyY(0.6), foe.getZ(), ParticleTypes.CRIT, 6, 0.3);
                }
            }
        });

        // Tick-driven perks (ally auras, war-pack scaling). A 10-tick cadence is plenty for a buff
        // that lasts longer than that and keeps the per-player scan cheap.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % AURA_INTERVAL != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                tickAllyAura(player);
                tickWarPack(player);
                tickCamouflage(player);
                BeastPerks.tick(player);
                DwarfSmithing.tickMendGear(player);
            }
        });
    }

    private static final int AURA_INTERVAL = 10;
    private static final Identifier ATTACK_DAMAGE = Identifier.of("minecraft", "attack_damage");
    private static int tickCounter;

    /** <b>ally_aura</b> ({@code radius}, {@code effect}): other players within range are granted the
     * effect - the Captain-of-Men leadership buff, and the backbone of the Fellowship "lent gifts". */
    /** <b>camouflage</b>: the Cloak of Lórien - while the wearer is sneaking they melt into the
     * surroundings (Invisibility), the Silvan art of going unseen. Refreshed each cadence, so it
     * lasts exactly as long as they stay still and hidden. */
    private static void tickCamouflage(ServerPlayerEntity player) {
        if (PerkService.perksOfType(player, "camouflage").isEmpty() || !player.isSneaking()) {
            return;
        }
        player.addStatusEffect(new StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.INVISIBILITY,
                AURA_INTERVAL * 3, 0, false, false, false));
    }

    private static void tickAllyAura(ServerPlayerEntity player) {
        List<PerkDef> auras = PerkService.perksOfType(player, "ally_aura");
        if (auras.isEmpty()) {
            return;
        }
        for (PerkDef perk : auras) {
            if (perk.effect().isEmpty()) {
                continue;
            }
            double radius = perk.param("radius", 8f);
            // The singer/captain is heartened by their own song too, so the aura is felt even when
            // you march alone (and still lends its gift to every ally in range).
            perk.effect().ifPresent(eff -> Registries.STATUS_EFFECT.getEntry(eff.effect()).ifPresent(e ->
                    player.addStatusEffect(new StatusEffectInstance(e,
                            eff.durationTicks() < 0 ? AURA_INTERVAL * 4 : eff.durationTicks(), eff.amplifier(),
                            false, false, true))));
            Box box = player.getBoundingBox().expand(radius);
            for (ServerPlayerEntity other : player.getWorld().getEntitiesByClass(ServerPlayerEntity.class, box,
                    p -> p != player && p.isAlive() && p.squaredDistanceTo(player) <= radius * radius)) {
                perk.effect().ifPresent(eff -> Registries.STATUS_EFFECT.getEntry(eff.effect()).ifPresent(e ->
                        other.addStatusEffect(new StatusEffectInstance(e,
                                // outlast the tick cadence so it never flickers between refreshes
                                eff.durationTicks() < 0 ? AURA_INTERVAL * 4 : eff.durationTicks(), eff.amplifier(),
                                false, false, true))));
            }
        }
    }

    /** <b>war_pack</b> ({@code radius}, {@code per_ally}, {@code max}): the owner's melee damage grows
     * with the number of nearby hostiles (the horde at their back) - the Orc-kind's strength in
     * numbers. Applied as a live temporary {@code attack_damage} modifier recomputed each tick. */
    private static void tickWarPack(ServerPlayerEntity player) {
        List<PerkDef> packs = PerkService.perksOfType(player, "war_pack");
        if (packs.isEmpty()) {
            return;
        }
        // Only the strongest war_pack a player owns applies (they don't compound with themselves).
        float perAlly = 0f;
        float bonus = 0f;
        for (PerkDef perk : packs) {
            double radius = perk.param("radius", 12f);
            int max = Math.max(1, Math.round(perk.param("max", 6f)));
            Box box = player.getBoundingBox().expand(radius);
            int allies = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
                    m -> m instanceof Monster && m.isAlive() && m.squaredDistanceTo(player) <= radius * radius).size();
            float thisBonus = perk.param("per_ally", 0.05f) * Math.min(allies, max);
            if (thisBonus > bonus) {
                bonus = thisBonus;
                perAlly = perk.param("per_ally", 0.05f);
            }
        }
        AbilityApplier.setDynamicModifier(player, ATTACK_DAMAGE, "perk/war_pack", bonus,
                EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    /**
     * The multiplier a defending player's perks apply to incoming damage - {@code 1.0} for none.
     * Called by {@code LivingEntityDamageMixin} when the victim is a player. Currently backs
     * <b>evasion</b> ({@code chance}, {@code reduction}): a roll to shrug off part or all of a hit
     * (the Little Folk are wondrously hard to catch).
     */
    public static float incomingDamageMultiplier(ServerPlayerEntity victim, DamageSource source) {
        float multiplier = 1.0f;
        for (PerkDef perk : PerkService.perksOfType(victim, "evasion")) {
            if (victim.getRandom().nextFloat() < perk.param("chance", 0f)) {
                multiplier *= (1.0f - Math.min(1.0f, perk.param("reduction", 1f)));
            }
        }
        // Foresight: the Eldar sense the blow ere it falls and turn its edge - a steady damage cut.
        for (PerkDef perk : PerkService.perksOfType(victim, "foresight")) {
            multiplier *= (1.0f - Math.min(0.8f, perk.param("reduction", 0.1f)));
        }
        if (multiplier < 1.0f && victim.getWorld() instanceof ServerWorld world) {
            // Dodge: a puff of dust and a whoosh mark the shrugged-off blow.
            flash(world, victim.getX(), victim.getBodyY(0.6), victim.getZ(), ParticleTypes.CLOUD, 12, 0.3);
            sound(world, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.6f);
        }
        return multiplier;
    }

    /**
     * The total outgoing-damage multiplier a player's bane/arrow-slaying perks apply against {@code
     * target} - {@code 1.0} for none. Called by {@code LivingEntityDamageMixin}. {@code projectile}
     * selects arrow-slaying perks (bow hits) over melee banes; a {@code "bane"} perk applies to both.
     */
    public static float outgoingDamageMultiplier(ServerPlayerEntity attacker, LivingEntity target, boolean projectile) {
        float multiplier = 1.0f;
        boolean foeBonus = false; // a foe-specific (bane/arrow-slaying) bonus landed - worth a spark
        for (PerkDef perk : PerkService.ownedPerks(attacker)) {
            switch (perk.perk()) {
                case "bane" -> {
                    if (matchesFoe(target, perk.foe().orElse("any"))) {
                        multiplier += perk.param("bonus", 0f);
                        foeBonus = true;
                    }
                }
                case "arrow_slaying" -> {
                    if (projectile && matchesFoe(target, perk.foe().orElse("any"))) {
                        multiplier += perk.param("bonus", 0f);
                        foeBonus = true;
                    }
                }
                // Real bow damage: a flat percent on every arrow hit (fixes 'fake' +bow-damage nodes,
                // which were a melee attribute that arrows never used).
                case "arrow_damage" -> {
                    if (projectile) {
                        multiplier += perk.param("bonus", 0.15f);
                    }
                }
                // Long-shot: bonus scaling with how far the target is - rewards the quarter-mile shot.
                case "long_shot" -> {
                    if (projectile) {
                        double dist = Math.sqrt(attacker.squaredDistanceTo(target));
                        multiplier += Math.min(perk.param("max", 1.0f), (float) dist * perk.param("per_block", 0.02f));
                    }
                }
                // Ambush: the Silvan Shadow strikes unseen - bonus damage while sneaking (melee or bow).
                case "ambush" -> {
                    if (attacker.isSneaking()) {
                        multiplier += perk.param("bonus", 0.5f);
                        foeBonus = true;
                    }
                }
                // Pounce: the wall-crawler drops on its prey - bonus damage while falling onto a foe.
                case "pounce" -> {
                    if (!attacker.isOnGround() && attacker.getVelocity().y < 0.0) {
                        multiplier += perk.param("bonus", 0.5f);
                        foeBonus = true;
                    }
                }
                default -> {
                }
            }
        }
        if (foeBonus && attacker.getWorld() instanceof ServerWorld world) {
            // Bane/arrow-slaying lands: an enchanted-hit spark and a sharp crack, so the bonus reads.
            flash(world, target.getX(), target.getBodyY(0.7), target.getZ(), ParticleTypes.ENCHANTED_HIT, 12, 0.3);
            sound(world, target.getX(), target.getY(), target.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 1.2f);
        }
        return multiplier;
    }

    // --- Feedback helpers ------------------------------------------------------------------------

    /** A short burst of {@code count} particles centred at (x,y,z) with a small random spread - the
     * visible "this perk fired" cue. */
    private static void flash(ServerWorld world, double x, double y, double z, ParticleEffect particle,
                              int count, double spread) {
        world.spawnParticles(particle, x, y, z, count, spread, spread, spread, 0.02);
    }

    /** A one-shot sound at (x,y,z) in the players category - the audible half of a perk's feedback. */
    private static void sound(ServerWorld world, double x, double y, double z, SoundEvent event,
                              float volume, float pitch) {
        world.playSound(null, x, y, z, event, SoundCategory.PLAYERS, volume, pitch);
    }

    /** Whether {@code target} belongs to a named foe category. Uses vanilla's own smite/bane-of-
     * arthropods entity-type tags where they fit, and an entity-id substring match for the base mod's
     * custom peoples ({@code orc}, {@code troll}) that have no vanilla tag. */
    static boolean matchesFoe(LivingEntity target, String foe) {
        return switch (foe) {
            case "any" -> target instanceof Monster;
            case "undead" -> target.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE);
            case "spider", "arthropod" -> target.getType().isIn(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS);
            case "illager" -> target instanceof IllagerEntity;
            case "dragon" -> target instanceof EnderDragonEntity;
            case "orc", "troll", "goblin" -> {
                Identifier id = Registries.ENTITY_TYPE.getId(target.getType());
                yield id.getPath().contains(foe);
            }
            default -> false;
        };
    }
}
