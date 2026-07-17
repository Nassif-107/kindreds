package com.kindreds.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;

import java.util.Map;

/**
 * One race's per-discipline XP multiplier table, data-driven (Task 12 Stage C).
 *
 * <p>Registered as its own synced dynamic registry ({@link KindredsRegistries#RACE_SCALING}),
 * loaded from {@code data/<namespace>/kindreds/race_scaling/<path>.json} the same way {@link Discipline}/
 * {@link SkillTree}/{@link Theme} are - e.g. {@code data/kindreds/kindreds/race_scaling/elf.json} resolves
 * as the registry entry {@code kindreds:elf}, whose {@link #race} field then names the actual
 * base-mod race ({@code middle-earth:elf}) it applies to. {@code com.kindreds.progression.RaceScaling}
 * materializes every entry in this registry into its plain in-memory lookup table on server start
 * and datapack reload (see {@code RaceScaling#loadFrom}) - gameplay code keeps calling {@code
 * RaceScaling.multiplier(race, discipline)} exactly as before; only where that table's data comes
 * from changed.
 *
 * @param race        the {@code middle-earth:*} race this multiplier table applies to
 * @param multipliers discipline id -&gt; xp gain multiplier; a discipline absent from this map
 *                    falls back to {@code RaceScaling}'s default multiplier (1.0)
 */
public record RaceScalingEntry(Identifier race, Map<Identifier, Double> multipliers) {
    public static final Codec<RaceScalingEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("race").forGetter(RaceScalingEntry::race),
            Codec.unboundedMap(Identifier.CODEC, Codec.DOUBLE).fieldOf("multipliers").forGetter(RaceScalingEntry::multipliers)
    ).apply(instance, RaceScalingEntry::new));
}
