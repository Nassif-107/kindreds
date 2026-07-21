package com.kindreds.command;

import com.kindreds.Kindreds;
import com.kindreds.config.DeathPenalty;
import com.kindreds.config.KindredsConfig;
import com.kindreds.data.BirthTrait;
import com.kindreds.data.Disciplines;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillTree;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import com.kindreds.progression.ProgressionService;
import com.kindreds.progression.RespecService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /kindreds} - the in-game inspection/test/admin command for Kindreds of Middle-earth.
 * Registered via {@link CommandRegistrationCallback#EVENT} (Fabric's server-side Brigadier hook;
 * fires for both dedicated and integrated servers).
 *
 * <h2>Subcommands</h2>
 * <ul>
 *   <li>{@code /kindreds inspect [player]} - anyone can inspect themselves (op not required); a
 *       race/discipline/node dump used as the primary play-test tool.</li>
 *   <li>{@code /kindreds grantxp <discipline> <amount> [player]} (op level 2, and
 *       <b>disabled by default</b> - see {@code allowGrantXp}) - awards xp for testing progression
 *       without grinding.</li>
 *   <li>{@code /kindreds doctor} (op level 2) - self-check: are the mixins applied, the payloads
 *       agreed with the client, the deeds loaded, the trees sound, the abilities handled? Prints a
 *       short report and logs the detail.</li>
 *   <li>{@code /kindreds reload} (op level 2) - reloads {@link Kindreds#CONFIG} from
 *       {@code <configDir>/kindreds-server.json}.</li>
 *   <li>{@code /kindreds respec [player]} (op level 2) - admin/mechanism respec, via
 *       {@link RespecService#reverseAll}. The player-facing respec UI + item cost
 *       ({@code RespecC2S}) shares that same reversal logic; this is the admin escape hatch.</li>
 * </ul>
 *
 * <h2>Discipline argument</h2>
 * {@code <discipline>} accepts the short path of one of the 7 built-in ids ({@code combat},
 * {@code archery}, {@code mining}, {@code stealth}, {@code smithing}, {@code survival},
 * {@code lore}) - i.e. without the {@code kindreds:} namespace, matching how a command-line user
 * would expect to type it. {@link Disciplines#ALL} (shared with the skill-tree UI's discipline
 * gauges, so the two can't desync) both drives suggestions and validates the input; the full
 * {@link Identifier} is reconstructed as {@code kindreds:<word>}.
 */
public final class KindredsCommand {
    private KindredsCommand() {
    }

    private static final SuggestionProvider<ServerCommandSource> DISCIPLINE_SUGGESTIONS =
            (context, builder) -> CommandSource.suggestMatching(Disciplines.ALL, builder);

    /** Registers the command. Call once from {@link Kindreds#onInitialize()}. */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerTree(dispatcher));
    }

    private static void registerTree(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("kindreds")
                .then(CommandManager.literal("inspect")
                        .executes(ctx -> inspect(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> inspect(ctx.getSource(), EntityArgumentType.getPlayer(ctx, "player")))))
                .then(CommandManager.literal("grantxp")
                        // requires() hides it from tab-completion; grantXp() re-checks the flag,
                        // because a client's command tree can be stale after a config change.
                        .requires(source -> source.hasPermissionLevel(2)
                                && Kindreds.CONFIG != null && Kindreds.CONFIG.allowGrantXp)
                        .then(CommandManager.argument("discipline", StringArgumentType.word())
                                .suggests(DISCIPLINE_SUGGESTIONS)
                                .then(CommandManager.argument("amount", LongArgumentType.longArg())
                                        .executes(ctx -> grantXp(ctx.getSource(), ctx.getSource().getPlayerOrThrow(),
                                                StringArgumentType.getString(ctx, "discipline"),
                                                LongArgumentType.getLong(ctx, "amount")))
                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                .executes(ctx -> grantXp(ctx.getSource(),
                                                        EntityArgumentType.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "discipline"),
                                                        LongArgumentType.getLong(ctx, "amount")))))))
                .then(CommandManager.literal("doctor")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> KindredsDoctor.run(ctx.getSource())))
                .then(CommandManager.literal("reload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> reload(ctx.getSource())))
                .then(CommandManager.literal("respec")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> respec(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> respec(ctx.getSource(), EntityArgumentType.getPlayer(ctx, "player")))))
                .then(CommandManager.literal("difficulty")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> difficultyShow(ctx.getSource()))
                        .then(CommandManager.argument("preset", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (com.kindreds.config.Difficulty d : com.kindreds.config.Difficulty.values()) {
                                        b.suggest(d.name().toLowerCase(Locale.ROOT));
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> difficultySet(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "preset")))))
                .then(CommandManager.literal("config")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> configList(ctx.getSource()))
                        .then(CommandManager.argument("key", StringArgumentType.word())
                                .suggests(CONFIG_KEY_SUGGESTIONS)
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> configSet(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "key"),
                                                StringArgumentType.getString(ctx, "value")))))));
    }

    // --- inspect -----------------------------------------------------------------------------

    private static int inspect(ServerCommandSource source, ServerPlayerEntity target) {
        KindredData data = KindredAttachment.get(target);
        Optional<Identifier> race = RaceAccess.getRace(target);
        Optional<SkillTree> tree = race.flatMap(r -> findTreeForRace(source.getServer(), r));
        String targetName = target.getGameProfile().getName();

        source.sendFeedback(() -> Text.literal("=== Kindreds inspect: " + targetName + " ==="), false);
        source.sendFeedback(() -> Text.literal("Race: " + race.map(Identifier::toString).orElse("none")), false);

        race.flatMap(r -> findBirthTrait(source.getServer(), r)).ifPresent(bt -> {
            source.sendFeedback(() -> Text.literal("Birth traits (applied: "
                    + (data.appliedBirthRace() != null) + "):"), false);
            for (String plus : bt.pluses()) {
                source.sendFeedback(() -> Text.literal("  + ").append(Text.translatable(plus)), false);
            }
            for (String minus : bt.minuses()) {
                source.sendFeedback(() -> Text.literal("  - ").append(Text.translatable(minus)), false);
            }
        });

        // Live attribute readout - the ground truth. Attribute modifiers (speed, attack, etc.) never
        // show in the effects HUD (only status effects do), so this is how to verify they're applied:
        // 'current' already includes the base mod's base value AND our modifiers stacked on top.
        source.sendFeedback(() -> Text.literal("Live attributes (base -> current, incl. base mod + Kindred):"), false);
        printAttr(source, target, "minecraft:max_health", "max_health");
        printAttr(source, target, "minecraft:movement_speed", "movement_speed");
        printAttr(source, target, "minecraft:attack_damage", "attack_damage");
        printAttr(source, target, "minecraft:attack_speed", "attack_speed");
        printAttr(source, target, "minecraft:knockback_resistance", "knockback_resist");
        printAttr(source, target, "middle-earth:climbing_strength", "climbing_strength");
        printAttr(source, target, "middle-earth:detection_range", "detection_range");

        for (String disciplinePath : Disciplines.ALL) {
            Identifier disciplineId = Identifier.of(Kindreds.MOD_ID, disciplinePath);
            long xp = data.xpIn(disciplineId);
            int level = ProgressionService.pointsForLevel(xp);
            int spent = tree.map(t -> ProgressionService.pointsSpent(data, t, disciplineId)).orElse(0);
            int available = level - spent;
            source.sendFeedback(() -> Text.literal(String.format(
                    "  %-9s xp=%-6d level=%-3d points=%d/%d", disciplinePath, xp, level, available, level)), false);
        }

        source.sendFeedback(() -> Text.literal("Unlocked nodes (" + data.unlockedNodes().size() + "):"), false);
        String nodesLine = data.unlockedNodes().isEmpty()
                ? "  (none)"
                : "  " + String.join(", ", data.unlockedNodes());
        source.sendFeedback(() -> Text.literal(nodesLine), false);
        return 1;
    }

    /** Prints one attribute's base value, current (post-modifier) value, and modifier count - lets a
     * player verify that a Kindred/base-mod attribute (which never appears in the effects HUD) is
     * actually applied. Silently notes attributes the player's entity doesn't carry. */
    private static void printAttr(ServerCommandSource source, ServerPlayerEntity target, String attrId, String label) {
        int colon = attrId.indexOf(':');
        Identifier id = Identifier.of(attrId.substring(0, colon), attrId.substring(colon + 1));
        Registries.ATTRIBUTE.getEntry(id).ifPresentOrElse(entry -> {
            EntityAttributeInstance instance = target.getAttributeInstance(entry);
            if (instance == null) {
                source.sendFeedback(() -> Text.literal(String.format("  %-18s (not on this entity)", label)), false);
                return;
            }
            double base = instance.getBaseValue();
            double current = instance.getValue();
            int mods = instance.getModifiers().size();
            source.sendFeedback(() -> Text.literal(String.format(
                    "  %-18s %.3f -> %.3f  (%d modifier%s)", label, base, current, mods, mods == 1 ? "" : "s")), false);
        }, () -> source.sendFeedback(() -> Text.literal(String.format("  %-18s (attribute not registered)", label)), false));
    }

    // --- grantxp -----------------------------------------------------------------------------

    private static int grantXp(ServerCommandSource source, ServerPlayerEntity target, String disciplinePath, long amount) {
        if (Kindreds.CONFIG == null || !Kindreds.CONFIG.allowGrantXp) {
            source.sendError(Text.literal("grantxp is disabled on this server. An operator can enable it "
                    + "in the Kindreds rules screen, or set allowGrantXp in kindreds-server.json."));
            return 0;
        }
        if (!Disciplines.ALL.contains(disciplinePath)) {
            source.sendError(Text.literal("Unknown discipline '" + disciplinePath + "'. Valid: "
                    + String.join(", ", Disciplines.ALL)));
            return 0;
        }
        Identifier discipline = Identifier.of(Kindreds.MOD_ID, disciplinePath);
        KindredData data = KindredAttachment.get(target);
        Identifier race = RaceAccess.getRace(target).orElse(null);

        ProgressionService.awardXp(data, race, discipline, amount, 1.0);
        SyncKindredDataS2C.sendTo(target);

        String targetName = target.getGameProfile().getName();
        source.sendFeedback(() -> Text.literal(
                "Granted " + amount + " " + disciplinePath + " xp to " + targetName), true);
        return 1;
    }

    // --- reload ------------------------------------------------------------------------------

    /** Shows the active difficulty and the values it implies. */
    private static int difficultyShow(ServerCommandSource source) {
        KindredsConfig c = Kindreds.CONFIG;
        source.sendFeedback(() -> Text.literal("Difficulty: " + c.difficulty), false);
        source.sendFeedback(() -> Text.literal("  xpRate " + c.xpRateGlobal + " · death " + c.deathPenalty
                + " · softCap " + (c.pointSoftCap > 0 ? c.pointSoftCap : "off")
                + " · enemyScaling " + c.enableEnemyScaling + " · respecCost " + c.respecCost), false);
        source.sendFeedback(() -> Text.literal("  presets: "
                + java.util.Arrays.stream(com.kindreds.config.Difficulty.values())
                        .map(d -> d.name().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.joining(", "))), false);
        return 1;
    }

    /** Applies a difficulty preset and persists it. */
    private static int difficultySet(ServerCommandSource source, String preset) {
        com.kindreds.config.Difficulty d;
        try {
            d = com.kindreds.config.Difficulty.valueOf(preset.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal("Unknown difficulty '" + preset + "'"));
            return 0;
        }
        KindredsConfig c = Kindreds.CONFIG;
        c.difficulty = d;
        d.applyTo(c);
        c.save(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
        source.sendFeedback(() -> Text.literal("Kindreds difficulty set to " + d), true);
        return difficultyShow(source);
    }

    private static int reload(ServerCommandSource source) {
        Kindreds.CONFIG = KindredsConfig.load(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
        KindredsConfig config = Kindreds.CONFIG;
        source.sendFeedback(() -> Text.literal("Kindreds config reloaded (xpRateGlobal=" + config.xpRateGlobal
                + ", deathPenalty=" + config.deathPenalty + ")"), true);
        return 1;
    }

    // --- config ------------------------------------------------------------------------------

    private static final List<String> CONFIG_KEYS = List.of(
            "enableBirthTraits", "enableCurses", "enableVision", "allowCrossTraining", "enableEnemyScaling",
            "xpRateGlobal", "deathPenalty", "deathPercent", "pointSoftCap", "respecItem", "respecCost",
            // added with the cap rework, the grantxp gate and the HUD toggle - a setting that exists
            // but that no command can reach is a setting an operator cannot actually use
            "pointCapPercent", "allowGrantXp", "hudAnimations");

    private static final SuggestionProvider<ServerCommandSource> CONFIG_KEY_SUGGESTIONS =
            (context, builder) -> CommandSource.suggestMatching(CONFIG_KEYS, builder);

    /** {@code /kindreds config} - prints every current server-config value. */
    private static int configList(ServerCommandSource source) {
        KindredsConfig c = Kindreds.CONFIG;
        source.sendFeedback(() -> Text.literal("=== Kindreds server config ==="), false);
        source.sendFeedback(() -> Text.literal("  enableBirthTraits = " + c.enableBirthTraits), false);
        source.sendFeedback(() -> Text.literal("  enableCurses = " + c.enableCurses), false);
        source.sendFeedback(() -> Text.literal("  enableVision = " + c.enableVision), false);
        source.sendFeedback(() -> Text.literal("  allowCrossTraining = " + c.allowCrossTraining), false);
        source.sendFeedback(() -> Text.literal("  allowGrantXp = " + c.allowGrantXp), false);
        source.sendFeedback(() -> Text.literal("  pointCapPercent = " + c.pointCapPercent), false);
        source.sendFeedback(() -> Text.literal("  hudAnimations = " + c.hudAnimations), false);
        source.sendFeedback(() -> Text.literal("  enableEnemyScaling = " + c.enableEnemyScaling), false);
        source.sendFeedback(() -> Text.literal("  xpRateGlobal = " + c.xpRateGlobal), false);
        source.sendFeedback(() -> Text.literal("  deathPenalty = " + c.deathPenalty), false);
        source.sendFeedback(() -> Text.literal("  deathPercent = " + c.deathPercent), false);
        source.sendFeedback(() -> Text.literal("  pointSoftCap = " + c.pointSoftCap), false);
        source.sendFeedback(() -> Text.literal("  respecItem = " + c.respecItem), false);
        source.sendFeedback(() -> Text.literal("  respecCost = " + c.respecCost), false);
        source.sendFeedback(() -> Text.literal("Change with: /kindreds config <key> <value>  (saved to kindreds-server.json)"), false);
        return 1;
    }

    /** {@code /kindreds config <key> <value>} - sets one config value, persists it, and it takes
     * effect immediately (birth traits/curses reconcile within a couple seconds; rates on next award). */
    private static int configSet(ServerCommandSource source, String key, String value) {
        KindredsConfig c = Kindreds.CONFIG;
        try {
            switch (key) {
                case "enableBirthTraits" -> c.enableBirthTraits = parseBool(value);
                case "enableCurses" -> c.enableCurses = parseBool(value);
                case "enableVision" -> c.enableVision = parseBool(value);
                case "allowCrossTraining" -> c.allowCrossTraining = parseBool(value);
                case "enableEnemyScaling" -> c.enableEnemyScaling = parseBool(value);
                case "xpRateGlobal" -> c.xpRateGlobal = Double.parseDouble(value);
                case "deathPercent" -> c.deathPercent = Double.parseDouble(value);
                case "pointSoftCap" -> c.pointSoftCap = Integer.parseInt(value);
                case "pointCapPercent" -> c.pointCapPercent = Integer.parseInt(value);
                case "allowGrantXp" -> c.allowGrantXp = parseBool(value);
                case "hudAnimations" -> c.hudAnimations = parseBool(value);
                case "respecCost" -> c.respecCost = Integer.parseInt(value);
                case "respecItem" -> c.respecItem = value;
                case "deathPenalty" -> c.deathPenalty = DeathPenalty.valueOf(value.toUpperCase(Locale.ROOT));
                default -> {
                    source.sendError(Text.literal("Unknown key '" + key + "'. Valid: " + String.join(", ", CONFIG_KEYS)));
                    return 0;
                }
            }
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal("Bad value '" + value + "' for " + key
                    + (key.equals("deathPenalty") ? " (expected KEEP/LOSE_UNSPENT/LOSE_PERCENT/HARDCORE)" : "")));
            return 0;
        }
        c.save(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
        source.sendFeedback(() -> Text.literal("Set " + key + " = " + value + " (saved)"), true);
        return 1;
    }

    private static boolean parseBool(String v) {
        if (v.equalsIgnoreCase("true") || v.equals("1")) {
            return true;
        }
        if (v.equalsIgnoreCase("false") || v.equals("0")) {
            return false;
        }
        throw new IllegalArgumentException("expected true/false");
    }

    // --- respec ------------------------------------------------------------------------------

    private static int respec(ServerCommandSource source, ServerPlayerEntity target) {
        // Delegates to RespecService (Task 11), which also backs the player-facing RespecC2S -
        // this admin command and the paid in-game respec must never quietly diverge in what
        // "reversed" means.
        int reversedCount = RespecService.reverseAll(target);
        SyncKindredDataS2C.sendTo(target);

        String targetName = target.getGameProfile().getName();
        source.sendFeedback(() -> Text.literal(
                "Respecced " + targetName + ": reversed " + reversedCount + " node(s)"), true);
        return 1;
    }

    // --- shared lookups ------------------------------------------------------------------------

    private static Optional<SkillTree> findTreeForRace(MinecraftServer server, Identifier race) {
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        for (SkillTree tree : trees) {
            if (tree.race().equals(race)) {
                return Optional.of(tree);
            }
        }
        return Optional.empty();
    }

    private static Optional<BirthTrait> findBirthTrait(MinecraftServer server, Identifier race) {
        Registry<BirthTrait> traits = server.getRegistryManager().getOrThrow(KindredsRegistries.BIRTH_TRAIT);
        for (BirthTrait trait : traits) {
            if (trait.race().equals(race)) {
                return Optional.of(trait);
            }
        }
        return Optional.empty();
    }
}
