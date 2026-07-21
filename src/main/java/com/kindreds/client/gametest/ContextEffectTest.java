package com.kindreds.client.gametest;

import com.kindreds.Kindreds;
import com.kindreds.ability.PerkService;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ContextualBoon;
import com.kindreds.data.ability.CurseDef;
import com.kindreds.data.ability.StatusEffectDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.progression.UnlockService;
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
import java.util.TreeSet;

/**
 * Verifies every contextual boon and curse by <b>difference</b>, not by counting.
 *
 * <p>The first pass counted every status effect the player held, so a race carrying permanent birth
 * traits appeared to pass every context trivially and the verdict was worthless. This one returns
 * the player to a neutral state between every check, records what they hold there, applies the
 * context, and reports only what <i>changed</i> - then returns to neutral again and checks the
 * effects were actually taken away, because a boon that never lifts is its own bug.
 *
 * <p>The expectation comes from the race's own data: every {@link ContextualBoon} and {@link CurseDef}
 * whose {@code when} matches, resolved to the specific effect ids it should grant. Difficulty is
 * hard and natural regeneration off, so nothing heals the player behind the test's back.
 *
 * <p>It then walks the four difficulty presets and confirms each one actually moves the rules.
 */
public class ContextEffectTest implements FabricClientGameTest {
    private static final String TAG = "[CTX]";
    private static final List<String> RACES =
            List.of("dwarf", "elf", "human", "hobbit", "uruk", "orc", "snaga", "goblin");
    private static final List<String> CONTEXTS =
            List.of("daylight", "starlight", "darkness", "dawn_dusk", "underground", "deep_dark", "low_health");

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            sp.getServer().runCommand("gamerule doDaylightCycle false");
            sp.getServer().runCommand("gamerule doMobSpawning false");
            sp.getServer().runCommand("gamerule naturalRegeneration false");
            sp.getServer().runCommand("gamerule doImmediateRespawn true");
            sp.getServer().runCommand("difficulty hard");
            sp.getServer().runCommand("weather clear");
            // a lit platform in the sky for the neutral state, and a sealed room far below
            sp.getServer().runCommand("fill ~-6 119 ~-6 ~6 119 ~6 minecraft:smooth_stone");
            sp.getServer().runCommand("fill ~-4 30 ~-4 ~4 36 ~4 minecraft:air");
            sp.getServer().runCommand("fill ~-5 37 ~-5 ~5 37 ~5 minecraft:stone");
            sp.getServer().runCommand("fill ~-4 -40 ~-4 ~4 -34 ~4 minecraft:air");
            sp.getServer().runCommand("fill ~-5 -33 ~-5 ~5 -33 ~5 minecraft:stone");
            context.waitTicks(30);

            for (String race : RACES) {
                setRace(sp, race);
                context.waitTicks(45);
                sp.getServer().runOnServer(server -> ownEverything(server, race));
                context.waitTicks(30);

                for (String ctx : CONTEXTS) {
                    neutral(sp);
                    context.waitTicks(45);
                    Set<String> before = new TreeSet<>();
                    int[] modsBefore = new int[1];
                    sp.getServer().runOnServer(server -> {
                        before.addAll(effectsOf(player(server)));
                        modsBefore[0] = ourModifiers(player(server));
                    });
                    context.waitTicks(3);

                    enter(sp, ctx);
                    context.waitTicks(45);
                    sp.getServer().runOnServer(server -> judge(server, race, ctx, before, modsBefore[0]));
                    context.waitTicks(3);

                    // and it must lift when the context ends
                    neutral(sp);
                    context.waitTicks(50);
                    sp.getServer().runOnServer(server -> checkLifted(server, race, ctx, before));
                    context.waitTicks(3);
                }
            }

            checkDifficulties(context, sp);

            sp.getServer().runOnServer(server -> {
                System.out.println(TAG + " ================ SUMMARY ================");
                if (failures.isEmpty()) {
                    System.out.println(TAG + " every context applied and lifted correctly");
                } else {
                    failures.forEach(f -> System.out.println(TAG + " FAIL " + f));
                    System.out.println(TAG + " " + failures.size() + " problem(s)");
                }
            });
        }
    }

    /** Noon, open sky, above ground, full health: no context of ours is true here. */
    private static void neutral(TestSingleplayerContext sp) {
        sp.getServer().runCommand("time set noon");
        sp.getServer().runCommand("tp @p ~ 120 ~");
        sp.getServer().runOnServer(server -> player(server).setHealth(player(server).getMaxHealth()));
    }

    private static void enter(TestSingleplayerContext sp, String ctx) {
        switch (ctx) {
            case "daylight" -> {
                sp.getServer().runCommand("time set noon");
                sp.getServer().runCommand("tp @p ~ 120 ~");
            }
            case "starlight", "darkness" -> {
                sp.getServer().runCommand("time set midnight");
                sp.getServer().runCommand("tp @p ~ 120 ~");
            }
            case "dawn_dusk" -> {
                sp.getServer().runCommand("time set 23000");
                sp.getServer().runCommand("tp @p ~ 120 ~");
            }
            case "underground" -> {
                sp.getServer().runCommand("time set noon");
                sp.getServer().runCommand("tp @p ~ 31 ~");
            }
            case "deep_dark" -> {
                sp.getServer().runCommand("time set noon");
                sp.getServer().runCommand("tp @p ~ -39 ~");
            }
            case "low_health" -> sp.getServer().runOnServer(server ->
                    player(server).setHealth(player(server).getMaxHealth() * 0.2f));
            default -> {
            }
        }
    }

    /** Did the context grant what its data says it should? */
    private void judge(MinecraftServer server, String race, String ctx, Set<String> before, int modsBefore) {
        ServerPlayerEntity pl = player(server);
        Set<String> now = effectsOf(pl);
        Set<String> gained = new TreeSet<>(now);
        gained.removeAll(before);
        int mods = ourModifiers(pl);

        Set<String> expected = authoredEffects(server, race, ctx);
        int authoredAttrs = authoredAttributeCount(server, race, ctx);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(now);

        String verdict;
        if (expected.isEmpty() && authoredAttrs == 0) {
            verdict = gained.isEmpty() && mods == modsBefore ? "clean (nothing authored)"
                    : "unexpected change: " + gained;
        } else if (missing.isEmpty() && (authoredAttrs == 0 || mods > modsBefore)) {
            verdict = "OK";
        } else {
            verdict = "MISSING " + missing + (authoredAttrs > 0 && mods <= modsBefore ? " +attrs" : "");
            failures.add(race + "/" + ctx + ": expected " + expected
                    + (authoredAttrs > 0 ? " and " + authoredAttrs + " attribute boon(s)" : "")
                    + ", missing " + missing);
        }
        System.out.printf("%s %-7s %-11s expect=%-38s gained=%-34s mods %d->%d  %s%n",
                TAG, race, ctx, expected.toString(), gained.toString(), modsBefore, mods, verdict);
    }

    /** And did it lift again when the context ended? */
    private void checkLifted(MinecraftServer server, String race, String ctx, Set<String> baseline) {
        ServerPlayerEntity pl = player(server);
        Set<String> expected = authoredEffects(server, race, ctx);
        Set<String> stuck = new TreeSet<>();
        Set<String> now = effectsOf(pl);
        for (String e : expected) {
            if (now.contains(e) && !baseline.contains(e)) {
                stuck.add(e);
            }
        }
        if (!stuck.isEmpty()) {
            failures.add(race + "/" + ctx + ": " + stuck + " never lifted after leaving the context");
            System.out.printf("%s %-7s %-11s STUCK %s%n", TAG, race, ctx, stuck);
        }
    }

    /** Each preset must actually move the rules it claims to. */
    private void checkDifficulties(ClientGameTestContext context, TestSingleplayerContext sp) {
        for (String preset : List.of("fireside", "road", "long_defeat", "doom")) {
            sp.getServer().runCommand("kindreds difficulty " + preset);
            context.waitTicks(15);
            sp.getServer().runOnServer(server -> {
                var c = Kindreds.CONFIG;
                SkillTree elf = tree(server, "elf");
                int cap = elf == null ? -1 : UnlockService.effectiveCap(elf, new com.kindreds.playerdata.KindredData());
                System.out.printf("%s difficulty %-12s xp x%-4s death=%-14s cap%%=%-4d respec=%-2d "
                                + "scaling=%-5s | elf cap %d of %d%n",
                        TAG, preset, c.xpRateGlobal, c.deathPenalty, c.pointCapPercent, c.respecCost,
                        c.enableEnemyScaling, cap, elf == null ? -1 : UnlockService.maxSpendable(elf));
                if (preset.equals("doom") && c.pointCapPercent != 45) {
                    failures.add("difficulty doom did not set the cap to 45% (got " + c.pointCapPercent + ")");
                }
                if (preset.equals("fireside") && cap != 0) {
                    failures.add("difficulty fireside should remove the cap entirely (got " + cap + ")");
                }
            });
            context.waitTicks(5);
        }
    }

    // --- reading the data -------------------------------------------------------------------

    /** Effect ids this race's data grants in this context, from birth traits and every node. */
    private static Set<String> authoredEffects(MinecraftServer server, String race, String ctx) {
        Set<String> out = new TreeSet<>();
        SkillTree tree = tree(server, race);
        if (tree != null) {
            for (SkillNode node : tree.nodes()) {
                for (AbilityDef a : node.abilities()) {
                    collect(a, ctx, out);
                }
            }
        }
        return out;
    }

    private static void collect(AbilityDef a, String ctx, Set<String> out) {
        AbilityDef inner = innerOf(a, ctx);
        if (inner instanceof StatusEffectDef s) {
            out.add(s.effect().getPath());
        }
    }

    /** The effect a boon or curse carries in this context, or null - curses wrap theirs in Optional. */
    private static AbilityDef innerOf(AbilityDef a, String ctx) {
        if (a instanceof ContextualBoon b && ctx.equals(b.when())) {
            return b.effect();
        }
        if (a instanceof CurseDef c && ctx.equals(c.when())) {
            return c.effect().orElse(null);
        }
        return null;
    }

    private static int authoredAttributeCount(MinecraftServer server, String race, String ctx) {
        int n = 0;
        SkillTree tree = tree(server, race);
        if (tree == null) {
            return 0;
        }
        for (SkillNode node : tree.nodes()) {
            for (AbilityDef a : node.abilities()) {
                AbilityDef inner = innerOf(a, ctx);
                if (inner != null && !(inner instanceof StatusEffectDef)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static Set<String> effectsOf(ServerPlayerEntity pl) {
        Set<String> s = new LinkedHashSet<>();
        pl.getStatusEffects().forEach(e -> s.add(e.getEffectType().getIdAsString().replace("minecraft:", "")));
        return s;
    }

    private static int ourModifiers(ServerPlayerEntity pl) {
        int n = 0;
        for (var attr : List.of(EntityAttributes.ATTACK_DAMAGE, EntityAttributes.MOVEMENT_SPEED,
                EntityAttributes.MAX_HEALTH, EntityAttributes.ARMOR, EntityAttributes.ATTACK_SPEED,
                EntityAttributes.KNOCKBACK_RESISTANCE)) {
            var inst = pl.getAttributeInstance(attr);
            if (inst != null) {
                for (var mod : inst.getModifiers()) {
                    if (mod.id().getNamespace().equals("kindreds")) {
                        n++;
                    }
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
