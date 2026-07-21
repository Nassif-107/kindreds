package com.kindreds.client.gametest;

import com.kindreds.client.screen.KindredCodexScreen;
import com.kindreds.client.screen.SkillTreeScreen;
import com.kindreds.playerdata.ClientKindredData;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.Identifier;

/**
 * A tight loop for working on one screen: photographs the traits page and the skill tree at every
 * GUI scale a player can select, so a layout that only breaks at scale 4 cannot pass unseen.
 *
 * <p>Kept separate from {@link UiScreenshotTest} because that one is a broad sweep and this one is
 * meant to be run alone while a screen is being changed.
 */
public class ScreenIterationTest implements FabricClientGameTest {
    private static final int[] SCALES = {1, 2, 3, 4};

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();

            sp.getServer().runOnServer(server -> net.sevenstars.middleearth.resources.StateSaverAndLoader
                    .getPlayerState(server.getPlayerManager().getPlayerList().get(0))
                    .assignNewRace(Identifier.of("middle-earth", "dwarf")));
            // A singleplayer test server has no console player, so anything that reads "the player who
            // ran this" has to be told which one - and there is no /op here to grant with either.
            sp.getServer().runCommand("kindreds config allowGrantXp true");
            for (String disc : new String[]{"mining", "combat", "smithing", "archery", "stealth"}) {
                sp.getServer().runCommand("kindreds grantxp " + disc + " 40000 @p");
            }
            context.waitTicks(40);
            sp.getServer().runCommand("kindreds doctor");
            context.waitTicks(10);

            for (int scale : SCALES) {
                setScale(context, scale);

                context.setScreen(() -> new KindredCodexScreen(ClientKindredData.INSTANCE, null));
                context.waitTicks(10);
                context.takeScreenshot("traits-own-s" + scale);

                // one page right: an Elf, whose page is not the player's own, so the disciplines and
                // the guide fall away and only the three trait groups remain
                context.runOnClient(mc -> mc.currentScreen.keyPressed(262, 0, 0));
                context.waitTicks(8);
                context.takeScreenshot("traits-other-s" + scale);

                context.setScreen(() -> new SkillTreeScreen(ClientKindredData.INSTANCE));
                context.waitTicks(12);
                context.takeScreenshot("tree-s" + scale);

                // Pick a node so the panel has to render a full detail - the longest thing it ever
                // shows, and the case that used to run out through the footer. The exact node does
                // not matter, so sweep the middle of the canvas until one takes.
                context.runOnClient(mc -> {
                    int w = mc.getWindow().getScaledWidth();
                    int h = mc.getWindow().getScaledHeight();
                    for (int dy = -60; dy <= 60 && mc.currentScreen != null; dy += 6) {
                        mc.currentScreen.mouseClicked(w * 0.46, h / 2.0 + dy, 0);
                        mc.currentScreen.mouseReleased(w * 0.46, h / 2.0 + dy, 0);
                    }
                });
                context.waitTicks(8);
                context.takeScreenshot("tree-node-s" + scale);

                // Shut the floating panel: on a narrow window this is what gives the tree the screen.
                context.runOnClient(mc -> {
                    int w = mc.getWindow().getScaledWidth();
                    mc.currentScreen.mouseClicked(w - 24.0, 20.0, 0);
                    mc.currentScreen.mouseReleased(w - 24.0, 20.0, 0);
                });
                context.waitTicks(8);
                context.takeScreenshot("tree-panelshut-s" + scale);
            }

            setScale(context, 2);
        }
    }

    private static void setScale(ClientGameTestContext context, int scale) {
        context.setScreen(() -> null);
        context.runOnClient(mc -> {
            mc.options.getGuiScale().setValue(scale);
            mc.onResolutionChanged();
        });
        context.waitTicks(6);
    }
}
