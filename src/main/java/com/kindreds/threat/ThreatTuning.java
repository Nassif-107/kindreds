package com.kindreds.threat;

/**
 * The tunables behind threat, as a pure record so {@link ThreatMath} stays free of Minecraft while
 * still being fully configurable. {@code ThreatService} builds one from the server config.
 *
 * <p>{@code bandMin}/{@code bandMax} are the exception to "everything is a setting": they are the
 * anti-farming floor, not tuning. {@link ThreatMath#bandFor} clamps them so a server may narrow the
 * band but never widen it - a config field that could widen it would hand back the death-farming
 * exploit the band exists to prevent.
 *
 * <p>{@code hardshipTarget} must satisfy {@code 0 < hardshipTarget < 1}: {@link ThreatMath#foldHardship}
 * normalizes the coasting side of the error by dividing by it directly, and the struggling side by
 * dividing by {@code 1 - hardshipTarget}, so a value of exactly 0 or 1 turns one of those two divisions
 * into a division by zero.
 */
public record ThreatTuning(float hardshipTarget, float riseRate, float fallRate, float deathPenalty,
                           float bandMin, float bandMax) {

    public static final ThreatTuning DEFAULTS =
            new ThreatTuning(0.25f, 0.10f, 0.04f, 0.05f, 0.75f, 1.25f);

    /**
     * {@link #DEFAULTS} with its band narrowed by {@code adaptiveStrength} (see
     * {@link ThreatMath#adaptiveBand}) - the internal calibration constants ({@code hardshipTarget},
     * {@code riseRate}, {@code fallRate}, {@code deathPenalty}) are never exposed as a server
     * setting, only the band moves. This is the one place {@link ThreatEvidence} should build a
     * {@link ThreatTuning} from config, so the never-widen guarantee is applied uniformly rather than
     * re-derived at each fold call site.
     */
    public static ThreatTuning withAdaptiveStrength(int adaptiveStrength) {
        float[] band = ThreatMath.adaptiveBand(adaptiveStrength);
        return new ThreatTuning(DEFAULTS.hardshipTarget(), DEFAULTS.riseRate(), DEFAULTS.fallRate(),
                DEFAULTS.deathPenalty(), band[0], band[1]);
    }
}
