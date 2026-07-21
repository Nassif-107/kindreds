package com.kindreds.progression;

import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredData;
import net.minecraft.util.Identifier;

import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Pure server-side rules for whether a {@link SkillNode} may be unlocked, and mutating
 * {@link KindredData} once it has been. No Minecraft server/world types are touched here so this
 * is fully unit-testable (see {@code UnlockServiceTest}); the network layer
 * ({@code com.kindreds.network.RequestUnlockC2S}) is what wires this to real player state.
 */
public final class UnlockService {
    private UnlockService() {
    }

    /**
     * Result of a {@link #canUnlock} check.
     *
     * @param ok     whether the node may be unlocked
     * @param reason {@code "ok"} if {@code ok}, otherwise the first failing rule:
     *               {@code "unknown_node"}, {@code "already_unlocked"}, {@code "insufficient_points"},
     *               {@code "missing_prereq"}, {@code "exclusive_conflict"}, or {@code "deed_not_earned"}.
     */
    public record UnlockResult(boolean ok, String reason) {
        static final UnlockResult OK = new UnlockResult(true, "ok");

        static UnlockResult fail(String reason) {
            return new UnlockResult(false, reason);
        }
    }

    /**
     * Checks whether {@code nodeId} (looked up in {@code tree}) can be unlocked for {@code data},
     * in the following order (first failure wins):
     * <ol>
     *   <li>the node exists in {@code tree}</li>
     *   <li>it is not already unlocked</li>
     *   <li>{@code pointsAvailableForDiscipline} reports enough points in the node's cost discipline</li>
     *   <li>every id in {@code prereqs} is present in {@code data.unlockedNodes()}</li>
     *   <li>if the node has an {@code exclusiveGroup}, no other already-unlocked node shares it</li>
     *   <li>if the node has a {@code deedAdvancement}, {@code deedEarned.test(it)} is true</li>
     * </ol>
     *
     * @param pointsAvailableForDiscipline supplies currently-unspent points for a given discipline id
     *                                     (e.g. {@code id -> ProgressionService.pointsAvailable(data, tree, id)})
     * @param deedEarned                   reports whether a given advancement id has been earned by the player
     */
    public static UnlockResult canUnlock(
            KindredData data,
            SkillTree tree,
            String nodeId,
            ToIntFunction<Identifier> pointsAvailableForDiscipline,
            Predicate<Identifier> deedEarned) {
        SkillNode node = findNode(tree, nodeId);
        if (node == null) {
            return UnlockResult.fail("unknown_node");
        }
        if (data.hasNode(nodeId)) {
            return UnlockResult.fail("already_unlocked");
        }

        SkillNode.Cost cost = node.cost();
        int available = pointsAvailableForDiscipline.applyAsInt(cost.disciplineId());
        if (available < cost.points()) {
            return UnlockResult.fail("insufficient_points");
        }

        // Soft cap: a ceiling on TOTAL points spent across the whole tree - the knob that actually
        // forces a build identity (at 60 you master roughly one discipline and dabble; 0 = off).
        // Config read defensively so pure unit tests with no loaded config still pass.
        int cap = com.kindreds.Kindreds.CONFIG != null ? com.kindreds.Kindreds.CONFIG.pointSoftCap : 0;
        if (cap > 0 && totalPointsSpent(data, tree) + cost.points() > cap) {
            return UnlockResult.fail("soft_cap");
        }

        for (String prereq : node.prereqs()) {
            if (!data.hasNode(prereq)) {
                return UnlockResult.fail("missing_prereq");
            }
        }

        if (node.exclusiveGroup().isPresent()) {
            String group = node.exclusiveGroup().get();
            for (SkillNode other : tree.nodes()) {
                if (other.id().equals(nodeId)) {
                    continue;
                }
                if (other.exclusiveGroup().isPresent()
                        && other.exclusiveGroup().get().equals(group)
                        && data.hasNode(other.id())) {
                    return UnlockResult.fail("exclusive_conflict");
                }
            }
        }

        if (node.deedAdvancement().isPresent() && !deedEarned.test(node.deedAdvancement().get())) {
            return UnlockResult.fail("deed_not_earned");
        }

        return UnlockResult.OK;
    }

    /** Total points the player has already committed anywhere in {@code tree} - the quantity the
     * {@code pointSoftCap} limits. */
    public static int totalPointsSpent(KindredData data, SkillTree tree) {
        int spent = 0;
        for (SkillNode n : tree.nodes()) {
            if (data.hasNode(n.id())) {
                spent += n.cost().points();
            }
        }
        return spent;
    }

    /** Marks {@code nodeId} as unlocked on {@code data}. Assumes {@link #canUnlock} already passed. */
    public static void applyUnlock(KindredData data, String nodeId) {
        data.unlockedNodes().add(nodeId);
    }

    private static SkillNode findNode(SkillTree tree, String nodeId) {
        for (SkillNode node : tree.nodes()) {
            if (node.id().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }
}
