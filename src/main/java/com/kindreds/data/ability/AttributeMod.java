package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

/**
 * Flat or percentage modifier applied to a vanilla entity attribute.
 *
 * @param attribute the attribute registry id (e.g. {@code minecraft:max_health})
 * @param operation attribute modifier operation name (e.g. {@code add_value},
 *                  {@code add_multiplied_base}, {@code add_multiplied_total})
 * @param amount    modifier magnitude
 */
public record AttributeMod(Identifier attribute, String operation, double amount) implements AbilityDef {
    public static final MapCodec<AttributeMod> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("attribute").forGetter(AttributeMod::attribute),
            Codec.STRING.fieldOf("operation").forGetter(AttributeMod::operation),
            Codec.DOUBLE.fieldOf("amount").forGetter(AttributeMod::amount)
    ).apply(instance, AttributeMod::new));

    @Override
    public String type() {
        return "attribute";
    }
}
