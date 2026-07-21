package com.kindreds.ability;

import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Re-applies every unlocked skill-node's passive abilities to a player. Node abilities are installed
 * once at unlock ({@code RequestUnlockC2S}) as persistent attribute modifiers / status effects, which
 * normally survive relog on their own - but the base Middle-earth mod's {@code clearModifiers()} on
 * login and dimension change strips the attribute ones (exactly as it does birth traits), and nothing
 * was re-asserting them, so a tree's stat/percentage nodes silently died after a relog or a Nether
 * trip. {@link BirthTraitService} calls {@link #reapply} on the same deferred, post-base-mod schedule
 * it uses for birth traits, so node effects are re-asserted last and stick.
 *
 * <p>Re-application is idempotent: {@code AbilityApplier.apply} removes-then-adds each attribute
 * modifier by its node-tagged id (so no duplicate-id throw), refreshes infinite status effects, and
 * is a no-op for contextual/perk/vision/active abilities (whose lifecycles other systems own). It is
 * therefore safe to run on every reconcile without stacking or blipping.
 */
public final class NodeReconcileService {
    private NodeReconcileService() {
    }

    /** Re-assert all of {@code player}'s unlocked-node passive abilities. No-op if they have none. */
    public static void reapply(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        KindredData data = KindredAttachment.get(player);
        if (data.unlockedNodes().isEmpty()) {
            return;
        }
        Identifier race = RaceAccess.getRace(player).orElse(null);
        if (race == null) {
            return;
        }
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        SkillTree tree = SkillTreeResolver.byRace(trees, race).tree().orElse(null);
        if (tree == null) {
            return;
        }
        pruneForeignNodes(player, data, tree, trees);

        for (String nodeId : data.unlockedNodes()) {
            tree.node(nodeId).ifPresent(node -> {
                for (AbilityDef ability : node.abilities()) {
                    AbilityApplier.apply(player, ability, nodeId);
                }
            });
        }
    }

    /**
     * Reverses and drops any unlocked node that does not belong to the player's <b>current</b> tree.
     *
     * <p>Changing kindred used to leave the previous people's node modifiers installed forever: the
     * ids stayed in {@code unlockedNodes}, nothing reversed them, and the new tree simply did not
     * contain them, so they became invisible permanent bonuses. Cycling all eight races took a Dwarf
     * from 12 hearts to 33.5 - a player who rerolls a few times becomes absurd. A node from another
     * people's tree is not a node you own, so it is reversed here and forgotten.
     *
     * <p>Deliberately conservative about wiping: it only prunes when this race's tree resolved and
     * has nodes, so a datapack that fails to load cannot be mistaken for "you own nothing", and it
     * only reverses ids it can actually find in some registered tree - an unknown id is dropped
     * without pretending to reverse abilities it cannot see.
     */
    private static void pruneForeignNodes(ServerPlayerEntity player, KindredData data, SkillTree tree,
                                          Registry<SkillTree> trees) {
        if (tree.nodes().isEmpty()) {
            return;
        }
        java.util.List<String> foreign = new java.util.ArrayList<>();
        for (String nodeId : data.unlockedNodes()) {
            if (tree.node(nodeId).isEmpty()) {
                foreign.add(nodeId);
            }
        }
        if (foreign.isEmpty()) {
            return;
        }
        for (String nodeId : foreign) {
            for (SkillTree other : trees) {
                var node = other.node(nodeId);
                if (node.isPresent()) {
                    AbilityApplier.removeNode(player, node.get().abilities(), nodeId);
                    break;
                }
            }
            data.unlockedNodes().remove(nodeId);
        }
        com.kindreds.ability.PerkService.invalidate(player.getUuid());
        com.kindreds.Kindreds.LOGGER.info(
                "[Kindreds] {} changed kindred: reversed {} node(s) belonging to their former people",
                player.getGameProfile().getName(), foreign.size());
    }
}
