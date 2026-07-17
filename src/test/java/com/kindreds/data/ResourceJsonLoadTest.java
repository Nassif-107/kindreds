package com.kindreds.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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

    @ParameterizedTest
    @ValueSource(strings = {"elf", "dwarf"})
    void themeJsonDecodes(String name) {
        assertDecodes(Theme.CODEC, "data/kindreds/theme/" + name + ".json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"elf", "dwarf"})
    void skillTreeJsonDecodes(String name) {
        assertDecodes(SkillTree.CODEC, "data/kindreds/skill_tree/" + name + ".json");
    }

    @Test
    void sanityCheckAllSevenDisciplinesArePresent() {
        for (String name : new String[]{"combat", "archery", "mining", "stealth", "smithing", "survival", "lore"}) {
            assertTrue(getClass().getClassLoader().getResource("data/kindreds/discipline/" + name + ".json") != null,
                    "Missing discipline JSON: " + name);
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
