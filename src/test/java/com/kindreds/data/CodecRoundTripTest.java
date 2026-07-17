package com.kindreds.data;

import com.kindreds.data.ability.AttributeMod;
import com.mojang.serialization.JsonOps;
import com.google.gson.*;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CodecRoundTripTest {

    @Test
    void disciplineRoundTrips() {
        Discipline d = new Discipline("Archery", 0x8FBC6C);
        JsonElement j = Discipline.CODEC.encodeStart(JsonOps.INSTANCE, d).result().orElseThrow();
        Discipline back = Discipline.CODEC.parse(JsonOps.INSTANCE, j).result().orElseThrow();
        assertEquals(d, back);
    }

    @Test
    void skillTreeWithAttributeAbilityRoundTrips() {
        AttributeMod attributeMod = new AttributeMod(
                Identifier.of("minecraft", "generic.max_health"), "add_value", 4.0);

        SkillNode node = new SkillNode(
                "keen_eyes",
                1,
                new int[]{0, 0},
                new SkillNode.Cost(Identifier.of("kindreds", "archery"), 1),
                List.of(),
                List.of(attributeMod),
                Optional.empty(),
                Optional.empty());

        SkillTree tree = new SkillTree(
                Identifier.of("kindreds", "elf"),
                Identifier.of("kindreds", "elf"),
                List.of(node));

        JsonElement j = SkillTree.CODEC.encodeStart(JsonOps.INSTANCE, tree).result().orElseThrow();
        SkillTree back = SkillTree.CODEC.parse(JsonOps.INSTANCE, j).result().orElseThrow();

        assertEquals(1, back.nodes().size());
        SkillNode backNode = back.nodes().get(0);
        assertEquals(node.id(), backNode.id());
        assertEquals(1, backNode.abilities().size());
        assertInstanceOf(AttributeMod.class, backNode.abilities().get(0));
        assertEquals("attribute", backNode.abilities().get(0).type());
    }
}
