package com.kindreds.playerdata;

import com.kindreds.threat.ThreatState;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Server-authoritative, per-player skill state. Mutable so callers can update in place (see
 * {@link com.kindreds.playerdata.KindredAttachment}), but exposes pure helpers for the common
 * read/accumulate operations so gameplay code doesn't poke at the raw collections directly.
 *
 * <p>{@code corruption} is reserved for a later phase but is carried now so the attachment's
 * on-disk/wire shape doesn't need to change again when it lands. A {@code titles} set was carried
 * on the same reasoning and never earned its place: nothing ever wrote to it, so the two screens
 * that displayed it read "None earned yet" for good. Titles are derived from the deeds now (see
 * {@link com.kindreds.progression.RenownService#titleKeys}), which is the only way they can never
 * disagree with them.
 */
public final class KindredData {
    private final Object2LongMap<Identifier> disciplineXp;
    private final Set<String> unlockedNodes;
    private Identifier activeVisionLens;
    private int corruption;

    /**
     * How deep each multi-rank node has been taken, for the nodes taken past rank 1.
     *
     * <p>Deliberately kept <b>beside</b> {@link #unlockedNodes} rather than replacing it. Membership
     * of that set still means "owned", so every existing call site, save file and sync packet keeps
     * its exact meaning, and a node simply absent from this map sits at rank 1. That is what lets
     * ranks arrive in a live world without disturbing a single player's progress - see {@link #rankOf}.
     */
    private final Map<String, Integer> nodeRanks = new HashMap<>();

    /**
     * Great Deeds of renown this player has performed (advancement ids under {@code kindreds:renown/},
     * stored by path). Each one permanently widens the tree-wide point cap - see
     * {@link com.kindreds.progression.RenownService}.
     *
     * <p>Persisted rather than re-derived from the advancement tracker on demand, deliberately:
     * renown is a deed <i>done</i>, and an operator running {@code /advancement revoke} must not be
     * able to retroactively narrow a cap the player has already spent points under (which would leave
     * them committed above their own ceiling, a state no other code expects).
     */
    private final Set<String> renown;
    private final Object2LongMap<String> cooldowns;

    /**
     * Biomes (by id) this player has already been awarded Survival "new biome discovered" xp for
     * (see {@link com.kindreds.progression.ActivityHooks}). <b>Must</b> be part of the persistent
     * {@link #CODEC} (unlike {@link #race}) - Task 7 originally tracked this in an in-memory
     * {@code Set} on {@code ActivityHooks} keyed by player, which reset on every relog/server
     * restart and let a relog macro farm the same biome's xp repeatedly. Deliberately <b>not</b>
     * part of {@link #PACKET_CODEC}: the client has no use for it (it's a pure server-side
     * anti-farm guard), so it doesn't need to ride the wire on every sync.
     */
    private final Set<Identifier> discoveredBiomes;

    /**
     * The player's base-mod race id, mirrored client-side so the tree screen (Task 11) can resolve
     * {@code race -> SkillTree -> Theme} without needing its own network round trip. Deliberately
     * <b>not</b> part of the persistent {@link #CODEC} (NBT storage): it's derived, transient state
     * ({@link com.kindreds.playerdata.RaceAccess} re-resolves it from the base mod every time), not
     * owned data - persisting a stale value would serve no purpose and risks the attachment
     * disagreeing with the live base-mod race after e.g. a race change. It IS part of
     * {@link #PACKET_CODEC} (see that field) so every {@code SyncKindredDataS2C} refreshes it.
     */
    private Identifier race;

    /**
     * The race whose innate birth traits are currently applied to this player, or {@code null} if
     * none yet. Like {@link #race}, this is transient server-side state (NOT persisted): birth-trait
     * attribute modifiers themselves ARE persisted on the player, so {@code BirthTraitService}
     * intentionally reconciles once per session (this resets to {@code null} on relog) - clearing
     * any leftover modifiers for the current race before re-applying, which avoids duplicate-id
     * stacking without needing this field on disk.
     */
    private Identifier appliedBirthRace;

    /**
     * This player's threat state (see {@link ThreatState}). Not a constructor
     * parameter - every existing call site would otherwise have to pass one - so it's always
     * initialised fresh here and mutated in place afterward, the same way {@link #race} is set
     * post-construction rather than threaded through every constructor.
     */
    private final ThreatState threat;

    public KindredData() {
        this(new Object2LongOpenHashMap<>(), new HashSet<>(), null, 0, new Object2LongOpenHashMap<>(),
                new HashSet<>(), new HashSet<>());
    }

    /** Six-arg convenience constructor: {@link #discoveredBiomes} is persistence-only (see its
     * field javadoc) so {@link #PACKET_CODEC} never carries it - this is the shape that codec's
     * factory function calls, defaulting to an empty (mutable) set. */
    public KindredData(
            Object2LongMap<Identifier> disciplineXp,
            Set<String> unlockedNodes,
            Identifier activeVisionLens,
            int corruption,
            Object2LongMap<String> cooldowns) {
        this(disciplineXp, unlockedNodes, activeVisionLens, corruption, cooldowns, new HashSet<>(),
                new HashSet<>());
    }

    public KindredData(
            Object2LongMap<Identifier> disciplineXp,
            Set<String> unlockedNodes,
            Identifier activeVisionLens,
            int corruption,
            Object2LongMap<String> cooldowns,
            Set<Identifier> discoveredBiomes) {
        this(disciplineXp, unlockedNodes, activeVisionLens, corruption, cooldowns, discoveredBiomes,
                new HashSet<>());
    }

    public KindredData(
            Object2LongMap<Identifier> disciplineXp,
            Set<String> unlockedNodes,
            Identifier activeVisionLens,
            int corruption,
            Object2LongMap<String> cooldowns,
            Set<Identifier> discoveredBiomes,
            Set<String> renown) {
        this.disciplineXp = disciplineXp;
        this.unlockedNodes = unlockedNodes;
        this.activeVisionLens = activeVisionLens;
        this.corruption = corruption;
        this.cooldowns = cooldowns;
        this.discoveredBiomes = discoveredBiomes;
        this.renown = renown;
        this.threat = new ThreatState();
    }

    public Object2LongMap<Identifier> disciplineXp() {
        return disciplineXp;
    }

    public Set<String> unlockedNodes() {
        return unlockedNodes;
    }

    /** Live, mutable rank table - see the field javadoc. Only holds nodes taken past rank 1. */
    public Map<String, Integer> nodeRanks() {
        return nodeRanks;
    }

    /**
     * How deep {@code nodeId} has been taken: {@code 0} if it is not owned at all, otherwise at least
     * {@code 1}. An owned node with no entry in {@link #nodeRanks} is rank 1, which is what makes every
     * node written before ranks existed - and every save file holding one - read correctly.
     */
    public int rankOf(String nodeId) {
        if (!unlockedNodes.contains(nodeId)) {
            return 0;
        }
        return Math.max(1, nodeRanks.getOrDefault(nodeId, 1));
    }

    /** Records {@code nodeId} as taken to {@code rank}. Rank 1 is left out of the map so the common
     * case adds nothing to the save file. */
    public void setRank(String nodeId, int rank) {
        if (rank <= 1) {
            nodeRanks.remove(nodeId);
        } else {
            nodeRanks.put(nodeId, rank);
        }
    }

    public Identifier activeVisionLens() {
        return activeVisionLens;
    }

    public void setActiveVisionLens(Identifier activeVisionLens) {
        this.activeVisionLens = activeVisionLens;
    }

    public int corruption() {
        return corruption;
    }

    public void setCorruption(int corruption) {
        this.corruption = corruption;
    }

    /** Great Deeds performed - see the {@link #renown} field. */
    public Set<String> renown() {
        return renown;
    }

    public Object2LongMap<String> cooldowns() {
        return cooldowns;
    }

    /** Biomes already awarded Survival discovery xp for - see {@link #discoveredBiomes} field javadoc. */
    public Set<Identifier> discoveredBiomes() {
        return discoveredBiomes;
    }

    /** The player's base-mod race id, or {@code null} if unknown (base mod absent, or no race
     * chosen yet). See {@link #race} for why this isn't persisted. */
    public Identifier race() {
        return race;
    }

    public void setRace(Identifier race) {
        this.race = race;
    }

    /** The race whose birth traits are currently applied (see {@link #appliedBirthRace} field). */
    public Identifier appliedBirthRace() {
        return appliedBirthRace;
    }

    public void setAppliedBirthRace(Identifier appliedBirthRace) {
        this.appliedBirthRace = appliedBirthRace;
    }

    /** This player's threat state. Always present; never null. */
    public ThreatState threat() {
        return threat;
    }

    /**
     * Copies {@code threat}'s fields into this instance's own (final) {@link #threat} field, rather
     * than replacing the reference - used by the {@link #CODEC}/{@link #PACKET_CODEC} decode
     * factories and by callers (like {@code DeathHandler.copyOf} and {@code
     * SyncKindredDataS2C.snapshot}, both in other packages - hence {@code public}, same as {@link
     * #setRace}) restoring a snapshot onto a freshly-constructed {@code KindredData}.
     *
     * <p>{@link ThreatState} deliberately exposes no {@code setPlayedTicks} (only
     * {@code addPlayedTicks}), so the played-ticks total is transplanted via the delta between the
     * two - correct regardless of this instance's current total, and exact for the fresh (zeroed)
     * instances every call site actually passes.
     */
    public void setThreat(ThreatState threat) {
        this.threat.setPriorMark(threat.priorMark());
        this.threat.setMaxHealthMark(threat.maxHealthMark());
        this.threat.setCompetence(threat.competence());
        this.threat.familyCompetence().clear();
        this.threat.familyCompetence().putAll(threat.familyCompetence());
        this.threat.addPlayedTicks(threat.playedTicks() - this.threat.playedTicks());
    }

    /** Accumulates {@code amount} xp into {@code discipline} (creating the entry if absent). */
    public void addXp(Identifier discipline, long amount) {
        disciplineXp.put(discipline, disciplineXp.getLong(discipline) + amount);
    }

    /** Current xp in {@code discipline}, or {@code 0} if none has been earned yet. */
    public long xpIn(Identifier discipline) {
        return disciplineXp.getLong(discipline);
    }

    /** Whether the skill node {@code id} has been unlocked. */
    public boolean hasNode(String id) {
        return unlockedNodes.contains(id);
    }

    // --- Persistent codec (data attachment NBT storage) ---------------------------------------

    /** Explicit type witness: without it, {@code xmap} infers the concrete
     * {@code Object2LongOpenHashMap} rather than the {@code Object2LongMap} interface type that
     * {@code forGetter} needs to match the field's declared getter return type. */
    private static <K> Function<Map<K, Long>, Object2LongMap<K>> toObject2LongMap() {
        return Object2LongOpenHashMap::new;
    }

    /** Same reasoning as {@link #toObject2LongMap()}, but for the {@code Set<String>} fields. */
    private static Function<List<String>, Set<String>> toStringSet() {
        return HashSet::new;
    }

    /** Shared JSON codec for the {@code Set<String>} fields ({@code unlockedNodes}, {@code renown}).
     * Factored out so both fields go through the exact same codec instance, rather than two
     * independently-written (and independently-swappable) call sites. */
    private static final Codec<Set<String>> STRING_SET_CODEC =
            Codec.STRING.listOf().xmap(toStringSet(), ArrayList::new);

    /** Shared wire codec for the {@code Set<String>} fields ({@code unlockedNodes}, {@code renown}).
     * Same rationale as {@link #STRING_SET_CODEC}. */
    private static final PacketCodec<RegistryByteBuf, Set<String>> STRING_SET_PACKET_CODEC =
            PacketCodecs.collection((IntFunction<Set<String>>) HashSet::new, PacketCodecs.STRING);

    public static final Codec<KindredData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Identifier.CODEC, Codec.LONG)
                    .xmap(KindredData.<Identifier>toObject2LongMap(), m -> m)
                    .fieldOf("discipline_xp").forGetter(KindredData::disciplineXp),
            STRING_SET_CODEC.fieldOf("unlocked_nodes").forGetter(KindredData::unlockedNodes),
            Identifier.CODEC.optionalFieldOf("active_vision_lens")
                    .forGetter(d -> Optional.ofNullable(d.activeVisionLens)),
            Codec.INT.fieldOf("corruption").forGetter(KindredData::corruption),
            Codec.unboundedMap(Codec.STRING, Codec.LONG)
                    .xmap(KindredData.<String>toObject2LongMap(), m -> m)
                    .fieldOf("cooldowns").forGetter(KindredData::cooldowns),
            // optionalFieldOf so pre-existing save data (written before this field existed) still
            // loads - defaults to an empty *list* (immutable, but never mutated directly) rather
            // than a shared mutable Set default: the apply() factory below always wraps it in a
            // fresh HashSet per decode, so distinct players' KindredData never end up sharing one
            // mutable Set instance (which a shared mutable-Set default would risk).
            Identifier.CODEC.listOf().optionalFieldOf("discovered_biomes", List.of())
                    .forGetter(d -> List.copyOf(d.discoveredBiomes())),
            // optionalFieldOf for the same save-compatibility reason as discovered_biomes above:
            // worlds written before Great Deeds existed simply load with no renown.
            Codec.STRING.listOf().optionalFieldOf("renown", List.of())
                    .forGetter(d -> List.copyOf(d.renown())),
            // optionalFieldOf so worlds written before threat scaling existed load cleanly, with a
            // fresh default ThreatState rather than a failed decode.
            ThreatState.CODEC.optionalFieldOf("threat",
                    new ThreatState()).forGetter(KindredData::threat),
            // optionalFieldOf, same save-compatibility reason as the fields above: a world written
            // before ranks existed loads with an empty table, which reads as "every owned node is at
            // rank 1" - exactly what it was.
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("node_ranks", Map.of())
                    .forGetter(d -> Map.copyOf(d.nodeRanks()))
    ).apply(instance, (xp, nodes, lens, corruption, cooldowns, discoveredBiomes, renown, threat, ranks) -> {
        KindredData data = new KindredData(xp, nodes, lens.orElse(null), corruption, cooldowns,
                new HashSet<>(discoveredBiomes), new HashSet<>(renown));
        data.setThreat(threat);
        data.nodeRanks().putAll(ranks);
        return data;
    }));

    // --- Network codec (S2C sync) --------------------------------------------------------------

    /** Same optional-{@link Identifier} shape/rationale as {@code activeVisionLens}'s packet codec
     * entry below; factored out since both it and {@link #race} need the identical
     * {@code Optional<Identifier> <-> nullable Identifier} adaptation. */
    private static final PacketCodec<? super RegistryByteBuf, Identifier> NULLABLE_IDENTIFIER_PACKET_CODEC =
            PacketCodecs.optional(Identifier.PACKET_CODEC).xmap(opt -> opt.orElse(null), Optional::ofNullable);

    /**
     * Eight fields wide: {@link #race} (see its javadoc) rides along on the wire only - it's
     * deliberately absent from the persistent {@link #CODEC} above. {@link #threat} rides on both
     * codecs (see {@link ThreatState#PACKET_CODEC} for what it omits on this
     * one). The trailing factory builds the object via the existing six-arg constructor and then sets
     * {@code race} and {@code threat} on it, rather than growing the constructor with parameters that
     * every other (persistence-only) call site would have to pass placeholder values for.
     */
    public static final PacketCodec<RegistryByteBuf, KindredData> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.map((IntFunction<Object2LongMap<Identifier>>) Object2LongOpenHashMap::new,
                    Identifier.PACKET_CODEC, PacketCodecs.VAR_LONG),
            KindredData::disciplineXp,
            STRING_SET_PACKET_CODEC,
            KindredData::unlockedNodes,
            NULLABLE_IDENTIFIER_PACKET_CODEC,
            KindredData::activeVisionLens,
            PacketCodecs.VAR_INT,
            KindredData::corruption,
            PacketCodecs.map((IntFunction<Object2LongMap<String>>) Object2LongOpenHashMap::new,
                    PacketCodecs.STRING, PacketCodecs.VAR_LONG),
            KindredData::cooldowns,
            NULLABLE_IDENTIFIER_PACKET_CODEC,
            KindredData::race,
            STRING_SET_PACKET_CODEC,
            KindredData::renown,
            ThreatState.PACKET_CODEC,
            KindredData::threat,
            // The client needs ranks to draw "Rank 2/3" and to price the next rank, so they ride the
            // sync packet alongside the owned set rather than being inferred client-side.
            PacketCodecs.map((IntFunction<Map<String, Integer>>) HashMap::new,
                    PacketCodecs.STRING, PacketCodecs.VAR_INT),
            KindredData::nodeRanks,
            (xp, unlockedNodes, lens, corruption, cooldowns, race, renown, threat, ranks) -> {
                KindredData data = new KindredData(xp, unlockedNodes, lens, corruption, cooldowns,
                        new HashSet<>(), renown);
                data.setRace(race);
                data.setThreat(threat);
                data.nodeRanks().putAll(ranks);
                return data;
            });
}
