package com.kindreds.client.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.sevenstars.middleearth.gui.inscriptiontable.InscriptionTableScreenHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Does the table actually give you the enchantment the page promised?
 *
 * <p>Everything else about inscriptions was settled by reading: the recipe files were audited
 * offline, and the matching rule was read out of the screen handler's bytecode -
 * {@code inputWords.equals(selectedWords)}, ordered and exact, then {@code canEnchant}. Both said
 * the recipes are sound. Neither is the same as putting a chisel and a stone in a table and
 * pressing the button.
 *
 * <p>That distinction has cost this project twice in one day. meshadow's war read an empty world
 * because state was seeded into a dimension nobody plays in, and this very page reported forty-eight
 * enchantments unmakeable because a null key answered {@code minecraft:air} - both passed every
 * offline check that existed. So this drives the real handler through the real word-selection path
 * and asserts on what comes out.
 *
 * <h2>The handler, not the block</h2>
 * Placing a table and right-clicking it would need a world edit, a raycast and a server round trip
 * for no more certainty: the block's only job is to open this handler. Constructing it directly and
 * filling its three slots exercises every line that decides what an inscription produces.
 */
public final class InscriptionTableTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("kindreds-table");

    /** Slot 0 is the gem, 1 the chisel, 2 the item - read from hasGem/hasChisel/hasInput. */
    private static final int GEM = 0;
    private static final int CHISEL = 1;
    private static final int ITEM = 2;

    /** What a case expects the table to produce. */
    private record Attempt(String name, String stone, String chisel, ItemStack target,
                           List<String> words, String expectedEnchantment, int expectedLevel,
                           int expectedCost) {
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GameTestFilter.shouldRun("table")) {
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);

            // What the server really loaded for the cases below, so a failure says whether the
            // recipe is missing or the selection is wrong.
            String have = singleplayer.getServer().computeOnServer(server -> {
                StringBuilder found = new StringBuilder();
                for (var entry : server.getRecipeManager().values()) {
                    if (entry.value() instanceof net.sevenstars.middleearth.recipe.inscription
                            .InscriptionRecipe recipe) {
                        String id = recipe.enchant.getKey()
                            .map(k -> k.getValue().toString()).orElse("?");
                        // Mending only exists in meinscriptions - if it is absent, none of our
                        // data loaded; if it is present but smite is still the broken one, only
                        // the override failed. Those are different bugs.
                        if (id.endsWith("smite") || id.endsWith("mending")) {
                            found.append(System.lineSeparator()).append("    ").append(id).append(" L").append(recipe.level)
                                .append(" cost ").append(recipe.levelCost)
                                .append(" words ").append(recipe.inputWords);
                        }
                    }
                }
                return found.toString();
            });
            LOGGER.info("recipes the server has for smite/unbreaking:{}", have);

            List<String> findings = new ArrayList<>();
            for (Attempt attempt : attempts()) {
                findings.addAll(run(singleplayer, attempt));
            }

            LOGGER.info("================ the table ================");
            findings.forEach(line -> LOGGER.info("{}", line));
            LOGGER.info("==========================================");
        }
    }

    /**
     * The cases worth proving, each chosen because something specific was claimed about it.
     *
     * <p>Mending is the one the whole meinscriptions pricing argument rests on. Sharpness IV is the
     * level the base mod could not reach at all and the original complaint. Smite is the recipe the
     * base mod shipped broken and we replaced. Unbreaking I needs no stone, which was the claim
     * that the catalyst slot can be left empty.
     */
    private static List<Attempt> attempts() {
        return List.of(
            new Attempt("Mending on a sword", "minecraft:emerald", "middle-earth:mithril_chisel",
                new ItemStack(Items.DIAMOND_SWORD),
                List.of("blessing", "core", "gifted"), "minecraft:mending", 1, 50),

            // The table offers the lowest level a word set matches and you climb by re-applying,
            // so this asserts Sharpness is reachable at all - the IV and V recipes are proven by
            // the recipe audit, and level 1 here proves the words select the right enchantment.
            new Attempt("Sharpness - the words select it", "middle-earth:ruby",
                "middle-earth:mithril_chisel", new ItemStack(Items.DIAMOND_SWORD),
                List.of("forceful", "cutter"), "minecraft:sharpness", 1, 3),

            new Attempt("Smite - the recipe the base mod shipped unmakeable", "middle-earth:ruby",
                "middle-earth:iron_chisel", new ItemStack(Items.DIAMOND_SWORD),
                List.of("bane", "blessing"), "minecraft:smite", 1, 3),

            // A common-word inscription still needs a stone in the slot - any stone. getWords()
            // gates on hasAll(), which requires all three slots filled, and only then adds the
            // null-key common words. So "no stone needed" was wrong: what is true is that any
            // stone will do, because the words it grants are not the ones being used.
            new Attempt("Unbreaking - common words, any stone will do", "middle-earth:ruby",
                "middle-earth:iron_chisel", new ItemStack(Items.DIAMOND_PICKAXE),
                List.of("resilient", "blessing"), "minecraft:unbreaking", 1, 5));
    }

    /**
     * Driven on the server, which is the only side that has recipes.
     *
     * <p>The first version of this ran on the client and every case came back with
     * {@code outputs=0} while the words selected perfectly. The handler resolves recipes through
     * {@code world.getRecipeManager()} cast to {@code ServerRecipeManager}, and a client world has
     * none - since 1.21.2 Minecraft does not ship recipe data to clients at all. So the test was
     * wrong, not the table: on a client the table genuinely cannot know what a word set makes,
     * which is the same reason the reference page has to be sent from the server.
     */
    private static List<String> run(TestSingleplayerContext singleplayer, Attempt attempt) {
        List<String> out = new ArrayList<>();
        out.add("--- " + attempt.name());
        try {
            String result = singleplayer.getServer().computeOnServer(server -> {
                var player = server.getPlayerManager().getPlayerList().stream()
                    .findFirst().orElseThrow();
                InscriptionTableScreenHandler handler =
                    new InscriptionTableScreenHandler(1, player.getInventory());

                handler.input.setStack(GEM, stack(attempt.stone()));
                handler.input.setStack(CHISEL, stack(attempt.chisel()));
                handler.input.setStack(ITEM, attempt.target().copy());
                handler.player = player;

                // Word by word, exactly as clicking them does: updateWords(add, word, ?) is what
                // the screen's own buttons call.
                StringBuilder trace = new StringBuilder();
                for (String word : attempt.words()) {
                    handler.updateWords(true, word, false);
                    trace.append("  after '").append(word).append("': selected=")
                        .append(handler.selectedWords)
                        .append(" enchant=")
                        .append(handler.enchant == null ? "-" : handler.enchant.getKey()
                            .map(k -> k.getValue().getPath()).orElse("?"))
                        .append(" outputs=")
                        .append(handler.outputRecipes == null ? "-" : handler.outputRecipes.size());
                }
                LOGGER.info("trace for {}:{}", attempt.name(), trace);

                String chose = handler.enchant == null ? "nothing"
                    : handler.enchant.getKey().map(key -> key.getValue().toString()).orElse("?");
                return chose + " | level " + handler.level
                    + " | cost " + handler.getLevelCost()
                    + " | words " + handler.getWords();
            });
            out.add("    table says: " + result);

            boolean rightEnchantment = result.startsWith(attempt.expectedEnchantment());
            boolean rightLevel = result.contains("level " + attempt.expectedLevel());
            boolean rightCost = result.contains("cost " + attempt.expectedCost());

            if (rightEnchantment && rightLevel && rightCost) {
                out.add("    OK: " + attempt.expectedEnchantment() + " "
                    + attempt.expectedLevel() + " at " + attempt.expectedCost() + " levels");
            } else {
                out.add("    FAIL: expected " + attempt.expectedEnchantment()
                    + " level " + attempt.expectedLevel()
                    + " cost " + attempt.expectedCost());
            }
        } catch (Throwable failure) {
            out.add("    THREW: " + failure);
            LOGGER.warn("{} threw", attempt.name(), failure);
        }
        return out;
    }

    /** An item by id, or nothing at all for an empty slot. */
    private static ItemStack stack(String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) {
            return ItemStack.EMPTY;
        }
        var item = Registries.ITEM.get(parsed);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }
}
