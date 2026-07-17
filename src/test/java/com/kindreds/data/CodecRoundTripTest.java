package com.kindreds.data;

import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.data.ability.AttributeMod;
import com.kindreds.data.ability.CurseDef;
import com.kindreds.data.ability.StatusEffectDef;
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

    @Test
    void activeAbilityDefWithEffectsRoundTrips() {
        StatusEffectDef effect = new StatusEffectDef(Identifier.of("minecraft", "speed"), 1, 100);
        ActiveAbilityDef def = new ActiveAbilityDef(Identifier.of("kindreds", "starlit_aim"), 1200, List.of(effect));

        JsonElement j = ActiveAbilityDef.CODEC.codec().encodeStart(JsonOps.INSTANCE, def).result().orElseThrow();
        ActiveAbilityDef back = ActiveAbilityDef.CODEC.codec().parse(JsonOps.INSTANCE, j).result().orElseThrow();

        assertEquals(def, back);
        assertEquals(1, back.effects().size());
        assertEquals(effect, back.effects().get(0));
    }

    @Test
    void activeAbilityDefWithoutEffectsDefaultsToEmptyList() {
        JsonObject json = new JsonObject();
        json.addProperty("ability_id", "kindreds:legacy_ability");
        json.addProperty("cooldown_ticks", 200);

        ActiveAbilityDef back = ActiveAbilityDef.CODEC.codec().parse(JsonOps.INSTANCE, json).result().orElseThrow();
        assertTrue(back.effects().isEmpty());
    }

    @Test
    void curseDefUnconditionalWithEffectRoundTrips() {
        AttributeMod effect = new AttributeMod(Identifier.of("minecraft", "generic.max_health"), "add_value", 2.0);
        CurseDef curse = new CurseDef("deep_dark_unease", 1, "", Optional.of(effect));

        JsonElement j = CurseDef.CODEC.codec().encodeStart(JsonOps.INSTANCE, curse).result().orElseThrow();
        CurseDef back = CurseDef.CODEC.codec().parse(JsonOps.INSTANCE, j).result().orElseThrow();

        assertEquals(curse, back);
        assertEquals("", back.when());
        assertTrue(back.effect().isPresent());
    }

    @Test
    void curseDefContextualWithEffectRoundTrips() {
        AttributeMod effect = new AttributeMod(Identifier.of("middle-earth", "delvers_fear_strength"), "add_value", 20.0);
        CurseDef curse = new CurseDef("deep_dark_unease", 1, "deep_dark", Optional.of(effect));

        JsonElement j = CurseDef.CODEC.codec().encodeStart(JsonOps.INSTANCE, curse).result().orElseThrow();
        CurseDef back = CurseDef.CODEC.codec().parse(JsonOps.INSTANCE, j).result().orElseThrow();

        assertEquals("deep_dark", back.when());
        assertEquals(effect, back.effect().orElseThrow());
    }

    @Test
    void curseDefWithoutWhenOrEffectDefaultsForBackwardCompatibility() {
        // Pre-Task-12 JSON shape: only curse_id + severity, no "when"/"effect" fields.
        JsonObject json = new JsonObject();
        json.addProperty("curse_id", "frailty");
        json.addProperty("severity", 2);

        CurseDef back = CurseDef.CODEC.codec().parse(JsonOps.INSTANCE, json).result().orElseThrow();
        assertEquals("frailty", back.curseId());
        assertEquals(2, back.severity());
        assertEquals("", back.when());
        assertTrue(back.effect().isEmpty());
    }
}
