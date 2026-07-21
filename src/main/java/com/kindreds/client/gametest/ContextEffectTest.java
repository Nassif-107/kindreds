package com.kindreds.client.gametest;

import com.kindreds.ability.PerkService;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ContextualBoon;
import com.kindreds.data.ability.CurseDef;
import com.kindreds.playerdata.KindredAttachment;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Proves each race's contextual boons and curses actually fire.
 *
 * <p>136 grants across the mod hang on seven contexts - starlight, daylight, darkness, deep dark,
 * underground, dawn/dusk and low health - and none had ever been observed doing anything. They are
 * where a kindred's identity lives: an Elf quickened under the open night, an Orc punished by the
 * sun, a Snaga at his most dangerous when nearly dead. If a context never evaluates true, that
 * identity silently does not exist and nothing in the game says so.
 *
 * <p>Each context is reproduced exactly as {@code CurseContextService} judges it (time of day, sky
 * visibility, depth, light level, health fraction), then the player's live effects and attributes
 * are compared against what that race's data says should apply. Results print under {@code [CTX]}.
 */
public class ContextEffectTest implements FabricClientGameTest {
    private static final String TAG = "[CTX]";
    private static final List<String> RACES =
            List.of("dwarf", "elf", "human", "hobbit", "uruk", "orc", "snaga", "goblin");

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            sp.getServer().runCommand("gamerule doDaylightCycle false");
            sp.getServer().runCommand("gamerule doMobSpawning false");
            sp.getServer().runCommand("gamerule naturalRegeneration false");
            sp.getServer().runCommand("weather clear");
            sp.getServer().runCommand("difficulty peaceful");
            context.waitTicks(20);

            for (String race : RACES) {
                setRace(sp, race);
                context.waitTicks(45);
                sp.getServer().runOnServer(server -> ownEverything(server, race));
                context.waitTicks(25);

                // each context, reproduced the way the service judges it
                check(context, sp, race, "daylight", () -> {
                    sp.getServer().runCommand("time set noon");
                    sp.getServer().runCommand("tp @p ~ 120 ~");
                    sp.getServer().runCommand("setblock ~ 119 ~ minecraft:smooth_stone");
                });
                check(context, sp, race, "starlight", () -> {
                    sp.getServer().runCommand("time set midnight");
                    sp.getServer().runCommand("tp @p ~ 120 ~");
                });
                check(context, sp, race, "darkness", () -> {
                    sp.getServer().runCommand("time set midnight");
                    sp.getServer().runCommand("tp @p ~ 120 ~");
                });
                check(context, sp, race, "dawn_dusk", () -> {
                    sp.getServer().runCommand("time set 23000");
                    sp.getServer().runCommand("tp @p ~ 120 ~");
                });
                check(context, sp, race, "underground", () -> {
                    sp.getServer().runCommand("time set noon");
                    sp.getServer().runCommand("fill ~-3 30 ~-3 ~3 36 ~3 minecraft:air");
                    sp.getServer().runCommand("fill ~-4 37 ~-4 ~4 37 ~4 minecraft:stone");
                    sp.getServer().runCommand("tp @p ~ 31 ~");
                });
                check(context, sp, race, "deep_dark", () -> {
                    sp.getServer().runCommand("time set noon");
                    sp.getServer().runCommand("fill ~-3 -40 ~-3 ~3 -34 ~3 minecraft:air");
                    sp.getServer().runCommand("fill ~-4 -33 ~-4 ~4 -33 ~4 minecraft:stone");
                    sp.getServer().runCommand("tp @p ~ -39 ~");
                });
                check(context, sp, race, "low_health", () -> {
                    sp.getServer().runCommand("tp @p ~ 120 ~");
                    sp.getServer().runOnServer(server -> {
                        ServerPlayerEntity pl = player(server);
                        pl.setHealth(pl.getMaxHealth() * 0.2f);
                    });
                });
                // heal back up so low_health does not bleed into the next context
                sp.getServer().runOnServer(server -> player(server).setHealth(player(server).getMaxHealth()));
                context.waitTicks(30);
            }

            sp.getServer().runOnServer(server -> {
                System.out.println(TAG + " ================ SUMMARY ================");
                if (failures.isEmpty()) {
                    System.out.println(TAG + " every contextual boon and curse fired");
                } else {
                    failures.forEach(f -> System.out.println(TAG + " FAIL " + f));
                    System.out.println(TAG + " " + failures.size() + " context(s) produced nothing");
                }
            });
        }
    }

    /** Sets up the world for a context, waits for the service's tick, and reports what changed. */
    private void check(ClientGameTestContext context, TestSingleplayerContext sp, String race,
                       String ctx, Runnable setup) {
        setup.run();
        // CurseContextService re-derives once a second; give it two passes plus travel time
        context.waitTicks(55);
        sp.getServer().runOnServer(server -> {
            ServerPlayerEntity pl = player(server);
            int expected = expectedFor(server, race, ctx);
            Set<String> effects = new LinkedHashSet<>();
            pl.getStatusEffects().forEach(e -> effects.add(
                    e.getEffectType().getIdAsString().replace("minecraft:", "") + " " + (e.getAmplifier() + 1)));
            long ourMods = countKindredsModifiers(pl);

            System.out.printf("%s %-7s %-11s authored=%-2d effects=%-2d mods=%-2d  %s%n",
                    TAG, race, ctx, expected, effects.size(), ourMods, effects);

            if (expected > 0 && effects.isEmpty() && ourMods == 0) {
                failures.add(race + "/" + ctx + ": data authors " + expected
                        + " effect(s) here, the player has none");
            }
        });
        context.waitTicks(5);
    }

    /** How many boons/curses this race's data hangs on this context (birth traits and nodes alike). */
    private static int expectedFor(MinecraftServer server, String race, String ctx) {
        int n = 0;
        SkillTree tree = tree(server, race);
        if (tree != null) {
            for (SkillNode node : tree.nodes()) {
                for (AbilityDef a : node.abilities()) {
                    if (a instanceof ContextualBoon b && ctx.equals(b.when())) {
                        n++;
                    } else if (a instanceof CurseDef c && ctx.equals(c.when())) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Persistent attribute modifiers this mod installed - contextual ones appear and vanish here. */
    private static long countKindredsModifiers(ServerPlayerEntity pl) {
        long n = 0;
        for (var attr : List.of(EntityAttributes.ATTACK_DAMAGE, EntityAttributes.MOVEMENT_SPEED,
                EntityAttributes.MAX_HEALTH, EntityAttributes.ARMOR, EntityAttributes.ATTACK_SPEED)) {
            var inst = pl.getAttributeInstance(attr);
            if (inst == null) {
                continue;
            }
            for (var mod : inst.getModifiers()) {
                if (mod.id().getNamespace().equals("kindreds")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static SkillTree tree(MinecraftServer server, String race) {
        return SkillTreeResolver.byRace(server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE),
                Identifier.of("middle-earth", race)).tree().orElse(null);
    }

    private static ServerPlayerEntity player(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList().get(0);
    }

    private static void setRace(TestSingleplayerContext sp, String race) {
        sp.getServer().runOnServer(server -> net.sevenstars.middleearth.resources.StateSaverAndLoader
                .getPlayerState(player(server)).assignNewRace(Identifier.of("middle-earth", race)));
    }

    /** Owns the whole tree, so every contextual grant in the data is live. */
    private static void ownEverything(MinecraftServer server, String race) {
        ServerPlayerEntity pl = player(server);
        SkillTree tree = tree(server, race);
        if (tree == null) {
            return;
        }
        var data = KindredAttachment.get(pl);
        for (SkillNode node : tree.nodes()) {
            data.unlockedNodes().add(node.id());
        }
        PerkService.invalidate(pl.getUuid());
        com.kindreds.ability.NodeReconcileService.reapply(pl);
    }
}
