package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

/**
 * The <b>Dwarf smithing</b> master-craft: <b>mend_gear</b> ({@code amount}) - the Children of Aulë make
 * gear so well it mends itself. Each aura cadence a little durability is restored to every damaged item
 * the player wears or holds, so a Dwarf's arms and armour effectively never wear out. Called per-player
 * from {@link PerkEventHandlers}'s tick loop.
 */
public final class DwarfSmithing {
    private DwarfSmithing() {
    }

    public static void tickMendGear(ServerPlayerEntity player) {
        List<PerkDef> perks = PerkService.perksOfType(player, "mend_gear");
        if (perks.isEmpty()) {
            return;
        }
        int amount = 0;
        for (PerkDef p : perks) {
            amount = Math.max(amount, Math.round(p.param("amount", 1f)));
        }
        if (amount <= 0) {
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getEquippedStack(slot);
            if (stack.isDamageable() && stack.getDamage() > 0) {
                stack.setDamage(Math.max(0, stack.getDamage() - amount));
            }
        }
    }

    /** A smith's touch: fully mend everything the player wears or holds. Backs the {@code masters_forge}
     * active. Returns true if anything was actually repaired. */
    public static boolean repairAll(ServerPlayerEntity player) {
        boolean any = false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getEquippedStack(slot);
            if (stack.isDamageable() && stack.getDamage() > 0) {
                stack.setDamage(0);
                any = true;
            }
        }
        return any;
    }
}
