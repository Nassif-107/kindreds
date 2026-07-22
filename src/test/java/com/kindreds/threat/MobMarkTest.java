package com.kindreds.threat;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The per-mob mark must survive chunk unload byte-for-byte - a lost mark means a mob scaled twice
 * (compounding health) or an elite that forgets its name when you walk away (spec §11). */
class MobMarkTest {

    @Test
    void markSurvivesTheCodecRoundTrip() {
        MobMark mark = MobMark.DEFAULT.withSpawnReason("NATURAL").withScaled(true)
                .withElite("rally", "kindreds.elite.name.orc_kin.2").withEscort(true);
        JsonElement json = MobMark.CODEC.encodeStart(JsonOps.INSTANCE, mark).getOrThrow();
        MobMark back = MobMark.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(mark, back);
    }

    @Test
    void defaultMarkIsInertEverywhere() {
        assertEquals("", MobMark.DEFAULT.spawnReason());
        assertFalse(MobMark.DEFAULT.scaled());
        assertEquals("", MobMark.DEFAULT.eliteAbility());
        assertFalse(MobMark.DEFAULT.escort());
    }

    @Test
    void anOldWorldsMobDecodesFromAnEmptyObject() {
        // every field optionalFieldOf: a mob saved before phase 2 loads as DEFAULT, not a crash
        MobMark back = MobMark.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonObject()).getOrThrow();
        assertEquals(MobMark.DEFAULT, back);
    }
}
