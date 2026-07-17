package com.kindreds.playerdata;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KindredDataTest {

    @Test
    void addXpAccumulatesPerDiscipline() {
        KindredData data = new KindredData();
        Identifier archery = Identifier.of("kindreds", "archery");
        Identifier mining = Identifier.of("kindreds", "mining");

        data.addXp(archery, 10L);
        data.addXp(archery, 5L);
        data.addXp(mining, 3L);

        assertEquals(15L, data.xpIn(archery));
        assertEquals(3L, data.xpIn(mining));
        assertEquals(0L, data.xpIn(Identifier.of("kindreds", "smithing")));
    }

    @Test
    void hasNodeReflectsUnlockedNodes() {
        KindredData data = new KindredData();
        assertFalse(data.hasNode("keen_eyes"));

        data.unlockedNodes().add("keen_eyes");

        assertTrue(data.hasNode("keen_eyes"));
        assertFalse(data.hasNode("stone_sense"));
    }

    @Test
    void codecRoundTripsPopulatedData() {
        KindredData data = new KindredData();
        Identifier archery = Identifier.of("kindreds", "archery");
        Identifier lens = Identifier.of("kindreds", "stone_sense");

        data.addXp(archery, 42L);
        data.unlockedNodes().add("keen_eyes");
        data.setActiveVisionLens(lens);
        data.cooldowns().put("shout_of_valor", 12345L);

        JsonElement json = KindredData.CODEC.encodeStart(JsonOps.INSTANCE, data).result().orElseThrow();
        KindredData back = KindredData.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();

        assertEquals(42L, back.xpIn(archery));
        assertTrue(back.hasNode("keen_eyes"));
        assertEquals(lens, back.activeVisionLens());
        assertEquals(12345L, back.cooldowns().getLong("shout_of_valor"));
    }
}
