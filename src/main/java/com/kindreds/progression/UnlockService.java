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

        // The tree-wide cap on TOTAL points - the rule that forces a build identity rather than
        // eventual omniscience. Checked LAST on purpose: it is the least specific reason to refuse, so
        // a node that is also missing a prereq or closed off by an exclusive choice reports that
        // instead. Otherwise "capped" would hide the real answer once a player nears the ceiling.
        int cap = effectiveCap(tree, data);
        if (cap > 0 && totalPointsSpent(data, tree) + cost.points() > cap) {
            return UnlockResult.fail("soft_cap");
        }

        return UnlockResult.OK;
    }

    /** The most points {@code tree} could ever absorb: every node, except that only the cheapest
     * member of each exclusive group is ever ownable. This is the denominator the percentage cap
     * scales against, so a 4-lane race and a 5-lane race are limited proportionally. */
    public static int maxSpendable(SkillTree tree) {
        int total = 0;
        java.util.Map<String, Integer> cheapestExclusive = new java.util.HashMap<>();
        for (SkillNode n : tree.nodes()) {
            String group = n.exclusiveGroup().orElse(null);
            if (group == null) {
                total += n.cost().points();
            } else {
                cheapestExclusive.merge(group, n.cost().points(), Math::min);
            }
        }
        for (int c : cheapestExclusive.values()) {
            total += c;
        }
        return total;
    }

    /**
     * The point ceiling that actually applies to {@code tree} for {@code data}'s owner: a percentage of
     * the tree's full cost, widened by any renown the player has earned. {@code 0} means no cap.
     *
     * <p>The base percentage comes from the difficulty preset ({@code pointCapPercent}, 1-99), or from
     * the legacy absolute {@code pointSoftCap} when the percentage is off. On top of it,
     * {@link RenownService#bonusPercent} adds each Great Deed performed and the Bargain, if taken.
     *
     * <p>The total is clamped to {@link #MAX_EARNED_PERCENT}: a player who has done every deed AND sold
     * themselves still gives something up. Becoming all things at once is the one outcome the cap
     * exists to prevent, and no amount of renown should buy it.
     */
    public static int effectiveCap(SkillTree tree, KindredData data) {
        com.kindreds.config.KindredsConfig c = com.kindreds.Kindreds.CONFIG;
        if (c == null || tree == null) {
            return 0;
        }
        int base = c.pointCapPercent;
        if (base >= 100) {
            return 0; // the cap is switched off entirely - renown has nothing to widen
        }
        if (base <= 0) {
            // Legacy absolute cap: widen it proportionally so deeds still mean something.
            int flat = Math.max(0, c.pointSoftCap);
            return flat <= 0 ? 0 : flat + Math.round(flat * (RenownService.bonusPercent(data) / 100f));
        }
        int percent = Math.min(MAX_EARNED_PERCENT, base + RenownService.bonusPercent(data));
        return Math.max(1, Math.round(maxSpendable(tree) * (percent / 100f)));
    }

    /** The most of your own tree renown can ever open up. See {@link #effectiveCap}. */
    public static final int MAX_EARNED_PERCENT = 95;

    /** Total points the player has already committed anywhere in {@code tree} - the quantity the
     * cap limits. */
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
