package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the two parts of {@link MobDanger} that do not need a running game to prove: the id-path
 * classifier and the pure arithmetic of {@link MobDanger#expectedAt}. {@link MobDanger#of} and
 * {@link MobDanger#isInScope} touch live Minecraft attribute/entity state and are exercised in-game
 * instead (see the client gametest suite), not here.
 */
class MobDangerTest {

    @Test
    void trollsMatchTrollOrGiant() {
        assertEquals("trolls", MobDanger.family("cave_troll"));
        assertEquals("trolls", MobDanger.family("hill_troll"));
        assertEquals("trolls", MobDanger.family("giant"));
    }

    @Test
    void spidersMatchSpiderOrShelob() {
        assertEquals("spiders", MobDanger.family("cave_spider"));
        assertEquals("spiders", MobDanger.family("shelob"));
    }

    @Test
    void wargsMatchWargOrWolf() {
        assertEquals("wargs", MobDanger.family("warg"));
        assertEquals("wargs", MobDanger.family("wolf"));
    }

    @Test
    void orcKinMatchesOrcUrukGoblinSnagaOrNpc() {
        assertEquals("orc_kin", MobDanger.family("mordor_orc"));
        assertEquals("orc_kin", MobDanger.family("uruk_hai"));
        assertEquals("orc_kin", MobDanger.family("goblin"));
        assertEquals("orc_kin", MobDanger.family("snaga"));
        assertEquals("orc_kin", MobDanger.family("npc"));
    }

    @Test
    void undeadMatchesZombieSkeletonHuskDrownedOrWither() {
        assertEquals("undead", MobDanger.family("zombie"));
        assertEquals("undead", MobDanger.family("skeleton"));
        assertEquals("undead", MobDanger.family("husk"));
        assertEquals("undead", MobDanger.family("drowned"));
        assertEquals("undead", MobDanger.family("wither_skeleton"));
    }

    @Test
    void anUnmatchedIdFallsThroughToOther() {
        assertEquals("other", MobDanger.family("pig"));
        assertEquals("other", MobDanger.family("villager"));
    }

    @Test
    void expectedAtIsLinearFromAZombieAcrossTheWholeRange() {
        // a zombie (20 hp x 3 dmg = 60) is the floor, at threat 0
        assertEquals(60.0, MobDanger.expectedAt(0f), 0.001);
        // the top of the range is roughly ten zombies' worth
        assertEquals(600.0, MobDanger.expectedAt(100f), 0.001);
        // halfway through threat is halfway up the line, not some other curve
        assertEquals(330.0, MobDanger.expectedAt(50f), 0.001);
    }

    @Test
    void expectedAtClampsThreatToItsValidRange() {
        assertEquals(60.0, MobDanger.expectedAt(-50f), 0.001);
        assertEquals(600.0, MobDanger.expectedAt(250f), 0.001);
    }
}
