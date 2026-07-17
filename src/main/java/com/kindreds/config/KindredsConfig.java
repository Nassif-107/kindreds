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

    public DeathPenalty deathPenalty = DeathPenalty.KEEP;
    public double deathPercent = 0.25;
    public double xpRateGlobal = 1.0;
    public int pointSoftCap = 60;
    public String respecItem = "minecraft:amethyst_shard";
    public int respecCost = 1;
    public boolean enableVision = true;
    public boolean enableCurses = true;
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
     * Applies a named difficulty bundle: {@code "casual"}, {@code "normal"}, or
     * {@code "legendary"}. Unknown preset names are ignored.
     */
    public void applyPreset(String preset) {
        if (preset == null) {
            return;
        }
        switch (preset.toLowerCase(Locale.ROOT)) {
            case "casual" -> {
                deathPenalty = DeathPenalty.KEEP;
                deathPercent = 0.10;
                xpRateGlobal = 1.5;
                pointSoftCap = 80;
                enableCurses = false;
                enableEnemyScaling = false;
                allowCrossTraining = true;
            }
            case "normal" -> {
                deathPenalty = DeathPenalty.LOSE_UNSPENT;
                deathPercent = 0.25;
                xpRateGlobal = 1.0;
                pointSoftCap = 60;
                enableCurses = true;
                enableEnemyScaling = false;
                allowCrossTraining = true;
            }
            case "legendary" -> {
                deathPenalty = DeathPenalty.LOSE_PERCENT;
                deathPercent = 0.5;
                xpRateGlobal = 0.75;
                pointSoftCap = 40;
                enableCurses = true;
                enableEnemyScaling = true;
                allowCrossTraining = false;
            }
            default -> {
                // Unrecognized preset name: leave current settings untouched.
            }
        }
    }

    /**
     * Computes how many Kindred points are lost on death given the current
     * {@link #deathPenalty} setting.
     *
     * @param unspent       unspent (unallocated) points currently banked
     * @param totalProgress total accumulated progress (spent + unspent)
     * @return points lost; {@link Integer#MAX_VALUE} is a sentinel meaning "full wipe"
     */
    public int pointsLostOnDeath(int unspent, double totalProgress) {
        return switch (deathPenalty) {
            case KEEP -> 0;
            case LOSE_UNSPENT -> unspent;
            case LOSE_PERCENT -> (int) Math.round(totalProgress * deathPercent);
            case HARDCORE -> Integer.MAX_VALUE;
        };
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
