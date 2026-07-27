package com.kindreds.data;

import com.kindreds.Kindreds;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.VisionUnlock;
import com.kindreds.playerdata.KindredData;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared "which vision lenses does this player have unlocked" logic, used by both the client-side
 * {@code com.kindreds.vision.VisionManager} (to decide what the cycle keybind can select) and the
 * server-side {@code com.kindreds.network.SetVisionLensC2S} handler (to re-validate a lens-select
 * request rather than trusting the client). Factored out here - rather than duplicated in both
 * places - so the two sides can never disagree about what counts as "unlocked": they read the same
 * {@code SKILL_TREE} registry shape and the same {@link KindredData#unlockedNodes()} set through
 * this one method.
 */
public final class VisionLenses {
    private VisionLenses() {
    }

    /** Maps a {@link VisionUnlock#visionId()} string (as authored in a skill tree JSON, e.g.
     * {@code "stone_sense"}) to the lens {@link Identifier} the vision framework and network layer
     * key off of. A value already containing a namespace ({@code ':'}) is parsed as-is; a bare path
     * is namespaced under {@link Kindreds#MOD_ID}. */
    public static Identifier lensId(String visionId) {
        return visionId.indexOf(':') >= 0 ? Identifier.of(visionId) : Identifier.of(Kindreds.MOD_ID, visionId);
    }

    /** Every vision lens {@code data} has unlocked - i.e. every {@link VisionUnlock} ability
     * carried by a node in {@code trees} that {@code data} owns - mapped to the <b>widest</b> radius
     * any unlocking node authored, in encounter order.
     *
     * <p>Widest, not first: more than one node may grant the same lens, and the Elf's tree does -
     * {@code elf.archery.eye_of_the_eldar} opens keen-sight at 32 blocks, and
     * {@code elf.lore.sight_beyond_sight} opens it at 40. Taking the first encountered meant that
     * whichever node happened to appear earlier in the tree JSON won, so an Elf who bought both got
     * the archery node's 32 and the lore node's larger sight silently did nothing - two points spent
     * on a "vision" line that, for anyone already carrying the lens, granted no vision at all. Every
     * grant of a lens now counts for the reach it declares.
     *
     * <p>Scans every tree (rather than resolving a single race tree the way {@code
     * RequestUnlockC2S} does for an unlock request) since there's no single "requested node" here
     * to disambiguate from - this just asks "what do I already own", and a player only ever owns
     * nodes from their own race's tree in practice, so scanning every tree carries none of the
     * ambiguity risk a same-id-in-two-trees unlock request would.
     */
    public static Map<Identifier, Integer> unlockedLenses(KindredData data, Registry<SkillTree> trees) {
        Map<Identifier, Integer> result = new LinkedHashMap<>();
        for (SkillTree tree : trees) {
            for (SkillNode node : tree.nodes()) {
                if (!data.hasNode(node.id())) {
                    continue;
                }
                for (AbilityDef ability : node.abilities()) {
                    if (ability instanceof VisionUnlock vision) {
                        result.merge(lensId(vision.visionId()), vision.radius(), Math::max);
                    }
                }
            }
        }
        return result;
    }
}
