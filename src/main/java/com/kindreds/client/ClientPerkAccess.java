package com.kindreds.client;

import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.PerkDef;
import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Client-only check for whether the LOCAL player owns a given perk, resolved from the synced
 * {@link ClientKindredData} (race + unlocked nodes) against the synced skill-tree registry. Used by
 * {@link com.kindreds.ability.BowDrawSpeed} so the local player's bow-draw animation matches the
 * server's shot power for perk-gated draw speed. Only the local player is knowable client-side, which
 * is exactly the case that needs animation sync. Loaded lazily on the client only (see {@link
 * ClientRaceAccess}).
 */
@Environment(EnvType.CLIENT)
public final class ClientPerkAccess {
    private ClientPerkAccess() {
    }

    public static boolean localHasPerk(PlayerEntity player, String perkId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player != mc.player || mc.world == null) {
            return false;
        }
        KindredData data = ClientKindredData.INSTANCE;
        Identifier race = data.race();
        if (race == null) {
            return false;
        }
        Registry<SkillTree> trees = mc.world.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        SkillTree tree = SkillTreeResolver.byRace(trees, race).tree().orElse(null);
        if (tree == null) {
            return false;
        }
        for (String nodeId : data.unlockedNodes()) {
            var node = tree.node(nodeId);
            if (node.isPresent()) {
                for (AbilityDef ability : node.get().abilities()) {
                    if (ability instanceof PerkDef perk && perk.perk().equals(perkId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
