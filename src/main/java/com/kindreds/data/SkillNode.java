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
 */
public record SkillNode(
        String id,
        int tier,
        int[] pos,
        Cost cost,
        List<String> prereqs,
        List<AbilityDef> abilities,
        Optional<Identifier> deedAdvancement,
        Optional<String> exclusiveGroup
) {
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
            Codec.STRING.optionalFieldOf("exclusive_group").forGetter(SkillNode::exclusiveGroup)
    ).apply(instance, SkillNode::new));
}
