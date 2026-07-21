package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreatStateTest {

    @Test
    void copyDeepCopiesFamilyCompetenceRatherThanSharingTheReference() {
        // SyncKindredDataS2C.snapshot relies on this: netty encodes the packet on its own thread,
        // some ticks after the snapshot is taken, while the server thread may still be mutating the
        // live player's familyCompetence map. If copy() shared the map instead of cloning it, that
        // concurrent mutation would throw a ConcurrentModificationException mid-encode.
        ThreatState original = new ThreatState();
        original.familyCompetence().put("orcs", 1.0f);

        ThreatState copy = original.copy();
        original.familyCompetence().put("trolls", 2.0f);

        assertTrue(copy.familyCompetence().containsKey("orcs"));
        assertTrue(!copy.familyCompetence().containsKey("trolls"),
                "mutating the original after copy() must not be visible through the copy");
        assertEquals(1, copy.familyCompetence().size());
        assertEquals(2, original.familyCompetence().size());
    }

    @Test
    void copyPreservesAllScalarFields() {
        ThreatState original = new ThreatState();
        original.setPriorMark(10f);
        original.setMaxHealthMark(20f);
        original.setCompetence(1.5f);
        original.addPlayedTicks(100L);

        ThreatState copy = original.copy();

        assertEquals(10f, copy.priorMark(), 0.001f);
        assertEquals(20f, copy.maxHealthMark(), 0.001f);
        assertEquals(1.5f, copy.competence(), 0.001f);
        assertEquals(100L, copy.playedTicks());
    }
}
