package com.kindreds.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards against Task 13's FINAL-REVIEW Fix 1 regressing: MC snapshot 24w21a removed the
 * {@code generic.}/{@code player.} prefixes from vanilla attribute identifiers, but {@code
 * Identifier.of} happily accepts the old dotted strings and {@code Registries.ATTRIBUTE.getEntry}
 * just silently returns empty for them - {@code AbilityApplier.applyAttributeMod} then logs a
 * warning and skips the modifier rather than throwing, so a reintroduced {@code generic.}/{@code
 * player.} prefix would pass {@link ResourceJsonLoadTest}'s codec-decode check yet still silently
 * no-op every ability that used it in-game.
 *
 * <p>Parses every {@code skill_tree/*.json} the same way {@link ResourceJsonLoadTest} does (plain
 * Gson, no {@code DynamicRegistryManager}/codec involved) and recursively walks each node's
 * {@code abilities} array - including into a {@code curse}'s nested {@code effect} payload, e.g.
 * {@code elf.json}'s {@code deep_dark_unease} node - for every JSON object shaped {@code {"type":
 * "attribute", "attribute": "<id>", ...}}. Every {@code minecraft:}-namespaced id found must be a
 * real 1.21.8 vanilla attribute; {@code middle-earth:}-namespaced ids are skipped (Fix 2's scope -
 * a standalone unit test has no way to resolve a base mod's registry).
 *
 * <p>This test has no live Minecraft registry to check against (no bootstrap seam exists elsewhere
 * in {@code src/test} - see the sibling tests in this package), so it asserts against a hardcoded
 * set of valid id paths instead, sourced by decompiling {@code net.minecraft.entity.attribute.
 * EntityAttributes} for 1.21.8 (yarn mappings) out of the Loom cache's mapped Minecraft jar and
 * reading every path string passed to its private {@code register(String, EntityAttribute)}
 * helper. <b>This set must be kept in sync with that class</b> if a future Minecraft version
 * renames/adds/removes vanilla attributes.
 */
class TreeAttributeIdsTest {

    private static final Gson GSON = new Gson();

    private static final String[] RACES =
            {"elf", "dwarf", "human", "hobbit", "orc", "uruk", "snaga", "goblin"};

    /**
     * Every path {@code net.minecraft.entity.attribute.EntityAttributes} registers in 1.21.8
     * (yarn 1.21.8+build.1) - i.e. every string literal passed as the first argument to that
     * class's private {@code register(String, EntityAttribute)} in its static initializer.
     */
    private static final Set<String> VALID_VANILLA_ATTRIBUTE_PATHS = Set.of(
            "armor",
            "armor_toughness",
            "attack_damage",
            "attack_knockback",
            "attack_speed",
            "block_break_speed",
            "block_interaction_range",
            "burning_time",
            "camera_distance",
            "explosion_knockback_resistance",
            "entity_interaction_range",
            "fall_damage_multiplier",
            "flying_speed",
            "follow_range",
            "gravity",
            "jump_strength",
            "knockback_resistance",
            "luck",
            "max_absorption",
            "max_health",
            "mining_efficiency",
            "movement_efficiency",
            "movement_speed",
            "oxygen_bonus",
            "safe_fall_distance",
            "scale",
            "sneaking_speed",
            "spawn_reinforcements",
            "step_height",
            "submerged_mining_speed",
            "sweeping_damage_ratio",
            "tempt_range",
            "water_movement_efficiency",
            "waypoint_transmit_range",
            "waypoint_receive_range"
    );

    @Test
    void everyMinecraftAttributeIdInEverySkillTreeIsARealVanillaAttribute() {
        Set<String> badIds = new TreeSet<>();
        for (String race : RACES) {
            JsonObject tree = readJson("data/kindreds/skill_tree/" + race + ".json").getAsJsonObject();
            Set<String> attributeIds = new HashSet<>();
            collectAttributeIds(tree, attributeIds);
            for (String id : attributeIds) {
                if (!id.startsWith("minecraft:")) {
                    continue; // middle-earth: ids are Fix 2's scope, unverifiable in a standalone unit test.
                }
                String path = id.substring("minecraft:".length());
                if (path.startsWith("generic.") || path.startsWith("player.")) {
                    badIds.add(race + ": " + id + " (removed pre-1.21 prefix)");
                } else if (!VALID_VANILLA_ATTRIBUTE_PATHS.contains(path)) {
                    badIds.add(race + ": " + id + " (not a known 1.21.8 vanilla attribute)");
                }
            }
        }
        assertTrue(badIds.isEmpty(), "Invalid minecraft: attribute id(s) found:\n"
                + String.join("\n", badIds));
    }

    /**
     * Recursively walks {@code element} looking for every JSON object shaped {@code {"type":
     * "attribute", "attribute": "<id>", ...}}, adding each such {@code attribute} value to {@code
     * out}. Covers both a node's top-level {@code abilities} entries and an attribute mod nested
     * inside a {@code curse}'s {@code effect} field, without needing to know the exact shape of
     * every ability type - it just recurses into every object/array it sees.
     */
    private static void collectAttributeIds(JsonElement element, Set<String> out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("type") && obj.get("type").isJsonPrimitive()
                    && "attribute".equals(obj.get("type").getAsString())
                    && obj.has("attribute") && obj.get("attribute").isJsonPrimitive()) {
                out.add(obj.get("attribute").getAsString());
            }
            for (String key : obj.keySet()) {
                collectAttributeIds(obj.get(key), out);
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                collectAttributeIds(child, out);
            }
        }
    }

    private static JsonElement readJson(String classpathResource) {
        try (InputStream in = TreeAttributeIdsTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
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
