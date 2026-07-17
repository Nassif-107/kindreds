package com.kindreds.data;

import java.util.List;

/**
 * Single source of truth for the mod's 7 built-in discipline ids (short, namespace-less paths -
 * e.g. {@code "combat"} rather than {@code "kindreds:combat"} - matching how both call sites
 * reconstruct the full {@link net.minecraft.util.Identifier} themselves).
 *
 * <p>Previously duplicated as {@code SkillTreeScreen.DISCIPLINE_PATHS} and
 * {@code KindredsCommand.DISCIPLINE_IDS}; factored out here so a future added/renamed discipline
 * can't desync the skill-tree UI's gauges from the {@code /kindreds} command's suggestions and
 * validation.
 */
public final class Disciplines {
    private Disciplines() {
    }

    /** The 7 built-in discipline ids, in display order. */
    public static final List<String> ALL = List.of(
            "combat", "archery", "mining", "stealth", "smithing", "survival", "lore");
}
