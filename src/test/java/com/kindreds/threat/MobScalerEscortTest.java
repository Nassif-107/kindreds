package com.kindreds.threat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The 80%-cap suppression is what keeps escorts from ever being a runaway population (spec §3).
 * cap = capacity * spawningChunks / 289 - 289 is SpawnHelper.CHUNK_AREA (17x17), package-private in
 * vanilla, hardcoded here and guarded by this test's arithmetic. */
class MobScalerEscortTest {

    @Test
    void escortsStopAtEightyPercentOfTheMonsterCap() {
        // 70 capacity * 289 spawning chunks / 289 = cap 70; 80% = 56
        assertEquals(0, MobScaler.escortBudget(56, 70, 289), "at 80% exactly, no escorts");
        assertEquals(0, MobScaler.escortBudget(69, 70, 289), "nearly full, no escorts");
        assertEquals(2, MobScaler.escortBudget(20, 70, 289), "a quiet night can be crowded");
    }

    @Test
    void anEmptySpawningAreaHasNoBudget() {
        assertEquals(0, MobScaler.escortBudget(0, 70, 0), "no spawning chunks -> cap 0 -> no escorts");
    }
}
