package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A <b>positive</b> effect that is active only while the owner is in a named context - the boon
 * counterpart to a contextual {@link CurseDef} (nonblank {@code when}). Tolkien's peoples draw power
 * from place and time: Elves under the open night sky (starlight), Dwarves in the deep stone, Orcs
 * emboldened by darkness. Applied/removed each interval by {@code CurseContextService} exactly like a
 * contextual curse, so it never applies while out of context and cleanly reverses on context-exit.
 *
 * @param when   the context key that gates the effect (e.g. {@code "starlight"}, {@code "underground"},
 *               {@code "darkness"}, {@code "daylight"}, {@code "dawn_dusk"})
 * @param effect the effect granted while in that context (typically a {@link StatusEffectDef} or
 *               {@link AttributeMod})
 */
public record ContextualBoon(String when, AbilityDef effect) implements AbilityDef {
    public static final MapCodec<ContextualBoon> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("when").forGetter(ContextualBoon::when),
            AbilityDef.CODEC.fieldOf("effect").forGetter(ContextualBoon::effect)
    ).apply(instance, ContextualBoon::new));

    @Override
    public String type() {
        return "contextual_boon";
    }
}
