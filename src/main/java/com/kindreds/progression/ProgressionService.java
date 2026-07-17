package com.kindreds.progression;

import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredData;
import net.minecraft.util.Identifier;

/**
 * Pure entry points tying {@link LevelCurve}, {@link RaceScaling}, and {@link KindredData}
 * together: awarding scaled xp, and computing discipline points available vs. spent.
 *
 * <h2>Points accounting</h2>
 * A discipline grants one point per level ({@link #pointsForLevel(long)} ==
 * {@link LevelCurve#levelForXp(long)}). {@code KindredData} only records which node ids are
 * unlocked (not which discipline/cost each one is), so turning "unlocked nodes" into "points
 * spent in this discipline" needs the {@link SkillTree} those ids resolve against:
 *
 * <ul>
 *   <li>{@link #pointsSpent(KindredData, SkillTree, Identifier)} sums the point cost of every
 *       unlocked node in {@code tree} whose cost discipline matches.</li>
 *   <li>{@link #pointsAvailable(KindredData, Identifier, int)} is the raw formula
 *       {@code pointsForLevel(xp) - spent} for callers that already know {@code spent} some other
 *       way (e.g. a cached count).</li>
 *   <li>{@link #pointsAvailable(KindredData, SkillTree, Identifier)} is the convenience overload
 *       that derives {@code spent} from the tree automatically.</li>
 * </ul>
 */
public final class ProgressionService {
    private ProgressionService() {
    }

    /**
     * Awards {@code baseXp}, scaled by {@link RaceScaling#multiplier(Identifier, Identifier)} for
     * {@code race}/{@code discipline} and by {@code globalRate}, into {@code discipline} on
     * {@code data}. The scaled amount is rounded to the nearest long before being added. Mutates
     * {@code data} in place.
     */
    public static void awardXp(KindredData data, Identifier race, Identifier discipline, long baseXp, double globalRate) {
        double scaled = baseXp * RaceScaling.multiplier(race, discipline) * globalRate;
        data.addXp(discipline, Math.round(scaled));
    }

    /** The number of points {@code xp} total experience grants (one per level). */
    public static int pointsForLevel(long xp) {
        return LevelCurve.levelForXp(xp);
    }

    /**
     * Points currently unspent in {@code discipline}: {@code pointsForLevel(xp in discipline) - spent}.
     * {@code spent} is supplied by the caller (e.g. already computed via
     * {@link #pointsSpent(KindredData, SkillTree, Identifier)}, or cached).
     */
    public static int pointsAvailable(KindredData data, Identifier discipline, int spent) {
        return pointsForLevel(data.xpIn(discipline)) - spent;
    }

    /** Convenience overload that derives {@code spent} from {@code tree} automatically. */
    public static int pointsAvailable(KindredData data, SkillTree tree, Identifier discipline) {
        return pointsAvailable(data, discipline, pointsSpent(data, tree, discipline));
    }

    /**
     * Sum of the point cost of every node in {@code tree} that is both unlocked in {@code data}
     * and costs points in {@code discipline}.
     */
    public static int pointsSpent(KindredData data, SkillTree tree, Identifier discipline) {
        int spent = 0;
        for (SkillNode node : tree.nodes()) {
            SkillNode.Cost cost = node.cost();
            if (cost.disciplineId().equals(discipline) && data.hasNode(node.id())) {
                spent += cost.points();
            }
        }
        return spent;
    }
}
