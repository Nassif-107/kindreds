package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * A drawback ("curse") attached to a node, gated behind the
 * {@code enableCurses} server config option at apply time.
 *
 * @param curseId  mod-internal id naming the curse implementation; when {@link #effect} is absent
 *                 this must be one of {@code CurseService}'s built-in legacy ids ({@code frailty},
 *                 {@code clumsiness}, {@code sluggishness}) - kept for backward compatibility with
 *                 pre-Task-12 tree JSON. When {@link #effect} is present, {@code curseId} is purely
 *                 a descriptive/log label and any string is fine.
 * @param severity relative strength of the curse's effect; only consulted by the legacy
 *                 {@code curseId} dispatch (ignored when {@link #effect} is present, since the
 *                 effect payload already carries its own magnitude).
 * @param when     context this curse's drawback is gated behind: {@code ""} (default) means
 *                 "always, applied once at unlock"; a nonblank value (e.g. {@code "deep_dark"},
 *                 {@code "daylight"}) means the drawback is applied/removed dynamically by {@code
 *                 CurseContextService}'s server tick instead, based on whether the owning player
 *                 currently matches that context. Unrecognized contexts are treated as never-active
 *                 (logged once, not applied) rather than erroring - see {@code
 *                 CurseContextService#matchesContext}.
 * @param effect   the drawback itself, as a plain {@link AbilityDef} (typically {@link
 *                 AttributeMod} or {@link StatusEffectDef}) to apply/reverse. Absent for
 *                 backward-compatible pre-Task-12 JSON, which instead relies on {@code
 *                 CurseService}'s hardcoded {@code curseId} dispatch.
 */
public record CurseDef(String curseId, int severity, String when, Optional<AbilityDef> effect) implements AbilityDef {
    public static final MapCodec<CurseDef> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("curse_id").forGetter(CurseDef::curseId),
            Codec.INT.fieldOf("severity").forGetter(CurseDef::severity),
            Codec.STRING.optionalFieldOf("when", "").forGetter(CurseDef::when),
            AbilityDef.CODEC.optionalFieldOf("effect").forGetter(CurseDef::effect)
    ).apply(instance, CurseDef::new));

    @Override
    public String type() {
        return "curse";
    }
}
