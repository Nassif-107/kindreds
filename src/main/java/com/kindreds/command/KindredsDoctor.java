package com.kindreds.command;

import com.kindreds.Kindreds;
import com.kindreds.ability.ActiveAbilityHandlers;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.data.ability.AttributeMod;
import com.kindreds.data.ability.ContextualBoon;
import com.kindreds.data.ability.PerkDef;
import com.kindreds.data.ability.StatusEffectDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.RenownService;
import com.kindreds.progression.UnlockService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code /kindreds doctor} - a <b>self-check</b> that answers "is this build actually wired up?"
 * without needing a stack trace to interpret.
 *
 * <p>This mod's failure modes are mostly silent rather than crashing: a mixin that stopped applying
 * after a Minecraft update, a payload the client doesn't know about, an advancement whose predicate
 * the datapack loader rejected (vanilla logs one line and skips the file), a skill node pointing at a
 * prereq that no longer exists, or an active ability with no handler behind it. Every one of those
 * looks like "the feature just doesn't do anything" in play. The doctor names them instead.
 *
 * <p>Every check interrogates the <b>running game</b>, not this mod's own opinion of itself: mixins
 * are confirmed by looking for their merged handler methods on the real target classes, payloads by
 * asking Fabric which receivers are registered and what the connected client accepts, and deeds by
 * diffing the advancement ids the server actually loaded against the files shipped in this jar.
 */
public final class KindredsDoctor {
    private KindredsDoctor() {
    }

    /**
     * Mixin handler methods, by the class they should have been merged into. A mixin that silently
     * stopped applying (the usual outcome of a Minecraft update moving its target) leaves its handler
     * absent, which is directly observable through reflection.
     *
     * <p>Two things this must NOT do, both learned the hard way from this check's own first run:
     * <ul>
     *   <li>Name the target class by string. {@code Class.forName("net.minecraft.entity.LivingEntity")}
     *       works in dev and fails in a built jar, where Minecraft classes carry intermediary names -
     *       a string is not remapped, a class literal is.</li>
     *   <li>Compare handler names with {@code equals}. Mixin conforms merged handler names with a
     *       generated prefix, so the authored name is a <i>suffix</i>, not the whole name.</li>
     * </ul>
     */
    private record MixinCheck(String label, Class<?> target, String handlerSuffix) {
    }

    private static final MixinCheck[] MIXIN_CHECKS = {
            new MixinCheck("LivingEntityDamage", net.minecraft.entity.LivingEntity.class,
                    "kindreds$scalePerkDamage"),
            new MixinCheck("LivingEntityBowSpeed", net.minecraft.entity.LivingEntity.class,
                    "kindreds$swiftDraw"),
            new MixinCheck("LivingEntityStatusEffect", net.minecraft.entity.LivingEntity.class,
                    "kindreds$unyielding"),
            new MixinCheck("PersistentProjectile",
                    net.minecraft.entity.projectile.PersistentProjectileEntity.class, "kindreds$onEntityHit"),
            new MixinCheck("Item", net.minecraft.item.Item.class, "kindreds$onFinishUsing"),
            new MixinCheck("CraftingResultSlot", net.minecraft.screen.slot.CraftingResultSlot.class,
                    "kindreds$onTakeItem"),
            new MixinCheck("PlayerAdvancementTracker", net.minecraft.advancement.PlayerAdvancementTracker.class,
                    "kindreds$onGrantCriterion"),
    };

    /** S2C payloads: the connected client must know these or the feature they carry is dead. */
    private static final Identifier[] S2C = {
            com.kindreds.network.SyncKindredDataS2C.ID.id(),
            com.kindreds.network.UnlockResultS2C.ID.id(),
            com.kindreds.network.SyncConfigS2C.ID.id(),
    };

    /** C2S payloads the server must be listening for. */
    private static final Identifier[] C2S = {
            com.kindreds.network.RequestUnlockC2S.ID.id(),
            com.kindreds.network.ActivateAbilityC2S.ID.id(),
            com.kindreds.network.SetVisionLensC2S.ID.id(),
            com.kindreds.network.OpenTreeC2S.ID.id(),
            com.kindreds.network.RespecC2S.ID.id(),
            com.kindreds.network.SetDifficultyC2S.ID.id(),
            com.kindreds.network.SetConfigFlagC2S.ID.id(),
            com.kindreds.network.TakeBargainC2S.ID.id(),
    };

    /**
     * Perks the gameplay code still reads as a yes/no rather than by rank
     * ({@code perksOfType(..).isEmpty()}). Granting one of these twice is identical to granting it
     * once, so a second node spends a point on nothing. Keep in step with the call sites: when a perk
     * is converted to {@code PerkService.rankOf}, drop it from here.
     */
    private static final Set<String> BOOLEAN_PERKS = Set.of("auto_smelt", "ore_magnet");

    /** Runs every check, reporting to {@code source} and to the log. Returns the number of problems. */
    public static int run(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        List<String> problems = new ArrayList<>();

        line(source, Text.literal("Kindreds self-check").formatted(Formatting.GOLD, Formatting.BOLD));

        checkMixins(source, problems);
        checkPayloads(source, server, problems);
        checkDeeds(source, server, problems);
        checkTrees(source, server, problems);
        checkAbilities(source, server, problems);
        checkClamps(source, server, problems);
        checkStacking(source, server, problems);
        checkPlayer(source, problems);

        if (problems.isEmpty()) {
            line(source, Text.literal("  all checks passed").formatted(Formatting.GREEN));
        } else {
            line(source, Text.literal("  " + problems.size() + " problem(s) - full detail in latest.log")
                    .formatted(Formatting.RED));
            for (String p : problems) {
                Kindreds.LOGGER.warn("[Kindreds doctor] {}", p);
            }
        }
        return problems.size();
    }

    // --- checks ---------------------------------------------------------------------------------

    /** Confirms each mixin's handler method was actually merged into its target class. */
    private static void checkMixins(ServerCommandSource source, List<String> problems) {
        int live = 0;
        List<String> missing = new ArrayList<>();
        for (MixinCheck check : MIXIN_CHECKS) {
            if (hasMethod(check.target(), check.handlerSuffix())) {
                live++;
            } else {
                missing.add(check.label());
                problems.add("mixin not applied: " + check.label() + " (no handler ending in "
                        + check.handlerSuffix() + " on " + check.target().getName() + ")");
            }
        }
        // Accessor mixins merge an interface rather than a method, so they are confirmed differently.
        boolean hunger = com.kindreds.mixin.HungerManagerAccessor.class
                .isAssignableFrom(net.minecraft.entity.player.HungerManager.class);
        if (!hunger) {
            missing.add("HungerManagerAccessor");
            problems.add("mixin not applied: HungerManagerAccessor (HungerManager does not implement it)");
        }
        report(source, "mixins", (live + (hunger ? 1 : 0)) + "/" + (MIXIN_CHECKS.length + 1) + " live",
                missing.isEmpty() ? null : "missing: " + String.join(", ", missing));
    }

    /** Confirms the server is listening for every C2S payload, and that this client accepts every S2C
     * one (a mismatch here is exactly what a version-skewed client looks like). */
    private static void checkPayloads(ServerCommandSource source, MinecraftServer server, List<String> problems) {
        Set<Identifier> receivers = ServerPlayNetworking.getGlobalReceivers();
        List<String> missing = new ArrayList<>();
        for (Identifier id : C2S) {
            if (!receivers.contains(id)) {
                missing.add(id.getPath());
                problems.add("no server receiver registered for C2S payload " + id);
            }
        }
        report(source, "packets", (C2S.length - missing.size()) + "/" + C2S.length + " receiving",
                missing.isEmpty() ? null : "missing: " + String.join(", ", missing));

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return; // console: no client to interrogate
        }
        List<String> unknown = new ArrayList<>();
        for (Identifier id : S2C) {
            if (!ServerPlayNetworking.canSend(player, id)) {
                unknown.add(id.getPath());
                problems.add("client cannot receive S2C payload " + id + " (client-side mod missing or stale?)");
            }
        }
        report(source, "client", (S2C.length - unknown.size()) + "/" + S2C.length + " payloads accepted",
                unknown.isEmpty() ? null : "rejected: " + String.join(", ", unknown));
    }

    /**
     * Diffs the renown advancements shipped in this jar against the ones the server actually loaded.
     * A file whose predicate the datapack loader rejected is simply absent - which is invisible in
     * play (the deed can never be earned) but obvious here.
     */
    private static void checkDeeds(ServerCommandSource source, MinecraftServer server, List<String> problems) {
        Set<String> loaded = new LinkedHashSet<>();
        for (AdvancementEntry entry : server.getAdvancementLoader().getAdvancements()) {
            // The renown root is a container node, not a deed - excluded on both sides of the diff.
            if (RenownService.isRenown(entry.id()) && !entry.id().getPath().equals("renown/root")) {
                loaded.add(entry.id().getPath());
            }
        }
        Set<String> shipped = shippedDeeds();
        if (shipped.isEmpty()) {
            report(source, "deeds", loaded.size() + " loaded", "(jar contents unreadable - cannot diff)");
            return;
        }
        List<String> rejected = new ArrayList<>(shipped);
        rejected.removeAll(loaded);
        for (String r : rejected) {
            problems.add("deed did not load: kindreds:" + r
                    + " - the datapack loader rejected it, search latest.log for its id");
        }
        report(source, "deeds", loaded.size() + "/" + shipped.size() + " loaded",
                rejected.isEmpty() ? null : "rejected: " + String.join(", ", rejected));
    }

    /** Per-race tree health: node counts, dangling prereqs, and the cap the tree resolves to. */
    private static void checkTrees(ServerCommandSource source, MinecraftServer server, List<String> problems) {
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        int treeCount = 0;
        int nodeCount = 0;
        int dangling = 0;
        TreeMap<String, String> caps = new TreeMap<>();
        for (SkillTree tree : trees) {
            treeCount++;
            nodeCount += tree.nodes().size();
            Set<String> ids = new LinkedHashSet<>();
            for (SkillNode n : tree.nodes()) {
                ids.add(n.id());
            }
            for (SkillNode n : tree.nodes()) {
                for (String prereq : n.prereqs()) {
                    if (!ids.contains(prereq)) {
                        dangling++;
                        problems.add("dangling prereq: " + tree.race().getPath() + " node " + n.id()
                                + " requires '" + prereq + "', which is not in the tree");
                    }
                }
            }
            int max = UnlockService.maxSpendable(tree);
            int cap = UnlockService.effectiveCap(tree, new KindredData());
            caps.put(tree.race().getPath(), max + " pts -> " + (cap > 0 ? String.valueOf(cap) : "uncapped"));
        }
        report(source, "trees", treeCount + " races, " + nodeCount + " nodes",
                dangling == 0 ? null : dangling + " dangling prereq(s)");
        for (var e : caps.entrySet()) {
            line(source, Text.literal("    " + pad(e.getKey(), 8) + e.getValue()).formatted(Formatting.DARK_GRAY));
        }
    }

    /** Finds active abilities that no handler backs - they unlock, bind to a slot, and do nothing. */
    private static void checkAbilities(ServerCommandSource source, MinecraftServer server, List<String> problems) {
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        Set<String> orphans = new LinkedHashSet<>();
        int total = 0;
        for (SkillTree tree : trees) {
            for (SkillNode node : tree.nodes()) {
                for (AbilityDef ability : node.abilities()) {
                    if (!(ability instanceof ActiveAbilityDef active)) {
                        continue;
                    }
                    total++;
                    if (!ActiveAbilityHandlers.hasHandler(active.abilityId())) {
                        orphans.add(active.abilityId().getPath());
                        problems.add("active ability with no handler: " + active.abilityId()
                                + " (node " + node.id() + ") - it will unlock and do nothing");
                    }
                }
            }
        }
        report(source, "abilities", (total - orphans.size()) + "/" + total + " have handlers",
                orphans.isEmpty() ? null : "orphaned: " + String.join(", ", orphans));
    }

    /**
     * Attribute totals that a clamp would silently eat.
     *
     * <p>Every attribute has a hard range, and anything past it is discarded without a word - a node
     * that pushes a stat beyond its ceiling costs a point and changes nothing. The bound is read from
     * the attribute itself via {@code clamp()}, so this stays correct for vanilla attributes, the base
     * mod's, and anything a datapack adds, with no table here to fall out of date.
     *
     * <p>Worst case per race: every node, except that only one member of each exclusive group can be
     * owned (the one pushing the attribute furthest is assumed taken).
     */
    private static void checkClamps(ServerCommandSource source, MinecraftServer server, List<String> problems) {
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        Set<String> absentProviders = new java.util.TreeSet<>();
        int overflowing = 0;
        for (SkillTree tree : trees) {
            Map<Identifier, Double> flat = new java.util.HashMap<>();
            Map<Identifier, Double> scaled = new java.util.HashMap<>();
            Map<String, Map<Identifier, Double>> groupBest = new java.util.HashMap<>();

            for (SkillNode node : tree.nodes()) {
                Map<Identifier, Double> flatHere = new java.util.HashMap<>();
                Map<Identifier, Double> scaledHere = new java.util.HashMap<>();
                for (AbilityDef ability : node.abilities()) {
                    if (!(ability instanceof AttributeMod mod)) {
                        continue;
                    }
                    (mod.operation().contains("multiplied") ? scaledHere : flatHere)
                            .merge(mod.attribute(), mod.amount(), Double::sum);
                }
                if (flatHere.isEmpty() && scaledHere.isEmpty()) {
                    continue;
                }
                String group = node.exclusiveGroup().orElse(null);
                if (group == null) {
                    flatHere.forEach((k, v) -> flat.merge(k, v, Double::sum));
                    scaledHere.forEach((k, v) -> scaled.merge(k, v, Double::sum));
                } else {
                    // keep whichever rival moves things furthest - that is the reachable worst case
                    Map<Identifier, Double> best = groupBest.get(group);
                    double here = flatHere.values().stream().mapToDouble(Math::abs).sum()
                            + scaledHere.values().stream().mapToDouble(Math::abs).sum();
                    double there = best == null ? -1
                            : best.values().stream().mapToDouble(Math::abs).sum();
                    if (here > there) {
                        Map<Identifier, Double> merged = new java.util.HashMap<>(flatHere);
                        scaledHere.forEach((k, v) -> merged.merge(k, v, Double::sum));
                        groupBest.put(group, merged);
                    }
                }
            }
            for (Map<Identifier, Double> best : groupBest.values()) {
                best.forEach((k, v) -> flat.merge(k, v, Double::sum));
            }

            for (Map.Entry<Identifier, Double> e : flat.entrySet()) {
                var entry = net.minecraft.registry.Registries.ATTRIBUTE.getEntry(e.getKey()).orElse(null);
                if (entry == null) {
                    // An attribute from another mod is only a DEFECT if that mod is present and simply
                    // has no such id (a typo, or a rename). If the providing mod is absent, the trees
                    // are fine and the install is merely missing an optional dependency - said once,
                    // plainly, rather than counted as eight broken races.
                    String namespace = e.getKey().getNamespace();
                    if (!FabricLoader.getInstance().isModLoaded(namespace)) {
                        absentProviders.add(namespace);
                    } else {
                        problems.add("attribute " + e.getKey() + " does not exist though " + namespace
                                + " is loaded - every modifier of it in " + tree.race().getPath()
                                + " is discarded");
                    }
                    continue;
                }
                var attribute = entry.value();
                double total = attribute.getDefaultValue() + e.getValue();
                total *= 1 + scaled.getOrDefault(e.getKey(), 0.0);
                double allowed = attribute.clamp(total);
                if (Math.abs(allowed - total) > 1e-6) {
                    overflowing++;
                    problems.add(String.format(
                            "%s reaches %.2f %s but it clamps to %.2f - %.2f of it is spent on nothing",
                            tree.race().getPath(), total, e.getKey(), allowed, Math.abs(total - allowed)));
                }
            }
        }
        report(source, "clamps", overflowing == 0 ? "all attributes fit" : overflowing + " overflowing",
                overflowing == 0 ? null : "see the log for which race and which attribute");
        if (!absentProviders.isEmpty()) {
            line(source, Text.literal("    attributes from " + String.join(", ", absentProviders)
                    + " skipped - that mod is not installed").formatted(Formatting.YELLOW));
        }
    }

    /**
     * Data that looks stacked but cannot stack: a perk the code reads as a yes/no granted more than
     * once, and two contextual boons filling the same context/effect/amplifier slot (status effects do
     * not stack, so only one is ever felt). Rivals inside one exclusive group are alternatives, not
     * duplicates, and are not counted.
     */
    private static void checkStacking(ServerCommandSource source, MinecraftServer server, List<String> problems) {
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        int redundant = 0;
        for (SkillTree tree : trees) {
            Map<String, List<String>> perkSources = new java.util.TreeMap<>();
            Map<String, List<String>> boonSlots = new java.util.TreeMap<>();
            Map<String, Set<String>> groupsFor = new java.util.HashMap<>();

            for (SkillNode node : tree.nodes()) {
                String group = node.exclusiveGroup().orElse("");
                for (AbilityDef ability : node.abilities()) {
                    if (ability instanceof PerkDef perk && BOOLEAN_PERKS.contains(perk.perk())) {
                        perkSources.computeIfAbsent(perk.perk(), k -> new ArrayList<>()).add(node.id());
                        groupsFor.computeIfAbsent("perk/" + perk.perk(), k -> new java.util.HashSet<>()).add(group);
                    } else if (ability instanceof ContextualBoon boon
                            && boon.effect() instanceof StatusEffectDef effect) {
                        String slot = boon.when() + "/" + effect.effect() + "/" + effect.amplifier();
                        boonSlots.computeIfAbsent(slot, k -> new ArrayList<>()).add(node.id());
                        groupsFor.computeIfAbsent("boon/" + slot, k -> new java.util.HashSet<>()).add(group);
                    }
                }
            }

            redundant += flagDuplicates(tree, "perk", perkSources, groupsFor, problems,
                    " - the code reads it as a yes/no, so the extra grants change nothing");
            redundant += flagDuplicates(tree, "boon", boonSlots, groupsFor, problems,
                    " - status effects do not stack, so only one of these is ever felt");
        }
        report(source, "stacking", redundant == 0 ? "nothing redundant" : redundant + " redundant grant(s)",
                redundant == 0 ? null : "see the log for which nodes");
    }

    private static int flagDuplicates(SkillTree tree, String kind, Map<String, List<String>> sources,
                                      Map<String, Set<String>> groupsFor, List<String> problems, String why) {
        int count = 0;
        for (Map.Entry<String, List<String>> e : sources.entrySet()) {
            List<String> nodes = e.getValue();
            if (nodes.size() < 2) {
                continue;
            }
            Set<String> groups = groupsFor.getOrDefault(kind + "/" + e.getKey(), Set.of());
            if (groups.size() == 1 && !groups.contains("")) {
                continue; // mutually exclusive rivals: alternatives, not duplicates
            }
            count += nodes.size() - 1;
            problems.add(tree.race().getPath() + ": " + kind + " " + e.getKey() + " granted by "
                    + nodes.size() + " nodes " + nodes + why);
        }
        return count;
    }

    /** The calling player\'s own progression state, which is what most "is it working?" questions
     * are really about. */
    private static void checkPlayer(ServerCommandSource source, List<String> problems) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return;
        }
        KindredData data = KindredAttachment.get(player);
        Identifier race = data.race();
        line(source, Text.literal("  " + pad("config", 11)
                + "difficulty " + String.valueOf(Kindreds.CONFIG.difficulty).toLowerCase(Locale.ROOT)
                + ", cap " + Kindreds.CONFIG.pointCapPercent + "%"
                + ", xp x" + Kindreds.CONFIG.xpRateGlobal).formatted(Formatting.GRAY));
        line(source, Text.literal("  " + pad("you", 11)
                + (race == null ? "no race resolved" : race.getPath())
                + ", " + RenownService.deedsForRace(data) + "/4 deeds"
                + (com.kindreds.ability.CorruptionService.hasBargained(data) ? ", bargained" : "")
                + ", +" + RenownService.bonusPercent(data) + "% earned").formatted(Formatting.GRAY));
        if (race == null) {
            problems.add("this player's race did not resolve from the base Middle-earth mod - "
                    + "no tree, no traits, no renown will apply");
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Deed ids shipped inside this jar, read from the mod container so the expected set can never
     * drift out of sync with the files actually present. */
    private static Set<String> shippedDeeds() {
        Set<String> found = new LinkedHashSet<>();
        var container = FabricLoader.getInstance().getModContainer(Kindreds.MOD_ID).orElse(null);
        if (container == null) {
            return found;
        }
        Path root = container.findPath("data/" + Kindreds.MOD_ID + "/advancement/renown").orElse(null);
        if (root == null) {
            return found;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                rel = rel.substring(0, rel.length() - ".json".length());
                if (!rel.equals("root")) {
                    found.add("renown/" + rel);
                }
            });
        } catch (Exception e) {
            Kindreds.LOGGER.warn("[Kindreds doctor] could not read shipped deeds from the mod jar", e);
        }
        return found;
    }

    private static boolean hasMethod(Class<?> target, String handlerSuffix) {
        for (Method m : target.getDeclaredMethods()) {
            if (m.getName().endsWith(handlerSuffix)) {
                return true;
            }
        }
        return false;
    }

    private static void report(ServerCommandSource source, String label, String value, String warning) {
        line(source, Text.literal("  " + pad(label, 11) + value)
                .formatted(warning == null ? Formatting.GREEN : Formatting.RED));
        if (warning != null) {
            line(source, Text.literal("    " + warning).formatted(Formatting.RED));
        }
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) {
            b.append(' ');
        }
        return b.toString();
    }

    private static void line(ServerCommandSource source, Text text) {
        source.sendFeedback(() -> text, false);
        Kindreds.LOGGER.info("[Kindreds doctor] {}", text.getString().strip());
    }
}
