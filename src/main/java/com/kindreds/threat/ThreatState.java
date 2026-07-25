package com.kindreds.threat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

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

    /**
     * At most three derived translation keys for the Deeds page's per-family voice (spec §3a) -
     * see {@link #copy()} for where this is (re)derived and why it exists at all. Wire-only:
     * present in {@link #PACKET_CODEC}, deliberately absent from the persistent {@link #CODEC} (it
     * is display data recomputed fresh on every copy, never something worth saving), and irrelevant
     * to death handling (it is recomputed the next time this state is copied for a sync, same as
     * everywhere else). Defaults to empty until the first copy.
     */
    private List<String> familyVoiceKeys;

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
        this.familyVoiceKeys = new ArrayList<>();
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

    /** The Deeds page's per-family voice lines, as translation keys - see the field javadoc and
     * {@link #copy()} for what computes this and why. */
    public List<String> familyVoiceKeys() {
        return familyVoiceKeys;
    }

    public void setFamilyVoiceKeys(List<String> familyVoiceKeys) {
        this.familyVoiceKeys = familyVoiceKeys;
    }

    /**
     * A detached copy: {@link #familyCompetence} is cloned, not shared, so the result is safe to
     * hand to another thread (see {@code SyncKindredDataS2C.snapshot}, which relies on this to avoid
     * netty encoding a map the server thread is still mutating).
     *
     * <p>Also where {@link #familyVoiceKeys} is (re)derived, fresh, from the live
     * {@link #familyCompetence} map via {@link ThreatMath#familyVoiceKeys} - this is the §3a/§7
     * reconciliation in code: spec §7 keeps the raw per-family table server-side, but spec §3a's
     * Deeds-page voice lines need something to actually reach the client. The fix is derivation, not
     * exposure - only these at-most-three bounded translation keys are ever synced (see
     * {@link #PACKET_CODEC}), never {@link #familyCompetence} itself, which stays out of that codec
     * exactly as §7 says. Deriving here (rather than at every call site that wants a synced snapshot)
     * means both of {@code copy()}'s current callers - the network snapshot in
     * {@code SyncKindredDataS2C.snapshot} and the respawn copy in {@code DeathHandler.copyOf} - get a
     * correct, fresh derivation for free: cheap (bounded at five families), and one seam to keep
     * correct instead of two.
     */
    public ThreatState copy() {
        ThreatState c = new ThreatState(priorMark, maxHealthMark, competence,
                new HashMap<>(familyCompetence), playedTicks);
        c.familyVoiceKeys = ThreatMath.familyVoiceKeys(familyCompetence, competence);
        return c;
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

    /** Wire-only shape for {@link #familyVoiceKeys}: a plain list of strings, defaulting to empty. */
    private static final PacketCodec<RegistryByteBuf, List<String>> FAMILY_VOICE_KEYS_PACKET_CODEC =
            PacketCodecs.collection((IntFunction<List<String>>) ArrayList::new, PacketCodecs.STRING);

    /**
     * Omits {@link #familyCompetence} itself: the client never receives the raw per-family table
     * (spec §7 - "the per-family table stays server-side"). {@link #familyVoiceKeys} rides the wire
     * in its place - an at-most-three, already-decided list of translation keys derived from that
     * table at copy time (see {@link #copy()}) - which is how spec §3a's per-family voice lines on
     * the Deeds page are satisfied without breaking §7's rule: derivation, not exposure.
     */
    public static final PacketCodec<RegistryByteBuf, ThreatState> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.FLOAT, ThreatState::priorMark,
            PacketCodecs.FLOAT, ThreatState::maxHealthMark,
            PacketCodecs.FLOAT, ThreatState::competence,
            PacketCodecs.VAR_LONG, ThreatState::playedTicks,
            FAMILY_VOICE_KEYS_PACKET_CODEC, ThreatState::familyVoiceKeys,
            (prior, health, competence, ticks, voiceKeys) -> {
                ThreatState state = new ThreatState(prior, health, competence, new HashMap<>(), ticks);
                state.familyVoiceKeys = new ArrayList<>(voiceKeys);
                return state;
            });
}
