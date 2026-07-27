package com.kindreds.threat;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class EliteMobsTest {

    @Test
    void promotionPicksARealAbilityAndAFamilyName() {
        // seeded Random: deterministic pick, and the name key embeds the mob's own family
        MobMark mark = EliteMobs.choose("orc_kin", new Random(42));
        assertTrue(EliteMobs.abilityPool().contains(mark.eliteAbility()),
                "picked an ability outside the pool: " + mark.eliteAbility());
        assertTrue(mark.eliteName().startsWith("kindreds.elite.name.orc_kin."),
                "an orc champion must not carry a troll's name");
    }

    @Test
    void everyAbilityIsReachable() {
        // Asserted against the pool itself rather than a retyped copy of it. The copy was a bug
        // waiting to happen: growing the pool without editing this line would have left every new
        // ability untested while the test carried on passing - which is exactly the failure this test
        // exists to catch in the production code.
        java.util.Set<String> seen = new java.util.HashSet<>();
        Random r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            seen.add(EliteMobs.choose("undead", r).eliteAbility());
        }
        assertEquals(new java.util.HashSet<>(EliteMobs.abilityPool()), seen,
                "a pool entry no roll can reach is a dead ability");
    }

    @Test
    void everyAbilityInThePoolValidates() {
        // The doctor calls abilityFor to decide whether a live mob's mark is corrupt. An ability that
        // rolls but does not validate would have it reporting healthy champions as damaged data.
        for (String ability : EliteMobs.abilityPool()) {
            assertTrue(EliteMobs.abilityFor(ability), ability + " is rolled but does not validate");
        }
        assertFalse(EliteMobs.abilityFor("not_an_ability"));
    }

    @Test
    void thePoolHasNoRepeats() {
        assertEquals(EliteMobs.abilityPool().size(),
                new java.util.HashSet<>(EliteMobs.abilityPool()).size(),
                "a duplicated pool entry rolls twice as often as it reads");
    }
}
