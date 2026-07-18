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
 * @param race    the {@code middle-earth:*} race id these traits belong to
 * @param traits  the always-on abilities granted (or penalties imposed) at birth
 * @param pluses  human-readable, lore-flavored "boon" lines for the Codex/tooltips
 * @param minuses human-readable, lore-flavored "bane" lines for the Codex/tooltips
 */
public record BirthTrait(Identifier race, List<AbilityDef> traits, List<String> pluses, List<String> minuses) {
    public static final Codec<BirthTrait> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("race").forGetter(BirthTrait::race),
            AbilityDef.CODEC.listOf().optionalFieldOf("traits", List.of()).forGetter(BirthTrait::traits),
            Codec.STRING.listOf().optionalFieldOf("pluses", List.of()).forGetter(BirthTrait::pluses),
            Codec.STRING.listOf().optionalFieldOf("minuses", List.of()).forGetter(BirthTrait::minuses)
    ).apply(instance, BirthTrait::new));
}
