package com.kindreds.progression;

import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredData;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionTest {

    private static final Identifier ELF = Identifier.of("middle-earth", "elf");
    private static final Identifier DWARF = Identifier.of("middle-earth", "dwarf");
    private static final Identifier HUMAN = Identifier.of("middle-earth", "human");
    private static final Identifier ARCHERY = Identifier.of("kindreds", "archery");
    private static final Identifier MINING = Identifier.of("kindreds", "mining");
    private static final Identifier SMITHING = Identifier.of("kindreds", "smithing");

    @AfterEach
    void resetRaceScalingTable() {
        RaceScaling.resetToDefaults();
    }

    // --- LevelCurve -----------------------------------------------------------------------------

    @Test
    void levelZeroIsXpZero() {
        assertEquals(0L, LevelCurve.xpForLevel(0));
        assertEquals(0, LevelCurve.levelForXp(0L));
    }

    @Test
    void xpForLevelIsStrictlyIncreasing() {
        long previous = -1;
        for (int level = 0; level <= 100; level++) {
            long xp = LevelCurve.xpForLevel(level);
            assertTrue(xp > previous, "xpForLevel(" + level + ") should exceed the previous level's xp");
            previous = xp;
        }
    }

    @Test
    void levelForXpRoundTripsXpForLevel() {
        for (int level : new int[]{0, 1, 2, 3, 5, 10, 25, 50, 100}) {
            long xp = LevelCurve.xpForLevel(level);
            assertEquals(level, LevelCurve.levelForXp(xp),
                    "round trip failed for level " + level + " (xp=" + xp + ")");
        }
    }

    @Test
    void levelForXpIsMonotonicNonDecreasing() {
        int previousLevel = 0;
        for (long xp = 0; xp <= 200_000; xp += 137) {
            int level = LevelCurve.levelForXp(xp);
            assertTrue(level >= previousLevel, "level should never decrease as xp grows");
            previousLevel = level;
        }
    }

    // --- RaceScaling ------------------------------------------------------------------------------

    @Test
    void raceScalingReturnsDefaultTableValues() {
        assertEquals(1.5, RaceScaling.multiplier(ELF, ARCHERY), 1e-9);
        assertEquals(0.6, RaceScaling.multiplier(ELF, MINING), 1e-9);
        assertEquals(1.6, RaceScaling.multiplier(DWARF, MINING), 1e-9);
        assertEquals(1.4, RaceScaling.multiplier(DWARF, SMITHING), 1e-9);
        assertEquals(0.7, RaceScaling.multiplier(DWARF, ARCHERY), 1e-9);
    }

    @Test
    void raceScalingDefaultsToOneForUnlistedCombinations() {
        assertEquals(1.0, RaceScaling.multiplier(HUMAN, ARCHERY), 1e-9);
        assertEquals(1.0, RaceScaling.multiplier(ELF, SMITHING), 1e-9);
        assertEquals(1.0, RaceScaling.multiplier(Identifier.of("middle-earth", "goblin"), MINING), 1e-9);
    }

    @Test
    void setTableOverridesDefaults() {
        RaceScaling.setTable(java.util.Map.of(
                ELF, java.util.Map.of(ARCHERY, 9.0)
        ));

        assertEquals(9.0, RaceScaling.multiplier(ELF, ARCHERY), 1e-9);
        // Overriding replaces the whole table, so previously-default entries are gone.
        assertEquals(1.0, RaceScaling.multiplier(DWARF, MINING), 1e-9);
    }

    // --- ProgressionService.awardXp --------------------------------------------------------------

    @Test
    void awardXpAppliesRaceScalingAndGlobalRate() {
        KindredData data = new KindredData();

        ProgressionService.awardXp(data, ELF, ARCHERY, 100L, 1.0);

        assertEquals(150L, data.xpIn(ARCHERY));
    }

    @Test
    void awardXpAppliesGlobalRateOnTopOfScaling() {
        KindredData data = new KindredData();

        ProgressionService.awardXp(data, ELF, ARCHERY, 100L, 2.0);

        assertEquals(300L, data.xpIn(ARCHERY));
    }

    @Test
    void awardXpAccumulatesAcrossCalls() {
        KindredData data = new KindredData();

        ProgressionService.awardXp(data, DWARF, MINING, 100L, 1.0); // 160
        ProgressionService.awardXp(data, DWARF, MINING, 50L, 1.0);  // +80

        assertEquals(240L, data.xpIn(MINING));
    }

    @Test
    void awardXpRoundsFractionalScaledXp() {
        KindredData data = new KindredData();

        // 0.7 * 3 = 2.1 -> rounds to 2
        ProgressionService.awardXp(data, DWARF, ARCHERY, 3L, 1.0);

        assertEquals(2L, data.xpIn(ARCHERY));
    }

    // --- ProgressionService points math -----------------------------------------------------------

    @Test
    void pointsForLevelMatchesLevelCurve() {
        long xp = LevelCurve.xpForLevel(7);
        assertEquals(7, ProgressionService.pointsForLevel(xp));
    }

    @Test
    void pointsAvailableSubtractsSpentFromLevel() {
        KindredData data = new KindredData();
        ProgressionService.awardXp(data, HUMAN, MINING, LevelCurve.xpForLevel(5), 1.0);

        assertEquals(5, ProgressionService.pointsAvailable(data, MINING, 0));
        assertEquals(3, ProgressionService.pointsAvailable(data, MINING, 2));
    }

    @Test
    void pointsSpentCountsOnlyUnlockedNodesInMatchingDiscipline() {
        KindredData data = new KindredData();
        data.unlockedNodes().add("archer_1"); // cost 1 in archery, unlocked
        data.unlockedNodes().add("archer_2"); // cost 2 in archery, unlocked
        // "miner_1" (cost in mining) deliberately left locked.

        SkillTree tree = new SkillTree(HUMAN, Identifier.of("kindreds", "human_theme"), List.of(
                node("archer_1", ARCHERY, 1),
                node("archer_2", ARCHERY, 2),
                node("miner_1", MINING, 4)
        ));

        assertEquals(3, ProgressionService.pointsSpent(data, tree, ARCHERY));
        assertEquals(0, ProgressionService.pointsSpent(data, tree, MINING));
    }

    @Test
    void pointsAvailableWithTreeDerivesSpentAutomatically() {
        KindredData data = new KindredData();
        ProgressionService.awardXp(data, HUMAN, ARCHERY, LevelCurve.xpForLevel(4), 1.0);
        data.unlockedNodes().add("archer_1"); // cost 1

        SkillTree tree = new SkillTree(HUMAN, Identifier.of("kindreds", "human_theme"), List.of(
                node("archer_1", ARCHERY, 1)
        ));

        assertEquals(3, ProgressionService.pointsAvailable(data, tree, ARCHERY));
    }

    private static SkillNode node(String id, Identifier discipline, int cost) {
        return new SkillNode(
                id,
                0,
                new int[]{0, 0},
                new SkillNode.Cost(discipline, cost),
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty()
        );
    }
}
