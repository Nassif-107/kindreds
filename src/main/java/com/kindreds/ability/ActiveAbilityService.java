package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.SkillNode;
import com.kindreds.data.SkillTree;
import com.kindreds.data.SkillTreeResolver;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.data.ability.ActiveAbilityDef;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.Optional;

/**
 * Server-side entry point for keybind-triggered active abilities: {@code ActivateAbilityC2S}'s
 * handler calls {@link #activate} directly.
 *
 * <h2>Ability resolution</h2>
 * {@link #activate} resolves the requesting player's {@link SkillTree} the same way
 * {@code RequestUnlockC2S} resolves it for unlocks - via {@link RaceAccess#getRace} matched
 * against {@link KindredsRegistries#SKILL_TREE} - then walks that tree's nodes <b>in authored
 * order</b> (not {@link KindredData#unlockedNodes()}'s hash-set iteration order, which isn't
 * stable or meaningful) looking for an unlocked node whose abilities include an
 * {@link ActiveAbilityDef}.
 *
 * <h2>{@code abilityId} matching (P1 client selection)</h2>
 * A blank/empty {@code abilityId} matches the <b>first</b> such ability found in tree order -
 * this is what the client keybind always sends (see {@code ActivateAbilityC2S}'s javadoc for why:
 * resolving "the player's tree" needs the player's race, a base-mod concept the client-side
 * {@code ClientKindredData} mirror doesn't carry, so the already-authoritative server does that
 * resolution once here instead of the client attempting to duplicate it). A non-blank
 * {@code abilityId} matches exactly (by {@link ActiveAbilityDef#abilityId()}), for a future UI
 * that lets a player choose a specific ability/slot without any change to this method.
 *
 * <p>If no unlocked active ability can be resolved (unknown race/tree, nothing unlocked, or a
 * non-blank id matching nothing), this is a silent no-op - matches the brief's "if not unlocked ->
 * no-op" and avoids spamming a player who presses the (default-unbound) key with nothing bound to
 * it.
 *
 * <h2>Cooldowns</h2>
 * Stored in {@link KindredData#cooldowns()}, keyed by {@code abilityId.toString()}, as an
 * <b>absolute world tick</b> ({@link net.minecraft.world.World#getTime()}) at which the cooldown
 * ends - not wall-clock milliseconds - so it stays in lockstep with
 * {@link ActiveAbilityDef#cooldownTicks()} (which is tick-denominated) and is unaffected by
 * wall-clock skew that {@code System.currentTimeMillis()} would introduce.
 */
public final class ActiveAbilityService {
    private ActiveAbilityService() {
    }

    public static void activate(ServerPlayerEntity player, String abilityId) {
        KindredData data = KindredAttachment.get(player);
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        Optional<SkillTree> tree = resolveTree(server, player);
        ActiveAbilityDef def = findActiveAbility(tree, data, abilityId);
        if (def == null) {
            return;
        }

        long now = player.getWorld().getTime();
        long cooldownEnd = data.cooldowns().getLong(def.abilityId().toString());
        if (now < cooldownEnd) {
            long remainingTicks = cooldownEnd - now;
            player.sendMessage(Text.literal(String.format(
                    "%s is on cooldown for %.1fs", def.abilityId(), remainingTicks / 20.0)), true);
            return;
        }

        AbilityApplier.applyActiveEffect(player, def);
        data.cooldowns().put(def.abilityId().toString(), now + def.cooldownTicks());
        castFeedback(player, def);
        SyncKindredDataS2C.sendTo(player);
    }

    /** Unmistakable "you fired an ability" feedback: a burst of enchant/end-rod particles, a beacon
     * chime, and the ability's name on the action bar. Without this an active reads as "nothing
     * happened" - the buff it grants is otherwise silent. */
    private static void castFeedback(ServerPlayerEntity player, ActiveAbilityDef def) {
        if (player.getWorld() instanceof ServerWorld world) {
            double x = player.getX(), y = player.getBodyY(0.6), z = player.getZ();
            world.spawnParticles(ParticleTypes.ENCHANT, x, y, z, 60, 0.6, 0.9, 0.6, 0.6);
            world.spawnParticles(ParticleTypes.END_ROD, x, player.getBodyY(1.0), z, 16, 0.35, 0.5, 0.35, 0.06);
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 12, 0.5, 0.6, 0.5, 0.15);
            world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, 1.5f);
            world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 0.5f, 1.2f);
        }
        String name = titleCase(def.abilityId().getPath());
        player.sendMessage(Text.literal("✦ ").formatted(Formatting.AQUA)
                .append(Text.literal(name).formatted(Formatting.AQUA, Formatting.BOLD))
                .append(Text.literal(" ✦").formatted(Formatting.AQUA)), true);
    }

    private static String titleCase(String path) {
        String[] words = path.replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** Mirrors {@code RequestUnlockC2S}'s race-based tree resolution (including its Task 12 Stage
     * B ambiguous-race guard, via {@link SkillTreeResolver}), without its node-id-scan fallback: an
     * active ability can only be resolved once we know which tree "the player's unlocked nodes"
     * refers to, so an unknown (or ambiguous) race means no active ability is resolvable here
     * (a silent no-op in {@link #activate}, not an error - though the ambiguous case is still
     * logged, so a duplicate-race authoring slip doesn't go unnoticed). */
    private static Optional<SkillTree> resolveTree(MinecraftServer server, ServerPlayerEntity player) {
        Optional<Identifier> race = RaceAccess.getRace(player);
        if (race.isEmpty()) {
            return Optional.empty();
        }
        Registry<SkillTree> trees = server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE);
        SkillTreeResolver.Resolution resolution = SkillTreeResolver.byRace(trees, race.get());
        if (resolution.matchCount() > 1) {
            Kindreds.LOGGER.warn(
                    "[Kindreds] race {} matches {} different skill trees; no active ability can be resolved "
                            + "unambiguously (fix the duplicate race authoring)", race.get(), resolution.matchCount());
        }
        return resolution.tree();
    }

    private static ActiveAbilityDef findActiveAbility(Optional<SkillTree> treeOpt, KindredData data, String abilityId) {
        if (treeOpt.isEmpty()) {
            return null;
        }
        boolean matchAny = abilityId == null || abilityId.isBlank();
        for (SkillNode node : treeOpt.get().nodes()) {
            if (!data.hasNode(node.id())) {
                continue;
            }
            for (AbilityDef ability : node.abilities()) {
                if (ability instanceof ActiveAbilityDef active
                        && (matchAny || active.abilityId().toString().equals(abilityId))) {
                    return active;
                }
            }
        }
        return null;
    }
}
