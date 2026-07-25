package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.List;

/**
 * The <b>Dwarf smithing</b> master-craft: <b>mend_gear</b> ({@code amount}) - the Children of Aulë make
 * gear so well it mends itself. Called per-player from {@link PerkEventHandlers}'s tick loop.
 *
 * <h2>Rate</h2>
 * The mend is deliberately slow. It used to restore {@code amount} durability on every aura cadence -
 * ten ticks - which at the top rank was four points every half second on all six slots: eight a second
 * per item, a ruined diamond pickaxe whole again in three minutes while its owner stood still. Gear did
 * not merely last longer, it stopped being a resource.
 *
 * <p>Now one point of repair costs {@link #CADENCES_PER_POINT} cadences and {@code amount} only shortens
 * that wait, so even the best Dwarf smith mends single points on a slow beat. Gear lasts a very long
 * time and rarely sees an anvil; it still wears, and a long fight or a deep dig still costs something.
 *
 * <h2>Why an item in use is skipped</h2>
 * Repairing writes to the stack, and writing to the stack the player is currently swinging or holding a
 * use on makes the server re-send that slot. The client answers a re-sent slot by re-equipping it: the
 * item pops in the hand and - the part that actually hurt - <b>block-breaking progress resets</b>, so a
 * Dwarf mining with a self-mending pickaxe kept losing the block they were part-way through. The mend now
 * leaves a hand alone while it is working and catches it up the moment it goes idle, which costs almost
 * nothing and removes the stutter entirely. Armour is never in use and mends regardless.
 */
public final class DwarfSmithing {
    private DwarfSmithing() {
    }

    /** Aura cadences between single points of repair at {@code amount = 1}. Twelve cadences of ten
     * ticks is six seconds a point; the top rank divides this down to one cadence, half a second - but
     * still only ever one point at a time. */
    static final int CADENCES_PER_POINT = 12;

    /** Counts cadences so the mend fires on a slow beat rather than every cadence. Shared across
     * players: it is a metronome, not per-player state, and every Dwarf mends on the same beat. */
    private static int cadenceCounter;

    /** Advances the shared beat. Called once per aura cadence by {@link PerkEventHandlers}, before the
     * per-player pass, so every player sees the same tick of the metronome. */
    static void beat() {
        cadenceCounter++;
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
        // amount shortens the wait rather than multiplying the repair: a higher rank mends more often,
        // never more at once, so no rank can outpace the wear of ordinary use.
        int every = Math.max(1, CADENCES_PER_POINT / amount);
        if (cadenceCounter % every != 0) {
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (isInUse(player, slot)) {
                continue;   // see class javadoc: writing a held stack mid-use resets mining progress
            }
            ItemStack stack = player.getEquippedStack(slot);
            if (stack.isDamageable() && stack.getDamage() > 0) {
                stack.setDamage(Math.max(0, stack.getDamage() - 1));
            }
        }
    }

    /** Whether this slot holds the item the player is actively swinging or using right now. Only a hand
     * can be in use; armour is always safe to mend. */
    private static boolean isInUse(ServerPlayerEntity player, EquipmentSlot slot) {
        if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) {
            return false;
        }
        Hand hand = slot == EquipmentSlot.MAINHAND ? Hand.MAIN_HAND : Hand.OFF_HAND;
        if (player.isUsingItem() && player.getActiveHand() == hand) {
            return true;    // drawing a bow, eating, raising a shield
        }
        // handSwinging is the mining/attacking case - the one that was losing block progress.
        return slot == EquipmentSlot.MAINHAND && player.handSwinging;
    }

    /** A smith's touch: fully mend everything the player wears or holds. Backs the {@code masters_forge}
     * active. Returns true if anything was actually repaired.
     *
     * <p>Unlike the passive, this repairs held items unconditionally: it is a deliberate one-off act the
     * player just triggered, so there is no swing of theirs to interrupt and nothing to stutter. */
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
