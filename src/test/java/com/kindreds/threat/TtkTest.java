package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ThreatEvidence#isFastKill}, the pure decision core of the time-to-kill signal
 * (spec §2.3). The bookkeeping around it - {@code ENGAGEMENTS}, the Fabric event registrations -
 * is MC-bound and exercised in-game instead, same testability boundary as the rest of
 * {@link ThreatEvidence} (see {@link ThreatEvidenceTest}).
 */
class TtkTest {

    @Test
    void onlyGenuinelyFastKillsQualify() {
        assertTrue(ThreatEvidence.isFastKill(60, 160, 1.0f));    // 3s vs 8s expected at full weight
        assertFalse(ThreatEvidence.isFastKill(100, 160, 1.0f));  // 5s vs 8s: not under half
        assertFalse(ThreatEvidence.isFastKill(1, 160, 0.0f));    // trivial mob: expected collapses to 0
    }
}
