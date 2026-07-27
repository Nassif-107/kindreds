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

    /** Difficulty preset - <b>your</b> pacing and risk: xp rate, what a death costs, how much of your
     * tree you may master. Applied over the tuning fields below whenever it is not {@link
     * Difficulty#CUSTOM}, so picking a feel beats hand-balancing six numbers. Defaults to
     * {@link Difficulty#ROAD}, whose values are exactly what this mod shipped with - so adding
     * presets changes nothing for an existing world until you choose otherwise. */
    public Difficulty difficulty = Difficulty.ROAD;

    /**
     * Enemy-difficulty preset - <b>their</b> danger, the second and independent axis. See
     * {@link Menace} for why the two are separate.
     *
     * <p>Defaults to {@link Menace#CUSTOM} rather than to a named preset, and that is deliberate:
     * {@code CUSTOM} applies nothing, so a config written before this field existed keeps every
     * enemy-scaling number exactly as its operator left it. Defaulting to any real preset would
     * silently overwrite a hand-tuned server on the first launch after an update - the one thing a
     * difficulty setting must never do.
     */
    public Menace menace = Menace.CUSTOM;

    public DeathPenalty deathPenalty = DeathPenalty.KEEP;
    public double deathPercent = 0.25;
    public double xpRateGlobal = 1.0;
    /** Absolute ceiling on total points spent across the tree; {@code 0} = off. Superseded by
     * {@link #pointCapPercent} when that is set, which scales fairly across races of different
     * tree sizes (a flat number stops binding at all for the smallest tree). */
    public int pointSoftCap = 0;
    /** Cap expressed as a percentage of the player's own full tree cost (1-99). {@code 0} = use the
     * absolute {@link #pointSoftCap} instead; {@code >=100} = unlimited. */
    public int pointCapPercent = 75;
    public String respecItem = "minecraft:amethyst_shard";
    public int respecCost = 1;
    /** Pulsing HUD/tree animations (points pip, unlockable-node halos, "N ready" badges). Set false
     * for a completely static UI - motion sensitivity, or just preference. */
    public boolean hudAnimations = true;
    public boolean enableVision = true;
    public boolean enableCurses = true;
    public boolean enableBirthTraits = true;
    /**
     * Whether {@code /kindreds grantxp} may be used at all. <b>Off by default</b>: it hands out
     * progression directly, which is a testing tool, not a game mechanic - an operator on a live
     * server should have to switch it on deliberately rather than find it available by accident.
     * Being an operator is not the same as intending to cheat.
     */
    public boolean allowGrantXp = false;

    public boolean allowCrossTraining = true;
    /** Enemy scaling is on by default now: the world answering a grown hero is the intended game. */
    public boolean enableEnemyScaling = true;
    /** §2.6: how much of threat becomes difficulty. See {@link #scalingCurveExponent()}. */
    public ScalingCurve scalingCurve = ScalingCurve.FEEL_STRONGER;
    /** §2.1 prior weights: {@code Wc}, {@code Wg}, {@code Wr}. A server wanting pure skill-based
     * scaling sets {@link #weightGear} to 0. */
    public int weightCommitment = 3;
    public int weightGear = 2;
    public int weightRenown = 1;
    /** §2.2: the high-water prior mark falls toward the live reading by at most this many points
     * per <b>hour of played time</b> (never wall-clock or in-game-day time - see
     * {@code ThreatMath#decayed}). */
    public float priorDecayPerHour = 2f;
    /** §2.5-ish: ceiling on how much harder scaled enemies may hit, as a percent bonus over their
     * unscaled damage. Bounded so even {@code LONG_DEFEAT} at full threat stays survivable rather
     * than one-shotting a geared player. */
    public int maxDamageBonus = 60;
    /** A global per-player rate bonus that tracks threat: danger pays, on every award, as a percent
     * bonus - the world getting harder should also pay better, or growing stronger becomes a
     * strictly worse trade. */
    public int xpBonus = 50;
    /** 100 = the full evidence band; lower narrows it toward 1.0. It can never widen it (spec §2.4). */
    public int adaptiveStrength = 100;

    /** How much extra max health a mob may arrive with at full group threat. Percent. */
    public int maxHealthBonus = 100;
    /** Chance an in-scope mob is promoted to an elite at full group threat. Percent; 0 disables. */
    public int eliteChance = 25;
    /** Chance a scaled mob brings 1-2 escorts at full group threat. Percent; 0 disables. The escort
     * HARD BOUNDS (max 2, same species, natural spawns only, mob-cap suppression) are deliberately
     * not configurable - they are what keeps escorts from ever being a runaway population. */
    public int escortChance = 30;
    /** Extra group difficulty per additional nearby player, percent. A generous internal stop keeps a
     * full server from multiplying a mob past recognition, but this percentage is what governs for
     * any realistic party - see {@code ThreatService#GROUP_CAP}. */
    public int groupScalingPercent = 15;
    /**
     * How far coasting may push the evidence multiplier above 1.0 - the ceiling on how hard the world
     * may become for a player nothing threatens any more.
     *
     * <p>Was a hard constant at {@code 1.25} and became the binding limit on a live server, where all
     * three players sat pinned at exactly it. The matching floor stays a constant and is not settable:
     * the downward direction is the exploitable one (dying on purpose to soften the world), while
     * nothing about the upward direction can be farmed - the only route to it is to genuinely stop
     * being threatened. See {@code ThreatMath#competenceMax()}.
     */
    public float maxCompetence = 2.0f;
    /** Difficulty pacing per dimension: the old world stays gentler than the new one. */
    public float dimensionMultiplierMiddleEarth = 1.0f;
    public float dimensionMultiplierOverworld = 0.75f;

    // Deliberately NOT config fields: the hardship target, the two EWMA rates, the death penalty,
    // the gear reference and the danger yardstick. They are calibration rather than difficulty, and
    // several hold an invariant an operator could break without knowing - the EWMA rates must keep
    // rise > fall or death-farming returns, and the danger yardstick is what makes a trivial
    // attacker count for nothing. They live in ThreatTuning.DEFAULTS. See the Global Constraints.

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
                    // Applied after the pacing preset because the two axes no longer overlap: since
                    // Difficulty stopped writing enableEnemyScaling and scalingCurve, there is nothing
                    // for this to contend with, and ordering is documentation rather than precedence.
                    if (loaded.menace != null) {
                        loaded.menace.applyTo(loaded);
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
        if (scalingCurve == null) {
            scalingCurve = ScalingCurve.FEEL_STRONGER;
        }
        // Absent from every config written before the second axis existed, and CUSTOM is the only
        // value that leaves such a file's hand-tuned enemy numbers alone - see the field's javadoc.
        if (menace == null) {
            menace = Menace.CUSTOM;
        }
        // 0 is what Gson leaves a float field at when the key is absent, and a zero ceiling would
        // clamp every competence to 1.0 - adaptation silently switched off by an upgrade rather than
        // by a decision.
        if (maxCompetence <= 0f) {
            maxCompetence = 2.0f;
        }
    }

    /** The exponent {@code ThreatMath#scaled} applies, derived from {@link #scalingCurve}. A
     * method (not a plain field) so the curve stays the single authored setting - the JSON only
     * ever stores the named choice, never a raw float that could drift from it. */
    public float scalingCurveExponent() {
        return scalingCurve.exponent;
    }
}
