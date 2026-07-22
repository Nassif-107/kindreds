package com.kindreds.threat;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    /**
     * The wire-only invariant's other half: {@link #familyVoiceKeysSurviveThePacketRoundTripButTheRawMapNeverRidesTheWire}
     * proves {@link ThreatState#familyVoiceKeys} rides the {@link ThreatState#PACKET_CODEC packet
     * codec}; this proves the opposite for the {@link ThreatState#CODEC persistence codec} - derived
     * display data must never come back from disk. Until now that absence was only enforced by
     * omission (the field simply isn't listed in {@code CODEC}'s {@code RecordCodecBuilder} group) -
     * nothing failed if a future edit added it back in. This pins it as an actual assertion.
     */
    @Test
    void familyVoiceKeysNeverSurviveThePersistenceRoundTrip() {
        ThreatState original = new ThreatState();
        original.setCompetence(1.0f);
        original.familyCompetence().put("trolls", 1.35f); // +0.35 mastered
        original.familyCompetence().put("undead", 0.80f); // -0.20 feared
        ThreatState toSave = original.copy(); // populates familyVoiceKeys, exactly as the real save path would

        assertTrue(!toSave.familyVoiceKeys().isEmpty(),
                "copy() must have derived non-empty voice keys for this to be a meaningful proof");

        JsonElement json = ThreatState.CODEC.encodeStart(JsonOps.INSTANCE, toSave).result().orElseThrow();
        ThreatState decoded = ThreatState.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();

        assertTrue(decoded.familyVoiceKeys().isEmpty(),
                "derived display data (familyVoiceKeys) must never come back from disk - "
                        + "ThreatState.CODEC has no field for it, so a fresh ThreatState() is what "
                        + "decoding must produce; if a future edit ever adds a `family_voice_keys` "
                        + "field to CODEC's RecordCodecBuilder group (mirroring PACKET_CODEC), this "
                        + "constructed instance's list would come back non-empty and this assertion "
                        + "would fail, exactly as it should - CODEC is the persistence codec, and spec "
                        + "§7 says derived Deeds-page voice data is recomputed fresh, never saved");
        // The mirror image, for contrast: familyCompetence (the raw table itself, not the derived
        // voice keys) IS meant to persist - CODEC's "family" field exists precisely to save it. If
        // this ever came back empty too, the round trip would be silently losing real player data
        // rather than correctly discarding derived display data.
        assertEquals(Map.of("trolls", 1.35f, "undead", 0.80f), decoded.familyCompetence(),
                "familyCompetence itself must survive persistence - only its derived voiceKeys must not");
    }
}
