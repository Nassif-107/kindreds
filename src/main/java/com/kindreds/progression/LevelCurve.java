package com.kindreds.progression;

/**
 * Pure xp&lt;-&gt;level curve shared by every discipline.
 *
 * <p>{@code xpForLevel(level) = round(BASE * level^1.5)}, with level 0 pinned to xp 0. The curve is
 * strictly increasing for {@code level >= 0}, so {@link #levelForXp(long)} is its exact
 * (non-lossy) inverse: it returns the greatest level whose required xp does not exceed the given
 * amount, i.e. {@code levelForXp(xpForLevel(l)) == l} for every {@code l >= 0}.
 */
public final class LevelCurve {
    /** Tuning constant: roughly how much xp the first level costs. */
    private static final double BASE = 100.0;

    private LevelCurve() {
    }

    /** Total xp required to reach {@code level} (0 at level &lt;= 0). */
    public static long xpForLevel(int level) {
        if (level <= 0) {
            return 0L;
        }
        return Math.round(BASE * Math.pow(level, 1.5));
    }

    /** The level reached with {@code xp} total experience (0 at xp &lt;= 0). */
    public static int levelForXp(long xp) {
        if (xp <= 0) {
            return 0;
        }
        // Closed-form inverse of xpForLevel as a starting guess, then nudged to the exact
        // boundary so rounding in xpForLevel never desyncs the two directions.
        int guess = (int) Math.floor(Math.pow(xp / BASE, 1.0 / 1.5));
        if (guess < 0) {
            guess = 0;
        }
        while (guess > 0 && xpForLevel(guess) > xp) {
            guess--;
        }
        while (xpForLevel(guess + 1) <= xp) {
            guess++;
        }
        return guess;
    }
}
