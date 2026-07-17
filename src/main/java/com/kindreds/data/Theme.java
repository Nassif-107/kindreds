package com.kindreds.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

/**
 * Visual theming for a race's skill tree screen (colors + background texture).
 *
 * Registered as its own synced dynamic registry ({@link KindredsRegistries#THEME}) and
 * referenced by {@link SkillTree#theme()} (an {@link Identifier}); consumed directly by the
 * {@code client.screen} GUI layer (Task 11).
 *
 * @param primaryColor      packed RGB primary accent color
 * @param secondaryColor    packed RGB secondary accent color
 * @param backgroundTexture identifier of the background texture asset
 */
public record Theme(int primaryColor, int secondaryColor, Identifier backgroundTexture) {
    public static final Codec<Theme> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("primary_color").forGetter(Theme::primaryColor),
            Codec.INT.fieldOf("secondary_color").forGetter(Theme::secondaryColor),
            Identifier.CODEC.fieldOf("background_texture").forGetter(Theme::backgroundTexture)
    ).apply(instance, Theme::new));
}
