package com.kindreds.threat;

import com.kindreds.Kindreds;
import com.kindreds.data.Disciplines;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.ProgressionService;
import com.kindreds.progression.RenownService;
import com.kindreds.progression.UnlockService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single authority on how strong a player is, and the only class the rest of the mod asks.
 *
 * <p>Cached per player, because the prior walks the whole skill tree and the damage hook that
 * reads it runs on every blow struck in the world. {@code PerkService} solves the same problem the
 * same way; the cache is invalidated wherever the inputs change (unlock, respec, race change,
 * disconnect), and refreshed on a slow timer besides so a player who never triggers one of those
 * events still sees their evidence-driven competence move over time.
 */
public final class ThreatService {
    private ThreatService() {
    }

    private static final Map<UUID, Float> CACHE = new ConcurrentHashMap<>();
    private static final int REFRESH_TICKS = 40;
    private static int tickCounter;

    /** Registers the disconnect-invalidation hook and the slow refresh timer. Call once from
     * {@link Kindreds#onInitialize()}. */
    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> invalidate(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % REFRESH_TICKS != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                KindredData data = KindredAttachment.get(player);
                data.threat().addPlayedTicks(REFRESH_TICKS);
                refresh(player, data);
            }
        });
    }

    /** Forgets {@code uuid}'s cached threat; the next {@link #threatOf} call rebuilds it. Called
     * wherever an input to the prior changes: unlock, respec, race change, and (via
     * {@link #register}) disconnect. */
    public static void invalidate(UUID uuid) {
        CACHE.remove(uuid);
    }

    /** The player's threat, 0..100. Cheap: served from cache between refreshes - the damage hook
     * that reads this on every hit must never itself trigger a recompute. */
    public static float threatOf(ServerPlayerEntity player) {
        Float cached = CACHE.get(player.getUuid());
        if (cached != null) {
            return cached;
        }
        return refresh(player, KindredAttachment.get(player));
    }

    /** Threat as world difficulty, 0..1, with the server's curve applied. */
    public static float scaledFor(ServerPlayerEntity player) {
        if (Kindreds.CONFIG == null || !Kindreds.CONFIG.enableEnemyScaling) {
            return 0f;
        }
        return ThreatMath.scaled(threatOf(player), Kindreds.CONFIG.scalingCurveExponent());
    }

    /** As {@link #scaledFor}, adjusted by the player's record against this mob's family. */
    public static float scaledAgainst(ServerPlayerEntity player, LivingEntity mob) {
        if (Kindreds.CONFIG == null || !Kindreds.CONFIG.enableEnemyScaling) {
            return 0f;
        }
        KindredData data = KindredAttachment.get(player);
        float global = data.threat().competence();
        float family = data.threat().familyCompetence().getOrDefault(MobDanger.family(mob), global);
        float competence = ThreatMath.effectiveCompetence(global, family);
        float threat = ThreatMath.threat(data.threat().priorMark(), competence);
        return ThreatMath.scaled(threat, Kindreds.CONFIG.scalingCurveExponent());
    }

    private static float refresh(ServerPlayerEntity player, KindredData data) {
        ThreatState state = data.threat();

        // the live reading of declared power
        float commitment = commitmentOf(player, data);
        float gear = gearOf(player);
        float renown = Math.min(1f, RenownService.deedsForRace(data) / 4f);
        float live = ThreatMath.prior(commitment, gear, renown,
                Kindreds.CONFIG.weightCommitment, Kindreds.CONFIG.weightGear, Kindreds.CONFIG.weightRenown);

        // the mark: rises at once, falls only with played time - never a live snapshot, so
        // stripping gear or respeccing can never collapse threat on the spot (see ThreatMath#decayed).
        // REFRESH_TICKS (not state.playedTicks(), which accumulates for the player's whole life) is
        // the played-time INCREMENT since the last decay step - using the lifetime total here would
        // make decay accelerate without bound for a veteran character.
        float mark = ThreatMath.decayed(state.priorMark(), live,
                Kindreds.CONFIG.priorDecayPerHour, REFRESH_TICKS);
        state.setPriorMark(mark);
        state.setMaxHealthMark(Math.max(state.maxHealthMark(),
                (float) player.getAttributeValue(EntityAttributes.MAX_HEALTH)));

        float threat = ThreatMath.threat(mark, state.competence());
        // Captured BEFORE the cache is overwritten - this is the one place the "before" figure a
        // rank-crossing announcement needs still exists. Absent (null) means this player has no
        // prior refresh to compare against (first population after login), which announceRankChange
        // must treat as silence rather than as a crossing from "nothing".
        Float previous = CACHE.get(player.getUuid());
        CACHE.put(player.getUuid(), threat);
        announceRankChange(player, previous, threat);
        return threat;
    }

    /**
     * Tells the player when their threat crosses a named rank boundary - the story beat is the
     * crossing, not the number moving (see {@link ThreatRank}). Silent when {@code previous} is
     * absent: that means this is the player's first refresh since joining, and "nothing" is not a
     * rank to have risen or fallen from - announcing here would just be a login-spam message.
     */
    private static void announceRankChange(ServerPlayerEntity player, Float previous, float threat) {
        if (previous == null) {
            return;
        }
        ThreatRank was = ThreatRank.of(previous);
        ThreatRank now = ThreatRank.of(threat);
        if (was == now) {
            return;
        }
        String key = now.ordinal() > was.ordinal() ? "kindreds.threat.risen" : "kindreds.threat.fallen";
        player.sendMessage(Text.translatable(key, Text.translatable(now.translationKey())), false);
    }

    /**
     * Points a player has committed <b>plus those they could commit right now</b>, against the
     * tree's full cost. Both halves are exploit fixes: a fixed denominator so earning a deed cannot
     * shrink the fraction, and counting unspent points so banking them is not a way to stay
     * invisible. A player with no race has no tree, so this is {@code 0} rather than throwing.
     */
    private static float commitmentOf(ServerPlayerEntity player, KindredData data) {
        Optional<SkillTree> tree = UnlockService.treeFor(player);
        if (tree.isEmpty()) {
            return 0f;   // no race yet: the prior is gear and renown only
        }
        int max = UnlockService.maxSpendable(tree.get());
        int spent = UnlockService.totalPointsSpent(data, tree.get());
        int available = 0;
        for (String discipline : Disciplines.ALL) {
            available += Math.max(0, ProgressionService.pointsAvailable(data, tree.get(),
                    Identifier.of(Kindreds.MOD_ID, discipline)));
        }
        return commitmentFrom(spent, available, max);
    }

    /**
     * The pure arithmetic core of {@link #commitmentOf}, split out so the anti-exploit shape of the
     * formula can be proved by unit test with no Minecraft on the classpath - the same shape
     * {@link MobDanger#family(String)} uses. See {@code ThreatServiceTest} for the two properties
     * this must hold: the denominator is the tree's fixed {@code max} (never a cap that could rise
     * with renown), and unspent {@code available} points count exactly as much as {@code spent}
     * ones.
     */
    static float commitmentFrom(int spent, int available, int max) {
        if (max <= 0) {
            return 0f;
        }
        return Math.min(1f, (spent + available) / (float) max);
    }

    /** Armour and weapon, normalised against a full-mithril reference. */
    private static float gearOf(ServerPlayerEntity player) {
        double armour = player.getAttributeValue(EntityAttributes.ARMOR);
        double damage = player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        float armourPart = (float) Math.min(1.0, armour / 25.0);     // 25 is about full mithril
        float damagePart = (float) Math.min(1.0, damage / 12.0);     // 12 is about a mithril sword
        return Math.min(1f, 0.6f * armourPart + 0.4f * damagePart);
    }
}
