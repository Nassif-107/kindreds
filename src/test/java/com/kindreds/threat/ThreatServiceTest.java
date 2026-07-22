package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Covers {@link ThreatService#commitmentFrom}, the pure arithmetic core of {@code commitmentOf}
 * extracted so the two exploit fixes it carries can be proved without a running game - the same
 * shape {@code MobDangerTest} uses for {@link MobDanger#family(String)}. {@code commitmentOf}
 * itself (the {@code ServerPlayerEntity}-resolving wrapper) is a thin resolve-then-delegate call
 * and is not unit-tested; its "no race -> 0" branch is a one-line early return, verified by
 * inspection rather than a test that would need a live player.
 */
class ThreatServiceTest {

    @Test
    void commitmentCountsSpentPlusAvailableNotSpentAlone() {
        // 10 spent + 30 available (banked, not yet spent) out of an 80-point tree.
        float commitment = ThreatService.commitmentFrom(10, 30, 80);
        assertEquals((10 + 30) / 80f, commitment, 0.0001f,
                "commitment must be (spent + available) / max");

        // The discriminating half of the proof: this must NOT equal the spent-only reading. If a
        // future change collapses the formula to `spent / max`, this assertion is what catches it -
        // ThreatExploitTest#ignoringBankedPointsWouldShowALowerThreat only proves the CONSEQUENCE
        // (a spent-only reading is strictly lower once fed through ThreatMath.prior/threat); this
        // proves the actual formula used here counts the banked points.
        float spentOnly = 10 / 80f;
        assertNotEquals(spentOnly, commitment, 0.0001f,
                "commitment must not equal the spent-only reading - banked points must count");
    }

    @Test
    void commitmentIsClampedToOneWhenSpentPlusAvailableExceedsMax() {
        // A player who has somehow banked more than the tree could ever cost (e.g. a discipline's
        // raw XP-derived allowance outruns the tree's fixed cap) must not report over 100% committed.
        assertEquals(1f, ThreatService.commitmentFrom(80, 40, 80), 0.0001f);
    }

    @Test
    void commitmentIsZeroWhenTheTreeHasNoSpendablePoints() {
        // max <= 0 (an empty or misconfigured tree) must not divide by zero or go negative.
        assertEquals(0f, ThreatService.commitmentFrom(10, 30, 0), 0.0001f);
        assertEquals(0f, ThreatService.commitmentFrom(0, 0, -5), 0.0001f);
    }

    @Test
    void commitmentIsZeroWithNothingSpentOrAvailable() {
        assertEquals(0f, ThreatService.commitmentFrom(0, 0, 80), 0.0001f);
    }

    /**
     * Covers {@link ThreatService#rebanded}, the pure core of FIX 2: lowering {@code adaptiveStrength}
     * must immediately narrow competence already sitting in storage, not merely narrow it going
     * forward on that value's next fold. If this helper were bypassed (competence returned
     * unchanged), a stored 0.75 at strength 0 would stay 0.75 instead of collapsing to 1.0 - which is
     * exactly the "0% adaptive strength keeps a permanent 25% discount" bug this fix closes.
     */
    @Test
    void rebandedNarrowsStoredCompetenceAsAdaptiveStrengthIsLowered() {
        // strength 0: no adaptation at all - a wide stored value collapses to exactly the prior-only 1.0
        assertEquals(1.0f, ThreatService.rebanded(0.75f, 0), 0.0001f);
        // strength 100: the full evidence band - an in-band stored value is left untouched
        assertEquals(0.75f, ThreatService.rebanded(0.75f, 100), 0.0001f);
    }
}
