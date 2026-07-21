package com.kindreds.threat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

import java.util.HashMap;
import java.util.Map;

/**
 * A player's stored threat state - the part that must persist, as opposed to the resolved number,
 * which {@code ThreatService} recomputes.
 *
 * <p>Every mark here is a <b>high-water</b> figure rather than a live reading. That is the single
 * rule that keeps threat from being a difficulty slider: a player may always make themselves weaker,
 * by stripping gear or respeccing their tree, but may never make the world forget what they were.
 */
public final class ThreatState {
    private float priorMark;
    private float maxHealthMark;
    private float competence;
    private final Map<String, Float> familyCompetence;
    private long playedTicks;

    public ThreatState() {
        this(0f, 0f, 1.0f, new HashMap<>(), 0L);
    }

    public ThreatState(float priorMark, float maxHealthMark, float competence,
                        Map<String, Float> familyCompetence, long playedTicks) {
        this.priorMark = priorMark;
        this.maxHealthMark = maxHealthMark;
        this.competence = competence;
        this.familyCompetence = familyCompetence;
        this.playedTicks = playedTicks;
    }

    public float priorMark() {
        return priorMark;
    }

    public void setPriorMark(float priorMark) {
        this.priorMark = priorMark;
    }

    /** The high-water of the player's max health - the hardship denominator (see the spec section 2.3). */
    public float maxHealthMark() {
        return maxHealthMark;
    }

    public void setMaxHealthMark(float maxHealthMark) {
        this.maxHealthMark = maxHealthMark;
    }

    public float competence() {
        return competence;
    }

    public void setCompetence(float competence) {
        this.competence = competence;
    }

    /** Competence per mob family. Adjusts how hard a family is, never what the player meets. */
    public Map<String, Float> familyCompetence() {
        return familyCompetence;
    }

    public long playedTicks() {
        return playedTicks;
    }

    public void addPlayedTicks(long ticks) {
        this.playedTicks += ticks;
    }

    /**
     * A detached copy: {@link #familyCompetence} is cloned, not shared, so the result is safe to
     * hand to another thread (see {@code SyncKindredDataS2C.snapshot}, which relies on this to avoid
     * netty encoding a map the server thread is still mutating).
     */
    public ThreatState copy() {
        return new ThreatState(priorMark, maxHealthMark, competence,
                new HashMap<>(familyCompetence), playedTicks);
    }

    public static final Codec<ThreatState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.optionalFieldOf("prior_mark", 0f).forGetter(ThreatState::priorMark),
            Codec.FLOAT.optionalFieldOf("max_health_mark", 0f).forGetter(ThreatState::maxHealthMark),
            Codec.FLOAT.optionalFieldOf("competence", 1.0f).forGetter(ThreatState::competence),
            Codec.unboundedMap(Codec.STRING, Codec.FLOAT).optionalFieldOf("family", Map.of())
                    .forGetter(s -> Map.copyOf(s.familyCompetence())),
            Codec.LONG.optionalFieldOf("played_ticks", 0L).forGetter(ThreatState::playedTicks)
    ).apply(instance, (prior, health, competence, family, ticks) ->
            new ThreatState(prior, health, competence, new HashMap<>(family), ticks)));

    /**
     * Deliberately omits {@link #familyCompetence}: the client displays the overall rank, not the
     * per-family table, and the spec (section 7) says only the resolved figure and its components ride
     * the wire.
     */
    public static final PacketCodec<RegistryByteBuf, ThreatState> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.FLOAT, ThreatState::priorMark,
            PacketCodecs.FLOAT, ThreatState::maxHealthMark,
            PacketCodecs.FLOAT, ThreatState::competence,
            PacketCodecs.VAR_LONG, ThreatState::playedTicks,
            (prior, health, competence, ticks) ->
                    new ThreatState(prior, health, competence, new HashMap<>(), ticks));
}
