package com.kindreds.progression;

import com.kindreds.data.RaceScalingEntry;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-race, per-discipline xp gain multipliers (e.g. Elves are naturally better at Archery).
 *
 * <p>Backed by a mutable in-memory table, defaulting to a hardcoded P1 table until {@link
 * #loadFrom} runs at least once. Task 12 Stage C wired up the data-pack loader this class's
 * original javadoc anticipated: {@code data/kindreds/race_scaling/*.json} decodes into {@link
 * RaceScalingEntry} entries in the {@code kindreds:race_scaling} dynamic registry, and {@link
 * #loadFrom} materializes that registry into this class's plain table on server start and every
 * datapack reload (see {@code Kindreds#onInitialize()}) - {@link #multiplier} itself is unchanged,
 * so nothing downstream needed to change when the data source did.
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

    /** Rebuilds the table wholesale from every {@link RaceScalingEntry} currently in {@code
     * registry} (keyed by each entry's own {@link RaceScalingEntry#race()}, not its registry id) -
     * called on server start and datapack reload. An empty registry (e.g. a datapack that ships no
     * {@code race_scaling} JSON at all) leaves every race at the {@link #DEFAULT_MULTIPLIER}
     * fallback, same as any race/discipline combination the loaded data doesn't mention. */
    public static void loadFrom(Registry<RaceScalingEntry> registry) {
        Map<Identifier, Map<Identifier, Double>> newTable = new HashMap<>();
        for (RaceScalingEntry entry : registry) {
            newTable.put(entry.race(), new HashMap<>(entry.multipliers()));
        }
        setTable(newTable);
    }
}
