package com.kindreds.playerdata;

import com.kindreds.config.DeathPenalty;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.progression.LevelCurve;
import com.kindreds.progression.ProgressionService;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeathHandler}'s pure death-penalty math ({@link
 * DeathHandler#applyDeathPenalty}), which is factored out precisely so it's testable without a
 * live {@code ServerPlayerEntity} - the MC-bound {@code COPY_FROM} wiring itself is per the task
 * brief not unit-tested.
 */
class DeathHandlerTest {

    private static final Identifier ELF = Identifier.of("middle-earth", "elf");
    private static final Identifier ARCHERY = Identifier.of("kindreds", "archery");
    private static final Identifier MINING = Identifier.of("kindreds", "mining");
    private static final Identifier BIOME = Identifier.of("minecraft", "forest");

    // --- KEEP ---------------------------------------------------------------------------------

    @Test
    void keepCopiesDataUnchangedAndIndependently() {
        KindredData original = new KindredData();
        original.addXp(ARCHERY, 500L);
        original.unlockedNodes().add("keen_eyes");
        original.discoveredBiomes().add(BIOME);

        KindredData copy = DeathHandler.applyDeathPenalty(DeathPenalty.KEEP, 0.0, original, Optional.empty());

        assertEquals(500L, copy.xpIn(ARCHERY));
        assertTrue(copy.hasNode("keen_eyes"));
        assertTrue(copy.discoveredBiomes().contains(BIOME));

        // Deep copy: mutating either side afterward must not affect the other.
        original.addXp(ARCHERY, 999L);
        copy.addXp(MINING, 1L);
        assertEquals(500L, copy.xpIn(ARCHERY));
        assertEquals(0L, original.xpIn(MINING));
    }

    // --- LOSE_UNSPENT ---------------------------------------------------------------------------

    @Test
    void loseUnspentReducesXpDownToSpentLevelWithTree() {
        KindredData data = new KindredData();
        data.addXp(ARCHERY, LevelCurve.xpForLevel(5)); // well beyond what's spent below
        data.unlockedNodes().add("archer_1"); // costs 2 points in ARCHERY

        SkillTree tree = new SkillTree(ELF, Identifier.of("kindreds", "elf_theme"), List.of(
                node("archer_1", ARCHERY, 2)));

        // Sanity: 2 points really are spent per ProgressionService, and less than level 5's total.
        assertEquals(2, ProgressionService.pointsSpent(data, tree, ARCHERY));

        KindredData penalized = DeathHandler.applyDeathPenalty(
                DeathPenalty.LOSE_UNSPENT, 0.0, data, Optional.of(tree));

        assertEquals(LevelCurve.xpForLevel(2), penalized.xpIn(ARCHERY));
        assertTrue(penalized.hasNode("archer_1"), "already-unlocked nodes must survive LOSE_UNSPENT");
    }

    @Test
    void loseUnspentWithNoTreeTreatsSpentAsZeroForEveryDiscipline() {
        KindredData data = new KindredData();
        data.addXp(ARCHERY, LevelCurve.xpForLevel(4));
        data.addXp(MINING, LevelCurve.xpForLevel(1));

        KindredData penalized = DeathHandler.applyDeathPenalty(
                DeathPenalty.LOSE_UNSPENT, 0.0, data, Optional.empty());

        assertEquals(0L, penalized.xpIn(ARCHERY));
        assertEquals(0L, penalized.xpIn(MINING));
    }

    @Test
    void loseUnspentLeavesDisciplinesWithNoUnspentXpUntouched() {
        KindredData data = new KindredData();
        data.unlockedNodes().add("archer_1"); // costs exactly 2 points, xp set to exactly that below
        data.addXp(ARCHERY, LevelCurve.xpForLevel(2));

        SkillTree tree = new SkillTree(ELF, Identifier.of("kindreds", "elf_theme"), List.of(
                node("archer_1", ARCHERY, 2)));

        KindredData penalized = DeathHandler.applyDeathPenalty(
                DeathPenalty.LOSE_UNSPENT, 0.0, data, Optional.of(tree));

        assertEquals(LevelCurve.xpForLevel(2), penalized.xpIn(ARCHERY));
    }

    // --- LOSE_PERCENT ---------------------------------------------------------------------------

    @Test
    void losePercentMultipliesXpByKeptFractionRounded() {
        KindredData data = new KindredData();
        data.addXp(ARCHERY, 100L);
        data.addXp(MINING, 7L); // 7 * 0.75 = 5.25 -> rounds to 5

        KindredData penalized = DeathHandler.applyDeathPenalty(
                DeathPenalty.LOSE_PERCENT, 0.25, data, Optional.empty());

        assertEquals(75L, penalized.xpIn(ARCHERY));
        assertEquals(5L, penalized.xpIn(MINING));
    }

    @Test
    void losePercentKeepsUnlockedNodes() {
        KindredData data = new KindredData();
        data.addXp(ARCHERY, 100L);
        data.unlockedNodes().add("archer_1");

        KindredData penalized = DeathHandler.applyDeathPenalty(
                DeathPenalty.LOSE_PERCENT, 0.5, data, Optional.empty());

        assertEquals(50L, penalized.xpIn(ARCHERY));
        assertTrue(penalized.hasNode("archer_1"));
    }

    // --- HARDCORE -------------------------------------------------------------------------------

    @Test
    void hardcoreWipesRegardlessOfInputData() {
        KindredData data = new KindredData();
        data.addXp(ARCHERY, 99999L);
        data.unlockedNodes().add("archer_1");
        data.discoveredBiomes().add(BIOME);
        data.setCorruption(5);

        KindredData wiped = DeathHandler.applyDeathPenalty(DeathPenalty.HARDCORE, 0.0, data, Optional.empty());

        assertEquals(0L, wiped.xpIn(ARCHERY));
        assertTrue(wiped.unlockedNodes().isEmpty());
        assertTrue(wiped.discoveredBiomes().isEmpty());
        assertEquals(0, wiped.corruption());
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
                Optional.empty());
    }
}
