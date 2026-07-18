package com.kindreds.progression;

import com.kindreds.Kindreds;
import com.kindreds.ability.AbilityApplier;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Optional;

/**
 * Shared "undo every unlocked node" logic used by both {@code /kindreds respec} (the admin/testing
 * escape hatch) and {@code RespecC2S} (the player-facing respec, gated behind the config
 * {@code respecItem} cost - see that class). Factored out here rather than duplicated in both
 * places, so the two respec entry points can never quietly diverge in what "reversed" means.
 */
public final class RespecService {
    private RespecService() {
    }

    /**
     * Reverses every node {@code target} has unlocked - via {@link AbilityApplier#removeNode}, which
     * is given each node's actual ability list, so it fully reverses status effects too, not just
     * attribute modifiers - then clears {@link KindredData#unlockedNodes()}. Does <b>not</b> send
     * {@code SyncKindredDataS2C} itself;
     * callers are expected to re-sync afterward (both current callers do).
     *
     * @return the number of unlocked nodes that were found in a registered tree and reversed (nodes
     * whose id can no longer be found in any tree are still cleared from {@code unlockedNodes}, but
     * are logged and not counted, matching the pre-existing {@code /kindreds respec} behavior).
     */
    public static int reverseAll(ServerPlayerEntity target) {
        KindredData data = KindredAttachment.get(target);
        Registry<SkillTree> trees = target.getServer().getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);

        int reversedCount = 0;
        for (String nodeId : List.copyOf(data.unlockedNodes())) {
            SkillNode node = findNodeInAnyTree(trees, nodeId);
            if (node != null) {
                AbilityApplier.removeNode(target, node.abilities(), nodeId);
                reversedCount++;
            } else {
                Kindreds.LOGGER.warn(
                        "[Kindreds] respec: node {} (unlocked by {}) not found in any registered skill tree; "
                                + "clearing the unlock without reversing its abilities",
                        nodeId, target.getGameProfile().getName());
            }
        }
        data.unlockedNodes().clear();
        com.kindreds.ability.PerkService.invalidate(target.getUuid());
        return reversedCount;
    }

    /** Scans every registered {@link SkillTree} (not just the target's race tree) for {@code nodeId}
     * - mirrors {@code RequestUnlockC2S}'s node-id-scan fallback for the same reason: this is a
     * respec (admin or paid), not the untrusted-input unlock path, so the first match is used
     * without an ambiguity check. */
    private static SkillNode findNodeInAnyTree(Registry<SkillTree> trees, String nodeId) {
        for (SkillTree tree : trees) {
            Optional<SkillNode> node = tree.node(nodeId);
            if (node.isPresent()) {
                return node.get();
            }
        }
        return null;
    }
}
