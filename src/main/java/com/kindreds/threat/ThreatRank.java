package com.kindreds.threat;

/**
 * The land's regard for a player, as a name rather than a number.
 *
 * <p>A raw "Threat: 47/100" reads as a stat screen, invites min-maxing the figure itself, and answers
 * no question a player actually has. A rank answers "how much trouble am I in" at the resolution the
 * question deserves, and crossing between ranks is an event worth announcing.
 */
public enum ThreatRank {
    UNNOTICED(0),
    WATCHED(20),
    MARKED(40),
    HUNTED(60),
    SHADOW(80);

    /** Lowest threat, inclusive, that earns this rank. */
    public final float floor;

    ThreatRank(float floor) {
        this.floor = floor;
    }

    public static ThreatRank of(float threat) {
        ThreatRank best = UNNOTICED;
        for (ThreatRank rank : values()) {
            if (threat >= rank.floor) {
                best = rank;
            }
        }
        return best;
    }

    public String translationKey() {
        return "kindreds.threat.rank." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
