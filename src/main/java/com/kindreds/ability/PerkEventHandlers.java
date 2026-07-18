package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

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
            }
        });

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killed) -> {
            if (!(entity instanceof ServerPlayerEntity sp)) {
                return;
            }
            for (PerkDef perk : PerkService.perksOfType(sp, "heal_on_kill")) {
                float heal = perk.param("health", 2f);
                if (heal > 0f) {
                    sp.heal(heal);
                }
                int food = Math.round(perk.param("food", 0f));
                if (food > 0) {
                    sp.getHungerManager().add(food, 0.2f);
                }
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
            }
            return ActionResult.PASS;
        });
    }

    /**
     * The total outgoing-damage multiplier a player's bane/arrow-slaying perks apply against {@code
     * target} - {@code 1.0} for none. Called by {@code LivingEntityDamageMixin}. {@code projectile}
     * selects arrow-slaying perks (bow hits) over melee banes; a {@code "bane"} perk applies to both.
     */
    public static float outgoingDamageMultiplier(ServerPlayerEntity attacker, LivingEntity target, boolean projectile) {
        float multiplier = 1.0f;
        for (PerkDef perk : PerkService.ownedPerks(attacker)) {
            boolean isBane = perk.perk().equals("bane");
            boolean isArrow = perk.perk().equals("arrow_slaying");
            if (!isBane && !isArrow) {
                continue;
            }
            if (isArrow && !projectile) {
                continue; // arrow-slaying only augments bow hits
            }
            String foe = perk.foe().orElse("any");
            if (matchesFoe(target, foe)) {
                multiplier += perk.param("bonus", 0f);
            }
        }
        return multiplier;
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
