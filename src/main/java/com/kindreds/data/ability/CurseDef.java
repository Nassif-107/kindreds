package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A drawback ("curse") attached to a node, gated behind the
 * {@code enableCurses} server config option at apply time.
 *
 * @param curseId  mod-internal id naming the curse implementation
 * @param severity relative strength of the curse's effect
 */
public record CurseDef(String curseId, int severity) implements AbilityDef {
    public static final MapCodec<CurseDef> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("curse_id").forGetter(CurseDef::curseId),
            Codec.INT.fieldOf("severity").forGetter(CurseDef::severity)
    ).apply(instance, CurseDef::new));

    @Override
    public String type() {
        return "curse";
    }
}
