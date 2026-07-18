package com.kindreds.data.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Map;
import java.util.Optional;

/**
 * The flexible, data-first <b>perk</b>: a reactive/active gameplay mechanic a node (or birth trait)
 * grants, dispatched at runtime by {@code PerkService} to a handler registered under {@link #perk()}.
 * Unlike the other {@link AbilityDef} subtypes - which are each a fixed shape wired into this
 * package's exhaustive switches - a perk is a single generic carrier, so adding a brand-new mechanic
 * is "register one handler + author JSON", never a core edit. This is the engine that lets skill
 * nodes do real things (mine-fortune, heal-on-kill, bane damage, ally auras, arrow-slaying, ...)
 * instead of only bumping a stat.
 *
 * <h2>Shape</h2>
 * <pre>
 * { "type": "perk", "perk": "&lt;id&gt;",
 *   "params": { "chance": 0.2, "amount": 1 },   // optional tunables (default empty)
 *   "foe":    "spider",                          // optional foe category for combat perks
 *   "effect": { "type": "status_effect", ... } } // optional payload for strike/aura perks
 * </pre>
 *
 * <p>{@code params} keeps handlers free of per-perk record boilerplate; the typed {@link #foe()} and
 * {@link #effect()} exist because a foe category and a status-effect payload are common enough (and
 * awkward enough to stuff in a float map) to deserve first-class optional fields. Because a perk is
 * evaluated live on a game event and holds no persistent player state, {@code AbilityApplier} apply/
 * remove are deliberate no-ops for it (like {@link VisionUnlock}).
 *
 * @param perk    the handler id (e.g. {@code "bane"}, {@code "mining_fortune"}, {@code "heal_on_kill"})
 * @param params  named float tunables, defaulting to empty
 * @param foe     optional foe category ({@code spider}/{@code undead}/{@code illager}/{@code orc}/
 *                {@code dragon}/{@code troll}/{@code any}) for combat perks
 * @param effect  optional status-effect payload (strike-on-hit, ally aura, ...)
 */
public record PerkDef(String perk, Map<String, Float> params, Optional<String> foe, Optional<StatusEffectDef> effect)
        implements AbilityDef {

    public static final MapCodec<PerkDef> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("perk").forGetter(PerkDef::perk),
            Codec.unboundedMap(Codec.STRING, Codec.FLOAT).optionalFieldOf("params", Map.of()).forGetter(PerkDef::params),
            Codec.STRING.optionalFieldOf("foe").forGetter(PerkDef::foe),
            StatusEffectDef.CODEC.codec().optionalFieldOf("effect").forGetter(PerkDef::effect)
    ).apply(instance, PerkDef::new));

    @Override
    public String type() {
        return "perk";
    }

    /** A named tunable, or {@code fallback} when the perk's JSON didn't author it. */
    public float param(String key, float fallback) {
        Float value = params.get(key);
        return value != null ? value : fallback;
    }
}
