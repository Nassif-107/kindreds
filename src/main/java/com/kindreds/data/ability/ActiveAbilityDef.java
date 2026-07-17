package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

/**
 * A player-triggered active ability with a cooldown.
 *
 * @param abilityId     mod-internal id naming the ability implementation to invoke
 * @param cooldownTicks cooldown between activations, in ticks
 */
public record ActiveAbilityDef(Identifier abilityId, int cooldownTicks) implements AbilityDef {
    public static final MapCodec<ActiveAbilityDef> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("ability_id").forGetter(ActiveAbilityDef::abilityId),
            Codec.INT.fieldOf("cooldown_ticks").forGetter(ActiveAbilityDef::cooldownTicks)
    ).apply(instance, ActiveAbilityDef::new));

    @Override
    public String type() {
        return "active";
    }
}
