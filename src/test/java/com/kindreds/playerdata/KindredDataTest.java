package com.kindreds.playerdata;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
        // race is deliberately wire-only (see KindredData.race javadoc) - set it here so the
        // assertion below actually proves the persistent CODEC drops it, rather than trivially
        // passing because it was never set in the first place.
        data.setRace(Identifier.of("middle-earth", "elf"));

        JsonElement json = KindredData.CODEC.encodeStart(JsonOps.INSTANCE, data).result().orElseThrow();
        KindredData back = KindredData.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();

        assertEquals(42L, back.xpIn(archery));
        assertTrue(back.hasNode("keen_eyes"));
        assertEquals(lens, back.activeVisionLens());
        assertEquals(12345L, back.cooldowns().getLong("shout_of_valor"));
        assertNull(back.race(), "race must not be persisted by the JSON/NBT CODEC");
    }

    @Test
    void packetCodecRoundTripsPopulatedDataWithoutSwappingSets() {
        Identifier archery = Identifier.of("kindreds", "archery");
        Identifier mining = Identifier.of("kindreds", "mining");
        Identifier lens = Identifier.of("kindreds", "stone_sense");

        KindredData data = new KindredData();
        data.addXp(archery, 42L);
        data.addXp(mining, 7L);
        // Deliberately distinct contents in unlockedNodes vs. titles, and distinct in shape
        // (different sizes/values) so a positional swap between the two PACKET_CODEC entries
        // would be caught by asserting each set independently below.
        data.unlockedNodes().add("elf.keen_sight");
        data.unlockedNodes().add("elf.silent_step");
        data.titles().add("elf_friend");
        data.setActiveVisionLens(lens);
        data.setCorruption(3);
        data.cooldowns().put("shout_of_valor", 12345L);
        // race rides the wire only (see KindredData.race javadoc) - covered here since
        // SyncKindredDataS2C's PACKET_CODEC is the only place it's ever (de)serialized.
        Identifier elfRace = Identifier.of("middle-earth", "elf");
        data.setRace(elfRace);

        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        KindredData.PACKET_CODEC.encode(buf, data);
        KindredData back = KindredData.PACKET_CODEC.decode(buf);

        assertEquals(42L, back.xpIn(archery));
        assertEquals(7L, back.xpIn(mining));
        assertEquals(Set.of("elf.keen_sight", "elf.silent_step"), back.unlockedNodes());
        assertEquals(Set.of("elf_friend"), back.titles());
        assertNotEquals(back.unlockedNodes(), back.titles());
        assertEquals(lens, back.activeVisionLens());
        assertEquals(3, back.corruption());
        assertEquals(12345L, back.cooldowns().getLong("shout_of_valor"));
        assertEquals(elfRace, back.race());
    }
}
