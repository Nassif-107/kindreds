package com.kindreds.inscription;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * Which part of a kit an inscription belongs to, so the page can be read in sections.
 *
 * <p>Decided by asking the enchantment what it may be put on, not by a list of names kept here. A
 * list would have to be extended by hand for every enchantment Middle-earth already adds and every
 * one a datapack adds later, and the failure is silent - a new enchantment simply lands in "other"
 * and nobody notices it is miscategorised rather than uncategorised.
 *
 * <p>Order matters in {@link #of}: a bow accepts Unbreaking too, so archery is asked before tools,
 * and Mending fits everything, so the catch-all sits last rather than first.
 */
public final class InscriptionCategory {

    /** Sort keys, so the page's sections come out in a deliberate order rather than alphabetically. */
    public static final String WEAPON = "1_weapon";
    public static final String ARCHERY = "2_archery";
    public static final String ARMOUR = "3_armour";
    public static final String TOOL = "4_tool";
    public static final String OTHER = "5_other";

    private InscriptionCategory() {
    }

    /**
     * The section {@code enchantmentId} belongs in.
     *
     * <p>Probed with one representative item per section: whatever the enchantment accepts first
     * decides where it is printed. An enchantment that fits nothing recognisable - or one whose
     * registry entry cannot be resolved at all - lands in {@code other}, which is honest rather than
     * a guess.
     */
    public static String of(String enchantmentId) {
        Enchantment enchantment = resolve(enchantmentId);
        if (enchantment == null) {
            return OTHER;
        }
        if (accepts(enchantment, Items.BOW) || accepts(enchantment, Items.CROSSBOW)) {
            return ARCHERY;
        }
        if (accepts(enchantment, Items.IRON_SWORD) || accepts(enchantment, Items.TRIDENT)
            || accepts(enchantment, Items.MACE)) {
            return WEAPON;
        }
        if (accepts(enchantment, Items.IRON_CHESTPLATE) || accepts(enchantment, Items.IRON_BOOTS)
            || accepts(enchantment, Items.IRON_HELMET)) {
            return ARMOUR;
        }
        if (accepts(enchantment, Items.IRON_PICKAXE) || accepts(enchantment, Items.IRON_AXE)
            || accepts(enchantment, Items.FISHING_ROD) || accepts(enchantment, Items.SHEARS)) {
            return TOOL;
        }
        return OTHER;
    }

    /**
     * Enchantments that cannot sit on the same item as this one.
     *
     * <p>Read from the enchantment's own {@code exclusiveSet} rather than a table written here,
     * because vanilla's exclusivities are data and a datapack may change them. This is the thing a
     * player most wants to know before spending levels: Sharpness or Smite or Bane of Arthropods is
     * a choice, not a shopping list, and nothing in the game says so until the table refuses.
     *
     * @return sorted enchantment ids, never null
     */
    public static java.util.List<String> conflictsFor(String enchantmentId) {
        Enchantment enchantment = resolve(enchantmentId);
        if (enchantment == null) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (var entry : enchantment.exclusiveSet()) {
                entry.getKey().ifPresent(key -> {
                    String id = key.getValue().toString();
                    if (!id.equals(enchantmentId)) {
                        out.add(id);
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // An exclusivity list this version cannot resolve costs the warning, not the page.
        }
        java.util.Collections.sort(out);
        return java.util.List.copyOf(out);
    }

    private static Enchantment resolve(String enchantmentId) {
        Identifier id = Identifier.tryParse(enchantmentId);
        if (id == null) {
            return null;
        }
        // The enchantment registry is dynamic, so it is reached through the server's own registry
        // manager rather than the static Registries table, which holds no enchantments at all.
        net.minecraft.server.MinecraftServer server = currentServer();
        if (server == null) {
            return null;
        }
        try {
            return server.getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .get(RegistryKey.of(RegistryKeys.ENCHANTMENT, id));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * The running server, or null off-thread.
     *
     * <p>Held as a field rather than passed down because {@link #of} is called from deep inside a
     * per-recipe loop and threading a server reference through every caller for one lookup is worse
     * than this. Set once when the index is built.
     */
    private static volatile net.minecraft.server.MinecraftServer server;

    public static void serverFor(net.minecraft.server.MinecraftServer running) {
        server = running;
    }

    private static net.minecraft.server.MinecraftServer currentServer() {
        return server;
    }

    private static boolean accepts(Enchantment enchantment, Item item) {
        try {
            return enchantment.definition().supportedItems().contains(
                Registries.ITEM.getEntry(item));
        } catch (RuntimeException ignored) {
            // A malformed or unusual definition costs this one probe, never the page.
            return false;
        }
    }

    /** Kept so a caller can hand a stack rather than an item where that reads better. */
    public static boolean accepts(Enchantment enchantment, ItemStack stack) {
        return accepts(enchantment, stack.getItem());
    }
}
