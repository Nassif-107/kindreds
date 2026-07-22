package com.kindreds.threat;

import com.kindreds.Kindreds;
import com.kindreds.ability.AbilityApplier;
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
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
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
    /** The rank each player was last told they held - the announcement's "before" memory.
     * Deliberately NOT {@link #CACHE}: the cache is invalidated by exactly the events that MOVE
     * threat (every evidence fold, unlock, respec, race change), so a crossing caused by any of
     * those would find no "before" figure left to compare against and never be announced. This map
     * is untouched by {@link #invalidate}; only {@link #register}'s disconnect handler clears it. */
    private static final Map<UUID, ThreatRank> LAST_ANNOUNCED = new ConcurrentHashMap<>();
    private static final int REFRESH_TICKS = 40;
    private static int tickCounter;

    /** The base mod's stealth attribute - resolved generically by {@link AbilityApplier}, which
     * no-ops if it is absent, so this class has no compile-time dependency on the base mod. */
    private static final Identifier DETECTION_RANGE_ID = Identifier.of("middle-earth", "detection_range");

    /** Registers the disconnect-invalidation hook and the slow refresh timer. Call once from
     * {@link Kindreds#onInitialize()}. */
    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUuid();
            invalidate(uuid);
            LAST_ANNOUNCED.remove(uuid);
        });
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

    /** The +45%% group-size cap is a bound, not a dial (spec §4) - it is what keeps a full server
     * from multiplying a mob past recognition. */
    private static final float GROUP_CAP = 0.45f;
    /** "Nearby" for a spawn decision, blocks (spec §4). */
    private static final double GROUP_RADIUS = 128.0;

    /** Pure core: the strongest figure carries the group bonus. Package-private for the unit test. */
    static float groupOf(List<Float> scaledValues, float perPlayer) {
        float strongest = 0f;
        for (float s : scaledValues) {
            strongest = Math.max(strongest, s);
        }
        return ThreatMath.group(strongest, scaledValues.size(), perPlayer, GROUP_CAP);
    }

    /** Pure core: middle-earth paces at its own multiplier; everywhere else is the old world. */
    static float dimensionMultiplier(String dimensionNamespace, float middleEarth, float overworld) {
        return "middle-earth".equals(dimensionNamespace) ? middleEarth : overworld;
    }

    /**
     * The SHARED difficulty for a mob entering the world at {@code pos} (spec §4): the strongest
     * player within 128 blocks carries the group bonus; a spawn with no player in range uses the
     * strongest player in the dimension, undecayed - an AFK farm 130 blocks out must not be a
     * difficulty switch. No players in the dimension at all -> 0 (an unwitnessed mob costs nothing).
     */
    public static float scaledGroupAt(ServerWorld world, BlockPos pos) {
        if (Kindreds.CONFIG == null || !Kindreds.CONFIG.enableEnemyScaling) {
            return 0f;
        }
        List<Float> near = new ArrayList<>();
        float strongestInDimension = 0f;
        double radiusSq = GROUP_RADIUS * GROUP_RADIUS;
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.isSpectator()) continue; // a spectator is not "near enough to matter"
            float s = scaledFor(p);
            strongestInDimension = Math.max(strongestInDimension, s);
            if (p.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= radiusSq) {
                near.add(s);
            }
        }
        float group = near.isEmpty()
                ? strongestInDimension
                : groupOf(near, Kindreds.CONFIG.groupScalingPercent / 100f);
        return group * dimensionMultiplier(world.getRegistryKey().getValue().getNamespace(),
                Kindreds.CONFIG.dimensionMultiplierMiddleEarth,
                Kindreds.CONFIG.dimensionMultiplierOverworld);
    }

    /** The {@link ThreatTuning} every fold and re-band in the mod uses: {@link ThreatTuning#DEFAULTS}
     * narrowed by the server's {@code adaptiveStrength}. The one place this construction happens, so
     * {@link ThreatEvidence}'s folds and this class's refresh-time re-band can never quietly build it
     * two different ways. Only called where {@link Kindreds#CONFIG} is already known non-null. */
    static ThreatTuning tuningFor() {
        return ThreatTuning.withAdaptiveStrength(Kindreds.CONFIG.adaptiveStrength);
    }

    /**
     * Re-clamps a stored competence value into the band the given {@code adaptiveStrength} implies.
     * Pulled out as a pure function, provable without a running game, because it is the one piece of
     * the FIX 2 defence with no Minecraft in it: an operator LOWERING {@code adaptiveStrength} must
     * immediately narrow competence already sitting in {@link ThreatState}, not just competence
     * folded from here on - {@link ThreatEvidence#register} only ever narrows a value at the moment
     * it folds, so without this, a family never fought again keeps its old, wider value forever.
     */
    static float rebanded(float competence, int adaptiveStrength) {
        return ThreatMath.rebanded(competence, ThreatTuning.withAdaptiveStrength(adaptiveStrength));
    }

    private static float refresh(ServerPlayerEntity player, KindredData data) {
        ThreatState state = data.threat();

        // Re-band stored competence (global and per-family) before anything reads it: adaptiveStrength
        // may have been lowered since these were last folded, and a fold-time-only clamp would leave a
        // family the player hasn't fought since sitting at its old, wider value indefinitely (FIX 2).
        // Writing back keeps the client-synced ThreatState consistent with what refresh computes below.
        int adaptiveStrength = Kindreds.CONFIG.adaptiveStrength;
        state.setCompetence(rebanded(state.competence(), adaptiveStrength));
        for (Map.Entry<String, Float> entry : state.familyCompetence().entrySet()) {
            entry.setValue(rebanded(entry.getValue(), adaptiveStrength));
        }

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
        //
        // Acknowledged, not fixed: a cache-miss refresh (threatOf after invalidate - unlock, respec,
        // race change, a kill/death fold) also charges a full REFRESH_TICKS of decay allowance even
        // when far less than 40 ticks have actually elapsed since the last refresh. At
        // priorDecayPerHour's default of 2/hour that is about 0.001 points per invalidation - not
        // remotely exploitable even chained rapidly - so this is a documented phantom allowance, not
        // a bug to fix here.
        float mark = ThreatMath.decayed(state.priorMark(), live,
                Kindreds.CONFIG.priorDecayPerHour, REFRESH_TICKS);
        state.setPriorMark(mark);
        state.setMaxHealthMark(Math.max(state.maxHealthMark(),
                (float) player.getAttributeValue(EntityAttributes.MAX_HEALTH)));

        float threat = ThreatMath.threat(mark, state.competence());
        CACHE.put(player.getUuid(), threat);

        // Threat erodes stealth: a positive modifier drags a stealth-lowered detection_range back
        // toward its 1.0 baseline. The attribute's own [0.1, 1.0] clamp is the cap - at full threat
        // the counter (+0.9) cancels the deepest stealth build exactly, and can never exceed
        // baseline. setDynamicModifier resolves the attribute generically and no-ops when the base
        // mod is absent.
        float scaled = ThreatMath.scaled(threat, Kindreds.CONFIG.scalingCurveExponent());
        AbilityApplier.setDynamicModifier(player, DETECTION_RANGE_ID, "threat/detection",
                0.9 * scaled, EntityAttributeModifier.Operation.ADD_VALUE);

        announceRankChange(player, threat);
        return threat;
    }

    /**
     * Tells the player when their threat crosses a named rank boundary - the story beat is the
     * crossing, not the number moving (see {@link ThreatRank}). Silent when {@link #LAST_ANNOUNCED}
     * has no entry for this player: that means this is their first refresh since joining, and
     * "nothing" is not a rank to have risen or fallen from - announcing here would just be a
     * login-spam message. {@code Map#put} both reads the prior entry and stores the new one in a
     * single call, so a first-ever refresh is recorded silently and every later crossing compares
     * against it.
     */
    private static void announceRankChange(ServerPlayerEntity player, float threat) {
        ThreatRank now = ThreatRank.of(threat);
        ThreatRank was = LAST_ANNOUNCED.put(player.getUuid(), now);
        if (was == null || was == now) {
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
