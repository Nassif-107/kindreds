package com.kindreds.inscription;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.sevenstars.middleearth.recipe.inscription.InscriptionRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads every inscription the server actually knows, so the reference page can never be wrong.
 *
 * <h2>Why this is read rather than written down</h2>
 * The obvious way to document 160 recipes is to write them into a table by hand, and that table is
 * out of date the first time anybody edits a datapack. This walks the live recipe registry instead,
 * so it reports whatever the server is genuinely running - Middle-earth's own 99, the ones
 * {@code meinscriptions} adds, and anything a datapack lays on top - without knowing about any of
 * them in particular.
 *
 * <h2>Why it has to be server-side</h2>
 * The client is never told what the recipes are. Middle-earth's own table resolves them in its
 * screen handler and sends the client only {@code availableWords}, a byte array of which words are
 * lit - enough to drive its own UI and nothing like enough to explain the system. Since 1.21.2
 * Minecraft itself no longer ships full recipe data to clients either. So the page's contents are
 * gathered here and pushed down; see {@code SyncInscriptionsS2C}.
 */
public final class InscriptionIndex {

    private InscriptionIndex() {
    }

    /** One inscription, flattened to what the page needs. */
    public record Entry(String enchantment, String enchantmentKey, int maxLevel,
                        List<String> words, String stone, String chisel,
                        int minCost, int maxCost, String category) {
    }

    /**
     * Every inscription on this server, one row per enchantment rather than one per level.
     *
     * <p>Rows are merged across levels on purpose: Bane of Arthropods is five recipes with the same
     * words and a rising price, and printing it five times would make the page a wall of repetition
     * rather than a reference. The level range and the cost range carry what the merge drops.
     */
    public static List<Entry> build(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        InscriptionCategory.serverFor(server);
        Map<String, Draft> drafts = new LinkedHashMap<>();
        for (var entry : server.getRecipeManager().values()) {
            if (!(entry.value() instanceof InscriptionRecipe recipe)) {
                continue;
            }
            String id = recipe.enchant.getKey().map(key -> key.getValue().toString()).orElse(null);
            if (id == null) {
                continue;
            }
            Draft draft = drafts.computeIfAbsent(id, key -> new Draft());
            draft.words = new ArrayList<>(recipe.inputWords);
            draft.maxLevel = Math.max(draft.maxLevel, recipe.level);
            draft.minCost = Math.min(draft.minCost, recipe.levelCost);
            draft.maxCost = Math.max(draft.maxCost, recipe.levelCost);
            // The deepest chisel any level of this inscription asks for is the one worth printing:
            // it is the one that actually gates finishing the enchantment.
            draft.chisel = deepest(draft.chisel, chiselOf(recipe));
        }

        List<Entry> rows = new ArrayList<>(drafts.size());
        for (Map.Entry<String, Draft> row : drafts.entrySet()) {
            Draft draft = row.getValue();
            if (draft.words == null || draft.words.isEmpty()) {
                continue;
            }
            rows.add(new Entry(row.getKey(), translationKey(row.getKey()), draft.maxLevel,
                draft.words, stoneFor(draft.words), draft.chisel,
                draft.minCost == Integer.MAX_VALUE ? 0 : draft.minCost, draft.maxCost,
                InscriptionCategory.of(row.getKey())));
        }
        rows.sort((a, b) -> {
            int byCategory = a.category().compareTo(b.category());
            return byCategory != 0 ? byCategory : a.enchantment().compareTo(b.enchantment());
        });
        return rows;
    }

    private static final class Draft {
        List<String> words;
        int maxLevel;
        int minCost = Integer.MAX_VALUE;
        int maxCost;
        String chisel = "";
    }

    /** {@code minecraft:sharpness} → {@code enchantment.minecraft.sharpness}, vanilla's own key. */
    private static String translationKey(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : "enchantment." + id.substring(0, colon) + "." + id.substring(colon + 1);
    }

    /**
     * Which chisel a recipe demands, as a bare word.
     *
     * <p>Read from the ingredient rather than from the tag name, because the tag is not on the
     * recipe object - only the resolved {@link net.minecraft.recipe.Ingredient} is. The tiers nest
     * ({@code early} accepts all three, {@code mid} accepts steel and mithril, {@code late} accepts
     * only mithril), so the honest way to name a tier is by the <em>cheapest</em> chisel it still
     * accepts.
     */
    private static String chiselOf(InscriptionRecipe recipe) {
        if (accepts(recipe, "iron_chisel")) {
            return "iron";
        }
        if (accepts(recipe, "steel_chisel")) {
            return "steel";
        }
        return "mithril";
    }

    /** Whether this recipe's chisel slot would take the named Middle-earth chisel. */
    private static boolean accepts(InscriptionRecipe recipe, String chiselPath) {
        Item chisel = Registries.ITEM.get(Identifier.of("middle-earth", chiselPath));
        // An absent item resolves to air, which no ingredient accepts - so a renamed chisel costs
        // this probe rather than throwing, and the tier simply reads one step deeper.
        return chisel != Items.AIR && recipe.inputChisel.test(new ItemStack(chisel));
    }

    /** The deeper of two tiers, so a merged row reports the hardest chisel it ever needs. */
    private static String deepest(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String chisel) {
        return switch (chisel) {
            case "iron" -> 1;
            case "steel" -> 2;
            case "mithril" -> 3;
            default -> 0;
        };
    }

    /**
     * The catalyst a word set needs, or {@code "any"} when every word is common.
     *
     * <p>Read from Middle-earth's own {@code InscriptionWordBank}, which maps each catalyst item to
     * the words it grants, rather than from a copy of that table kept here - a copy would be one
     * datapack away from lying. A set touching two catalysts cannot be assembled at all, since the
     * table holds one stone; that is reported as {@code "impossible"} instead of being hidden,
     * because a recipe nobody can make is exactly what a reference page should be shouting about.
     */
    private static String stoneFor(List<String> words) {
        Map<String, Integer> owners = new TreeMap<>();
        for (Map.Entry<Item, String> pair : net.sevenstars.middleearth.recipe.inscription
                .InscriptionWordBank.wordBank.entries()) {
            if (!words.contains(pair.getValue())) {
                continue;
            }
            Identifier id = Registries.ITEM.getId(pair.getKey());
            owners.merge(id.getPath(), 1, Integer::sum);
        }
        // Lapis grants only the common words, so it is present for every set and never identifies
        // one. Naming it as "the stone this needs" would be true and useless.
        owners.remove(Registries.ITEM.getId(Items.LAPIS_LAZULI).getPath());
        if (owners.isEmpty()) {
            return "any";
        }
        if (owners.size() > 1) {
            return "impossible";
        }
        return owners.keySet().iterator().next();
    }
}
