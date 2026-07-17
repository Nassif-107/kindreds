package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Unlocks a special "vision" (e.g. ore-highlighting, mob-tracking) within a radius.
 *
 * @param visionId a mod-internal key naming which vision feature this unlocks
 * @param radius   effective radius in blocks
 */
public record VisionUnlock(String visionId, int radius) implements AbilityDef {
    public static final MapCodec<VisionUnlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("vision_id").forGetter(VisionUnlock::visionId),
            Codec.INT.fieldOf("radius").forGetter(VisionUnlock::radius)
    ).apply(instance, VisionUnlock::new));

    @Override
    public String type() {
        return "vision_unlock";
    }
}
