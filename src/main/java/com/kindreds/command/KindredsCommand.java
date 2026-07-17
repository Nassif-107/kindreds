package com.kindreds.command;

import com.kindreds.Kindreds;
import com.kindreds.ability.AbilityApplier;
import com.kindreds.config.KindredsConfig;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import com.kindreds.progression.ProgressionService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
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
 *   <li>{@code /kindreds grantxp <discipline> <amount> [player]} (op level 2) - awards xp for
 *       testing progression without grinding.</li>
 *   <li>{@code /kindreds reload} (op level 2) - reloads {@link Kindreds#CONFIG} from
 *       {@code <configDir>/kindreds-server.json}.</li>
 *   <li>{@code /kindreds respec [player]} (op level 2) - admin/mechanism respec: reverses every
 *       unlocked node's abilities via {@link AbilityApplier#removeNode} and clears them. The
 *       player-facing respec UI + item cost is Task 11; this is the admin escape hatch.</li>
 * </ul>
 *
 * <h2>Discipline argument</h2>
 * {@code <discipline>} accepts the short path of one of the 7 built-in ids ({@code combat},
 * {@code archery}, {@code mining}, {@code stealth}, {@code smithing}, {@code survival},
 * {@code lore}) - i.e. without the {@code kindreds:} namespace, matching how a command-line user
 * would expect to type it. {@link #DISCIPLINE_IDS} both drives suggestions and validates the
 * input; the full {@link Identifier} is reconstructed as {@code kindreds:<word>}.
 */
public final class KindredsCommand {
    private KindredsCommand() {
    }

    private static final List<String> DISCIPLINE_IDS = List.of(
            "combat", "archery", "mining", "stealth", "smithing", "survival", "lore");

    private static final SuggestionProvider<ServerCommandSource> DISCIPLINE_SUGGESTIONS =
            (context, builder) -> CommandSource.suggestMatching(DISCIPLINE_IDS, builder);

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
                        .requires(source -> source.hasPermissionLevel(2))
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
                .then(CommandManager.literal("reload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> reload(ctx.getSource())))
                .then(CommandManager.literal("respec")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(ctx -> respec(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> respec(ctx.getSource(), EntityArgumentType.getPlayer(ctx, "player"))))));
    }

    // --- inspect -----------------------------------------------------------------------------

    private static int inspect(ServerCommandSource source, ServerPlayerEntity target) {
        KindredData data = KindredAttachment.get(target);
        Optional<Identifier> race = RaceAccess.getRace(target);
        Optional<SkillTree> tree = race.flatMap(r -> findTreeForRace(source.getServer(), r));
        String targetName = target.getGameProfile().getName();

        source.sendFeedback(() -> Text.literal("=== Kindreds inspect: " + targetName + " ==="), false);
        source.sendFeedback(() -> Text.literal("Race: " + race.map(Identifier::toString).orElse("none")), false);

        for (String disciplinePath : DISCIPLINE_IDS) {
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

    // --- grantxp -----------------------------------------------------------------------------

    private static int grantXp(ServerCommandSource source, ServerPlayerEntity target, String disciplinePath, long amount) {
        if (!DISCIPLINE_IDS.contains(disciplinePath)) {
            source.sendError(Text.literal("Unknown discipline '" + disciplinePath + "'. Valid: "
                    + String.join(", ", DISCIPLINE_IDS)));
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

    private static int reload(ServerCommandSource source) {
        Kindreds.CONFIG = KindredsConfig.load(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
        KindredsConfig config = Kindreds.CONFIG;
        source.sendFeedback(() -> Text.literal("Kindreds config reloaded (xpRateGlobal=" + config.xpRateGlobal
                + ", deathPenalty=" + config.deathPenalty + ")"), true);
        return 1;
    }

    // --- respec ------------------------------------------------------------------------------

    private static int respec(ServerCommandSource source, ServerPlayerEntity target) {
        KindredData data = KindredAttachment.get(target);
        Registry<SkillTree> trees = source.getServer().getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);

        int reversedCount = 0;
        for (String nodeId : List.copyOf(data.unlockedNodes())) {
            SkillNode node = findNodeInAnyTree(trees, nodeId);
            if (node != null) {
                AbilityApplier.removeNode(target, node.abilities(), nodeId);
                reversedCount++;
            } else {
                Kindreds.LOGGER.warn(
                        "[Kindreds] /kindreds respec: node {} (unlocked by {}) not found in any registered skill "
                                + "tree; clearing the unlock without reversing its abilities",
                        nodeId, target.getGameProfile().getName());
            }
        }
        data.unlockedNodes().clear();
        SyncKindredDataS2C.sendTo(target);

        int reversed = reversedCount;
        String targetName = target.getGameProfile().getName();
        source.sendFeedback(() -> Text.literal(
                "Respecced " + targetName + ": reversed " + reversed + " node(s)"), true);
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

    /** Scans every registered {@link SkillTree} (not just the target's race tree) for
     * {@code nodeId}, so a respec still reverses correctly even if the player's race is unknown
     * (base mod absent) or has since changed - mirrors {@code RequestUnlockC2S}'s node-id-scan
     * fallback for the same reason. Node ids are authored unique per tree (see that class's
     * javadoc), so the first match is used without an ambiguity check here - this is an
     * admin/testing tool, not the untrusted-input unlock path. */
    private static SkillNode findNodeInAnyTree(Registry<SkillTree> trees, String nodeId) {
        for (SkillTree tree : trees) {
            Optional<SkillNode> node = tree.node(nodeId);
            if (node.isPresent()) {
                return node.get();
            }
        }
        return null;
    }
}
