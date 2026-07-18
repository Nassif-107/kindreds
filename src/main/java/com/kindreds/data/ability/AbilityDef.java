package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

/**
 * A single ability granted by a skill node.
 *
 * <p>Sealed hierarchy dispatched by a {@code "type"} string field in JSON via
 * {@link Codec#dispatch(String, java.util.function.Function, java.util.function.Function)}.
 * Adding a new ability type only requires: a new record implementing this
 * interface, and one new case in {@link #codecFor(String)} - nothing outside
 * this package needs to change since consumers only ever see {@link #CODEC}.
 */
public sealed interface AbilityDef
        permits AttributeMod, StatusEffectDef, VisionUnlock, ActiveAbilityDef, CurseDef, ContextualBoon, PerkDef {

    /** The discriminator string written to/read from the {@code "type"} field. */
    String type();

    Codec<AbilityDef> CODEC = Codec.STRING.dispatch("type", AbilityDef::type, AbilityDef::codecFor);

    private static MapCodec<? extends AbilityDef> codecFor(String type) {
        return switch (type) {
            case "attribute" -> AttributeMod.CODEC;
            case "status_effect" -> StatusEffectDef.CODEC;
            case "vision_unlock" -> VisionUnlock.CODEC;
            case "active" -> ActiveAbilityDef.CODEC;
            case "curse" -> CurseDef.CODEC;
            case "contextual_boon" -> ContextualBoon.CODEC;
            case "perk" -> PerkDef.CODEC;
            default -> throw new IllegalArgumentException("Unknown ability type: " + type);
        };
    }
}
