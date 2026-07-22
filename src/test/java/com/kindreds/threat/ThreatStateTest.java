package com.kindreds.threat;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    // --- familyVoiceKeys: the §3a/§7 reconciliation (see ThreatMath#familyVoiceKeys and copy()) ----

    @Test
    void copyDerivesFamilyVoiceKeysFromTheLiveFamilyMapAtCopyTime() {
        ThreatState original = new ThreatState();
        original.setCompetence(1.0f);
        original.familyCompetence().put("trolls", 1.3f); // +0.3, mastered - well past the 0.1 threshold

        ThreatState copy = original.copy();

        assertEquals(List.of("kindreds.family.mastered.trolls"), copy.familyVoiceKeys(),
                "copy() must derive the same keys ThreatMath.familyVoiceKeys would for this map");
        // The freshly-constructed original itself has none yet - derivation only happens at copy time.
        assertTrue(original.familyVoiceKeys().isEmpty());
    }

    @Test
    void familyVoiceKeysSurviveThePacketRoundTripButTheRawMapNeverRidesTheWire() {
        ThreatState original = new ThreatState();
        original.setCompetence(1.0f);
        original.familyCompetence().put("trolls", 1.35f);  // +0.35 mastered - clearly the stronger one
        original.familyCompetence().put("undead", 0.80f);  // -0.20 feared
        ThreatState toSend = original.copy(); // the real sync path always copies before encoding

        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        ThreatState.PACKET_CODEC.encode(buf, toSend);
        ThreatState back = ThreatState.PACKET_CODEC.decode(buf);

        assertEquals(List.of("kindreds.family.mastered.trolls", "kindreds.family.feared.undead"),
                back.familyVoiceKeys(), "the derived, bounded keys must survive the wire");
        assertTrue(back.familyCompetence().isEmpty(),
                "the raw per-family table must never itself ride the wire (spec §7)");
    }
}
