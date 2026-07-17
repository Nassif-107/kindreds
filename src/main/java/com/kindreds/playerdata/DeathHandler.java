package com.kindreds.playerdata;

import com.kindreds.Kindreds;
import com.kindreds.ability.AbilityApplier;
import com.kindreds.config.DeathPenalty;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.progression.LevelCurve;
import com.kindreds.progression.ProgressionService;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Copies {@link KindredData} from an old {@code ServerPlayerEntity} to its replacement across a
 * respawn or dimension change, applying {@link Kindreds#CONFIG}'s {@code deathPenalty} - and, on
 * an actual death, re-applying attribute/status-effect side effects that vanilla drops along the
 * way (see "Attribute re-apply on respawn" below).
 *
 * <h2>Why this exists</h2>
 * {@link KindredAttachment#TYPE} is registered with {@code AttachmentRegistry.createPersistent}
 * (Task 4), which is <b>not</b> {@code copyOnDeath} - so without this class, a fresh (empty)
 * {@link KindredData} would silently replace a player's real skill data on every respawn, whether
 * they died in creative-adjacent circumstances, changed dimension, or just hit the
 * respawn-after-death path. {@link net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents#COPY_FROM}
 * fires for <b>both</b> cases (real death and dimension change) with {@code alive} distinguishing
 * them - see its javadoc: "called after the old player is removed and untracked, but before the
 * new player is added and tracked."
 *
 * <h2>Death penalty per mode</h2>
 * Only applied when {@code !alive} (an actual death - {@code alive == true} means a dimension
 * change, which always copies unchanged regardless of config):
 * <ul>
 *   <li>{@link DeathPenalty#KEEP} - deep-copies {@link KindredData} unchanged.</li>
 *   <li>{@link DeathPenalty#LOSE_UNSPENT} - deep-copies, then for every discipline with recorded
 *       xp, sets that discipline's xp down to {@link LevelCurve#xpForLevel(int)} of however many
 *       points are currently spent in it (via {@link ProgressionService#pointsSpent}), so no
 *       *unspent* points survive. Already-unlocked nodes (and thus "spent" points) are untouched -
 *       only the excess/unspent xp above them is lost. If the player's race doesn't resolve to
 *       exactly one {@link SkillTree} (base mod absent, no race chosen, or an authoring collision -
 *       see {@link SkillTreeResolver}), spent is treated as {@code 0} for every discipline, i.e.
 *       all xp is lost - documented in the brief as the deliberate fallback for "no tree".</li>
 *   <li>{@link DeathPenalty#LOSE_PERCENT} - deep-copies, then multiplies every discipline's xp by
 *       {@code (1 - Kindreds.CONFIG.deathPercent)}, rounded to the nearest long.</li>
 *   <li>{@link DeathPenalty#HARDCORE} - full wipe: the new player gets a brand new default
 *       {@link KindredData} (old data discarded entirely, including unlocked nodes).</li>
 * </ul>
 * In every non-hardcore mode, unlocked nodes themselves are <b>never</b> touched - only xp/points
 * accounting changes. This mirrors the brief precisely: "unspent" or "percent of progress" refers
 * to banked xp, not already-unlocked nodes.
 *
 * <h2>Attribute re-apply on respawn</h2>
 * Investigated via the decompiled 1.21.8 {@code ServerPlayerEntity#copyFrom}: on the alive branch
 * (dimension change) vanilla calls both {@code getAttributes().setBaseFrom(...)} <b>and</b>
 * {@code getAttributes().addPersistentModifiersFrom(...)}, plus re-adds every {@code
 * StatusEffectInstance} - so a dimension change already carries this mod's persistent attribute
 * modifiers (added by {@link AbilityApplier#apply} for {@code AttributeMod}/{@code CurseDef}
 * nodes) and status effects across to the new entity with no help needed here. On the <b>not
 * alive</b> branch (a real death/respawn) vanilla only calls {@code setBaseFrom(...)} - the
 * persistent-modifier copy and the status-effect re-add are both skipped - so every attribute
 * modifier this mod ever installed via {@link AbilityApplier} is silently dropped on death. There
 * was no pre-existing "reapply on login" hook to reuse (checked: no other call site iterates
 * {@code unlockedNodes()} and calls {@link AbilityApplier#apply} outside of the unlock/curse-
 * context paths - {@link com.kindreds.progression.RespecService} only ever *removes*). This class
 * therefore re-applies every remaining unlocked node's abilities onto the new player after a real
 * death (skipped for {@code HARDCORE}, which has no unlocked nodes left to re-apply).
 *
 * <p><b>Not idempotent, but safe here regardless:</b> {@code EntityAttributeInstance.addModifier}
 * (which {@code addPersistentModifier} delegates to - confirmed from the decompiled source) throws
 * {@code IllegalArgumentException} if a modifier with the same id is already present, so calling
 * {@link AbilityApplier#apply} twice on the *same* attribute instance is not safe in general. That
 * doesn't apply here: on the {@code !alive} branch {@code newPlayer} is a brand-new entity whose
 * attribute instances vanilla only populated via {@code setBaseFrom} (base values only, per the
 * paragraph above) - no persistent modifier this mod ever installs can already be present on it,
 * so every re-apply below is a genuine first application, never a duplicate.
 */
public final class DeathHandler {
    private DeathHandler() {
    }

    /** Registers the {@code COPY_FROM} listener. Call once from {@link Kindreds#onInitialize()}. */
    public static void register() {
        ServerPlayerEvents.COPY_FROM.register(DeathHandler::onCopyFrom);
    }

    private static void onCopyFrom(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
        KindredData oldData = KindredAttachment.get(oldPlayer);

        if (alive) {
            // Dimension change, not death: always copy unchanged, regardless of deathPenalty.
            // Attribute modifiers/status effects are already handled by vanilla on this branch -
            // see the class javadoc's "Attribute re-apply on respawn" section.
            KindredAttachment.set(newPlayer, copyOf(oldData));
            SyncKindredDataS2C.sendTo(newPlayer);
            return;
        }

        Registry<SkillTree> trees = newPlayer.getServer().getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        Optional<SkillTree> tree = resolveTree(newPlayer, trees);

        DeathPenalty penalty = Kindreds.CONFIG.deathPenalty;
        KindredData newData = applyDeathPenalty(penalty, Kindreds.CONFIG.deathPercent, oldData, tree);
        KindredAttachment.set(newPlayer, newData);

        if (penalty != DeathPenalty.HARDCORE) {
            reapplyAbilities(newPlayer, newData, trees, tree);
        }

        SyncKindredDataS2C.sendTo(newPlayer);
    }

    // --- Pure death-penalty math (unit-tested directly - see DeathHandlerTest) -----------------

    /**
     * Computes the new player's {@link KindredData} given the old player's data, the configured
     * {@code penalty}/{@code deathPercent}, and (if resolvable) the player's {@link SkillTree} -
     * pure and MC-free so it's directly unit-testable, unlike {@link #onCopyFrom} which needs a
     * live {@link ServerPlayerEntity}.
     */
    static KindredData applyDeathPenalty(DeathPenalty penalty, double deathPercent, KindredData oldData,
                                          Optional<SkillTree> tree) {
        if (penalty == DeathPenalty.HARDCORE) {
            return new KindredData();
        }
        KindredData copy = copyOf(oldData);
        switch (penalty) {
            case KEEP -> {
                // No further change beyond the deep copy above.
            }
            case LOSE_UNSPENT -> applyLoseUnspent(copy, tree);
            case LOSE_PERCENT -> applyLosePercent(copy, deathPercent);
            case HARDCORE -> throw new IllegalStateException("HARDCORE handled above");
        }
        return copy;
    }

    /** For every discipline with recorded xp, reduces it down to exactly
     * {@code LevelCurve.xpForLevel(pointsSpent)} - see the class javadoc's LOSE_UNSPENT bullet. */
    private static void applyLoseUnspent(KindredData data, Optional<SkillTree> tree) {
        for (Identifier discipline : List.copyOf(data.disciplineXp().keySet())) {
            int spent = tree.map(t -> ProgressionService.pointsSpent(data, t, discipline)).orElse(0);
            data.disciplineXp().put(discipline, LevelCurve.xpForLevel(spent));
        }
    }

    /** Multiplies every discipline's xp by {@code (1 - deathPercent)}, rounded. */
    private static void applyLosePercent(KindredData data, double deathPercent) {
        double keepFraction = 1.0 - deathPercent;
        for (Identifier discipline : List.copyOf(data.disciplineXp().keySet())) {
            long xp = data.disciplineXp().getLong(discipline);
            data.disciplineXp().put(discipline, Math.round(xp * keepFraction));
        }
    }

    /** Deep-copies every collection so the new player's {@link KindredData} never shares mutable
     * state with the (about-to-be-discarded) old player's. */
    private static KindredData copyOf(KindredData data) {
        KindredData copy = new KindredData(
                new Object2LongOpenHashMap<>(data.disciplineXp()),
                new HashSet<>(data.unlockedNodes()),
                data.activeVisionLens(),
                new HashSet<>(data.titles()),
                data.corruption(),
                new Object2LongOpenHashMap<>(data.cooldowns()),
                new HashSet<>(data.discoveredBiomes()));
        copy.setRace(data.race());
        return copy;
    }

    // --- MC-bound helpers ------------------------------------------------------------------------

    /** Resolves {@code player}'s {@link SkillTree} by race, the same way
     * {@code RequestUnlockC2S}/{@code RespecService} do - empty if the base mod isn't loaded, the
     * player has no race yet, or the race is ambiguous (see {@link SkillTreeResolver}). */
    private static Optional<SkillTree> resolveTree(ServerPlayerEntity player, Registry<SkillTree> trees) {
        return RaceAccess.getRace(player).flatMap(race -> SkillTreeResolver.byRace(trees, race).tree());
    }

    /** Re-applies every ability of every node still unlocked in {@code data} onto {@code newPlayer}
     * - see the class javadoc's "Attribute re-apply on respawn" section for why this is needed only
     * on the death branch. Prefers the player's own {@code tree}, falling back to scanning every
     * registered tree for the node id (mirrors {@code RespecService#findNodeInAnyTree}) so a
     * temporarily-unresolvable race (or a node id that moved trees) doesn't strand a player with
     * unlocked-but-inert nodes after death. */
    private static void reapplyAbilities(ServerPlayerEntity newPlayer, KindredData data,
                                          Registry<SkillTree> trees, Optional<SkillTree> tree) {
        for (String nodeId : data.unlockedNodes()) {
            SkillNode node = findNode(trees, tree, nodeId);
            if (node == null) {
                Kindreds.LOGGER.warn(
                        "[Kindreds] death re-apply: node {} (unlocked by {}) not found in any registered skill "
                                + "tree; its abilities were not re-applied after respawn",
                        nodeId, newPlayer.getGameProfile().getName());
                continue;
            }
            for (AbilityDef ability : node.abilities()) {
                AbilityApplier.apply(newPlayer, ability, nodeId);
            }
        }
    }

    private static SkillNode findNode(Registry<SkillTree> trees, Optional<SkillTree> primary, String nodeId) {
        if (primary.isPresent()) {
            Optional<SkillNode> node = primary.get().node(nodeId);
            if (node.isPresent()) {
                return node.get();
            }
        }
        for (SkillTree candidate : trees) {
            Optional<SkillNode> node = candidate.node(nodeId);
            if (node.isPresent()) {
                return node.get();
            }
        }
        return null;
    }
}
