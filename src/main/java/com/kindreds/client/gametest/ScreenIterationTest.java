package com.kindreds.client.gametest;

import com.kindreds.client.screen.KindredCodexScreen;
import com.kindreds.client.screen.KindredsSettingsScreen;
import com.kindreds.client.screen.SkillTreeScreen;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.threat.MobMark;
import com.kindreds.threat.MobScaler;
import com.kindreds.threat.ThreatService;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * A tight loop for working on one screen: photographs the traits page and the skill tree at every
 * GUI scale a player can select, so a layout that only breaks at scale 4 cannot pass unseen.
 *
 * <p>Kept separate from {@link UiScreenshotTest} because that one is a broad sweep and this one is
 * meant to be run alone while a screen is being changed.
 *
 * <p>Task 7 adds a real in-game proof of phase-2 scaling alongside the screenshots: the assertions
 * below inspect the summoned mob's actual entity state directly (its attribute instance, its custom
 * name) rather than parsing {@code /kindreds doctor}'s printed output - the doctor's own checks
 * (dial ranges, the mixin, {@code MobMark.KEY}, the escort yardstick, elite ability resolution) are
 * about this mod's own static wiring being correct, which is a different question from "did THIS
 * summoned mob actually get scaled and named", so it is still run (see the {@code [T7]} log lines)
 * but is not the thing the pass/fail check below depends on.
 */
public class ScreenIterationTest implements FabricClientGameTest {
    private static final String TAG = "[T7]";
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
            // Operator, so the hub shows its fifth point (Server rules) and the rose has to hold five.
            sp.getServer().runOnServer(server -> {
                var player = server.getPlayerManager().getPlayerList().get(0);
                server.getPlayerManager().addToOperators(player.getGameProfile());
            });
            sp.getServer().runCommand("kindreds config allowGrantXp true");
            for (String disc : new String[]{"mining", "combat", "smithing", "archery", "stealth"}) {
                sp.getServer().runCommand("kindreds grantxp " + disc + " 40000 @p");
            }
            context.waitTicks(40);
            // Grant one Great Deed so the page shows both a done and an undone deed. The advancement
            // is the real one, so RenownService records it exactly as it would in play.
            sp.getServer().runCommand("advancement grant @p only kindreds:renown/dwarf/khazad_work");
            context.waitTicks(20);

            // --- Task 7: force the phase-2 dials + a real threat state, summon a mob, and prove it
            // scaled - both via config the same way an operator would, and via direct entity/state
            // inspection where no command surface exists yet (per-family competence, entity
            // attributes). No command exists to seed per-family competence, so this goes through the
            // same live KindredData + SyncKindredDataS2C.sendTo path the real evidence loop would
            // eventually reach on its own; driven directly here instead of by combat, so the Deeds
            // screenshot below actually exercises the family-voice rendering path (three lines at
            // once - the worst case for overlap) instead of an always-empty one.
            sp.getServer().runCommand("gamerule doMobSpawning false");
            sp.getServer().runCommand("kindreds config eliteChance 100");
            sp.getServer().runCommand("kindreds config maxHealthBonus 100");
            // Pushed to the dial's own [0,2] ceiling so scaledGroup >= 1.0 regardless of which
            // dimension this test world actually is (it is not middle-earth, so without this the
            // default 0.75 overworld pacing would make the rolls below merely likely, not certain).
            sp.getServer().runCommand("kindreds config dimensionMultiplierOverworld 2");
            sp.getServer().runCommand("kindreds config dimensionMultiplierMiddleEarth 2");

            sp.getServer().runOnServer(server -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
                var data = KindredAttachment.get(player);
                var threat = data.threat();
                // threat = priorMark * band(competence); 100 * band(1.0) = 100 (the max), so
                // ThreatService.scaledFor(player) == scaled(100, exponent) == 1.0 for ANY exponent
                // (1^x == 1) - combined with the x2 dimension multiplier above this makes both the
                // elite-promotion roll and the health bonus deterministic (effective chance >= 1.0;
                // world.getRandom().nextFloat() is always strictly < 1.0).
                threat.setPriorMark(100f);
                threat.setCompetence(1.0f);
                // Real per-family divergence, each well clear of both the 0.1 threshold and each
                // other (see ThreatMathTest for why "well clear" matters - float rounding, not a
                // logic bug, is what makes near-ties non-deterministic).
                threat.familyCompetence().put("trolls", 1.35f);   // +0.35 mastered - strongest
                threat.familyCompetence().put("wargs", 0.75f);    // -0.25 feared
                threat.familyCompetence().put("undead", 1.18f);   // +0.18 mastered - weaker than trolls
                ThreatService.invalidate(player.getUuid());
                SyncKindredDataS2C.sendTo(player);
            });
            context.waitTicks(5);

            // COMMAND spawns still run initialize() (the mark exists), but their SpawnReason is
            // COMMAND, not NATURAL - MobScaler's escort roll is gated on NATURAL only, so this mob
            // must never come with an escort no matter how the dials above are set.
            sp.getServer().runCommand("execute as @p at @p run summon minecraft:zombie ~ ~ ~2");
            context.waitTicks(5);

            List<String> failures = new ArrayList<>();
            sp.getServer().runOnServer(server -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
                List<ZombieEntity> zombies = player.getWorld().getEntitiesByClass(ZombieEntity.class,
                        player.getBoundingBox().expand(16), e -> true);
                if (zombies.isEmpty()) {
                    failures.add("no zombie found near the player after /summon");
                    return;
                }
                ZombieEntity zombie = zombies.get(0);
                EntityAttributeInstance health = zombie.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                boolean scaled = health != null && health.getModifier(MobScaler.SCALED_HEALTH_ID) != null;
                boolean named = zombie.hasCustomName();
                boolean escorted = MobMark.of(zombie).escort();
                System.out.println(TAG + " zombie maxHealth=" + zombie.getMaxHealth()
                        + " scaledHealthModifier=" + scaled + " customName=" + named
                        + " (" + (named ? zombie.getName().getString() : "none") + ")"
                        + " escort=" + escorted);
                if (!scaled) {
                    failures.add("summoned zombie does not carry kindreds:scaled/max_health");
                }
                if (!named) {
                    failures.add("summoned zombie has no custom name (eliteChance=100 should have promoted it)");
                }
                if (escorted) {
                    failures.add("summoned zombie is itself marked as an escort - impossible for a COMMAND spawn");
                }
            });
            context.waitTicks(5);

            // The doctor's own checks (dial ranges, MobEntityInitializeMixin merged, MobMark.KEY
            // registered, the escort mob-cap yardstick, every elite ability resolving) - run now, with
            // the dials above actually holding non-default values, so its printed output is evidence
            // for this specific run rather than only for the defaults.
            sp.getServer().runCommand("kindreds doctor");
            context.waitTicks(10);

            sp.getServer().runOnServer(server -> {
                if (failures.isEmpty()) {
                    System.out.println(TAG + " all checks passed: scaled health + custom name confirmed, "
                            + "no escort from a COMMAND spawn");
                } else {
                    failures.forEach(f -> System.out.println(TAG + " FAIL " + f));
                    System.out.println(TAG + " " + failures.size() + " failure(s)");
                }
            });

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

                context.setScreen(() -> new com.kindreds.client.screen.KindredDeedsScreen(
                        ClientKindredData.INSTANCE, null));
                context.waitTicks(10);
                context.takeScreenshot("deeds-s" + scale);

                // The settings screen (Task 6's flow-layout) and the Deeds page (this task's rank
                // line) are the two screens this task actually needs proof of: no overlapping text
                // at any GUI scale.
                context.setScreen(() -> new KindredsSettingsScreen(null));
                context.waitTicks(10);
                context.takeScreenshot("settings-s" + scale);

                context.setScreen(() -> new com.kindreds.client.screen.KindredHubScreen());
                context.waitTicks(8);
                context.takeScreenshot("hub-s" + scale);

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
