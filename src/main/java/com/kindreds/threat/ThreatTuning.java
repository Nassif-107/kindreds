package com.kindreds.threat;

/**
 * The tunables behind threat, as a pure record so {@link ThreatMath} stays free of Minecraft while
 * still being fully configurable. {@code ThreatService} builds one from the server config.
 *
 * <p>{@code bandMin}/{@code bandMax} are the exception to "everything is a setting": they are the
 * anti-farming floor, not tuning. {@link ThreatMath#bandFor} clamps them so a server may narrow the
 * band but never widen it - a config field that could widen it would hand back the death-farming
 * exploit the band exists to prevent.
 */
public record ThreatTuning(float hardshipTarget, float riseRate, float fallRate, float deathPenalty,
                           float bandMin, float bandMax) {

    public static final ThreatTuning DEFAULTS =
            new ThreatTuning(0.25f, 0.10f, 0.04f, 0.05f, 0.75f, 1.25f);
}
