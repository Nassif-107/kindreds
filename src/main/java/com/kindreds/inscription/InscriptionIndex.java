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

    /**
     * One inscription, flattened to what the page needs.
     *
     * @param levels    what each level of this inscription costs and demands, cheapest first, so
     *                  the page can say "III costs 12 and wants a steel chisel" instead of only a
     *                  range
     * @param conflicts enchantments that cannot share an item with this one
     */
    public record Entry(String enchantment, String enchantmentKey, int maxLevel,
                        List<String> words, String stone, String chisel,
                        int minCost, int maxCost, String category,
                        List<Rung> levels, List<String> conflicts) {
    }

    /** One level of one inscription: what it costs and what it has to be cut with. */
    public record Rung(int level, int cost, String chisel) {
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
        reportWordBank();
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
            // Levels of one enchantment normally share a word set, and where they do not, an
            // assemblable set beats an unassemblable one. Taking whichever recipe the registry
            // happened to yield last made a row's stone depend on iteration order, so Smite could
            // read "ruby" on one boot and "impossible" on the next with no data change at all.
            List<String> candidate = new ArrayList<>(recipe.inputWords);
            if (draft.words == null || (isImpossible(draft.words) && !isImpossible(candidate))) {
                draft.words = candidate;
            }
            draft.maxLevel = Math.max(draft.maxLevel, recipe.level);
            draft.minCost = Math.min(draft.minCost, recipe.levelCost);
            draft.maxCost = Math.max(draft.maxCost, recipe.levelCost);
            // The deepest chisel any level of this inscription asks for is the one worth printing:
            // it is the one that actually gates finishing the enchantment.
            draft.chisel = deepest(draft.chisel, chiselOf(recipe));
            draft.rungs.put(recipe.level, new Rung(recipe.level, recipe.levelCost, chiselOf(recipe)));
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
                InscriptionCategory.of(row.getKey()),
                List.copyOf(draft.rungs.values()),
                InscriptionCategory.conflictsFor(row.getKey())));
        }
        // Grouped by the stone you must bring, because that is the decision a player makes first:
        // they walk to the table holding one catalyst, and everything that stone can cut is the
        // list they actually want. Category and name order within it.
        rows.sort((a, b) -> {
            int byStone = stoneOrder(a.stone()) - stoneOrder(b.stone());
            if (byStone != 0) {
                return byStone;
            }
            int byCategory = a.category().compareTo(b.category());
            return byCategory != 0 ? byCategory : a.enchantment().compareTo(b.enchantment());
        });
        return rows;
    }

    /** Whether a word set spans two catalysts and so can never be assembled. */
    private static boolean isImpossible(List<String> words) {
        return "impossible".equals(stoneFor(words));
    }

    /** One-shot diagnostic: what the word bank actually looks like from here. */
    private static boolean dumped;

    public static void reportWordBank() {
        if (dumped) {
            return;
        }
        dumped = true;
        try {
            Map<String, List<String>> byItem = new TreeMap<>();
            for (Map.Entry<Item, String> pair : net.sevenstars.middleearth.recipe.inscription
                    .InscriptionWordBank.wordBank.entries()) {
                Item item = pair.getKey();
                String id = item == null ? "NULL-ITEM"
                    : Registries.ITEM.getId(item) + " [" + item.getClass().getSimpleName() + "]";
                byItem.computeIfAbsent(id, key -> new ArrayList<>()).add(pair.getValue());
            }
            com.kindreds.Kindreds.LOGGER.info("[kindreds] word bank has {} catalysts:", byItem.size());
            byItem.forEach((id, words) ->
                com.kindreds.Kindreds.LOGGER.info("[kindreds]   {} -> {}", id, words));

            for (String path : new String[]{"iron_chisel", "steel_chisel", "mithril_chisel"}) {
                Item chisel = Registries.ITEM.get(Identifier.of("middle-earth", path));
                com.kindreds.Kindreds.LOGGER.info("[kindreds] chisel {} resolves to {}",
                    path, chisel == Items.AIR ? "AIR (missing!)" : Registries.ITEM.getId(chisel));
            }
        } catch (Throwable failure) {
            com.kindreds.Kindreds.LOGGER.warn("[kindreds] could not read the word bank", failure);
        }
    }

    private static final class Draft {
        /** Sorted by level, so the page prints I, II, III in order without sorting again. */
        final Map<Integer, Rung> rungs = new TreeMap<>();
        List<String> words;
        int maxLevel;
        int minCost = Integer.MAX_VALUE;
        int maxCost;
        String chisel = "";
    }

    /** Common-word inscriptions first: they need no stone at all and are what a newcomer can cut. */
    private static int stoneOrder(String stone) {
        return switch (stone) {
            case "any" -> 0;
            case "middle-earth:ruby" -> 1;
            case "middle-earth:sapphire" -> 2;
            case "minecraft:emerald" -> 3;
            case "middle-earth:adamant" -> 4;
            case "impossible" -> 9;
            default -> 5;
        };
    }

    /** {@code minecraft:sharpness} → {@code enchantment.minecraft.sharpness}, vanilla's own key. */
    private static String translationKey(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : "enchantment." + id.substring(0, colon) + "." + id.substring(colon + 1);
    }

    /**
     * Which chisel a recipe demands, as a bare word.
     *
     * <h2>Cheapest that still works</h2>
     * The tiers nest: {@code early_chisels} contains the mid and late tags as well as iron, so an
     * early recipe accepts all three chisels and a late one accepts only mithril. Probing for
     * acceptance in cheapest-first order therefore names the cheapest chisel that still works,
     * which is the honest answer - and the runtime probe confirms it: an early recipe answers true
     * for iron, steel and mithril alike.
     *
     * <p>The column looked wrong for a different reason. Rows are merged across levels and take the
     * deepest chisel any level needs, so a five-level inscription whose last level wants mithril
     * reports mithril for the whole row even though level one is cuttable with iron. That is
     * correct for a single-line summary and useless for deciding what to carry, which is why the
     * hover now lists the chisel of every level separately.
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
     * <h2>The common words are keyed to a null item, and that was the whole bug</h2>
     * Read at runtime, {@code InscriptionWordBank.wordBank} looks like this:
     *
     * <pre>
     *   NULL-ITEM              -> resilient, blessing, warded, tidal, traveller, edge, cutter,
     *                             piercer, core, giant, collector, draw, point
     *   middle-earth:ruby      -> fierce, forceful, noiseless, bane
     *   middle-earth:sapphire  -> pierce, flame, sturdy
     *   minecraft:emerald      -> broad, careful, long, gifted
     *   middle-earth:adamant   -> swift
     * </pre>
     *
     * The thirteen common words hang off a <em>null</em> key rather than off lapis. Asking
     * {@code Registries.ITEM.getId(null)} for that key answers {@code minecraft:air}, so every set
     * containing any common word gained a phantom "air" catalyst - and since almost every recipe
     * uses common words, almost every recipe looked like it needed two stones and was reported
     * impossible. Forty-eight of them, with a header reading {@code stone.air} above the rest.
     *
     * <p>Skipping the null key is therefore not defensive coding; it is the meaning of the data.
     * A word with no catalyst is a word the table always grants.
     *
     * <p>Two stones genuinely is impossible - the table holds one catalyst - so that is still
     * reported rather than hidden. It should simply now be rare.
     */
    private static String stoneFor(List<String> words) {
        Map<String, Integer> owners = new TreeMap<>();
        for (Map.Entry<Item, String> pair : net.sevenstars.middleearth.recipe.inscription
                .InscriptionWordBank.wordBank.entries()) {
            if (pair.getKey() == null || !words.contains(pair.getValue())) {
                continue;
            }
            Identifier id = Registries.ITEM.getId(pair.getKey());
            if (id == null || Registries.ITEM.get(id) == Items.AIR) {
                continue;
            }
            owners.merge(id.toString(), 1, Integer::sum);
        }
        if (owners.isEmpty()) {
            return "any";
        }
        if (owners.size() > 1) {
            return "impossible";
        }
        return owners.keySet().iterator().next();
    }

}
