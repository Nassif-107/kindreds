package com.kindreds.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Plain-data server configuration for Kindreds of Middle-earth.
 *
 * Pure Java + Gson - no Minecraft APIs. Loaded/saved as pretty-printed JSON
 * at a caller-supplied path (typically the world's config or server config dir).
 */
public class KindredsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Difficulty preset. Applied over the tuning fields below whenever it is not {@link
     * Difficulty#CUSTOM}, so picking a feel beats hand-balancing six numbers. Defaults to
     * {@link Difficulty#ROAD}, whose values are exactly what this mod shipped with - so adding
     * presets changes nothing for an existing world until you choose otherwise. */
    public Difficulty difficulty = Difficulty.ROAD;

    public DeathPenalty deathPenalty = DeathPenalty.KEEP;
    public double deathPercent = 0.25;
    public double xpRateGlobal = 1.0;
    public int pointSoftCap = 60;
    public String respecItem = "minecraft:amethyst_shard";
    public int respecCost = 1;
    /** Pulsing HUD/tree animations (points pip, unlockable-node halos, "N ready" badges). Set false
     * for a completely static UI - motion sensitivity, or just preference. */
    public boolean hudAnimations = true;
    public boolean enableVision = true;
    public boolean enableCurses = true;
    public boolean enableBirthTraits = true;
    public boolean allowCrossTraining = true;
    public boolean enableEnemyScaling = false;

    /**
     * Loads config from {@code path}. If the file is missing, unreadable, or
     * fails to parse, returns fresh defaults and writes them to {@code path}
     * (creating parent directories as needed).
     */
    public static KindredsConfig load(Path path) {
        if (path != null && Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                KindredsConfig loaded = GSON.fromJson(reader, KindredsConfig.class);
                if (loaded != null) {
                    loaded.fillMissingWithDefaults();
                    if (loaded.difficulty != null) {
                        loaded.difficulty.applyTo(loaded);
                    }
                    return loaded;
                }
            } catch (IOException | JsonSyntaxException e) {
                // Tolerant of parse errors / IO issues - fall through to defaults.
            }
        }
        KindredsConfig defaults = new KindredsConfig();
        defaults.save(path);
        return defaults;
    }

    /** Writes this config as pretty-printed JSON to {@code path}. */
    public void save(Path path) {
        if (path == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save Kindreds config to " + path, e);
        }
    }

    /**
     * Applies a named difficulty bundle by its legacy string name ({@code "casual"}, {@code "normal"},
     * {@code "legendary"}) - kept so older configs/commands keep working, but it now delegates to the
     * typed {@link Difficulty} presets so there is exactly one table to maintain.
     *
     * <p>Note that difficulty no longer touches {@code enableCurses}: racial drawbacks are identity,
     * not difficulty (see {@link Difficulty}). Unknown names are ignored.
     */
    public void applyPreset(String preset) {
        if (preset == null) {
            return;
        }
        Difficulty d = switch (preset.toLowerCase(Locale.ROOT)) {
            case "casual", "fireside" -> Difficulty.FIRESIDE;
            case "normal", "road", "default" -> Difficulty.ROAD;
            case "hard", "long_defeat" -> Difficulty.LONG_DEFEAT;
            case "legendary", "doom" -> Difficulty.DOOM;
            default -> null;
        };
        if (d != null) {
            difficulty = d;
            d.applyTo(this);
        }
    }

    /** Guards against partially-populated JSON (e.g. an older config missing new fields). */
    private void fillMissingWithDefaults() {
        if (deathPenalty == null) {
            deathPenalty = DeathPenalty.KEEP;
        }
        if (respecItem == null) {
            respecItem = "minecraft:amethyst_shard";
        }
    }
}
