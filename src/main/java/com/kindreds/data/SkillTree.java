package com.kindreds.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * The full skill tree for one race.
 *
 * @param race  id of the race this tree belongs to (interop with the base Middle-earth mod's race registry)
 * @param theme id of the {@link Theme} used to render this tree's GUI
 * @param nodes all nodes in the tree
 */
public record SkillTree(Identifier race, Identifier theme, List<SkillNode> nodes) {
    public static final Codec<SkillTree> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("race").forGetter(SkillTree::race),
            Identifier.CODEC.fieldOf("theme").forGetter(SkillTree::theme),
            SkillNode.CODEC.listOf().fieldOf("nodes").forGetter(SkillTree::nodes)
    ).apply(instance, SkillTree::new));
}
