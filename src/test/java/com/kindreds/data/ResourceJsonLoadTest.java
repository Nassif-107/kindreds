package com.kindreds.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the hand-authored data pack JSON under {@code src/main/resources/data/kindreds}
 * against typos: every file must actually decode via its record's {@code Codec}.
 */
class ResourceJsonLoadTest {

    private static final Gson GSON = new Gson();

    @ParameterizedTest
    @ValueSource(strings = {"combat", "archery", "mining", "stealth", "smithing", "survival", "lore"})
    void disciplineJsonDecodes(String name) {
        assertDecodes(Discipline.CODEC, "data/kindreds/discipline/" + name + ".json");
    }

    private static final String[] RACES =
            {"elf", "dwarf", "human", "hobbit", "orc", "uruk", "snaga", "goblin"};

    @ParameterizedTest
    @ValueSource(strings = {"elf", "dwarf", "human", "hobbit", "orc", "uruk", "snaga", "goblin"})
    void themeJsonDecodes(String name) {
        assertDecodes(Theme.CODEC, "data/kindreds/theme/" + name + ".json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"elf", "dwarf", "human", "hobbit", "orc", "uruk", "snaga", "goblin"})
    void skillTreeJsonDecodes(String name) {
        assertDecodes(SkillTree.CODEC, "data/kindreds/skill_tree/" + name + ".json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"elf", "dwarf", "human", "hobbit", "orc", "uruk", "snaga", "goblin"})
    void raceScalingJsonDecodes(String name) {
        assertDecodes(RaceScalingEntry.CODEC, "data/kindreds/race_scaling/" + name + ".json");
    }

    @Test
    void sanityCheckAllSevenDisciplinesArePresent() {
        for (String name : new String[]{"combat", "archery", "mining", "stealth", "smithing", "survival", "lore"}) {
            assertTrue(getClass().getClassLoader().getResource("data/kindreds/discipline/" + name + ".json") != null,
                    "Missing discipline JSON: " + name);
        }
    }

    @Test
    void sanityCheckAllEightRacesHaveTreeThemeAndScaling() {
        for (String race : RACES) {
            assertTrue(getClass().getClassLoader().getResource("data/kindreds/skill_tree/" + race + ".json") != null,
                    "Missing skill_tree JSON: " + race);
            assertTrue(getClass().getClassLoader().getResource("data/kindreds/theme/" + race + ".json") != null,
                    "Missing theme JSON: " + race);
            assertTrue(getClass().getClassLoader().getResource("data/kindreds/race_scaling/" + race + ".json") != null,
                    "Missing race_scaling JSON: " + race);
        }
    }

    /**
     * Every {@code deed_advancement} id referenced by a skill tree node must resolve to an
     * advancement JSON file this datapack actually ships - a syntax/JSON-parseability check only
     * (advancement criteria use predicate codecs that need a live {@code DynamicRegistryManager}-
     * backed {@code RegistryOps} to fully decode, which a plain unit test can't provide without a
     * full game bootstrap; full criteria-schema validation happens at real datapack load, which is
     * this task's designated in-game/user verification step, not this test's job).
     */
    @Test
    void everyDeedAdvancementReferencedBySkillTreesExistsAndParsesAsJson() {
        for (String race : RACES) {
            JsonElement tree = readJson("data/kindreds/skill_tree/" + race + ".json");
            for (JsonElement nodeElement : tree.getAsJsonObject().getAsJsonArray("nodes")) {
                JsonObject node = nodeElement.getAsJsonObject();
                if (!node.has("deed_advancement")) {
                    continue;
                }
                String deedId = node.get("deed_advancement").getAsString(); // "kindreds:deed/xyz"
                String path = deedId.substring(deedId.indexOf(':') + 1); // "deed/xyz"
                String resource = "data/kindreds/advancement/" + path + ".json";
                assertTrue(getClass().getClassLoader().getResource(resource) != null,
                        "Node " + node.get("id").getAsString() + " references missing deed advancement: " + resource);
                // Just confirms it's well-formed JSON - see the method javadoc for why full
                // advancement-codec decoding isn't attempted here.
                readJson(resource);
            }
        }
    }

    private static <T> void assertDecodes(Codec<T> codec, String classpathResource) {
        JsonElement json = readJson(classpathResource);
        DataResult<T> result = codec.parse(JsonOps.INSTANCE, json);
        result.error().ifPresent(e -> fail("Failed to decode " + classpathResource + ": " + e.message()));
        assertTrue(result.result().isPresent(), "No result decoding " + classpathResource);
    }

    private static JsonElement readJson(String classpathResource) {
        try (InputStream in = ResourceJsonLoadTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                fail("Resource not found on classpath: " + classpathResource);
            }
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class);
        } catch (IOException e) {
            fail("Failed to read " + classpathResource + ": " + e.getMessage());
            throw new AssertionError(e);
        }
    }
}
