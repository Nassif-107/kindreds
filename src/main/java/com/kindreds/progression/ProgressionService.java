package com.kindreds.progression;

import com.kindreds.Kindreds;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.threat.ThreatService;
import net.minecraft.server.network.ServerPlayerEntity;
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

    /**
     * As {@link #awardXp(KindredData, Identifier, Identifier, long, double)}, but for a caller that
     * has the live {@code player} rather than an already-resolved {@link KindredData} - resolved
     * here via {@link KindredAttachment#get}. {@code baseXp} is additionally scaled by {@code 1 +
     * (xpBonus/100) * scaled}, {@code scaled} coming from {@link ThreatService#scaledFor}. Design
     * spec §3, "the world must pay for the danger": a world that gets harder for the same reward
     * teaches players to stay small, so the xp reward must rise with the danger the player is
     * actually facing.
     *
     * <p>{@link ThreatService#scaledFor} already returns {@code 0} when scaling is off or the config
     * has not loaded, which collapses this to exactly the unscaled award - no separate null/enabled
     * guard is needed here on top of that one, other than reading {@link Kindreds#CONFIG}'s {@code
     * xpBonus} defensively in case the config itself is momentarily null.
     */
    public static void awardXp(ServerPlayerEntity player, Identifier race, Identifier discipline,
                                long baseXp, double globalRate) {
        KindredData data = KindredAttachment.get(player);
        double scaled = ThreatService.scaledFor(player);
        double xpBonusFraction = Kindreds.CONFIG == null ? 0.0 : Kindreds.CONFIG.xpBonus / 100.0;
        double dangerScaledXp = baseXp * (1.0 + xpBonusFraction * scaled);
        awardXp(data, race, discipline, Math.round(dangerScaledXp), globalRate);
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
