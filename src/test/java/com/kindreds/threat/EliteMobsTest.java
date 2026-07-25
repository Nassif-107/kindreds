package com.kindreds.threat;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class EliteMobsTest {

    @Test
    void promotionPicksARealAbilityAndAFamilyName() {
        // seeded Random: deterministic pick, and the name key embeds the mob's own family
        MobMark mark = EliteMobs.choose("orc_kin", new Random(42));
        assertTrue(java.util.List.of("aura", "rally", "swift", "bulwark").contains(mark.eliteAbility()));
        assertTrue(mark.eliteName().startsWith("kindreds.elite.name.orc_kin."),
                "an orc champion must not carry a troll's name");
    }

    @Test
    void everyAbilityIsReachable() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            seen.add(EliteMobs.choose("undead", r).eliteAbility());
        }
        assertEquals(java.util.Set.of("aura", "rally", "swift", "bulwark"), seen,
                "a pool entry no roll can reach is a dead ability");
    }
}
