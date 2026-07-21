package com.kindreds.client.gametest;

import com.kindreds.ability.ActiveAbilityService;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.playerdata.KindredAttachment;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fires every active ability at real targets and photographs what happens.
 *
 * <p>The functional sweep proved the 39 actives do not throw. That is not the same as doing
 * something: an ability can run cleanly and produce no particles, no damage and no visible change.
 * This one stands the player on flat ground at noon, puts live targets in front of them, casts, and
 * captures a frame - plus the measurable outcome (targets hurt, targets killed, effects gained) so
 * the picture can be checked against a number.
 *
 * <p>Screenshots land in {@code run/screenshots} named {@code ab-<race>-<ability>}; the measurements
 * print with an {@code [ABIL]} tag.
 */
public class AbilityVisualTest implements FabricClientGameTest {
    private static final String TAG = "[ABIL]";
    private static final List<String> RACES =
            List.of("dwarf", "elf", "human", "hobbit", "uruk", "orc", "snaga", "goblin");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            sp.getServer().runCommand("gamerule doDaylightCycle false");
            sp.getServer().runCommand("gamerule doMobSpawning false");
            sp.getServer().runCommand("gamerule mobGriefing false");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("time set noon");
            // a flat, lit stage so an effect is the only thing that changes between frames
            sp.getServer().runCommand("fill ~-12 ~-1 ~-12 ~12 ~-1 ~12 minecraft:smooth_stone");
            sp.getServer().runCommand("fill ~-12 ~ ~-12 ~12 ~6 ~12 minecraft:air");
            context.waitTicks(20);

            for (String race : RACES) {
                sp.getServer().runOnServer(server -> {
                    ServerPlayerEntity pl = server.getPlayerManager().getPlayerList().get(0);
                    net.sevenstars.middleearth.resources.StateSaverAndLoader.getPlayerState(pl)
                            .assignNewRace(Identifier.of("middle-earth", race));
                });
                context.waitTicks(40);

                List<String> abilities = new ArrayList<>();
                sp.getServer().runOnServer(server -> abilities.addAll(ownAll(server, race)));
                context.waitTicks(5);

                for (String ability : abilities) {
                    String shortName = ability.substring(ability.indexOf(':') + 1);
                    // fresh targets, dead centre of the view, every time
                    // Targets stand still (NoAI) and far enough back to stay in frame - left to walk,
                    // they close on the camera and a hurt zombie at point-blank fills the screen red,
                    // which photographs nothing useful.
                    sp.getServer().runCommand("kill @e[type=!player]");
                    sp.getServer().runCommand("tp @p ~ ~ ~ 180 5");
                    sp.getServer().runCommand("summon minecraft:zombie ^ ^ ^7 {NoAI:1b,PersistenceRequired:1b}");
                    sp.getServer().runCommand("summon minecraft:zombie ^2 ^ ^8 {NoAI:1b,PersistenceRequired:1b}");
                    sp.getServer().runCommand("summon minecraft:skeleton ^-2 ^ ^8 {NoAI:1b,PersistenceRequired:1b}");
                    context.waitTicks(10);

                    Map<String, Object> before = new LinkedHashMap<>();
                    sp.getServer().runOnServer(server -> before.putAll(measure(server)));
                    context.waitTicks(2);

                    sp.getServer().runOnServer(server -> {
                        ServerPlayerEntity pl = server.getPlayerManager().getPlayerList().get(0);
                        KindredAttachment.get(pl).cooldowns().clear();
                        ActiveAbilityService.activate(pl, ability);
                    });
                    // one tick after the cast is where particles and knockback are most visible
                    context.waitTicks(3);
                    context.takeScreenshot("ab-" + race + "-" + shortName);

                    sp.getServer().runOnServer(server -> {
                        Map<String, Object> after = measure(server);
                        System.out.printf("%s %-7s %-24s targets %s->%s  theirHp %s->%s  myHp %s->%s  "
                                        + "myEffects %s->%s%n",
                                TAG, race, shortName,
                                before.get("mobs"), after.get("mobs"),
                                before.get("mobHp"), after.get("mobHp"),
                                before.get("hp"), after.get("hp"),
                                before.get("effects"), after.get("effects"));
                    });
                    context.waitTicks(2);
                }
            }
        }
    }

    /** Unlocks every node of this race's tree so all its actives are castable. */
    private static List<String> ownAll(net.minecraft.server.MinecraftServer server, String race) {
        List<String> abilities = new ArrayList<>();
        ServerPlayerEntity pl = server.getPlayerManager().getPlayerList().get(0);
        SkillTree tree = SkillTreeResolver.byRace(
                server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE),
                Identifier.of("middle-earth", race)).tree().orElse(null);
        if (tree == null) {
            return abilities;
        }
        var data = KindredAttachment.get(pl);
        for (SkillNode node : tree.nodes()) {
            data.unlockedNodes().add(node.id());
            for (AbilityDef ability : node.abilities()) {
                if (ability instanceof ActiveAbilityDef act && !abilities.contains(act.abilityId().toString())) {
                    abilities.add(act.abilityId().toString());
                }
            }
        }
        com.kindreds.ability.PerkService.invalidate(pl.getUuid());
        return abilities;
    }

    /** The numbers a cast should move, if it does anything at all. */
    private static Map<String, Object> measure(net.minecraft.server.MinecraftServer server) {
        ServerPlayerEntity pl = server.getPlayerManager().getPlayerList().get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        var world = pl.getWorld();
        var mobs = world.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class,
                pl.getBoundingBox().expand(24), e -> e.isAlive());
        float mobHp = 0;
        for (var mob : mobs) {
            mobHp += mob.getHealth();
        }
        m.put("mobs", mobs.size());
        m.put("mobHp", String.format("%.0f", mobHp));
        m.put("hp", String.format("%.1f", pl.getHealth()));
        m.put("effects", pl.getStatusEffects().size());
        m.put("attack", String.format("%.1f", pl.getAttributeValue(EntityAttributes.ATTACK_DAMAGE)));
        return m;
    }
}
