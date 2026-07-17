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
 * {@code data/<namespace>/discipline/<path>.json} and
 * {@code data/<namespace>/skill_tree/<path>.json}, and are synced from server
 * to client using the same codec (see {@link DynamicRegistries#registerSynced}).
 */
public final class KindredsRegistries {
    private KindredsRegistries() {
    }

    public static final RegistryKey<Registry<Discipline>> DISCIPLINE =
            RegistryKey.ofRegistry(Identifier.of(Kindreds.MOD_ID, "discipline"));

    public static final RegistryKey<Registry<SkillTree>> SKILL_TREE =
            RegistryKey.ofRegistry(Identifier.of(Kindreds.MOD_ID, "skill_tree"));

    /** Registers all Kindreds dynamic registries. Must be called from {@link Kindreds#onInitialize()}. */
    public static void register() {
        DynamicRegistries.registerSynced(DISCIPLINE, Discipline.CODEC);
        DynamicRegistries.registerSynced(SKILL_TREE, SkillTree.CODEC);
    }
}
