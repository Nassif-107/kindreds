package com.kindreds.progression;

import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-race, per-discipline xp gain multipliers (e.g. Elves are naturally better at Archery).
 *
 * <p>Backed by a mutable in-memory table, defaulting to a hardcoded P1 table. {@link #setTable}
 * is the override hook a future data-pack loader (reading {@code data/kindreds/race_scaling/*.json})
 * can call on reload to replace it wholesale; no JSON loading is wired up yet in this task, but the
 * table shape ({@code Map<race, Map<discipline, multiplier>>}) is exactly what such a loader would
 * produce.
 */
public final class RaceScaling {
    private static final Identifier ELF = Identifier.of("middle-earth", "elf");
    private static final Identifier DWARF = Identifier.of("middle-earth", "dwarf");

    private static final Identifier ARCHERY = Identifier.of("kindreds", "archery");
    private static final Identifier MINING = Identifier.of("kindreds", "mining");
    private static final Identifier SMITHING = Identifier.of("kindreds", "smithing");

    private static final Map<Identifier, Map<Identifier, Double>> DEFAULT_TABLE = Map.of(
            ELF, Map.of(
                    ARCHERY, 1.5,
                    MINING, 0.6
            ),
            DWARF, Map.of(
                    MINING, 1.6,
                    SMITHING, 1.4,
                    ARCHERY, 0.7
            )
    );

    /** Any race/discipline combination absent from the table falls back to this. */
    private static final double DEFAULT_MULTIPLIER = 1.0;

    private static Map<Identifier, Map<Identifier, Double>> table = new HashMap<>(DEFAULT_TABLE);

    private RaceScaling() {
    }

    /** The xp gain multiplier {@code race} gets in {@code discipline}, or {@code 1.0} if unset. */
    public static double multiplier(Identifier race, Identifier discipline) {
        Map<Identifier, Double> byDiscipline = table.get(race);
        if (byDiscipline == null) {
            return DEFAULT_MULTIPLIER;
        }
        return byDiscipline.getOrDefault(discipline, DEFAULT_MULTIPLIER);
    }

    /**
     * Replaces the entire table (e.g. from a data-pack reload). Combinations absent from
     * {@code newTable} fall back to {@link #DEFAULT_MULTIPLIER}, not to the previous table's
     * values.
     */
    public static void setTable(Map<Identifier, Map<Identifier, Double>> newTable) {
        table = new HashMap<>(newTable);
    }

    /** Restores the hardcoded P1 default table, discarding any override installed via {@link #setTable}. */
    public static void resetToDefaults() {
        table = new HashMap<>(DEFAULT_TABLE);
    }
}
