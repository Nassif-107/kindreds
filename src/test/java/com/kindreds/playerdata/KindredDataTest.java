package com.kindreds.playerdata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
        Identifier forest = Identifier.of("minecraft", "forest");
        data.discoveredBiomes().add(forest);
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
        assertEquals(Set.of(forest), back.discoveredBiomes(),
                "discoveredBiomes must survive the persistent CODEC round trip (Task 13 anti-farm fix)");
        assertNull(back.race(), "race must not be persisted by the JSON/NBT CODEC");
    }

    @Test
    void codecLoadsPreTask13DataMissingDiscoveredBiomesAsEmptyMutableSet() {
        // Simulates an old save file written before "discovered_biomes" existed: the field is
        // simply absent from the JSON. The optionalFieldOf default must still parse, and the
        // resulting set must be a real mutable Set (not a shared/immutable default) so
        // ActivityHooks can immediately .add() to it after load - see KindredData.CODEC's javadoc
        // note on this exact hazard.
        JsonObject json = new JsonObject();
        json.add("discipline_xp", new JsonObject());
        json.add("unlocked_nodes", new com.google.gson.JsonArray());
        json.add("titles", new com.google.gson.JsonArray());
        json.addProperty("corruption", 0);
        json.add("cooldowns", new JsonObject());
        // "discovered_biomes" intentionally omitted.

        KindredData back = KindredData.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();

        assertTrue(back.discoveredBiomes().isEmpty());
        assertDoesNotThrow(() -> back.discoveredBiomes().add(Identifier.of("minecraft", "plains")));

        // Decoding a second object from the same field-less JSON must not share the first
        // decode's Set instance (each decode gets its own fresh HashSet).
        KindredData other = KindredData.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
        assertTrue(other.discoveredBiomes().isEmpty(),
                "a second decode from field-less JSON must not observe the first decode's mutations");
    }

    @Test
    void packetCodecRoundTripsPopulatedDataWithoutSwappingSets() {
        Identifier archery = Identifier.of("kindreds", "archery");
        Identifier mining = Identifier.of("kindreds", "mining");
        Identifier lens = Identifier.of("kindreds", "stone_sense");

        KindredData data = new KindredData();
        data.addXp(archery, 42L);
        data.addXp(mining, 7L);
        // Deliberately distinct contents in unlockedNodes vs. renown, and distinct in shape
        // (different sizes/values) so a positional swap between the two Set<String> entries in
        // PACKET_CODEC would be caught by asserting each set independently below. (This guarded
        // unlockedNodes against titles until titles was removed for never being written to;
        // renown is the other Set<String> on the wire, so the hazard moved rather than went away.)
        data.unlockedNodes().add("elf.keen_sight");
        data.unlockedNodes().add("elf.silent_step");
        data.renown().add("renown/elf/starlit_aim");
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
        assertEquals(Set.of("renown/elf/starlit_aim"), back.renown());
        assertNotEquals(back.unlockedNodes(), back.renown());
        assertEquals(lens, back.activeVisionLens());
        assertEquals(3, back.corruption());
        assertEquals(12345L, back.cooldowns().getLong("shout_of_valor"));
        assertEquals(elfRace, back.race());
    }
}
