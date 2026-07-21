package com.kindreds.progression;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads what each Great Deed actually asks of you, so the Deeds page can say it.
 *
 * <p>The deed descriptions are riddles on purpose ("Put down a beast that shakes the ground"), which
 * is right for a book and useless for a player who wants to go and do it. This turns the deed's own
 * criteria into a plain line - "Slay a Ravager" - and because it reads the same JSON the game checks
 * the player against, the two cannot disagree.
 *
 * <p>Read from the {@link ResourceManager} rather than from the loaded {@code AdvancementEntry}
 * objects. The runtime objects hold their conditions as loot predicates, and digging an entity type
 * out of a {@code LootContextPredicate} means several layers of Mojang internals that move between
 * versions - and that would fail into blank text rather than loudly. The JSON is the same data,
 * flat, and a datapack that overrides a deed is picked up for free.
 *
 * <p>Every line is a {@link Text} built from translatable parts, never a formatted string: the
 * server builds the shape and the client resolves the words in its own language.
 */
public final class DeedIndex {
    private DeedIndex() {
    }

    /** Where the deed jsons live, relative to a data-pack namespace root. No trailing slash:
     * {@code findResources} rejects one outright. */
    private static final String DIR = "advancement/"
            + RenownService.RENOWN_PREFIX.substring(0, RenownService.RENOWN_PREFIX.length() - 1);

    /**
     * Every Great Deed, in path order, mapped to the plain requirement line.
     * Keys are renown paths as {@link RenownService} records them ({@code renown/dwarf/khazad_work}).
     */
    public static Map<String, Text> requirements(MinecraftServer server) {
        Map<String, Text> out = new LinkedHashMap<>();
        if (server == null) {
            return out;
        }
        ResourceManager resources = server.getResourceManager();
        for (Map.Entry<Identifier, Resource> entry : resources
                .findResources(DIR, id -> id.getPath().endsWith(".json")).entrySet()) {
            Identifier file = entry.getKey();
            String path = file.getPath().substring("advancement/".length());
            path = path.substring(0, path.length() - ".json".length());
            if (!RenownService.isRenown(Identifier.of(file.getNamespace(), path))) {
                continue;   // the renown root, or something else living under the same tree
            }
            try (BufferedReader reader = entry.getValue().getReader()) {
                Text line = describe(JsonParser.parseReader(reader).getAsJsonObject());
                if (line != null) {
                    out.put(path, line);
                }
            } catch (Exception e) {
                // A deed whose shape we cannot read gets no line rather than a wrong one; the doctor
                // reports the gap, so it is never silent.
                com.kindreds.Kindreds.LOGGER.warn("[Kindreds] could not read the deed {}: {}", file, e.toString());
            }
        }
        return out;
    }

    /**
     * The whole advancement as one line. Its {@code requirements} are groups that must ALL be met,
     * each group being alternatives - so groups join with "and", and criteria inside one with "or".
     */
    private static Text describe(JsonObject advancement) {
        JsonObject criteria = advancement.getAsJsonObject("criteria");
        if (criteria == null) {
            return null;
        }
        List<List<String>> groups = new ArrayList<>();
        JsonArray required = advancement.getAsJsonArray("requirements");
        if (required != null) {
            for (JsonElement group : required) {
                List<String> names = new ArrayList<>();
                for (JsonElement name : group.getAsJsonArray()) {
                    names.add(name.getAsString());
                }
                groups.add(names);
            }
        } else {
            criteria.keySet().forEach(name -> groups.add(List.of(name)));
        }

        List<Text> parts = new ArrayList<>();
        for (List<String> group : groups) {
            List<Text> alternatives = new ArrayList<>();
            for (String name : group) {
                JsonObject criterion = criteria.getAsJsonObject(name);
                Text one = criterion == null ? null : describeCriterion(criterion);
                if (one == null) {
                    return null;    // an unknown shape makes the whole line untrustworthy
                }
                alternatives.add(one);
            }
            parts.add(join(alternatives, "kindreds.deed.req.or"));
        }
        return join(parts, "kindreds.deed.req.and");
    }

    private static Text join(List<Text> parts, String separatorKey) {
        if (parts.isEmpty()) {
            return null;
        }
        Text out = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            out = Text.empty().append(out).append(Text.translatable(separatorKey)).append(parts.get(i));
        }
        return out;
    }

    private static Text describeCriterion(JsonObject criterion) {
        String trigger = str(criterion, "trigger");
        JsonObject conditions = criterion.getAsJsonObject("conditions");
        if (trigger == null || conditions == null) {
            return null;
        }
        return switch (trigger) {
            case "minecraft:player_killed_entity" -> {
                String type = firstPredicateString(conditions.getAsJsonArray("entity"), "type");
                yield type == null ? null : Text.translatable("kindreds.deed.req.kill", entityName(type));
            }
            case "minecraft:consume_item" ->
                    itemsOf(conditions.getAsJsonObject("item"))
                            .map(items -> Text.translatable("kindreds.deed.req.eat", items)).orElse(null);
            case "minecraft:inventory_changed" -> {
                JsonArray items = conditions.getAsJsonArray("items");
                if (items == null || items.isEmpty()) {
                    yield null;
                }
                yield itemsOf(items.get(0).getAsJsonObject())
                        .map(name -> Text.translatable("kindreds.deed.req.have", name)).orElse(null);
            }
            case "minecraft:location" -> location(conditions);
            default -> null;
        };
    }

    /** Biome, dimension or depth - the three ways a deed asks you to be somewhere. */
    private static Text location(JsonObject conditions) {
        JsonArray player = conditions.getAsJsonArray("player");
        if (player == null) {
            return null;
        }
        for (JsonElement element : player) {
            JsonObject location = element.getAsJsonObject().getAsJsonObject("predicate");
            location = location == null ? null : location.getAsJsonObject("location");
            if (location == null) {
                continue;
            }
            String biome = str(location, "biomes");
            if (biome != null) {
                return Text.translatable("kindreds.deed.req.biome", placeName("biome", biome));
            }
            String dimension = str(location, "dimension");
            if (dimension != null) {
                return Text.translatable("kindreds.deed.req.dimension", placeName("dimension", dimension));
            }
            JsonObject position = location.getAsJsonObject("position");
            JsonObject y = position == null ? null : position.getAsJsonObject("y");
            if (y != null && y.has("max")) {
                return Text.translatable("kindreds.deed.req.below", y.get("max").getAsInt());
            }
            if (y != null && y.has("min")) {
                return Text.translatable("kindreds.deed.req.above", y.get("min").getAsInt());
            }
        }
        return null;
    }

    /** An item predicate names either one item or a tag of them. */
    private static java.util.Optional<Text> itemsOf(JsonObject predicate) {
        if (predicate == null) {
            return java.util.Optional.empty();
        }
        JsonElement items = predicate.get("items");
        if (items == null) {
            return java.util.Optional.empty();
        }
        String id = items.isJsonArray()
                ? (items.getAsJsonArray().isEmpty() ? null : items.getAsJsonArray().get(0).getAsString())
                : items.getAsString();
        return java.util.Optional.ofNullable(id).map(DeedIndex::itemName);
    }

    private static String firstPredicateString(JsonArray array, String field) {
        if (array == null) {
            return null;
        }
        for (JsonElement element : array) {
            JsonObject predicate = element.getAsJsonObject().getAsJsonObject("predicate");
            if (predicate != null && predicate.has(field)) {
                return predicate.get(field).getAsString();
            }
        }
        return null;
    }

    /** A registered mob names itself; anything else falls back to a key of our own. */
    private static Text entityName(String id) {
        if (id.startsWith("#")) {
            return ourName("entity", id);
        }
        return Registries.ENTITY_TYPE.getOptionalValue(Identifier.of(id))
                .<Text>map(type -> Text.translatable(type.getTranslationKey()))
                .orElseGet(() -> ourName("entity", id));
    }

    private static Text itemName(String id) {
        if (id.startsWith("#")) {
            return ourName("item", id);
        }
        return Registries.ITEM.getOptionalValue(Identifier.of(id))
                .<Text>map(item -> Text.translatable(item.getTranslationKey()))
                .orElseGet(() -> ourName("item", id));
    }

    /**
     * Biomes, dimensions and tags have no name the game will translate for us, so we keep our own -
     * and they must read as a place a player recognises ("the deep dark"), not as an id.
     */
    private static Text placeName(String kind, String id) {
        return ourName(kind, id);
    }

    private static Text ourName(String kind, String id) {
        String path = id.replace("#", "").replace(':', '.');
        return Text.translatableWithFallback("kindreds.deed." + kind + "." + path, prettify(id));
    }

    /** Last-ditch English if a name key is ever missing - readable, and obvious enough to notice. */
    private static String prettify(String id) {
        String path = id.substring(id.indexOf(':') + 1).replace("#", "").replace('_', ' ');
        return path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    private static String str(JsonObject object, String field) {
        JsonElement value = object == null ? null : object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
