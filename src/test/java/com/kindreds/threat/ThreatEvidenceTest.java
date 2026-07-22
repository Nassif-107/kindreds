package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ThreatEvidence#hardshipOf} and {@link ThreatEvidence#newEngagementTable}, the two
 * pieces of {@link ThreatEvidence}'s bookkeeping that do not need a running game to prove. The rest
 * of the class (accumulating per-player damage, reading/writing {@code KindredData.threat()}, the
 * five Fabric event registrations) is exercised in-game instead - see the class javadoc's note on
 * the testability boundary.
 */
class ThreatEvidenceTest {

    @Test
    void hardshipIsAccumulatedDamageOverMaxHealth() {
        assertEquals(0.25f, ThreatEvidence.hardshipOf(5f, 20f), 0.0001f);
        assertEquals(1.0f, ThreatEvidence.hardshipOf(20f, 20f), 0.0001f);
        assertEquals(0f, ThreatEvidence.hardshipOf(0f, 20f), 0.0001f);
    }

    @Test
    void zeroMaxHealthNeverProducesNaNOrInfinity() {
        // A fresh player's ThreatState.maxHealthMark() defaults to 0 before the first refresh - this
        // must not divide by it. The discriminating half: NaN/Infinity are exactly what plain
        // division would hand back here, and both would silently poison every fold downstream
        // (ThreatMath's own band() has a NaN escape hatch, but nothing catches Infinity).
        float result = ThreatEvidence.hardshipOf(5f, 0f);
        assertFalse(Float.isNaN(result), "hardship must never be NaN");
        assertFalse(Float.isInfinite(result), "hardship must never be Infinite");
        assertEquals(5f, result, 0.0001f, "with the 1f floor, 5 accumulated / 1 effective max health");
    }

    @Test
    void negativeMaxHealthAlsoFloorsRatherThanFlippingSign() {
        // Corrupted save data (a negative mark) must not flip hardship negative, which would read as
        // a hardship far BELOW target - i.e. maximal false "coasting" evidence.
        float result = ThreatEvidence.hardshipOf(5f, -20f);
        assertEquals(5f, result, 0.0001f);
    }

    @Test
    void negativeAccumulatedDamageIsClampedToZero() {
        // Should never happen (damageTaken from the Fabric event is non-negative), but a defensive
        // floor here keeps a hypothetical negative accumulation from reading as negative hardship.
        assertEquals(0f, ThreatEvidence.hardshipOf(-5f, 20f), 0.0001f);
    }

    @Test
    void boundedEngagementTableCapsAtSixteenAndEvictsTheOldestByInsertionOrder() {
        // A sword sweep, an army skirmish, a mob farm - whatever causes one player to open
        // engagements against many mobs at once must not grow this table without limit. The 17th
        // distinct mob evicts the 1st (oldest by first-engaged order), never a middle or newest one.
        LinkedHashMap<UUID, ThreatEvidence.Engagement> table = ThreatEvidence.newEngagementTable();
        UUID[] mobs = new UUID[17];
        for (int i = 0; i < mobs.length; i++) {
            mobs[i] = UUID.randomUUID();
            table.put(mobs[i], new ThreatEvidence.Engagement(i, 0f, 20f));
        }

        assertEquals(16, table.size(), "table grew past the 16-entry cap");
        assertFalse(table.containsKey(mobs[0]), "oldest engagement (mob 0) was not evicted");
        for (int i = 1; i < mobs.length; i++) {
            assertTrue(table.containsKey(mobs[i]), "mob " + i + " was evicted but should have survived");
        }
    }

    @Test
    void reHittingAnAlreadyEngagedMobDoesNotCountAsANewInsertionForEviction() {
        // Insertion-order eviction (accessOrder = false): overwriting an existing key's value is not
        // a structural modification and must not move it, or re-hitting old mobs in a long fight
        // would let a player "refresh" them into permanent immunity from eviction by re-hitting them
        // just before every new mob tagged - the opposite of the size bound's intent.
        LinkedHashMap<UUID, ThreatEvidence.Engagement> table = ThreatEvidence.newEngagementTable();
        UUID[] mobs = new UUID[16];
        for (int i = 0; i < mobs.length; i++) {
            mobs[i] = UUID.randomUUID();
            table.put(mobs[i], new ThreatEvidence.Engagement(i, 0f, 20f));
        }

        // Re-hit (overwrite) the oldest mob several times - table is already at the cap.
        table.put(mobs[0], new ThreatEvidence.Engagement(0, 5f, 15f));
        table.put(mobs[0], new ThreatEvidence.Engagement(0, 9f, 11f));
        assertEquals(16, table.size(), "overwriting an existing entry changed the table's size");

        // A genuinely new, 17th mob now evicts mob 0 (still the oldest by insertion order) despite
        // the re-hits above, not mob 1.
        UUID seventeenth = UUID.randomUUID();
        table.put(seventeenth, new ThreatEvidence.Engagement(20, 0f, 20f));

        assertEquals(16, table.size());
        assertFalse(table.containsKey(mobs[0]), "re-hitting mob 0 should not have saved it from eviction");
        assertTrue(table.containsKey(mobs[1]), "mob 1 should not have been evicted instead of mob 0");
        assertTrue(table.containsKey(seventeenth));
    }
}
