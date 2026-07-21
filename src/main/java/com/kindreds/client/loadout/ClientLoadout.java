package com.kindreds.client.loadout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.kindreds.Kindreds;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side ability loadout: which unlocked active ability sits in each of {@link #SLOTS} slots,
 * and which slot is currently selected (the one the "use ability" key fires). This lives client-side
 * because it's a pure UI preference - the server stays authoritative on what's unlocked and on
 * cooldowns ({@code ActiveAbilityService.activate} validates both), so a slot only ever holds an id
 * the player already earned, and firing a bad/locked id is a server-side no-op. Kept per race (each
 * race trains different actives) and persisted to a small JSON in the config dir so a loadout
 * survives relog.
 */
public final class ClientLoadout {
    private ClientLoadout() {
    }

    public static final int SLOTS = 4;

    /** race path -> the ability id (or "" for empty) in each of the {@link #SLOTS} slots. */
    private static final Map<String, List<String>> BY_RACE = new HashMap<>();
    private static int selected = 0;
    private static boolean loaded = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MODEL_TYPE = new TypeToken<Map<String, List<String>>>() {
    }.getType();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kindreds-loadout.json");
    }

    // --- Persistence ---------------------------------------------------------------------------

    public static void load() {
        loaded = true;
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try {
            Map<String, List<String>> stored = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), MODEL_TYPE);
            if (stored != null) {
                BY_RACE.clear();
                stored.forEach((race, slots) -> BY_RACE.put(race, normalize(slots)));
            }
        } catch (IOException | RuntimeException e) {
            Kindreds.LOGGER.warn("[Kindreds] could not read loadout file: {}", e.toString());
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), GSON.toJson(BY_RACE, MODEL_TYPE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Kindreds.LOGGER.warn("[Kindreds] could not write loadout file: {}", e.toString());
        }
    }

    /** Force every stored slot list to exactly {@link #SLOTS} entries (pad/truncate with ""). */
    private static List<String> normalize(List<String> slots) {
        List<String> out = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            out.add(slots != null && i < slots.size() && slots.get(i) != null ? slots.get(i) : "");
        }
        return out;
    }

    // --- Current-race slots ---------------------------------------------------------------------

    private static String currentRacePath() {
        Identifier race = ClientKindredData.INSTANCE.race();
        return race != null ? race.getPath() : "unknown";
    }

    private static List<String> slots() {
        if (!loaded) {
            load();
        }
        return BY_RACE.computeIfAbsent(currentRacePath(), r -> normalize(null));
    }

    /** The ability id in slot {@code i} ("" if empty or out of range). */
    public static String slot(int i) {
        if (i < 0 || i >= SLOTS) {
            return "";
        }
        return slots().get(i);
    }

    /** Assigns {@code abilityId} ("" clears) to slot {@code i} and persists. */
    public static void setSlot(int i, String abilityId) {
        if (i < 0 || i >= SLOTS) {
            return;
        }
        slots().set(i, abilityId == null ? "" : abilityId);
        save();
    }

    public static int selected() {
        return selected;
    }

    /** Advances the selected slot (wrapping) and returns the new index. */
    public static int cycleSelected() {
        selected = (selected + 1) % SLOTS;
        return selected;
    }

    /** Selects slot {@code i} directly (the radial menu and the per-slot keybinds), ignoring an
     * out-of-range index. Returns the slot actually selected. */
    public static int setSelected(int i) {
        if (i >= 0 && i < SLOTS) {
            selected = i;
        }
        return selected;
    }

    /** The ability id of the selected slot, or "" if that slot is empty. */
    public static String selectedAbilityId() {
        return slot(selected);
    }

    // --- Unlocked-active enumeration ------------------------------------------------------------

    /** Every distinct active-ability id the player has unlocked, in tree order - the pool the loadout
     * screen assigns from. Empty if the race/tree can't be resolved client-side yet. */
    public static List<String> unlockedAbilityIds() {
        MinecraftClient mc = MinecraftClient.getInstance();
        KindredData data = ClientKindredData.INSTANCE;
        Identifier race = data.race();
        if (mc.world == null || race == null) {
            return List.of();
        }
        Registry<SkillTree> trees = mc.world.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        SkillTree tree = SkillTreeResolver.byRace(trees, race).tree().orElse(null);
        if (tree == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String nodeId : data.unlockedNodes()) {
            tree.node(nodeId).ifPresent(node -> {
                for (AbilityDef ability : node.abilities()) {
                    if (ability instanceof ActiveAbilityDef active) {
                        String id = active.abilityId().toString();
                        if (!ids.contains(id)) {
                            ids.add(id);
                        }
                    }
                }
            });
        }
        return ids;
    }

    /** A short, readable display name for an ability id (e.g. {@code kindreds:durins_wrath} ->
     * "Durin's Wrath"). */
    public static String displayName(String abilityId) {
        if (abilityId == null || abilityId.isEmpty()) {
            return net.minecraft.client.resource.language.I18n.translate("kindreds.loadout.empty");
        }
        String path = abilityId.contains(":") ? abilityId.substring(abilityId.indexOf(':') + 1) : abilityId;
        // Prefer a localized name (kindreds.ability.<path>); fall back to a title-cased path when a
        // given active has no translation yet, so nothing ever shows a raw key.
        String key = "kindreds.ability." + path;
        String localized = net.minecraft.client.resource.language.I18n.translate(key);
        if (!localized.equals(key)) {
            return localized;
        }
        String[] words = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
