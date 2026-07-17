package com.kindreds.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A trainable discipline (e.g. Archery, Smithing) that skill nodes cost points in.
 *
 * @param name     display name of the discipline
 * @param colorInt packed RGB color used for HUD/GUI theming
 */
public record Discipline(String name, int colorInt) {
    public static final Codec<Discipline> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(Discipline::name),
            Codec.INT.fieldOf("color").forGetter(Discipline::colorInt)
    ).apply(instance, Discipline::new));
}
