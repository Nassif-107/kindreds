package com.kindreds.data;

import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Shared "find the one {@link SkillTree} for this race" scan, factored out of the (previously
 * triplicated) resolution logic in {@code RequestUnlockC2S}, {@code ActiveAbilityService}, and
 * {@code CurseContextService} - all three need exactly the same answer to "does this player's race
 * match more than one authored tree", so Task 12 Stage B's ambiguous-race guard only has one place
 * to get right instead of three independently-maintained copies.
 */
public final class SkillTreeResolver {
    private SkillTreeResolver() {
    }

    /**
     * @param tree       present iff exactly one tree in the registry has {@code race() == race};
     *                   empty when zero or more than one match (see {@link #matchCount}
     *                   to distinguish the two - callers report different failure reasons for
     *                   "no tree for this race" vs. "ambiguous race").
     * @param matchCount how many trees in the registry matched; callers use this to log/report
     *                   which of the two empty-{@code tree} cases occurred.
     */
    public record Resolution(Optional<SkillTree> tree, int matchCount) {
    }

    /** Scans every {@link SkillTree} in {@code trees} for one whose {@link SkillTree#race()}
     * equals {@code race}. */
    public static Resolution byRace(Registry<SkillTree> trees, Identifier race) {
        SkillTree match = null;
        int count = 0;
        for (SkillTree tree : trees) {
            if (tree.race().equals(race)) {
                match = tree;
                count++;
            }
        }
        return new Resolution(count == 1 ? Optional.of(match) : Optional.empty(), count);
    }
}
