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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the hand-authored birth-trait data ({@code birth_trait/*.json}) against the same silent
 * no-op failure mode {@link TreeAttributeIdsTest} guards the trees against: a typo'd or removed
 * {@code minecraft:} attribute/status-effect id decodes fine (the codec is happy) but resolves to an
 * empty registry entry at runtime, so {@code AbilityApplier} logs a warning and skips it - the trait
 * silently does nothing in-game. Also asserts each file declares its race and at least one trait, and
 * that every {@code contextual_boon}/contextual {@code curse} uses a context the engine implements.
 *
 * <p>Walks each file recursively (into nested {@code effect} payloads), same technique as the sibling
 * tree test. {@code middle-earth:} ids are skipped (a standalone unit test can't resolve the base
 * mod's registry).
 */
class BirthTraitDataTest {

    private static final Gson GSON = new Gson();

    private static final String[] RACES =
            {"elf", "dwarf", "human", "hobbit", "orc", "uruk", "snaga", "goblin"};

    /** Mirrors {@link TreeAttributeIdsTest}'s set (1.21.8 vanilla {@code EntityAttributes} paths). */
    private static final Set<String> VALID_ATTRIBUTE_PATHS = Set.of(
            "armor", "armor_toughness", "attack_damage", "attack_knockback", "attack_speed",
            "block_break_speed", "block_interaction_range", "burning_time", "camera_distance",
            "explosion_knockback_resistance", "entity_interaction_range", "fall_damage_multiplier",
            "flying_speed", "follow_range", "gravity", "jump_strength", "knockback_resistance", "luck",
            "max_absorption", "max_health", "mining_efficiency", "movement_efficiency", "movement_speed",
            "oxygen_bonus", "safe_fall_distance", "scale", "sneaking_speed", "spawn_reinforcements",
            "step_height", "submerged_mining_speed", "sweeping_damage_ratio", "tempt_range",
            "water_movement_efficiency", "waypoint_transmit_range", "waypoint_receive_range");

    /** The vanilla status effects the birth traits use - all real 1.21.8 {@code StatusEffects} ids. */
    private static final Set<String> VALID_STATUS_EFFECT_PATHS = Set.of(
            "night_vision", "saturation", "fire_resistance", "regeneration", "speed", "haste",
            "resistance", "strength", "weakness", "slowness", "luck", "absorption", "jump_boost",
            "water_breathing", "invisibility", "glowing", "slow_falling");

    /** Contexts {@code CurseContextService.matchesContext} actually implements. */
    private static final Set<String> VALID_CONTEXTS = Set.of(
            "deep_dark", "daylight", "starlight", "underground", "darkness", "dawn_dusk", "low_health");

    @Test
    void everyBirthTraitIdIsRealAndEveryContextIsImplemented() {
        Set<String> problems = new TreeSet<>();
        for (String race : RACES) {
            JsonObject root = readJson("data/kindreds/kindreds/birth_trait/" + race + ".json").getAsJsonObject();

            if (!root.has("race")) {
                problems.add(race + ": missing \"race\"");
            }
            if (!root.has("traits") || !root.get("traits").isJsonArray()
                    || root.getAsJsonArray("traits").isEmpty()) {
                problems.add(race + ": missing/empty \"traits\"");
            }

            Set<String> attrIds = new HashSet<>();
            Set<String> effectIds = new HashSet<>();
            Set<String> contexts = new HashSet<>();
            collect(root, attrIds, effectIds, contexts);

            for (String id : attrIds) {
                if (id.startsWith("minecraft:") && !VALID_ATTRIBUTE_PATHS.contains(id.substring("minecraft:".length()))) {
                    problems.add(race + ": bad attribute id '" + id + "'");
                }
            }
            for (String id : effectIds) {
                if (id.startsWith("minecraft:") && !VALID_STATUS_EFFECT_PATHS.contains(id.substring("minecraft:".length()))) {
                    problems.add(race + ": unknown status effect id '" + id + "'");
                }
            }
            for (String when : contexts) {
                if (!VALID_CONTEXTS.contains(when)) {
                    problems.add(race + ": context '" + when + "' isn't implemented by CurseContextService");
                }
            }
        }
        assertTrue(problems.isEmpty(), "Birth-trait data problems:\n" + String.join("\n", problems));
    }

    @Test
    void everyRaceFileExists() {
        for (String race : RACES) {
            assertEquals(race, readJson("data/kindreds/kindreds/birth_trait/" + race + ".json")
                    .getAsJsonObject().get("race").getAsString().replace("middle-earth:", ""));
        }
    }

    /** Recursively collects attribute ids, status-effect ids, and nonblank {@code when} contexts. */
    private static void collect(JsonElement element, Set<String> attrs, Set<String> effects, Set<String> contexts) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String type = obj.has("type") && obj.get("type").isJsonPrimitive() ? obj.get("type").getAsString() : "";
            if ("attribute".equals(type) && obj.has("attribute")) {
                attrs.add(obj.get("attribute").getAsString());
            }
            if ("status_effect".equals(type) && obj.has("effect")) {
                effects.add(obj.get("effect").getAsString());
            }
            if (obj.has("when") && obj.get("when").isJsonPrimitive()) {
                String when = obj.get("when").getAsString();
                if (!when.isBlank()) {
                    contexts.add(when);
                }
            }
            for (String key : obj.keySet()) {
                collect(obj.get(key), attrs, effects, contexts);
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                collect(child, attrs, effects, contexts);
            }
        }
    }

    private static JsonElement readJson(String classpathResource) {
        try (InputStream in = BirthTraitDataTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
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
