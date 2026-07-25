package com.kindreds.data;

import com.kindreds.data.ability.AbilityDef;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * A single node in a race's skill tree.
 *
 * @param id               node id, unique within its {@link SkillTree}
 * @param tier             tier/row this node sits at
 * @param pos              {@code [x, y]} position on the tree canvas
 * @param cost              discipline + point cost required to unlock this node
 * @param prereqs           ids of nodes (within the same tree) required before this one
 * @param abilities         abilities granted by unlocking this node
 * @param deedAdvancement   optional advancement id awarded for the deed tied to this node
 * @param exclusiveGroup    optional group key; only one node sharing a group can be active
 * @param maxRank           how many times this node can be deepened; {@code 1} (the default) is a
 *                          plain once-and-done node. See {@link #maxRank()}.
 */
public record SkillNode(
        String id,
        int tier,
        int[] pos,
        Cost cost,
        List<String> prereqs,
        List<AbilityDef> abilities,
        Optional<Identifier> deedAdvancement,
        Optional<String> exclusiveGroup,
        int maxRank
) {
    /**
     * How deep this node can be taken, {@code 1} meaning "bought once and finished".
     *
     * <p>Ranks exist because a lane of separate nodes could not express depth honestly. Most perks
     * aggregate across owned nodes with {@code Math.max}, so a second node granting the same perk at
     * the same strength did precisely nothing - a player spent real points on {@code tempered_plate}
     * after {@code master_craft} and got no mending at all for it. A rank cannot lie that way: rank 2
     * multiplies the node's own numbers by two, so every point spent deepening it is felt.
     *
     * <p>Each rank costs {@link Cost#points()} again, so a rank-3 node is three times the investment
     * of a rank-1 one. That is the other half of a longer journey: the tree grows in depth rather than
     * only in width, and no new name, flavour line or translation is needed to make it longer.
     */
    public int maxRank() {
        return Math.max(1, maxRank);
    }
    /** Discipline points required to unlock a node. */
    public record Cost(Identifier disciplineId, int points) {
        public static final Codec<Cost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("discipline").forGetter(Cost::disciplineId),
                Codec.INT.fieldOf("points").forGetter(Cost::points)
        ).apply(instance, Cost::new));
    }

    private static final Codec<int[]> POS_CODEC = Codec.INT.listOf(2, 2).xmap(
            list -> new int[]{list.get(0), list.get(1)},
            arr -> List.of(arr[0], arr[1]));

    public static final Codec<SkillNode> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(SkillNode::id),
            Codec.INT.fieldOf("tier").forGetter(SkillNode::tier),
            POS_CODEC.fieldOf("pos").forGetter(SkillNode::pos),
            Cost.CODEC.fieldOf("cost").forGetter(SkillNode::cost),
            Codec.STRING.listOf().optionalFieldOf("prereqs", List.of()).forGetter(SkillNode::prereqs),
            AbilityDef.CODEC.listOf().optionalFieldOf("abilities", List.of()).forGetter(SkillNode::abilities),
            Identifier.CODEC.optionalFieldOf("deed_advancement").forGetter(SkillNode::deedAdvancement),
            Codec.STRING.optionalFieldOf("exclusive_group").forGetter(SkillNode::exclusiveGroup),
            // optionalFieldOf: every node authored before ranks existed reads back as a plain
            // rank-1 node, so the whole existing tree keeps its exact meaning untouched.
            Codec.INT.optionalFieldOf("max_rank", 1).forGetter(SkillNode::maxRank)
    ).apply(instance, SkillNode::new));
}
