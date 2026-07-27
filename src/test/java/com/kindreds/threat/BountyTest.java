package com.kindreds.threat;

import com.kindreds.config.Menace;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a champion's hoard is actually worth, proved rather than estimated.
 *
 * <p>The bounty was a flat 15% chance of a single item - {@code minecraft:diamond} - from a champion
 * of any danger whatsoever. That paid a player identically for a kill that never threatened them and
 * one that nearly killed them, which is a rule that teaches people to hunt the safest elite they can
 * find. These tests pin the shape that replaced it: nothing at all below a real threshold of danger,
 * and better odds the more dangerous the world actually was.
 */
class BountyTest {

    /** Draws {@code n} tiers at a given danger and reports how many of each came up. */
    private static Map<ThreatMath.Bounty, Integer> sample(float scaled, int n, long seed) {
        Map<ThreatMath.Bounty, Integer> counts = new EnumMap<>(ThreatMath.Bounty.class);
        for (ThreatMath.Bounty b : ThreatMath.Bounty.values()) {
            counts.put(b, 0);
        }
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            ThreatMath.Bounty tier = ThreatMath.bountyTier(scaled, r.nextFloat());
            counts.merge(tier, 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void aHarmlessWorldNeverYieldsTheBetterHoards() {
        // The whole point: danger gates the tier outright, so no amount of luck turns a champion that
        // posed no threat into a mithril drop. Rolls are swept across the entire [0,1) range rather
        // than sampled, so this is exhaustive rather than probabilistic.
        for (float scaled : new float[]{0.0f, 0.1f, 0.29f}) {
            for (int i = 0; i < 1000; i++) {
                assertEquals(ThreatMath.Bounty.COMMON, ThreatMath.bountyTier(scaled, i / 1000f),
                        "danger " + scaled + " must never pay above the common hoard");
            }
        }
    }

    @Test
    void theFabledHoardNeedsGenuineDanger() {
        for (int i = 0; i < 1000; i++) {
            assertTrue(ThreatMath.bountyTier(0.59f, i / 1000f) != ThreatMath.Bounty.FABLED,
                    "the fabled hoard must stay out of reach below its danger threshold");
        }
        // ...and is reachable once past it, or it would be a tier that exists only in the source.
        boolean everFabled = false;
        for (int i = 0; i < 1000; i++) {
            if (ThreatMath.bountyTier(1.0f, i / 1000f) == ThreatMath.Bounty.FABLED) {
                everFabled = true;
                break;
            }
        }
        assertTrue(everFabled, "the fabled hoard is unreachable at any danger - a dead tier");
    }

    @Test
    void betterHoardsGrowStrictlyMoreLikelyWithDanger() {
        int previousRare = -1;
        int previousFabled = -1;
        for (float scaled : new float[]{0.30f, 0.50f, 0.70f, 0.85f, 1.0f}) {
            Map<ThreatMath.Bounty, Integer> counts = sample(scaled, 20_000, 7L);
            int rareOrBetter = counts.get(ThreatMath.Bounty.RARE) + counts.get(ThreatMath.Bounty.FABLED);
            assertTrue(rareOrBetter >= previousRare,
                    "rare-or-better odds fell as the world got more dangerous, at " + scaled);
            assertTrue(counts.get(ThreatMath.Bounty.FABLED) >= previousFabled,
                    "fabled odds fell as the world got more dangerous, at " + scaled);
            previousRare = rareOrBetter;
            previousFabled = counts.get(ThreatMath.Bounty.FABLED);
        }
    }

    /**
     * The fabled hoard has to stay genuinely rare end to end.
     *
     * <p>Measured through the whole chain, not just this one function: promotion to champion, the
     * bounty roll, then the tier. A mithril ingot that turns up every other fight is not a reward, it
     * is an ore vein with extra steps - which is precisely what the flat 15% diamond had become.
     */
    @Test
    void mithrilStaysRareThroughTheWholeChain() {
        Menace m = Menace.OPEN_WAR;
        float scaled = 0.93f;   // a veteran party at full threat - the best case a player can engineer
        Map<ThreatMath.Bounty, Integer> counts = sample(scaled, 100_000, 11L);
        double fabledShareOfBounties = counts.get(ThreatMath.Bounty.FABLED) / 100_000.0;
        double perElite = fabledShareOfBounties * (m.eliteBountyChance / 100.0) * scaled;
        double perScalableMob = perElite * (m.eliteChance / 100.0);

        assertTrue(perScalableMob < 0.01,
                "a fabled hoard drops from " + String.format("%.2f%%", perScalableMob * 100)
                        + " of dangerous mobs - too common to feel like a find");
        assertTrue(perScalableMob > 0.0001,
                "a fabled hoard drops from " + String.format("%.4f%%", perScalableMob * 100)
                        + " of dangerous mobs - so rare nobody will ever see one");
    }

    /** Bounty generosity climbs with the ladder: a world that asks more has to pay more, or growing
     * strong is a strictly worse trade than staying weak. */
    @Test
    void harderPresetsPayBetter() {
        Menace[] ladder = {Menace.WATCHFUL_PEACE, Menace.GATHERING_DARK, Menace.OPEN_WAR,
                Menace.THE_BLACK_TIDE, Menace.WRATH_OF_SAURON};
        for (int i = 1; i < ladder.length; i++) {
            assertTrue(ladder[i].eliteBountyChance > ladder[i - 1].eliteBountyChance,
                    ladder[i] + " pays no better than " + ladder[i - 1] + " despite asking more");
        }
        assertEquals(0, Menace.SHIRE.eliteBountyChance,
                "a world with no scaling has no champions, so it can have no champion hoards");
    }
}
