package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A "bane" - bonus melee/ranged damage the owner deals against a category of foe, à la Sting against
 * spiders or the Dúnedain against the Dead. A passive that fires on the attack event (see
 * {@code PerkService}), not on unlock - so it needs no reversal.
 *
 * @param foe   the foe category: {@code "spider"} (arthropods - Mirkwood/Shelob), {@code "undead"}
 *              (Barrow-wights, the Dead), {@code "illager"} (raiders/champions of evil Men),
 *              {@code "orc"} (mapped to the pack's orc-analog hostiles), {@code "any"} (all hostiles)
 * @param bonus multiplier added to dealt damage (e.g. {@code 0.5} = +50% vs that foe)
 */
public record BaneDef(String foe, float bonus) implements AbilityDef {
    public static final MapCodec<BaneDef> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("foe").forGetter(BaneDef::foe),
            Codec.FLOAT.fieldOf("bonus").forGetter(BaneDef::bonus)
    ).apply(instance, BaneDef::new));

    @Override
    public String type() {
        return "bane";
    }
}
