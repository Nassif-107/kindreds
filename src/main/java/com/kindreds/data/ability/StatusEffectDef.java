package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

/**
 * A passive status effect granted while the node is active.
 *
 * @param effect       the status effect registry id (e.g. {@code minecraft:night_vision})
 * @param amplifier    effect amplifier (0 = level I)
 * @param durationTicks duration in ticks; {@code -1} means "reapplied indefinitely while owned"
 */
public record StatusEffectDef(Identifier effect, int amplifier, int durationTicks) implements AbilityDef {
    public static final MapCodec<StatusEffectDef> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("effect").forGetter(StatusEffectDef::effect),
            Codec.INT.fieldOf("amplifier").forGetter(StatusEffectDef::amplifier),
            Codec.INT.fieldOf("duration_ticks").forGetter(StatusEffectDef::durationTicks)
    ).apply(instance, StatusEffectDef::new));

    @Override
    public String type() {
        return "status_effect";
    }
}
