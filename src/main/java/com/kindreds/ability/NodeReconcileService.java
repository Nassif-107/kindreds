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
        for (String nodeId : data.unlockedNodes()) {
            tree.node(nodeId).ifPresent(node -> {
                for (AbilityDef ability : node.abilities()) {
                    AbilityApplier.apply(player, ability, nodeId);
                }
            });
        }
    }
}
