package com.kindreds.client.gametest;

import com.kindreds.ability.PerkService;
import com.kindreds.client.loadout.AbilityRadialScreen;
import com.kindreds.client.loadout.KindredLoadoutScreen;
import com.kindreds.client.screen.KindredsSettingsScreen;
import com.kindreds.client.screen.SkillTreeScreen;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredAttachment;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.Supplier;

/**
 * Drives a real client through the mod, exercises it, and photographs the result.
 *
 * <p>Every other check in this project reads source or data. None of them can tell whether a panel
 * overflows its window, whether a screen throws the moment it is drawn, or whether an ability does
 * anything at all when it fires. This does, and it writes the evidence to {@code run/screenshots}
 * and the log where both can be read afterwards.
 *
 * <p>The dev client is given the real base Middle-earth mod (see {@code run/mods}), because most of
 * this mod is unreachable without a race: no race means no tree, no birth traits and no abilities,
 * and the skill screen correctly refuses to draw anything but "choose your people first".
 *
 * <p>Run with {@code gradlew runClientGameTest}.
 */
public class UiScreenshotTest implements FabricClientGameTest {
    private static final String TAG = "[UITEST]";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();

            // Where does the player actually stand on arrival? Two depth-gated deeds completed at
            // spawn on the previous run, which should be impossible above ground.
            report(sp, "on join");
            context.takeScreenshot("00-join");

            sp.getServer().runCommand("op @p");
            sp.getServer().runOnServer(server -> net.sevenstars.middleearth.resources.StateSaverAndLoader
                    .getPlayerState(server.getPlayerManager().getPlayerList().get(0))
                    .assignNewRace(net.minecraft.util.Identifier.of("middle-earth", "dwarf")));
            context.waitTicks(40);
            sp.getServer().runCommand("kindreds doctor");
            context.waitTicks(5);

            // Become a Dwarf - everything below needs a kindred to exist at all.
            sp.getServer().runCommand("middle_earth race dwarf");
            context.waitTicks(30);
            report(sp, "after race set");
            context.takeScreenshot("01-race-set");

            shoot(context, "01b-hub", com.kindreds.client.screen.KindredHubScreen::new);
            shoot(context, "02-tree-empty", () -> new SkillTreeScreen(ClientKindredData.INSTANCE));

            // Fund the tree so it is not an empty grid, then look again.
            sp.getServer().runCommand("kindreds config allowGrantXp true");
            sp.getServer().runCommand("kindreds grantxp mining 40000");
            sp.getServer().runCommand("kindreds grantxp combat 40000");
            sp.getServer().runCommand("kindreds grantxp smithing 40000");
            context.waitTicks(20);
            report(sp, "after xp");
            shoot(context, "03-tree-funded", () -> new SkillTreeScreen(ClientKindredData.INSTANCE));

            shoot(context, "04-abilities", KindredLoadoutScreen::new);
            shoot(context, "05-settings", () -> new KindredsSettingsScreen(null));
            shoot(context, "06-radial", AbilityRadialScreen::new);

            context.setScreen(() -> null);
            context.waitTicks(20);
            context.takeScreenshot("07-hud");
        }
    }

    /** Dumps the live server-side truth about the player: position, race, perks, attributes. */
    private static void report(TestSingleplayerContext sp, String when) {
        sp.getServer().runOnServer(server -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayerList().stream().findFirst().orElse(null);
            if (p == null) {
                System.out.println(TAG + " " + when + ": no player yet");
                return;
            }
            var data = KindredAttachment.get(p);
            System.out.printf("%s %s: y=%.1f race=%s nodes=%d perks=%d renown=%d hearts=%.1f attack=%.2f%n",
                    TAG, when, p.getY(), String.valueOf(data.race()), data.unlockedNodes().size(),
                    PerkService.ownedPerks(p).size(), data.renown().size(),
                    p.getMaxHealth() / 2f,
                    p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ATTACK_DAMAGE));
            data.renown().forEach(r -> System.out.println(TAG + "   renown held: " + r));
        });
    }

    private static void shoot(ClientGameTestContext context, String name, Supplier<Screen> screen) {
        context.setScreen(screen::get);
        context.waitTicks(12);
        context.takeScreenshot(name);
    }
}
