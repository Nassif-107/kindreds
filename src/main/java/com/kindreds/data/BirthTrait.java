package com.kindreds.data;

import com.kindreds.data.ability.AbilityDef;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Innate, always-on traits a player has purely for <b>being</b> a given race - applied the moment
 * their race is known (see {@code BirthTraitService}), independent of any skill node. Reuses the
 * existing {@link AbilityDef} payload types (attribute/status/vision/curse) so birth traits and
 * earned nodes share one effect engine ({@code AbilityApplier}).
 *
 * <p>Loaded as a synced dynamic registry ({@code kindreds:birth_trait}) from
 * {@code data/kindreds/kindreds/birth_trait/<race>.json}, so the client (Lore Codex) reads the same
 * {@link #pluses}/{@link #minuses} lore lines the server applies, and the two can never desync.
 *
 * <p>The lore lines are split four ways rather than two. A trait that is always true of your body
 * ("13 hearts") and one that only wakes somewhere ("in the deep places, out of the sky's sight")
 * are different kinds of fact, and a reader of one flat list cannot tell which is which. The split
 * lives in the data rather than in the screen because only the author of a line knows whether it
 * carries a condition: the effect list cannot be matched back to the prose, since one line often
 * covers two effects and several describe base-race stats this mod never touches.
 *
 * @param race               the {@code middle-earth:*} race id these traits belong to
 * @param traits             the always-on abilities granted (or penalties imposed) at birth
 * @param pluses             boon lines that hold at all times
 * @param minuses            bane lines that hold at all times
 * @param conditionalPluses  boon lines that wake only in some place, hour, or state
 * @param conditionalMinuses bane lines that bite only in some place, hour, or state
 */
public record BirthTrait(Identifier race, List<AbilityDef> traits, List<String> pluses, List<String> minuses,
                         List<String> conditionalPluses, List<String> conditionalMinuses) {
    public static final Codec<BirthTrait> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("race").forGetter(BirthTrait::race),
            AbilityDef.CODEC.listOf().optionalFieldOf("traits", List.of()).forGetter(BirthTrait::traits),
            Codec.STRING.listOf().optionalFieldOf("pluses", List.of()).forGetter(BirthTrait::pluses),
            Codec.STRING.listOf().optionalFieldOf("minuses", List.of()).forGetter(BirthTrait::minuses),
            Codec.STRING.listOf().optionalFieldOf("conditional_pluses", List.of())
                    .forGetter(BirthTrait::conditionalPluses),
            Codec.STRING.listOf().optionalFieldOf("conditional_minuses", List.of())
                    .forGetter(BirthTrait::conditionalMinuses)
    ).apply(instance, BirthTrait::new));

    /** Every boon line in reading order, for callers that do not care about the split. */
    public List<String> allPluses() {
        return java.util.stream.Stream.concat(pluses.stream(), conditionalPluses.stream()).toList();
    }

    /** Every bane line in reading order, for callers that do not care about the split. */
    public List<String> allMinuses() {
        return java.util.stream.Stream.concat(minuses.stream(), conditionalMinuses.stream()).toList();
    }
}
