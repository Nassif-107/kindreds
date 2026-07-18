package com.kindreds.playerdata;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
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
 * <p>{@code titles} and {@code corruption} are reserved for later phases (unused by anything in
 * this task) but are included now so the attachment's on-disk/wire shape doesn't need to change
 * again when those phases land.
 */
public final class KindredData {
    private final Object2LongMap<Identifier> disciplineXp;
    private final Set<String> unlockedNodes;
    private Identifier activeVisionLens;
    private final Set<String> titles;
    private int corruption;
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

    public KindredData() {
        this(new Object2LongOpenHashMap<>(), new HashSet<>(), null, new HashSet<>(), 0, new Object2LongOpenHashMap<>(),
                new HashSet<>());
    }

    /** Six-arg convenience constructor: {@link #discoveredBiomes} is persistence-only (see its
     * field javadoc) so {@link #PACKET_CODEC} never carries it - this is the shape that codec's
     * factory function calls, defaulting to an empty (mutable) set. */
    public KindredData(
            Object2LongMap<Identifier> disciplineXp,
            Set<String> unlockedNodes,
            Identifier activeVisionLens,
            Set<String> titles,
            int corruption,
            Object2LongMap<String> cooldowns) {
        this(disciplineXp, unlockedNodes, activeVisionLens, titles, corruption, cooldowns, new HashSet<>());
    }

    public KindredData(
            Object2LongMap<Identifier> disciplineXp,
            Set<String> unlockedNodes,
            Identifier activeVisionLens,
            Set<String> titles,
            int corruption,
            Object2LongMap<String> cooldowns,
            Set<Identifier> discoveredBiomes) {
        this.disciplineXp = disciplineXp;
        this.unlockedNodes = unlockedNodes;
        this.activeVisionLens = activeVisionLens;
        this.titles = titles;
        this.corruption = corruption;
        this.cooldowns = cooldowns;
        this.discoveredBiomes = discoveredBiomes;
    }

    public Object2LongMap<Identifier> disciplineXp() {
        return disciplineXp;
    }

    public Set<String> unlockedNodes() {
        return unlockedNodes;
    }

    public Identifier activeVisionLens() {
        return activeVisionLens;
    }

    public void setActiveVisionLens(Identifier activeVisionLens) {
        this.activeVisionLens = activeVisionLens;
    }

    public Set<String> titles() {
        return titles;
    }

    public int corruption() {
        return corruption;
    }

    public void setCorruption(int corruption) {
        this.corruption = corruption;
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

    /** Shared JSON codec for the {@code Set<String>} fields ({@code unlockedNodes}, {@code titles}).
     * Factored out so both fields go through the exact same codec instance, rather than two
     * independently-written (and independently-swappable) call sites. */
    private static final Codec<Set<String>> STRING_SET_CODEC =
            Codec.STRING.listOf().xmap(toStringSet(), ArrayList::new);

    /** Shared wire codec for the {@code Set<String>} fields ({@code unlockedNodes}, {@code titles}).
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
            STRING_SET_CODEC.fieldOf("titles").forGetter(KindredData::titles),
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
                    .forGetter(d -> List.copyOf(d.discoveredBiomes()))
    ).apply(instance, (xp, nodes, lens, titles, corruption, cooldowns, discoveredBiomes) ->
            new KindredData(xp, nodes, lens.orElse(null), titles, corruption, cooldowns, new HashSet<>(discoveredBiomes))));

    // --- Network codec (S2C sync) --------------------------------------------------------------

    /** Same optional-{@link Identifier} shape/rationale as {@code activeVisionLens}'s packet codec
     * entry below; factored out since both it and {@link #race} need the identical
     * {@code Optional<Identifier> <-> nullable Identifier} adaptation. */
    private static final PacketCodec<? super RegistryByteBuf, Identifier> NULLABLE_IDENTIFIER_PACKET_CODEC =
            PacketCodecs.optional(Identifier.PACKET_CODEC).xmap(opt -> opt.orElse(null), Optional::ofNullable);

    /**
     * Seven fields wide: {@link #race} (see its javadoc) rides along on the wire only - it's
     * deliberately absent from the persistent {@link #CODEC} above. The trailing factory builds the
     * object via the existing six-arg constructor and then sets {@code race} on it, rather than
     * adding a seventh constructor parameter that every other (persistence-only) call site would
     * have to pass {@code null} for.
     */
    public static final PacketCodec<RegistryByteBuf, KindredData> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.map((IntFunction<Object2LongMap<Identifier>>) Object2LongOpenHashMap::new,
                    Identifier.PACKET_CODEC, PacketCodecs.VAR_LONG),
            KindredData::disciplineXp,
            STRING_SET_PACKET_CODEC,
            KindredData::unlockedNodes,
            NULLABLE_IDENTIFIER_PACKET_CODEC,
            KindredData::activeVisionLens,
            STRING_SET_PACKET_CODEC,
            KindredData::titles,
            PacketCodecs.VAR_INT,
            KindredData::corruption,
            PacketCodecs.map((IntFunction<Object2LongMap<String>>) Object2LongOpenHashMap::new,
                    PacketCodecs.STRING, PacketCodecs.VAR_LONG),
            KindredData::cooldowns,
            NULLABLE_IDENTIFIER_PACKET_CODEC,
            KindredData::race,
            (xp, unlockedNodes, lens, titles, corruption, cooldowns, race) -> {
                KindredData data = new KindredData(xp, unlockedNodes, lens, titles, corruption, cooldowns);
                data.setRace(race);
                return data;
            });
}
