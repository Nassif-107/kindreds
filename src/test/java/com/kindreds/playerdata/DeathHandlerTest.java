package com.kindreds.playerdata;

import com.kindreds.config.DeathPenalty;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.progression.LevelCurve;
import com.kindreds.progression.ProgressionService;
import com.kindreds.threat.ThreatState;
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

    /** {@code renown} (Great Deeds) predates Task 3 but shares this exact method: {@code
     * copyOf} used to build the new {@link KindredData} with the 6-arg constructor, which
     * defaults {@code renown} to empty, silently erasing every Great Deed on death (and, via
     * {@code RenownService.bonusPercent}, shrinking the skill-point cap) until the player's next
     * relog re-derives it on {@code ServerPlayConnectionEvents.JOIN}. */
    @Test
    void keepCarriesRenownAcrossDeathAndCopyIsIndependent() {
        KindredData original = new KindredData();
        original.renown().add("renown/elf/starlit_aim");

        KindredData copy = DeathHandler.applyDeathPenalty(DeathPenalty.KEEP, 0.0, original, Optional.empty());

        assertTrue(copy.renown().contains("renown/elf/starlit_aim"), "a Great Deed must survive death");

        // Deep copy: mutating the original's renown set afterward must not affect the copy.
        original.renown().add("renown/elf/second_deed");
        assertFalse(copy.renown().contains("renown/elf/second_deed"));
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

    @Test
    void losePercentClampsDeathPercentAboveOneToFullLoss() {
        // A misconfigured deathPercent > 1.0 must never drive xp negative - clamp to full loss.
        KindredData data = new KindredData();
        data.addXp(ARCHERY, 100L);

        KindredData penalized = DeathHandler.applyDeathPenalty(
                DeathPenalty.LOSE_PERCENT, 1.5, data, Optional.empty());

        assertEquals(0L, penalized.xpIn(ARCHERY));
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

    // --- Threat survives death (the task's central anti-exploit guarantee) ---------------------
    // ThreatState.priorMark/maxHealthMark are high-water marks; a mark a player could reset by
    // dying would be a difficulty switch, which is the entire reason DeathHandler.copyOf calls
    // copy.setThreat(data.threat().copy()) instead of leaving a fresh (default) ThreatState on
    // the copy. Covered for every branch that goes through copyOf - KEEP, LOSE_UNSPENT and
    // LOSE_PERCENT. HARDCORE deliberately does NOT go through copyOf (see the class javadoc:
    // "old data discarded entirely") so it is not covered here.

    private static final float THREAT_PRIOR_MARK = 42.5f;
    private static final float THREAT_MAX_HEALTH_MARK = 30f;
    private static final float THREAT_COMPETENCE = 1.35f;
    private static final long THREAT_PLAYED_TICKS = 12345L;
    private static final String THREAT_FAMILY = "orcs";
    private static final float THREAT_FAMILY_COMPETENCE = 0.75f;

    private static void setNonDefaultThreat(KindredData data) {
        data.threat().setPriorMark(THREAT_PRIOR_MARK);
        data.threat().setMaxHealthMark(THREAT_MAX_HEALTH_MARK);
        data.threat().setCompetence(THREAT_COMPETENCE);
        data.threat().familyCompetence().put(THREAT_FAMILY, THREAT_FAMILY_COMPETENCE);
        data.threat().addPlayedTicks(THREAT_PLAYED_TICKS);
    }

    private static void assertNonDefaultThreat(ThreatState threat) {
        assertEquals(THREAT_PRIOR_MARK, threat.priorMark(), 0.001f);
        assertEquals(THREAT_MAX_HEALTH_MARK, threat.maxHealthMark(), 0.001f);
        assertEquals(THREAT_COMPETENCE, threat.competence(), 0.001f);
        assertEquals(THREAT_PLAYED_TICKS, threat.playedTicks());
        assertEquals(THREAT_FAMILY_COMPETENCE, threat.familyCompetence().get(THREAT_FAMILY), 0.001f);
    }

    /** Mutates {@code original}'s threat (scalars and the family map) to different values after a
     * copy has already been taken, so a shared (rather than deep-copied) ThreatState would be
     * caught by a subsequent {@link #assertNonDefaultThreat} on the copy. */
    private static void mutateThreatAfterCopy(KindredData original) {
        original.threat().setPriorMark(0f);
        original.threat().setMaxHealthMark(0f);
        original.threat().setCompetence(0f);
        original.threat().addPlayedTicks(999L);
        original.threat().familyCompetence().put(THREAT_FAMILY, 0f);
        original.threat().familyCompetence().put("trolls", 9f);
    }

    @Test
    void keepCarriesThreatAcrossDeathAndCopyIsIndependent() {
        KindredData original = new KindredData();
        setNonDefaultThreat(original);

        KindredData copy = DeathHandler.applyDeathPenalty(DeathPenalty.KEEP, 0.0, original, Optional.empty());

        assertNonDefaultThreat(copy.threat());

        mutateThreatAfterCopy(original);
        assertNonDefaultThreat(copy.threat());
        assertFalse(copy.threat().familyCompetence().containsKey("trolls"));
    }

    @Test
    void loseUnspentCarriesThreatAcrossDeathAndCopyIsIndependent() {
        KindredData original = new KindredData();
        setNonDefaultThreat(original);
        original.addXp(ARCHERY, LevelCurve.xpForLevel(5));
        original.unlockedNodes().add("archer_1");

        SkillTree tree = new SkillTree(ELF, Identifier.of("kindreds", "elf_theme"), List.of(
                node("archer_1", ARCHERY, 2)));

        KindredData copy = DeathHandler.applyDeathPenalty(
                DeathPenalty.LOSE_UNSPENT, 0.0, original, Optional.of(tree));

        assertNonDefaultThreat(copy.threat());

        mutateThreatAfterCopy(original);
        assertNonDefaultThreat(copy.threat());
        assertFalse(copy.threat().familyCompetence().containsKey("trolls"));
    }

    @Test
    void losePercentCarriesThreatAcrossDeathAndCopyIsIndependent() {
        KindredData original = new KindredData();
        setNonDefaultThreat(original);
        original.addXp(ARCHERY, 100L);

        KindredData copy = DeathHandler.applyDeathPenalty(
                DeathPenalty.LOSE_PERCENT, 0.5, original, Optional.empty());

        assertNonDefaultThreat(copy.threat());

        mutateThreatAfterCopy(original);
        assertNonDefaultThreat(copy.threat());
        assertFalse(copy.threat().familyCompetence().containsKey("trolls"));
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
