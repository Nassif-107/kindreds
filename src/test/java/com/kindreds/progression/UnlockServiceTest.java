package com.kindreds.progression;

import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredData;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure rules test for {@link UnlockService#canUnlock}. Uses a tiny in-memory {@link SkillTree}
 * and fake points/deed suppliers - no Minecraft server needed.
 */
class UnlockServiceTest {

    private static final Identifier ARCHERY = Identifier.of("kindreds", "archery");
    private static final Identifier MINING = Identifier.of("kindreds", "mining");
    private static final Identifier DEED_A = Identifier.of("kindreds", "deeds/first_shot");

    // Always report plenty of points available, regardless of discipline.
    private static final ToIntFunction<Identifier> PLENTY_POINTS = discipline -> 99;
    // Never enough points, regardless of discipline.
    private static final ToIntFunction<Identifier> NO_POINTS = discipline -> 0;
    private static final Predicate<Identifier> ALL_DEEDS_EARNED = id -> true;
    private static final Predicate<Identifier> NO_DEEDS_EARNED = id -> false;

    private static SkillTree tree(SkillNode... nodes) {
        return new SkillTree(Identifier.of("middle-earth", "elf"), Identifier.of("kindreds", "elf_theme"), List.of(nodes));
    }

    private static SkillNode node(String id, Identifier discipline, int cost, List<String> prereqs,
                                   Optional<String> exclusiveGroup, Optional<Identifier> deed) {
        return new SkillNode(id, 0, new int[]{0, 0}, new SkillNode.Cost(discipline, cost), prereqs, List.of(),
                deed, exclusiveGroup, 1);
    }

    private static SkillNode simple(String id, Identifier discipline, int cost) {
        return node(id, discipline, cost, List.of(), Optional.empty(), Optional.empty());
    }

    private static SkillNode ranked(String id, Identifier discipline, int cost, int maxRank) {
        return new SkillNode(id, 0, new int[]{0, 0}, new SkillNode.Cost(discipline, cost), List.of(), List.of(),
                Optional.empty(), Optional.empty(), maxRank);
    }

    /** A rank-1 node is finished the moment it is bought - the old behaviour, unchanged. */
    @Test
    void plainNodeIsAlreadyUnlockedAfterOnePurchase() {
        SkillTree tree = tree(simple("archer_1", ARCHERY, 1));
        KindredData data = new KindredData();
        UnlockService.applyUnlock(data, "archer_1");

        assertEquals(1, data.rankOf("archer_1"));
        assertEquals("already_unlocked",
                UnlockService.canUnlock(data, tree, "archer_1", PLENTY_POINTS, ALL_DEEDS_EARNED).reason());
    }

    /** A multi-rank node stays buyable until its last rank, and then stops. */
    @Test
    void rankedNodeCanBeDeepenedUntilItsLastRank() {
        SkillTree tree = tree(ranked("deep_delver", MINING, 2, 3));
        KindredData data = new KindredData();

        assertTrue(UnlockService.canUnlock(data, tree, "deep_delver", PLENTY_POINTS, ALL_DEEDS_EARNED).ok());
        UnlockService.applyUnlock(data, "deep_delver");
        assertEquals(1, data.rankOf("deep_delver"));

        assertTrue(UnlockService.canUnlock(data, tree, "deep_delver", PLENTY_POINTS, ALL_DEEDS_EARNED).ok(),
                "rank 2 of 3 must still be buyable");
        UnlockService.applyUnlock(data, "deep_delver");
        UnlockService.applyUnlock(data, "deep_delver");
        assertEquals(3, data.rankOf("deep_delver"));

        assertEquals("already_unlocked",
                UnlockService.canUnlock(data, tree, "deep_delver", PLENTY_POINTS, ALL_DEEDS_EARNED).reason(),
                "past the last rank there is nothing left to buy");
    }

    /** Every rank is paid for, so the tree-wide spend counts them all - otherwise deepening would be
     * a way to slip past the point cap for free. */
    @Test
    void everyRankCountsTowardPointsSpent() {
        SkillTree tree = tree(ranked("deep_delver", MINING, 2, 3));
        KindredData data = new KindredData();

        UnlockService.applyUnlock(data, "deep_delver");
        assertEquals(2, UnlockService.totalPointsSpent(data, tree));
        UnlockService.applyUnlock(data, "deep_delver");
        assertEquals(4, UnlockService.totalPointsSpent(data, tree));
        UnlockService.applyUnlock(data, "deep_delver");
        assertEquals(6, UnlockService.totalPointsSpent(data, tree));
    }

    /** An unowned node is rank 0; a node owned before ranks existed reads as rank 1 with no entry in
     * the rank table, which is what keeps old saves meaning exactly what they meant. */
    @Test
    void anOwnedNodeWithNoRankEntryIsRankOne() {
        KindredData data = new KindredData();
        assertEquals(0, data.rankOf("never_bought"));

        data.unlockedNodes().add("legacy_node");     // as an old save decodes it: owned, no rank entry
        assertEquals(1, data.rankOf("legacy_node"));
        assertTrue(data.nodeRanks().isEmpty(), "rank 1 must not need an entry");
    }

    @Test
    void failsWithInsufficientPoints() {
        SkillTree tree = tree(simple("archer_1", ARCHERY, 5));
        KindredData data = new KindredData();

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "archer_1", NO_POINTS, ALL_DEEDS_EARNED);

        assertFalse(result.ok());
        assertEquals("insufficient_points", result.reason());
    }

    @Test
    void failsWithMissingPrereq() {
        SkillTree tree = tree(
                simple("archer_1", ARCHERY, 1),
                node("archer_2", ARCHERY, 1, List.of("archer_1"), Optional.empty(), Optional.empty()));
        KindredData data = new KindredData(); // archer_1 NOT unlocked

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "archer_2", PLENTY_POINTS, ALL_DEEDS_EARNED);

        assertFalse(result.ok());
        assertEquals("missing_prereq", result.reason());
    }

    @Test
    void failsWithMissingPrereqWhenOnlyOneOfTwoOwned() {
        // archer_3 requires BOTH archer_1 and archer_2; only archer_1 is owned. Proves every
        // prereq id is checked, not just the first one in the list.
        SkillTree tree = tree(
                simple("archer_1", ARCHERY, 1),
                simple("archer_2", ARCHERY, 1),
                node("archer_3", ARCHERY, 1, List.of("archer_1", "archer_2"), Optional.empty(), Optional.empty()));
        KindredData data = new KindredData();
        data.unlockedNodes().add("archer_1"); // archer_2 NOT unlocked

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "archer_3", PLENTY_POINTS, ALL_DEEDS_EARNED);

        assertFalse(result.ok());
        assertEquals("missing_prereq", result.reason());
    }

    @Test
    void failsWithExclusiveConflict() {
        SkillTree tree = tree(
                node("path_a", ARCHERY, 1, List.of(), Optional.of("vocation"), Optional.empty()),
                node("path_b", ARCHERY, 1, List.of(), Optional.of("vocation"), Optional.empty()));
        KindredData data = new KindredData();
        data.unlockedNodes().add("path_a"); // already committed to path_a

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "path_b", PLENTY_POINTS, ALL_DEEDS_EARNED);

        assertFalse(result.ok());
        assertEquals("exclusive_conflict", result.reason());
    }

    @Test
    void failsWhenDeedNotEarned() {
        SkillTree tree = tree(node("capstone", ARCHERY, 1, List.of(), Optional.empty(), Optional.of(DEED_A)));
        KindredData data = new KindredData();

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "capstone", PLENTY_POINTS, NO_DEEDS_EARNED);

        assertFalse(result.ok());
        assertEquals("deed_not_earned", result.reason());
    }

    @Test
    void failsWhenAlreadyUnlocked() {
        SkillTree tree = tree(simple("archer_1", ARCHERY, 1));
        KindredData data = new KindredData();
        data.unlockedNodes().add("archer_1");

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "archer_1", PLENTY_POINTS, ALL_DEEDS_EARNED);

        assertFalse(result.ok());
        assertEquals("already_unlocked", result.reason());
    }

    @Test
    void okWhenAllChecksSatisfied() {
        SkillTree tree = tree(
                simple("archer_1", ARCHERY, 1),
                node("archer_2", ARCHERY, 1, List.of("archer_1"), Optional.of("vocation"), Optional.of(DEED_A)));
        KindredData data = new KindredData();
        data.unlockedNodes().add("archer_1");

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "archer_2", PLENTY_POINTS, ALL_DEEDS_EARNED);

        assertTrue(result.ok());
        assertEquals("ok", result.reason());
    }

    @Test
    void unknownNodeFails() {
        SkillTree tree = tree(simple("archer_1", ARCHERY, 1));
        KindredData data = new KindredData();

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "does_not_exist", PLENTY_POINTS, ALL_DEEDS_EARNED);

        assertFalse(result.ok());
        assertEquals("unknown_node", result.reason());
    }

    @Test
    void checkOrderPrefersAlreadyUnlockedOverOtherFailures() {
        // archer_1 is both already unlocked AND (hypothetically) would fail on points/prereqs if
        // re-evaluated - already_unlocked must win since re-unlocking is nonsensical regardless.
        SkillTree tree = tree(simple("archer_1", ARCHERY, 5));
        KindredData data = new KindredData();
        data.unlockedNodes().add("archer_1");

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "archer_1", NO_POINTS, NO_DEEDS_EARNED);

        assertEquals("already_unlocked", result.reason());
    }

    @Test
    void pointsCheckedBeforePrereqs() {
        SkillTree tree = tree(
                simple("archer_1", ARCHERY, 1),
                node("archer_2", ARCHERY, 5, List.of("archer_1"), Optional.empty(), Optional.empty()));
        KindredData data = new KindredData(); // no points, no prereq either

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "archer_2", NO_POINTS, ALL_DEEDS_EARNED);

        assertEquals("insufficient_points", result.reason());
    }

    @Test
    void applyUnlockAddsNodeIdToUnlockedNodes() {
        KindredData data = new KindredData();
        assertFalse(data.hasNode("archer_1"));

        UnlockService.applyUnlock(data, "archer_1");

        assertTrue(data.hasNode("archer_1"));
        assertEquals(Set.of("archer_1"), data.unlockedNodes());
    }

    @Test
    void unrelatedDisciplinePointsDoNotAffectCheck() {
        // MINING has plenty of points, but the node costs ARCHERY - verify the correct
        // discipline id is what gets passed to the points function.
        SkillTree tree = tree(simple("archer_1", ARCHERY, 5));
        KindredData data = new KindredData();
        ToIntFunction<Identifier> onlyMiningHasPoints = discipline -> discipline.equals(MINING) ? 99 : 0;

        UnlockService.UnlockResult result = UnlockService.canUnlock(
                data, tree, "archer_1", onlyMiningHasPoints, ALL_DEEDS_EARNED);

        assertEquals("insufficient_points", result.reason());
    }

    // --- tree-wide point cap ---------------------------------------------------------------------

    /** Runs {@code body} with a temporary config in place, then restores whatever was there. */
    private static void withCap(int capPercent, Runnable body) {
        com.kindreds.config.KindredsConfig previous = com.kindreds.Kindreds.CONFIG;
        com.kindreds.config.KindredsConfig c = new com.kindreds.config.KindredsConfig();
        c.pointCapPercent = capPercent;
        c.pointSoftCap = 0;
        com.kindreds.Kindreds.CONFIG = c;
        try {
            body.run();
        } finally {
            com.kindreds.Kindreds.CONFIG = previous;
        }
    }

    @Test
    void maxSpendableCountsOnlyTheCheapestOfAnExclusiveGroup() {
        SkillTree tree = tree(
                simple("plain", ARCHERY, 4),
                node("path_a", ARCHERY, 3, List.of(), Optional.of("tip"), Optional.empty()),
                node("path_b", ARCHERY, 5, List.of(), Optional.of("tip"), Optional.empty()));

        // 4 (plain) + 3 (the cheaper of the two rivals) - never both branches, since owning one
        // permanently closes the other.
        assertEquals(7, UnlockService.maxSpendable(tree));
    }

    @Test
    void capIsAPercentageOfTheTreesOwnSize() {
        SkillTree tree = tree(simple("a", ARCHERY, 10), simple("b", MINING, 10));
        KindredData data = new KindredData();
        withCap(50, () -> assertEquals(10, UnlockService.effectiveCap(tree, data)));
        // 100% means "spend it all" - reported as 0, the no-cap sentinel.
        withCap(100, () -> assertEquals(0, UnlockService.effectiveCap(tree, data)));
    }

    @Test
    void failsWithSoftCapOnceTheCeilingIsReached() {
        SkillTree tree = tree(simple("a", ARCHERY, 10), simple("b", MINING, 10));
        KindredData data = new KindredData();
        data.unlockedNodes().add("a"); // 10 of a 10-point ceiling already committed

        withCap(50, () -> {
            UnlockService.UnlockResult result = UnlockService.canUnlock(
                    data, tree, "b", PLENTY_POINTS, ALL_DEEDS_EARNED);
            assertFalse(result.ok());
            assertEquals("soft_cap", result.reason());
        });
        // The same purchase is fine when the cap is off.
        withCap(100, () -> assertTrue(UnlockService.canUnlock(
                data, tree, "b", PLENTY_POINTS, ALL_DEEDS_EARNED).ok()));
    }

    @Test
    void moreSpecificFailuresWinOverTheCap() {
        SkillTree tree = tree(simple("a", ARCHERY, 10),
                node("b", MINING, 10, List.of("never_owned"), Optional.empty(), Optional.empty()));
        KindredData data = new KindredData();
        data.unlockedNodes().add("a");

        // Both the cap AND a missing prereq block this; the player needs to hear the actionable one.
        withCap(50, () -> assertEquals("missing_prereq", UnlockService.canUnlock(
                data, tree, "b", PLENTY_POINTS, ALL_DEEDS_EARNED).reason()));
    }

    // --- renown widens the cap -------------------------------------------------------------------

    @Test
    void eachGreatDeedWidensTheCap() {
        SkillTree tree = tree(simple("a", ARCHERY, 50), simple("b", MINING, 50)); // 100 points total
        KindredData data = elf();

        withCap(50, () -> {
            assertEquals(50, UnlockService.effectiveCap(tree, data));
            data.renown().add("renown/elf/road_to_lorien");
            assertEquals(55, UnlockService.effectiveCap(tree, data));
            data.renown().add("renown/elf/marchwarden");
            assertEquals(60, UnlockService.effectiveCap(tree, data));
        });
    }

    @Test
    void theBargainWidensTheCapOnTopOfDeeds() {
        SkillTree tree = tree(simple("a", ARCHERY, 50), simple("b", MINING, 50));
        KindredData data = elf();
        data.renown().add("renown/elf/road_to_lorien"); // +5
        data.setCorruption(1);                          // +10

        withCap(50, () -> assertEquals(65, UnlockService.effectiveCap(tree, data)));
    }

    @Test
    void renownCanNeverBuyTheWholeTree() {
        SkillTree tree = tree(simple("a", ARCHERY, 50), simple("b", MINING, 50));
        KindredData data = elf();
        for (String deed : List.of("a", "b", "c", "d")) {
            data.renown().add("renown/elf/" + deed); // +20
        }
        data.setCorruption(1);                    // +10 -> 105% on Fireside-adjacent settings

        // Clamped: every deed done AND the bargain struck still leaves something ungrasped.
        withCap(90, () -> assertEquals(95, UnlockService.effectiveCap(tree, data)));
    }

    @Test
    void renownDoesNotReviveACapThatIsSwitchedOff() {
        SkillTree tree = tree(simple("a", ARCHERY, 50), simple("b", MINING, 50));
        KindredData data = elf();
        data.renown().add("renown/elf/road_to_lorien");

        withCap(100, () -> assertEquals(0, UnlockService.effectiveCap(tree, data)));
    }

    /** A player of the Firstborn - renown only counts for the kindred that performed it. */
    private static KindredData elf() {
        KindredData data = new KindredData();
        data.setRace(Identifier.of("middle-earth", "elf"));
        return data;
    }

    @Test
    void anotherKindredsDeedsDoNotCountForYou() {
        SkillTree tree = tree(simple("a", ARCHERY, 50), simple("b", MINING, 50));
        KindredData data = elf();
        data.renown().add("renown/dwarf/khazad_work");   // done in a former life as a Dwarf
        data.renown().add("renown/dwarf/long_memory");
        data.renown().add("renown/elf/marchwarden");     // the only one that is actually yours

        withCap(50, () -> assertEquals(55, UnlockService.effectiveCap(tree, data)));

        // ...and they are still on record, so returning to that kindred restores them.
        data.setRace(Identifier.of("middle-earth", "dwarf"));
        withCap(50, () -> assertEquals(60, UnlockService.effectiveCap(tree, data)));
    }
}
