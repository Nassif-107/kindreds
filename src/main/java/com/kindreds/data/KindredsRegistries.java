package com.kindreds.data;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

/**
 * Synced dynamic registries backing the Kindreds data-driven content model.
 *
 * <p>Entries are loaded from data pack JSON at
 * {@code data/<namespace>/kindreds/discipline/<path>.json} and
 * {@code data/<namespace>/kindreds/skill_tree/<path>.json}, and are synced from server
 * to client using the same codec (see {@link DynamicRegistries#registerSynced}).
 */
public final class KindredsRegistries {
    private KindredsRegistries() {
    }

    public static final RegistryKey<Registry<Discipline>> DISCIPLINE =
            RegistryKey.ofRegistry(Identifier.of(Kindreds.MOD_ID, "discipline"));

    public static final RegistryKey<Registry<SkillTree>> SKILL_TREE =
            RegistryKey.ofRegistry(Identifier.of(Kindreds.MOD_ID, "skill_tree"));

    /**
     * Backs {@link SkillTree#theme()}: entries are loaded from
     * {@code data/<namespace>/kindreds/theme/<path>.json} (e.g. {@code data/kindreds/kindreds/theme/elf.json} resolves
     * as {@code kindreds:elf}, matching the id the Elf tree JSON references) and synced to the client
     * the same way {@link #DISCIPLINE}/{@link #SKILL_TREE} are, so the tree screen (Task 11) can
     * resolve a race's theme from the client-mirrored registry manager.
     */
    public static final RegistryKey<Registry<Theme>> THEME =
            RegistryKey.ofRegistry(Identifier.of(Kindreds.MOD_ID, "theme"));

    /**
     * Backs {@code com.kindreds.progression.RaceScaling}'s per-race, per-discipline xp multiplier
     * table: entries are loaded from {@code data/<namespace>/kindreds/race_scaling/<path>.json} the same way
     * the other three registries are, and materialized into {@code RaceScaling}'s plain lookup
     * table on server start / datapack reload (see {@code Kindreds#onInitialize()}).
     */
    public static final RegistryKey<Registry<RaceScalingEntry>> RACE_SCALING =
            RegistryKey.ofRegistry(Identifier.of(Kindreds.MOD_ID, "race_scaling"));

    /** Registers all Kindreds dynamic registries. Must be called from {@link Kindreds#onInitialize()}. */
    public static void register() {
        DynamicRegistries.registerSynced(DISCIPLINE, Discipline.CODEC);
        DynamicRegistries.registerSynced(SKILL_TREE, SkillTree.CODEC);
        DynamicRegistries.registerSynced(THEME, Theme.CODEC);
        DynamicRegistries.registerSynced(RACE_SCALING, RaceScalingEntry.CODEC);
    }
}
