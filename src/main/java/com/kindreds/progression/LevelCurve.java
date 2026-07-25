package com.kindreds.progression;

/**
 * Pure xp&lt;-&gt;level curve shared by every discipline.
 *
 * <p>{@code xpForLevel(level) = round(BASE * level^EXPONENT)}, with level 0 pinned to xp 0. The curve
 * is strictly increasing for {@code level >= 0}, so {@link #levelForXp(long)} is its exact
 * (non-lossy) inverse: it returns the greatest level whose required xp does not exceed the given
 * amount, i.e. {@code levelForXp(xpForLevel(l)) == l} for every {@code l >= 0}.
 *
 * <h2>Why the exponent, and why it moved</h2>
 * The exponent is the shape of the journey, and it is the right dial to slow mastery with because it
 * barely touches the beginning and bites at the end. At 1.5 a discipline was finishable far too
 * quickly: the late nodes - the ones written as the crown of a lane - arrived while the world was
 * still new. At 1.75 the first handful of levels cost about half again what they did, which nobody
 * notices, while a deep discipline costs nearly twice as much, which is the whole point: the last
 * ranks of a lane should be a long road rather than the next afternoon.
 *
 * <p>Raising it re-levels xp already banked: the same xp now buys a slightly lower level, so a player
 * mid-journey may briefly have more points spent than earned. Nothing is taken away - every unlocked
 * node stays unlocked - and {@code UnlockService} simply declines further purchases until the gap
 * closes, which the threat xp-bonus covers quickly.
 */
public final class LevelCurve {
    /** Tuning constant: roughly how much xp the first level costs. */
    private static final double BASE = 100.0;

    /** How sharply the cost of a level grows - the pacing dial. See the class javadoc. */
    private static final double EXPONENT = 1.75;

    private LevelCurve() {
    }

    /** Total xp required to reach {@code level} (0 at level &lt;= 0). */
    public static long xpForLevel(int level) {
        if (level <= 0) {
            return 0L;
        }
        return Math.round(BASE * Math.pow(level, EXPONENT));
    }

    /** The level reached with {@code xp} total experience (0 at xp &lt;= 0). */
    public static int levelForXp(long xp) {
        if (xp <= 0) {
            return 0;
        }
        // Closed-form inverse of xpForLevel as a starting guess, then nudged to the exact
        // boundary so rounding in xpForLevel never desyncs the two directions.
        int guess = (int) Math.floor(Math.pow(xp / BASE, 1.0 / EXPONENT));
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
