package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * A player-triggered active ability with a cooldown.
 *
 * @param abilityId     mod-internal id naming the ability implementation to invoke
 * @param cooldownTicks cooldown between activations, in ticks
 * @param effects       status effects applied to the caster on activation (Task 12 Stage A - the
 *                      real activation payload; empty in JSON authored before this task, in which
 *                      case activation is a no-op rather than falling back to any placeholder
 *                      effect - see {@code AbilityApplier#applyActiveEffect})
 */
public record ActiveAbilityDef(Identifier abilityId, int cooldownTicks, List<StatusEffectDef> effects) implements AbilityDef {
    public static final MapCodec<ActiveAbilityDef> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("ability_id").forGetter(ActiveAbilityDef::abilityId),
            Codec.INT.fieldOf("cooldown_ticks").forGetter(ActiveAbilityDef::cooldownTicks),
            // Reuses StatusEffectDef's bare field codec (no "type" discriminator - that's only
            // added by AbilityDef.CODEC's outer dispatch, so the MapCodec itself is already exactly
            // {effect, amplifier, duration_ticks}, safe to nest here as a plain list).
            StatusEffectDef.CODEC.codec().listOf().optionalFieldOf("effects", List.of()).forGetter(ActiveAbilityDef::effects)
    ).apply(instance, ActiveAbilityDef::new));

    @Override
    public String type() {
        return "active";
    }
}
