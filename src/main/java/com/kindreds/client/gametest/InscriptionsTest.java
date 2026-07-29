package com.kindreds.client.gametest;

import com.kindreds.client.screen.InscriptionsScreen;
import com.kindreds.inscription.InscriptionIndex;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The inscriptions page, on its own, against a real server's real recipes.
 *
 * <p>Deliberately not folded into {@code UiScreenshotTest}. That one photographs eight screens and
 * fights a dozen abilities, which is minutes of waiting when the question is only ever "is the
 * stone column right yet". This boots, reads the live recipe registry, prints what it found, takes
 * one picture and stops.
 *
 * <p>It reads the index through the same call the server uses to answer a client, so what it
 * reports is what a player would actually be shown - not a table assembled by the test.
 */
public final class InscriptionsTest implements FabricClientGameTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("kindreds-inscriptions");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.getInput().resizeWindow(1600, 1000);
            context.waitTicks(40);

            List<InscriptionIndex.Entry> table = singleplayer.getServer()
                .computeOnServer(InscriptionIndex::build);

            report(table);

            // The table's own screen, to prove the injected button is really there. Opened through
            // the block's screen handler would need a placed table and a right-click; constructing
            // the screen directly exercises the same init() the mixin injects into.
            reportTableButton(context, singleplayer);

            InscriptionsScreen.accept(table);
            context.setScreen(() -> new InscriptionsScreen(null));
            context.waitTicks(20);
            context.takeScreenshot(TestScreenshotOptions.of("inscriptions")
                .disableCounterPrefix());
            context.setScreen(() -> null);
            context.waitTicks(10);
        }
    }

    /**
     * Whether the button the mixin adds to Middle-earth's own table actually appeared.
     *
     * <p>The injection carries require = 0 so a base-mod rename costs the button rather than the
     * client, which means nothing anywhere reports its absence. This does.
     */
    private static void reportTableButton(ClientGameTestContext context,
                                          TestSingleplayerContext singleplayer) {
        try {
            List<String> buttons = context.computeOnClient(client -> {
                var handler = new net.sevenstars.middleearth.gui.inscriptiontable
                    .InscriptionTableScreenHandler(1, client.player.getInventory());
                var screen = new net.sevenstars.middleearth.gui.inscriptiontable
                    .InscriptionTableScreen(handler, client.player.getInventory(),
                        net.minecraft.text.Text.empty());
                screen.init(client, 1600, 1000);
                List<String> found = new ArrayList<>();
                for (var child : screen.children()) {
                    if (child instanceof net.minecraft.client.gui.widget.ButtonWidget button) {
                        found.add(button.getMessage().getString());
                    }
                }
                return found;
            });
            LOGGER.info("buttons on the table screen: {}", buttons);
            if (buttons.stream().anyMatch(name -> name.toLowerCase().contains("inscription")
                    || name.contains("Надпис"))) {
                LOGGER.info("OK: the inscriptions button is on the table");
            } else {
                LOGGER.warn("FAIL: the mixin did not add its button - the table has {}", buttons);
            }
        } catch (Throwable failure) {
            LOGGER.warn("could not check the table screen", failure);
        }
    }

    /**
     * Everything worth knowing about what the page will show, in the log.
     *
     * <p>A screenshot proves what one screenful looks like; this proves what all hundred and fifty
     * rows resolved to, which is the part that has been wrong twice.
     */
    private static void report(List<InscriptionIndex.Entry> table) {
        LOGGER.info("================ inscriptions ================");
        LOGGER.info("rows: {}", table.size());

        Map<String, Integer> byStone = new TreeMap<>();
        Map<String, Integer> byChisel = new TreeMap<>();
        List<String> impossible = new ArrayList<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();

        for (InscriptionIndex.Entry row : table) {
            byStone.merge(row.stone(), 1, Integer::sum);
            byChisel.merge(row.chisel(), 1, Integer::sum);
            if ("impossible".equals(row.stone())) {
                impossible.add(row.enchantment() + " " + row.words());
            }
            examples.computeIfAbsent(row.stone(), key -> new ArrayList<>());
            if (examples.get(row.stone()).size() < 3) {
                examples.get(row.stone()).add(row.enchantment() + " " + row.words());
            }
        }

        LOGGER.info("by stone:  {}", byStone);
        LOGGER.info("by chisel: {}", byChisel);
        examples.forEach((stone, rows) -> LOGGER.info("  {} e.g. {}", stone, rows));

        if (impossible.isEmpty()) {
            LOGGER.info("IMPOSSIBLE: none");
        } else {
            LOGGER.warn("IMPOSSIBLE: {} rows", impossible.size());
            impossible.forEach(row -> LOGGER.warn("   {}", row));
        }

        // The two columns that have been wrong. A page missing either is a page that lies quietly.
        if (byChisel.size() < 2) {
            LOGGER.warn("CHISEL COLUMN SUSPECT: every row reports {}", byChisel.keySet());
        }
        if (byStone.containsKey("minecraft:air") || byStone.containsKey("air")) {
            LOGGER.warn("STONE COLUMN BROKEN: a catalyst resolved to air");
        }

        long withLevels = table.stream()
            .filter(row -> row.levels() != null && !row.levels().isEmpty()).count();
        long withConflicts = table.stream()
            .filter(row -> row.conflicts() != null && !row.conflicts().isEmpty()).count();
        LOGGER.info("rows carrying per-level detail: {}/{}", withLevels, table.size());
        LOGGER.info("rows carrying conflicts:        {}/{}", withConflicts, table.size());
        LOGGER.info("==============================================");
    }
}
