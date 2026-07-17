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

    public KindredData() {
        this(new Object2LongOpenHashMap<>(), new HashSet<>(), null, new HashSet<>(), 0, new Object2LongOpenHashMap<>());
    }

    public KindredData(
            Object2LongMap<Identifier> disciplineXp,
            Set<String> unlockedNodes,
            Identifier activeVisionLens,
            Set<String> titles,
            int corruption,
            Object2LongMap<String> cooldowns) {
        this.disciplineXp = disciplineXp;
        this.unlockedNodes = unlockedNodes;
        this.activeVisionLens = activeVisionLens;
        this.titles = titles;
        this.corruption = corruption;
        this.cooldowns = cooldowns;
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
                    .fieldOf("cooldowns").forGetter(KindredData::cooldowns)
    ).apply(instance, (xp, nodes, lens, titles, corruption, cooldowns) ->
            new KindredData(xp, nodes, lens.orElse(null), titles, corruption, cooldowns)));

    // --- Network codec (S2C sync) --------------------------------------------------------------

    public static final PacketCodec<RegistryByteBuf, KindredData> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.map((IntFunction<Object2LongMap<Identifier>>) Object2LongOpenHashMap::new,
                    Identifier.PACKET_CODEC, PacketCodecs.VAR_LONG),
            KindredData::disciplineXp,
            STRING_SET_PACKET_CODEC,
            KindredData::unlockedNodes,
            PacketCodecs.optional(Identifier.PACKET_CODEC)
                    .xmap(opt -> opt.orElse(null), Optional::ofNullable),
            KindredData::activeVisionLens,
            STRING_SET_PACKET_CODEC,
            KindredData::titles,
            PacketCodecs.VAR_INT,
            KindredData::corruption,
            PacketCodecs.map((IntFunction<Object2LongMap<String>>) Object2LongOpenHashMap::new,
                    PacketCodecs.STRING, PacketCodecs.VAR_LONG),
            KindredData::cooldowns,
            KindredData::new);
}
