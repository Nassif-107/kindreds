package com.kindreds.threat;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure core of shared spawn difficulty: strongest-not-average, the group bonus, the
 * per-dimension pacing. The 128-block player walk is Minecraft-bound and verified by review. */
class GroupThreatTest {

    @Test
    void strongestPlayerDrivesTheGroupNeverTheAverage() {
        // a veteran (0.9 scaled) among three newcomers (0.1): the group figure must lean on 0.9
        float group = ThreatService.groupOf(List.of(0.1f, 0.9f, 0.1f, 0.1f), 0.15f);
        assertEquals(0.9f * (1f + 3 * 0.15f), group, 0.001f,
                "average would be 0.3-ish; hiding a veteran behind newcomers must not soften mobs");
    }

    @Test
    void groupBonusIsCappedHoweverManyPlayersPileIn() {
        // Asserted against the bound itself, not a copy of its value: the point of this test is that
        // a full server cannot multiply a mob without limit, not what this month's limit happens to
        // be. The setup is deliberately far past any plausible bound - 8 extra players at 15% is
        // +120% uncapped - so it keeps binding whatever GROUP_CAP is set to.
        float group = ThreatService.groupOf(List.of(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f), 0.15f);
        assertEquals(1f + ThreatService.GROUP_CAP, group, 0.001f,
                "8 extra players x 15% = +120% uncapped; the cap must bind");
    }

    @Test
    void dimensionMultiplierPacesOldWorldGentler() {
        assertEquals(0.75f, ThreatService.dimensionMultiplier("minecraft", 1.0f, 0.75f), 1e-6f);
        assertEquals(1.0f, ThreatService.dimensionMultiplier("middle-earth", 1.0f, 0.75f), 1e-6f);
        assertEquals(0.75f, ThreatService.dimensionMultiplier("some_other_mod", 1.0f, 0.75f), 1e-6f,
                "unknown dimensions pace like the old world, not the new one");
    }
}
