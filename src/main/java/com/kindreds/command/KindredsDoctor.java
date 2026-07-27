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
import com.kindreds.threat.EliteMobs;
import com.kindreds.threat.MobMark;
import com.kindreds.threat.MobScaler;
import com.kindreds.threat.ThreatRank;
import com.kindreds.threat.ThreatService;
import com.kindreds.threat.ThreatState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.ClampedEntityAttribute;
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
 *
 * <p><b>Deliberate exception to the "no user-facing English in Java" rule:</b> every line this class
 * prints is literal dev-English, never localized. The doctor is a diagnostic for whoever runs the
 * server, its output is meant to be pasted into bug reports verbatim, and localizing it would make
 * those reports untranslatable back. Comments elsewhere in this class citing "the class javadoc" for
 * this exception mean this paragraph.
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
            new MixinCheck("MobEntityInitialize", net.minecraft.entity.mob.MobEntity.class,
                    "kindreds$captureSpawnReason"),
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
        checkPhase2Dials(source, problems);
        checkDetectionSpan(source, problems);
        checkMobMark(source, problems);
        checkEscortYardstick(source, problems);
        checkEliteAbilities(source, problems);
        checkPlayer(source, problems);
        checkThreat(source);

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

        // A deed the Deeds page cannot describe is a deed the player is told to do and not told how.
        // The decoder returns nothing rather than a guess for a criterion shape it does not know, so
        // this is where that silence gets a voice.
        java.util.Map<String, net.minecraft.text.Text> described =
                com.kindreds.progression.DeedIndex.requirements(server);
        List<String> mute = new ArrayList<>(loaded);
        mute.removeAll(described.keySet());
        for (String m : mute) {
            problems.add("deed has no readable requirement: kindreds:" + m
                    + " - its criteria use a shape DeedIndex cannot describe, so the Deeds page"
                    + " shows its riddle with no plain line beneath");
        }
        report(source, "deed hints", described.size() + "/" + loaded.size() + " describable",
                mute.isEmpty() ? null : "mute: " + String.join(", ", mute));
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
            Map<String, List<String>> attrSlots = new java.util.TreeMap<>();
            Map<String, Set<String>> groupsFor = new java.util.HashMap<>();

            for (SkillNode node : tree.nodes()) {
                String group = node.exclusiveGroup().orElse("");
                for (AbilityDef ability : node.abilities()) {
                    if (ability instanceof PerkDef perk && BOOLEAN_PERKS.contains(perk.perk())) {
                        perkSources.computeIfAbsent(perk.perk(), k -> new ArrayList<>()).add(node.id());
                        groupsFor.computeIfAbsent("perk/" + perk.perk(), k -> new java.util.HashSet<>()).add(group);
                    } else if (ability instanceof AttributeMod mod) {
                        // A plain attribute modifier is tagged node/<id>/<attr>, so a node touching one
                        // attribute twice installs the second over the first and loses it in silence.
                        // (Contextual ones are keyed by ability index and cannot collide this way.)
                        String slot = "self/" + mod.attribute().getPath();
                        attrSlots.computeIfAbsent(slot, k -> new ArrayList<>()).add(node.id());
                        groupsFor.computeIfAbsent("attribute/" + slot, k -> new java.util.HashSet<>()).add(group);
                    } else {
                        // A boon and a curse compete for the same status-effect slot, so they are
                        // counted together: neither stacks with an equal effect at an equal amplifier.
                        AbilityDef inner = ability instanceof ContextualBoon boon ? boon.effect()
                                : ability instanceof com.kindreds.data.ability.CurseDef curse
                                        ? curse.effect().orElse(null) : null;
                        String when = ability instanceof ContextualBoon boon ? boon.when()
                                : ability instanceof com.kindreds.data.ability.CurseDef curse
                                        ? (curse.when().isEmpty() ? "always" : curse.when()) : null;
                        if (inner instanceof StatusEffectDef effect) {
                            String slot = when + "/" + effect.effect() + "/" + effect.amplifier();
                            boonSlots.computeIfAbsent(slot, k -> new ArrayList<>()).add(node.id());
                            groupsFor.computeIfAbsent("boon/" + slot, k -> new java.util.HashSet<>()).add(group);
                        }
                    }
                }
            }

            // Two nodes granting the SAME ability at the same cooldown with the same effects: the
            // engine picks the strongest owned version, so identical twins leave one of them with
            // nothing to contribute. (A weaker version is fine - it is a stepping stone you may own
            // on its own.)
            Map<String, List<String>> abilityTwins = new java.util.TreeMap<>();
            for (SkillNode node : tree.nodes()) {
                for (AbilityDef ability : node.abilities()) {
                    if (ability instanceof ActiveAbilityDef active) {
                        String shape = active.abilityId() + "/cd" + active.cooldownTicks()
                                + "/x" + active.effects().size();
                        abilityTwins.computeIfAbsent(shape, k -> new ArrayList<>()).add(node.id());
                        groupsFor.computeIfAbsent("ability/" + shape, k -> new java.util.HashSet<>())
                                .add(node.exclusiveGroup().orElse(""));
                    }
                }
            }
            redundant += flagDuplicates(tree, "ability", abilityTwins, groupsFor, problems,
                    " - identical version of the same ability, so owning both is owning one");

            redundant += flagDuplicates(tree, "perk", perkSources, groupsFor, problems,
                    " - the code reads it as a yes/no, so the extra grants change nothing");
            redundant += flagDuplicates(tree, "boon", boonSlots, groupsFor, problems,
                    " - status effects do not stack, so only one of these is ever felt");
            // Within a node only: two different nodes raising the same attribute is ordinary stacking,
            // since their modifier ids differ by node.
            redundant += flagSelfDuplicates(tree, "attribute", attrSlots, problems,
                    " - one node's modifiers share an id per attribute, so the later replaces the earlier");
        }
        report(source, "stacking", redundant == 0 ? "nothing redundant" : redundant + " redundant grant(s)",
                redundant == 0 ? null : "see the log for which nodes");
    }

    private static Map<String, Integer> countPerNode(List<String> nodes) {
        Map<String, Integer> perNode = new java.util.LinkedHashMap<>();
        for (String node : nodes) {
            perNode.merge(node, 1, Integer::sum);
        }
        return perNode;
    }

    /** Only the within-a-node case, for slots where two <em>different</em> nodes stack quite happily. */
    private static int flagSelfDuplicates(SkillTree tree, String kind, Map<String, List<String>> sources,
                                          List<String> problems, String why) {
        int count = 0;
        for (Map.Entry<String, List<String>> e : sources.entrySet()) {
            count += flagSelfDuplicates(tree, kind, e.getKey(), countPerNode(e.getValue()), problems, why);
        }
        return count;
    }

    private static int flagSelfDuplicates(SkillTree tree, String kind, String slot,
                                          Map<String, Integer> perNode, List<String> problems, String why) {
        int count = 0;
        for (Map.Entry<String, Integer> n : perNode.entrySet()) {
            if (n.getValue() > 1) {
                count += n.getValue() - 1;
                problems.add(tree.race().getPath() + ": " + kind + " " + slot + " granted "
                        + n.getValue() + " times by the one node " + n.getKey() + why);
            }
        }
        return count;
    }

    private static int flagDuplicates(SkillTree tree, String kind, Map<String, List<String>> sources,
                                      Map<String, Set<String>> groupsFor, List<String> problems, String why) {
        int count = 0;
        for (Map.Entry<String, List<String>> e : sources.entrySet()) {
            List<String> nodes = e.getValue();
            if (nodes.size() < 2) {
                continue;
            }

            // A node granting the same thing twice is redundant with itself, and no exclusive group
            // excuses it - a node is never its own rival. Checked before the group rule, because that
            // rule was hiding exactly this case: Snaga's lane capstone granted Speed II twice, and
            // every source being one node in one group read as "mutually exclusive, fine".
            Map<String, Integer> perNode = countPerNode(nodes);
            count += flagSelfDuplicates(tree, kind, e.getKey(), perNode, problems, why);

            List<String> distinct = new ArrayList<>(perNode.keySet());
            if (distinct.size() < 2) {
                continue;
            }
            Set<String> groups = groupsFor.getOrDefault(kind + "/" + e.getKey(), Set.of());
            if (groups.size() == 1 && !groups.contains("")) {
                continue; // mutually exclusive rivals: alternatives, not duplicates
            }
            count += distinct.size() - 1;
            problems.add(tree.race().getPath() + ": " + kind + " " + e.getKey() + " granted by "
                    + distinct.size() + " nodes " + distinct + why);
        }
        return count;
    }

    /**
     * Phase-2's exposed dials, sanity-checked against the ranges {@code KindredsCommand#configSet}
     * enforces at write time (spec §6). This does not re-implement that validation - it exists for
     * the case that validation is bypassed entirely: a config JSON hand-edited on disk, or a future
     * regression in {@code configSet} itself that stops rejecting an out-of-range value before it is
     * stored. A currently-loaded value outside its documented range is exactly what either failure
     * mode looks like.
     */
    private static void checkPhase2Dials(ServerCommandSource source, List<String> problems) {
        if (Kindreds.CONFIG == null) {
            report(source, "phase2 dials", "not loaded", "Kindreds.CONFIG is null");
            problems.add("phase-2 dials: Kindreds.CONFIG is null, cannot check ranges");
            return;
        }
        // Bounds read from RuleDial rather than from literals repeated here - and they had already
        // drifted: this still called maxHealthBonus out of range above 400 and the dimension
        // multipliers above 2.0 after the screen and the command had been widened, so the diagnostic
        // reported a correctly-configured server as broken. A checker with its own copy of the rules
        // is a second source of truth, which is the one thing a checker must never be.
        List<String> outOfRange = new ArrayList<>();
        for (com.kindreds.config.RuleDial dial : com.kindreds.config.RuleDial.values()) {
            dialInRange(outOfRange, problems, dial.key, dial.read(Kindreds.CONFIG), dial.min, dial.max);
        }
        int total = com.kindreds.config.RuleDial.values().length;
        report(source, "phase2 dials", (total - outOfRange.size()) + "/" + total + " within range",
                outOfRange.isEmpty() ? null : "out of range: " + String.join(", ", outOfRange));
    }

    private static void dialInRange(List<String> outOfRange, List<String> problems, String name,
                                    double value, double min, double max) {
        if (value < min || value > max) {
            outOfRange.add(name + "=" + value);
            problems.add(name + " is " + value + ", outside its allowed [" + min + "," + max + "] range");
        }
    }

    private static void dialInRange(List<String> outOfRange, List<String> problems, String name,
                                    float value, float min, float max) {
        if (value < min || value > max) {
            outOfRange.add(name + "=" + value);
            problems.add(name + " is " + value + ", outside its allowed [" + min + "," + max + "] range");
        }
    }

    /**
     * {@code ThreatService#refresh}'s detection modifier reaches for the base mod's
     * {@code middle-earth:detection_range} attribute with a fixed span of {@code 0.9} (the amount at
     * full threat) - the attribute's OWN clamp width is what actually bounds it in play (spec §3: at
     * full threat the counter cancels the deepest stealth build exactly, never past baseline). If a
     * future base-mod update widened that attribute past {@code [0.1, 1.0]} without this mod's dial
     * following it, {@code 0.9} would silently stop being "the whole span" and detection would cap
     * out below baseline instead of exactly at it. Skips silently (not a problem) if the base mod, or
     * the attribute itself, is absent - phase 2 is meant to degrade cleanly without it.
     */
    private static void checkDetectionSpan(ServerCommandSource source, List<String> problems) {
        final double detectionSpan = ThreatService.DETECTION_SPAN;
        Identifier id = Identifier.of("middle-earth", "detection_range");
        var entry = net.minecraft.registry.Registries.ATTRIBUTE.getEntry(id).orElse(null);
        if (entry == null) {
            line(source, Text.literal("  " + pad("detection", 11)
                    + "(middle-earth:detection_range not registered - base mod absent)")
                    .formatted(Formatting.DARK_GRAY));
            return;
        }
        if (!(entry.value() instanceof ClampedEntityAttribute clamped)) {
            problems.add("middle-earth:detection_range exists but is not a ClampedEntityAttribute - "
                    + "the [0.1,1.0] clamp ThreatService#refresh assumes may not hold");
            report(source, "detection", "not a ClampedEntityAttribute",
                    "actual type " + entry.value().getClass().getSimpleName());
            return;
        }
        double width = clamped.getMaxValue() - clamped.getMinValue();
        boolean ok = detectionSpan <= width + 1e-9;
        report(source, "detection", String.format(Locale.ROOT,
                        "span %.2f within [%.2f,%.2f] (width %.2f)",
                        detectionSpan, clamped.getMinValue(), clamped.getMaxValue(), width),
                ok ? null : "the " + detectionSpan + " detection span exceeds the attribute's own clamp "
                        + "width " + width + " - the counter can no longer cancel a full stealth build "
                        + "at full threat");
        if (!ok) {
            problems.add("detection counter span " + detectionSpan
                    + " exceeds middle-earth:detection_range's clamp width " + width);
        }
    }

    /** Confirms {@link MobMark#KEY} is actually a registered, persistent attachment type - the same
     * lazy-static-final hazard {@code KindredAttachment.TYPE} once had (see {@link MobMark#init()}'s
     * javadoc for the failure mode this guards against). Calling {@code init()} here is itself a
     * genuine use (forces the class to load if nothing has yet), so a broken registration would show
     * up as a null or wrongly-shaped {@link net.fabricmc.fabric.api.attachment.v1.AttachmentType}. */
    private static void checkMobMark(ServerCommandSource source, List<String> problems) {
        var key = MobMark.init();
        boolean ok = key != null && key.isPersistent()
                && key.identifier().equals(Identifier.of(Kindreds.MOD_ID, "mob_mark"));
        report(source, "mob mark", ok ? "kindreds:mob_mark registered, persistent" : "NOT registered",
                ok ? null : "MobMark.KEY did not resolve to the expected persistent attachment type");
        if (!ok) {
            problems.add("MobMark.KEY is not a registered persistent attachment (kindreds:mob_mark) - "
                    + "scaled/elite/escort marks would not survive a chunk reload");
        }
    }

    /**
     * Recomputes {@code SpawnGroup.MONSTER.getCapacity()} and {@code MobScaler#escortBudget} against
     * the exact numbers {@code MobScalerEscortTest} was written against (see {@code MobScaler}'s own
     * comment) - the tripwire for its hardcoded {@code 289} ({@code SpawnHelper.CHUNK_AREA}, package-
     * private in vanilla and so unreachable directly). If a Minecraft update ever changes the monster
     * cap or the chunk-area constant this formula assumes, this check drifts into red instead of
     * escorts silently spawning at the wrong rate forever.
     */
    private static void checkEscortYardstick(ServerCommandSource source, List<String> problems) {
        int capacity = SpawnGroup.MONSTER.getCapacity();
        boolean capacityOk = capacity == 70;
        if (!capacityOk) {
            problems.add("SpawnGroup.MONSTER.getCapacity() is " + capacity + ", not the 70 the 289 "
                    + "CHUNK_AREA tripwire was written against - MobScaler.escortBudget's hardcoded "
                    + "289 needs re-deriving against vanilla's real SpawnHelper.CHUNK_AREA");
        }
        int budget = MobScaler.escortBudget(56, 70, 289);
        boolean budgetOk = budget == 0;
        if (!budgetOk) {
            problems.add("MobScaler.escortBudget(56, 70, 289) returned " + budget + ", expected 0 - "
                    + "the hardcoded 289 constant has silently drifted from what SpawnHelper actually "
                    + "uses");
        }
        report(source, "escort cap", "capacity=" + capacity + ", escortBudget(56,70,289)=" + budget,
                (capacityOk && budgetOk) ? null : "the 289 CHUNK_AREA tripwire tripped - see the log");
    }

    /** Every elite ability id the promotion pool can hand out must resolve via
     * {@link EliteMobs#abilityFor} - its first caller, and the same check {@code MobMark#eliteAbility}
     * is meant to be validated against rather than trusted blindly (spec §3, Task 4's carry-forward). */
    private static void checkEliteAbilities(ServerCommandSource source, List<String> problems) {
        List<String> pool = EliteMobs.abilityPool();
        List<String> unresolved = new ArrayList<>();
        for (String id : pool) {
            if (!EliteMobs.abilityFor(id)) {
                unresolved.add(id);
                problems.add("elite ability id '" + id + "' does not resolve via EliteMobs.abilityFor "
                        + "- a promoted mob carrying it would be trusted blindly");
            }
        }
        report(source, "elite abils", (pool.size() - unresolved.size()) + "/" + pool.size() + " resolve",
                unresolved.isEmpty() ? null : "unresolved: " + String.join(", ", unresolved));
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

    /**
     * "Why is the world like this" for the calling player: the resolved threat, its rank, and the
     * high-water figures {@link ThreatState} actually retains.
     *
     * <p>Deliberately does NOT report the three raw prior components (commitment/gear/renown) that
     * feed {@code ThreatService#refresh} - {@code commitmentOf}/{@code gearOf} are private, ephemeral
     * (recomputed from live player state every refresh, never retained), and adding public plumbing
     * to {@code ThreatService} just so the doctor could read them once would be new surface for one
     * diagnostic line. {@code priorMark} (the blended, decayed figure those three feed into) and
     * {@code competence} are the components {@link ThreatState} actually persists, so those are what
     * is reported; renown alone is cheaply and correctly available via
     * {@link RenownService#deedsForRace}, so it is shown too. Informational only - a low/high threat
     * is not itself a defect, so this never adds to {@code problems}.
     */
    private static void checkThreat(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            // Dev-English is deliberate here, same exception as the rest of this class (see the class
            // javadoc) - silently skipping left a console-run doctor looking like this check just
            // never existed, rather than naming why it did nothing.
            line(source, Text.literal("  " + pad("threat", 11)
                    + "(needs a calling player - run /kindreds doctor as a player, not from console)")
                    .formatted(Formatting.DARK_GRAY));
            return;
        }
        KindredData data = KindredAttachment.get(player);
        ThreatState state = data.threat();
        float threat = ThreatService.threatOf(player);
        ThreatRank rank = ThreatRank.of(threat);
        line(source, Text.literal("  " + pad("threat", 11)
                + String.format(Locale.ROOT, "%.1f/100", threat)
                + " (" + rank.name().toLowerCase(Locale.ROOT) + ")"
                + ", prior mark " + String.format(Locale.ROOT, "%.1f", state.priorMark())
                + ", competence " + String.format(Locale.ROOT, "%.2f", state.competence())
                + ", renown " + RenownService.deedsForRace(data) + "/4").formatted(Formatting.GRAY));
        line(source, Text.literal("    commitment/gear are not separately reported - "
                + "ThreatService recomputes them live each refresh and does not retain them, only "
                + "the blended prior mark above").formatted(Formatting.DARK_GRAY));
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
