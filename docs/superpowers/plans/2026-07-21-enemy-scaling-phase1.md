# Enemy Scaling — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every player a *threat* number that the world reads, and make the per-player half of
the world answer it — mobs hit harder, notice you sooner, and pay more, the mightier you become.

**Architecture:** A pure-maths class (`ThreatMath`) holds every formula and is unit-tested with no
Minecraft on the classpath. `ThreatService` caches a resolved threat per player, exactly as
`PerkService` already caches perks. `ThreatEvidence` listens to combat and folds it into a competence
figure. The existing per-hit damage hook in `PerkEventHandlers` asks `ThreatService` for a number
instead of counting nodes. Nothing in phase 1 touches mobs at spawn — that is phase 2.

**Tech Stack:** Fabric 1.21.8, yarn mappings, JUnit 5, Fabric client gametests.

**Spec:** `docs/superpowers/specs/2026-07-21-enemy-scaling-design.md`. Read §2 (threat), §3 (effects),
§3a (presentation), §6 (settings), §7 (persistence), §11 (implementation hazards), §12 (testing).

## Global Constraints

- **Everything the player can read must be localized**, in **both** `en_us.json` and `ru_ru.json`.
  No user-facing English string may be built in Java. Use `Text.translatable` / `I18n.translate`.
- **A literal `%` in a lang value must be written `%%`**, or the game renders `Format error`. This has
  bitten this project twice. `%s` and `%d` placeholders are untouched.
- **Never rebuild while a gametest client is running** — it causes `NoClassDefFoundError`.
- **New `KindredData` fields use `optionalFieldOf`** in the persistence codec, so worlds written
  before this feature load cleanly.
- **`SyncKindredDataS2C` must send a snapshot**, never the live collections: netty encodes on its own
  thread and will `ConcurrentModificationException` on a set the server thread is still editing.
- Java 21. Existing code style: no wildcard imports in new files, javadoc on every public type
  explaining *why*, not *what*.
- Run `./gradlew test` after every task. 107 tests pass today; the count only goes up.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/kindreds/threat/ThreatMath.java` | **Create.** Every formula, pure, no Minecraft imports. |
| `src/main/java/com/kindreds/threat/ThreatRank.java` | **Create.** The five named ranks and their thresholds. |
| `src/main/java/com/kindreds/threat/ThreatState.java` | **Create.** A player's stored threat state: marks, competence, per-family table. |
| `src/main/java/com/kindreds/threat/ThreatService.java` | **Create.** Resolves and caches threat per player. The only thing that answers "how strong is this player". |
| `src/main/java/com/kindreds/threat/ThreatEvidence.java` | **Create.** Combat listeners folding evidence into competence. |
| `src/main/java/com/kindreds/threat/MobDanger.java` | **Create.** Base danger of a mob, and which family it belongs to. |
| `src/main/java/com/kindreds/playerdata/KindredData.java` | **Modify.** Carry `ThreatState`. |
| `src/main/java/com/kindreds/playerdata/DeathHandler.java` | **Modify.** Copy it across death. |
| `src/main/java/com/kindreds/network/SyncKindredDataS2C.java` | **Modify.** Snapshot it. |
| `src/main/java/com/kindreds/ability/PerkEventHandlers.java` | **Modify.** Replace node-count scaling with `ThreatService`. |
| `src/main/java/com/kindreds/config/KindredsConfig.java` | **Modify.** The phase-1 settings. |
| `src/main/java/com/kindreds/client/screen/KindredsSettingsScreen.java` | **Modify.** The new settings section. |
| `src/main/java/com/kindreds/client/screen/KindredDeedsScreen.java` | **Modify.** The rank readout. |
| `src/test/java/com/kindreds/threat/ThreatMathTest.java` | **Create.** The formulas. |
| `src/test/java/com/kindreds/threat/ThreatExploitTest.java` | **Create.** One test per closed exploit. Never delete as redundant. |

---

## Task 1: ThreatMath — the formulas, pure

**Files:**
- Create: `src/main/java/com/kindreds/threat/ThreatMath.java`
- Create: `src/main/java/com/kindreds/threat/ThreatRank.java`
- Test: `src/test/java/com/kindreds/threat/ThreatMathTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ThreatMath.prior(float,float,float,int,int,int) -> float`,
  `ThreatMath.decayed(float mark, float current, float perHour, long playedTicks) -> float`,
  `ThreatMath.threat(float prior, float competence) -> float`,
  `ThreatMath.scaled(float threat, float exponent) -> float`,
  `ThreatMath.foldHardship(float competence, float hardship, float attackerWeight) -> float`,
  `ThreatMath.foldDeath(float competence, float killerWeight) -> float`,
  `ThreatMath.foldFastKill(float competence) -> float`,
  `ThreatMath.attackerWeight(double attackerDanger, double expectedDanger) -> float`,
  `ThreatMath.effectiveCompetence(float global, float family) -> float`,
  `ThreatMath.group(float strongestScaled, int players, float perPlayer, float cap) -> float`,
  `ThreatRank.of(float threat) -> ThreatRank` with `ThreatRank.translationKey() -> String`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/kindreds/threat/ThreatMathTest.java`:

```java
package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules test - no Minecraft on the classpath, so every formula is provable here. */
class ThreatMathTest {

    @Test
    void priorIsTheWeightedBlendOfItsThreeTerms() {
        // all terms full -> 100, whatever the weights
        assertEquals(100f, ThreatMath.prior(1f, 1f, 1f, 3, 2, 1), 0.001f);
        // all terms empty -> 0
        assertEquals(0f, ThreatMath.prior(0f, 0f, 0f, 3, 2, 1), 0.001f);
        // only commitment, default weights 3/2/1 -> 3/6 of 100
        assertEquals(50f, ThreatMath.prior(1f, 0f, 0f, 3, 2, 1), 0.001f);
        // a zeroed gear weight removes gear from the blend entirely
        assertEquals(75f, ThreatMath.prior(1f, 0f, 0f, 3, 0, 1), 0.001f);
    }

    @Test
    void theHighWaterMarkRisesAtOnceAndFallsSlowly() {
        // rises instantly to a higher reading
        assertEquals(80f, ThreatMath.decayed(50f, 80f, 2f, 0L), 0.001f);
        // falls at no more than perHour, measured in PLAYED ticks (72000 ticks = 1 hour)
        assertEquals(78f, ThreatMath.decayed(80f, 10f, 2f, 72000L), 0.001f);
        // never falls below the current reading
        assertEquals(10f, ThreatMath.decayed(80f, 10f, 2f, 72000L * 100), 0.001f);
        // no played time, no decay at all
        assertEquals(80f, ThreatMath.decayed(80f, 10f, 2f, 0L), 0.001f);
    }

    @Test
    void competenceCannotEscapeItsBand() {
        float high = ThreatMath.foldFastKill(1.25f);
        assertTrue(high <= ThreatMath.COMPETENCE_MAX, "rose past the ceiling: " + high);
        float low = 1.0f;
        for (int i = 0; i < 500; i++) {
            low = ThreatMath.foldDeath(low, 1.0f);
        }
        assertEquals(ThreatMath.COMPETENCE_MIN, low, 0.001f);
    }

    @Test
    void threatIsThePriorMovedOnlyWithinTheBand() {
        assertEquals(75f, ThreatMath.threat(100f, ThreatMath.COMPETENCE_MIN), 0.001f);
        assertEquals(100f, ThreatMath.threat(100f, ThreatMath.COMPETENCE_MAX), 0.001f); // clamped
        assertEquals(50f, ThreatMath.threat(50f, 1.0f), 0.001f);
    }

    @Test
    void theCurveDecidesHowMuchOfThreatBecomesDifficulty() {
        // at full threat every curve agrees
        assertEquals(1.0f, ThreatMath.scaled(100f, 0.8f), 0.001f);
        assertEquals(1.0f, ThreatMath.scaled(100f, 1.2f), 0.001f);
        // below full, a sub-linear curve is gentler and a super-linear one harsher
        assertTrue(ThreatMath.scaled(50f, 0.8f) > ThreatMath.scaled(50f, 1.0f));
        assertTrue(ThreatMath.scaled(50f, 1.2f) < ThreatMath.scaled(50f, 1.0f));
    }

    @Test
    void aTrivialAttackerBarelyCounts() {
        // attacker as dangerous as expected -> full weight
        assertEquals(1.0f, ThreatMath.attackerWeight(100.0, 100.0), 0.001f);
        // a tenth as dangerous -> a tenth of the weight
        assertEquals(0.1f, ThreatMath.attackerWeight(10.0, 100.0), 0.001f);
        // more dangerous than expected is still only full weight
        assertEquals(1.0f, ThreatMath.attackerWeight(500.0, 100.0), 0.001f);
    }

    @Test
    void hardshipRisesCompetenceWhenCoastingAndLowersItWhenStruggling() {
        float coasting = ThreatMath.foldHardship(1.0f, 0.0f, 1.0f);
        float struggling = ThreatMath.foldHardship(1.0f, 1.0f, 1.0f);
        assertTrue(coasting > 1.0f, "an untouched win should read as coasting");
        assertTrue(struggling < 1.0f, "a near-death should read as struggling");
        // and it rises faster than it falls, by design
        assertTrue(coasting - 1.0f > 1.0f - struggling);
    }

    @Test
    void groupScalingLeansOnTheStrongestAndIsCapped() {
        assertEquals(0.5f, ThreatMath.group(0.5f, 1, 0.15f, 0.45f), 0.001f);
        assertEquals(0.5f * 1.15f, ThreatMath.group(0.5f, 2, 0.15f, 0.45f), 0.001f);
        assertEquals(0.5f * 1.45f, ThreatMath.group(0.5f, 9, 0.15f, 0.45f), 0.001f);
    }

    @Test
    void everyThreatHasARankAndTheRanksCoverTheWholeRange() {
        assertSame(ThreatRank.UNNOTICED, ThreatRank.of(0f));
        assertSame(ThreatRank.UNNOTICED, ThreatRank.of(19f));
        assertSame(ThreatRank.WATCHED, ThreatRank.of(20f));
        assertSame(ThreatRank.MARKED, ThreatRank.of(40f));
        assertSame(ThreatRank.HUNTED, ThreatRank.of(60f));
        assertSame(ThreatRank.SHADOW, ThreatRank.of(80f));
        assertSame(ThreatRank.SHADOW, ThreatRank.of(100f));
        for (ThreatRank rank : ThreatRank.values()) {
            assertTrue(rank.translationKey().startsWith("kindreds.threat.rank."));
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*ThreatMathTest*'`
Expected: FAIL — `cannot find symbol: class ThreatMath`.

- [ ] **Step 3: Write `ThreatRank`**

```java
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
```

- [ ] **Step 4: Write `ThreatMath`**

```java
package com.kindreds.threat;

/**
 * Every formula behind enemy scaling, and nothing else - no Minecraft types, no state, no side
 * effects. Kept pure so the rules can be proved by unit test rather than argued about, which matters
 * more here than anywhere else in the mod: this is a control loop, and a control loop that is wrong
 * is wrong quietly.
 *
 * <p>See {@code docs/superpowers/specs/2026-07-21-enemy-scaling-design.md} §2 for why each rule is
 * shaped the way it is. Several are exploit fixes and must not be "simplified" away.
 */
public final class ThreatMath {
    private ThreatMath() {
    }

    /** Evidence may move threat only this far around the prior - the floor that stops it being farmed. */
    public static final float COMPETENCE_MIN = 0.75f;
    public static final float COMPETENCE_MAX = 1.25f;
    /** A meaningful fight should cost about a quarter of a player's health. */
    public static final float HARDSHIP_TARGET = 0.25f;
    private static final float ALPHA_RISE = 0.10f;
    private static final float ALPHA_FALL = 0.04f;
    private static final float DEATH_PENALTY = 0.05f;
    private static final long TICKS_PER_HOUR = 72000L;

    /** Declared power, 0..100, as the weighted blend of its three terms. */
    public static float prior(float commitment, float gear, float renown, int wc, int wg, int wr) {
        int total = wc + wg + wr;
        if (total <= 0) {
            return 0f;
        }
        float blend = (wc * clamp01(commitment) + wg * clamp01(gear) + wr * clamp01(renown)) / total;
        return blend * 100f;
    }

    /**
     * The high-water mark after {@code playedTicks} of play: it rises to {@code current} at once and
     * falls toward it at no more than {@code perHour}.
     *
     * <p>Played ticks, never in-game days: a bed skips a day in about three seconds, so a day-based
     * decay would be melted by sleep-spam - the exploit the mark exists to prevent.
     */
    public static float decayed(float mark, float current, float perHour, long playedTicks) {
        if (current >= mark) {
            return current;
        }
        float allowance = perHour * (playedTicks / (float) TICKS_PER_HOUR);
        return Math.max(current, mark - allowance);
    }

    /** How much a lowering signal counts, given how dangerous the thing that hurt you actually was. */
    public static float attackerWeight(double attackerDanger, double expectedDanger) {
        if (expectedDanger <= 0) {
            return 1f;
        }
        return (float) Math.max(0.0, Math.min(1.0, attackerDanger / expectedDanger));
    }

    /** Folds one fight's hardship into competence. Rises when coasting, falls when struggling. */
    public static float foldHardship(float competence, float hardship, float attackerWeight) {
        float error = HARDSHIP_TARGET - hardship;              // positive = coasting
        float alpha = error >= 0 ? ALPHA_RISE : ALPHA_FALL * clamp01(attackerWeight);
        return band(competence + alpha * error / HARDSHIP_TARGET * 0.25f);
    }

    /** A fast kill is evidence of strength. Raise-only: a slow kill proves nothing, it can be staged. */
    public static float foldFastKill(float competence) {
        return band(competence + ALPHA_RISE * 0.05f);
    }

    /** A death, weighted by how dangerous the killer was relative to what the player should handle. */
    public static float foldDeath(float competence, float killerWeight) {
        return band(competence - DEATH_PENALTY * clamp01(killerWeight));
    }

    /** Half the player's overall record, half their record against this particular family. */
    public static float effectiveCompetence(float global, float family) {
        return band(0.5f * global + 0.5f * family);
    }

    public static float threat(float prior, float competence) {
        return Math.max(0f, Math.min(100f, prior * band(competence)));
    }

    /** How much of a player's threat becomes world difficulty, 0..1. The curve is a server setting. */
    public static float scaled(float threat, float exponent) {
        return (float) Math.pow(Math.max(0f, Math.min(100f, threat)) / 100f, exponent);
    }

    /** Shared effects lean on the strongest player present, plus a bump per extra body. */
    public static float group(float strongestScaled, int players, float perPlayer, float cap) {
        float bonus = Math.min(cap, Math.max(0, players - 1) * perPlayer);
        return strongestScaled * (1f + bonus);
    }

    private static float band(float competence) {
        return Math.max(COMPETENCE_MIN, Math.min(COMPETENCE_MAX, competence));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
```

- [ ] **Step 5: Run the tests until they pass**

Run: `./gradlew test --tests '*ThreatMathTest*'`
Expected: PASS, 9 tests.

If `hardshipRisesCompetenceWhenCoastingAndLowersItWhenStruggling` fails on the
"rises faster than it falls" assertion, the cause is `ALPHA_FALL` being scaled by `attackerWeight`
while `ALPHA_RISE` is not — that is deliberate; check the test passes `1.0f` as the weight.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/kindreds/threat src/test/java/com/kindreds/threat
git commit -m "feat(threat): the formulas, pure and proved

Every rule behind enemy scaling in one class with no Minecraft on the
classpath, so a control loop that would otherwise be wrong quietly is wrong
loudly instead. Several rules are exploit fixes rather than tuning - the
competence band, the played-time decay, the attacker weighting, and
time-to-kill being raise-only - and each has a test that says so."
```

---

## Task 2: The exploit tests

Written before the service exists, against `ThreatMath` alone, because these are the properties that
must survive every later refactor.

**Files:**
- Test: `src/test/java/com/kindreds/threat/ThreatExploitTest.java`

**Interfaces:**
- Consumes: all of `ThreatMath` from Task 1.
- Produces: nothing. This task is a net.

- [ ] **Step 1: Write the tests**

```java
package com.kindreds.threat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One test per closed exploit. <b>Do not delete these as redundant.</b> Each encodes an attack
 * someone will eventually try, and the reason it fails is not obvious from the code it guards.
 *
 * <p>See the design spec §2 and §12.
 */
class ThreatExploitTest {

    @Test
    void dyingRepeatedlyCannotGrindTheWorldSoft() {
        float competence = 1.0f;
        for (int i = 0; i < 1000; i++) {
            competence = ThreatMath.foldDeath(competence, 1.0f);
        }
        // the floor holds: 75% of the prior, never less
        assertEquals(75f, ThreatMath.threat(100f, competence), 0.001f);
    }

    @Test
    void dyingToSomethingTrivialBarelyCounts() {
        float weight = ThreatMath.attackerWeight(2.0, 200.0);   // a zombie against a veteran
        float afterOne = ThreatMath.foldDeath(1.0f, weight);
        assertTrue(1.0f - afterOne < 0.001f, "a trivial killer moved competence by " + (1.0f - afterOne));
    }

    @Test
    void slowKillsAreIgnoredEntirely() {
        // there is no fold that LOWERS competence for a slow kill - the only kill signal raises it
        float competence = 1.0f;
        for (int i = 0; i < 100; i++) {
            competence = ThreatMath.foldFastKill(competence);
        }
        assertTrue(competence >= 1.0f, "a kill signal must never lower competence");
    }

    @Test
    void chipDamageFromATrivialAttackerIsNotEvidenceOfStruggle() {
        float weight = ThreatMath.attackerWeight(2.0, 200.0);
        float after = ThreatMath.foldHardship(1.0f, 1.0f, weight);   // maximum hardship, trivial source
        assertTrue(1.0f - after < 0.005f, "trivial chip damage moved competence by " + (1.0f - after));
    }

    @Test
    void sleepingThroughTenNightsDoesNotDecayTheMark() {
        // ten in-game days pass in seconds in a bed; the mark is measured in PLAYED ticks, so a few
        // seconds of play decays almost nothing however many days went by
        float after = ThreatMath.decayed(80f, 0f, 2f, 20L * 10);   // ten seconds of play
        assertTrue(after > 79.9f, "the mark fell to " + after + " after ten seconds of play");
    }

    @Test
    void strippingGearOrRespeccingCannotCollapseThePriorAtOnce() {
        // the live reading falls to nothing; the mark barely moves within an hour of play
        float afterAnHour = ThreatMath.decayed(90f, 0f, 2f, 72000L);
        assertEquals(88f, afterAnHour, 0.001f);
    }

    @Test
    void earningADeedCanOnlyRaiseThreat() {
        // renown is a term of the prior; the denominator of commitment is fixed, so nothing shrinks
        float before = ThreatMath.prior(0.5f, 0.5f, 0.00f, 3, 2, 1);
        float after = ThreatMath.prior(0.5f, 0.5f, 0.25f, 3, 2, 1);
        assertTrue(after > before, "a deed lowered the prior: " + before + " -> " + after);
    }

    @Test
    void bankingPointsInsteadOfSpendingThemDoesNotHideYou() {
        // commitment counts spent + available against the tree's full cost; 20 spent + 20 banked out
        // of 80 reads exactly as 40 spent out of 80
        float spentOnly = (10 + 30) / 80f;
        float banked = (30 + 10) / 80f;
        assertEquals(ThreatMath.prior(spentOnly, 0f, 0f, 3, 2, 1),
                ThreatMath.prior(banked, 0f, 0f, 3, 2, 1), 0.001f);
    }
}
```

- [ ] **Step 2: Run and expect a pass**

Run: `./gradlew test --tests '*ThreatExploitTest*'`
Expected: PASS, 8 tests. They test Task 1's code, so they should pass immediately. **If any fails,
Task 1 is wrong — fix `ThreatMath`, not the test.**

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/kindreds/threat/ThreatExploitTest.java
git commit -m "test(threat): one test per closed exploit, never to be deleted

Each encodes an attack someone will eventually try - death farming, dying to
something trivial, slow-killing, chip damage, sleep-spam to decay the mark,
respec collapse, deed-lowers-threat, and point hoarding - and the reason each
fails is not obvious from the code it guards."
```

---

## Task 3: ThreatState, carried on the player

**Files:**
- Create: `src/main/java/com/kindreds/threat/ThreatState.java`
- Modify: `src/main/java/com/kindreds/playerdata/KindredData.java`
- Modify: `src/main/java/com/kindreds/playerdata/DeathHandler.java` (`copyOf`)
- Modify: `src/main/java/com/kindreds/network/SyncKindredDataS2C.java` (`snapshot`)
- Test: `src/test/java/com/kindreds/playerdata/KindredDataTest.java` (extend)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ThreatState` with `priorMark() -> float`, `setPriorMark(float)`,
  `maxHealthMark() -> float`, `setMaxHealthMark(float)`, `competence() -> float`,
  `setCompetence(float)`, `familyCompetence() -> Map<String, Float>`, `playedTicks() -> long`,
  `addPlayedTicks(long)`, `copy() -> ThreatState`, and `ThreatState.CODEC` / `PACKET_CODEC`.
  `KindredData.threat() -> ThreatState`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/kindreds/playerdata/KindredDataTest.java`:

```java
    @Test
    void threatStateSurvivesThePacketRoundTrip() {
        KindredData data = new KindredData();
        data.threat().setPriorMark(63.5f);
        data.threat().setMaxHealthMark(24f);
        data.threat().setCompetence(0.9f);
        data.threat().familyCompetence().put("trolls", 1.1f);
        data.threat().addPlayedTicks(4321L);

        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        KindredData.PACKET_CODEC.encode(buf, data);
        KindredData back = KindredData.PACKET_CODEC.decode(buf);

        assertEquals(63.5f, back.threat().priorMark(), 0.001f);
        assertEquals(0.9f, back.threat().competence(), 0.001f);
    }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*KindredDataTest*'`
Expected: FAIL — `cannot find symbol: method threat()`.

- [ ] **Step 3: Write `ThreatState`**

```java
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
    private float competence = 1.0f;
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

    /** The high-water of the player's max health - the hardship denominator (see the spec §2.3). */
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

    public static final PacketCodec<RegistryByteBuf, ThreatState> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.FLOAT, ThreatState::priorMark,
            PacketCodecs.FLOAT, ThreatState::maxHealthMark,
            PacketCodecs.FLOAT, ThreatState::competence,
            PacketCodecs.VAR_LONG, ThreatState::playedTicks,
            (prior, health, competence, ticks) ->
                    new ThreatState(prior, health, competence, new HashMap<>(), ticks));
}
```

Note the packet codec deliberately omits `familyCompetence`: the client displays the overall rank
and does not need the per-family table, and the spec (§7) says only the resolved figure and its
components ride the wire.

- [ ] **Step 4: Carry it on `KindredData`**

In `src/main/java/com/kindreds/playerdata/KindredData.java`:

1. Add the field beside `corruption`:

```java
    private final com.kindreds.threat.ThreatState threat;
```

2. Initialise it in the widest constructor (`this.threat = new com.kindreds.threat.ThreatState();`)
   — it is not a constructor parameter, because every existing call site would have to pass one.
3. Add the accessor:

```java
    /** This player's threat state. Always present; never null. */
    public com.kindreds.threat.ThreatState threat() {
        return threat;
    }
```

4. Add to the persistence `CODEC` group, before the closing `).apply(`:

```java
            com.kindreds.threat.ThreatState.CODEC.optionalFieldOf("threat",
                    new com.kindreds.threat.ThreatState()).forGetter(KindredData::threat),
```

and accept it in the factory lambda, assigning with a new package-private setter
`void setThreat(ThreatState)` that copies the fields into the existing instance (so the field can stay
final and no call site changes).

5. Add to `PACKET_CODEC` the same way, using `ThreatState.PACKET_CODEC`.

- [ ] **Step 5: Carry it across death and onto the wire**

In `DeathHandler.copyOf`, after `copy.setRace(data.race());`:

```java
        // Threat survives death. A high-water mark a player could reset by dying would be a
        // difficulty switch, which is the whole thing the mark exists to prevent.
        copy.setThreat(data.threat().copy());
```

In `SyncKindredDataS2C.snapshot`, the same line against `live`.

- [ ] **Step 6: Run the tests**

Run: `./gradlew test`
Expected: PASS, 108 tests (107 + the new round-trip).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(threat): carry threat state on the player

High-water marks, competence and the per-family table live beside the rest of
a player's state, persisted with optionalFieldOf so older worlds load clean.
It is copied across death deliberately: a mark a player could reset by dying
would be exactly the difficulty switch the mark exists to prevent. The
per-family table is left off the wire - the client shows a rank, not a table."
```

---

## Task 4: MobDanger — how dangerous is that thing, and what family is it

**Files:**
- Create: `src/main/java/com/kindreds/threat/MobDanger.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `MobDanger.of(LivingEntity) -> double` (unscaled max health × attack damage),
  `MobDanger.family(LivingEntity) -> String` (one of `trolls`, `spiders`, `wargs`, `orc_kin`,
  `undead`, `other`), `MobDanger.expectedAt(float threat) -> double`,
  `MobDanger.isInScope(Entity) -> boolean`.

- [ ] **Step 1: Write it**

```java
package com.kindreds.threat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * How dangerous a mob is on its own terms, and which family it belongs to.
 *
 * <p>Danger is deliberately crude - health times damage - because it is only ever used as a
 * <em>ratio</em> against what a player of a given threat should be handling. It never needs to be
 * accurate, only ordered: a cave troll must outrank a chicken.
 */
public final class MobDanger {
    private MobDanger() {
    }

    /** Danger of a mob at its base, unscaled values. */
    public static double of(LivingEntity entity) {
        double health = entity.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
        double damage = entity.getAttributes().hasAttribute(EntityAttributes.ATTACK_DAMAGE)
                ? entity.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE) : 1.0;
        return Math.max(1.0, health * Math.max(1.0, damage));
    }

    /** What a player at {@code threat} should be able to handle - the yardstick danger is judged by. */
    public static double expectedAt(float threat) {
        // a zombie is 20 x 3 = 60; a cave troll is far above. Linear from a zombie to roughly ten of
        // them across the whole threat range, which is enough to order "trivial" against "serious".
        return 60.0 + 540.0 * (Math.max(0f, Math.min(100f, threat)) / 100f);
    }

    /**
     * The family a mob belongs to, for per-family competence. Resolved from the entity id rather than
     * from tags in phase 1: the tag files land with the replacement ladder in phase 3, and a string
     * here keeps this phase from depending on data that does not exist yet.
     */
    public static String family(LivingEntity entity) {
        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        String path = id.getPath();
        if (path.contains("troll") || path.equals("giant")) {
            return "trolls";
        }
        if (path.contains("spider") || path.contains("shelob")) {
            return "spiders";
        }
        if (path.contains("warg") || path.equals("wolf")) {
            return "wargs";
        }
        if (path.contains("orc") || path.contains("uruk") || path.contains("goblin")
                || path.contains("snaga") || path.equals("npc")) {
            return "orc_kin";
        }
        if (path.contains("zombie") || path.contains("skeleton") || path.equals("husk")
                || path.equals("drowned") || path.contains("wither")) {
            return "undead";
        }
        return "other";
    }

    /** Whether this mod scales, and takes evidence from, this entity at all. */
    public static boolean isInScope(Entity entity) {
        return entity instanceof Monster;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. If `EntityAttributes.MAX_HEALTH` does not resolve, confirm the field
name with:
`javap -cp <minecraft-merged jar> net.minecraft.entity.attribute.EntityAttributes | grep -i health`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/kindreds/threat/MobDanger.java
git commit -m "feat(threat): how dangerous a mob is, and which family it belongs to

Danger is health times damage, deliberately crude: it is only ever used as a
ratio against what a player of a given threat should be handling, so it never
needs to be accurate, only ordered. Families are resolved from the entity id
in this phase; the tag files arrive with the replacement ladder in phase 3."
```

---

## Task 5: ThreatService — the one thing that answers "how strong is this player"

**Files:**
- Create: `src/main/java/com/kindreds/threat/ThreatService.java`
- Modify: `src/main/java/com/kindreds/Kindreds.java` (register)

**Interfaces:**
- Consumes: `ThreatMath`, `ThreatState`, `MobDanger`, `KindredData.threat()`.
- Produces: `ThreatService.threatOf(ServerPlayerEntity) -> float`,
  `ThreatService.scaledFor(ServerPlayerEntity) -> float`,
  `ThreatService.scaledAgainst(ServerPlayerEntity, LivingEntity) -> float`,
  `ThreatService.invalidate(UUID)`, `ThreatService.register()`.

- [ ] **Step 1: Write it**

Key requirements, all from the spec's §11 hazards:

- Cache the resolved figure per player in a `ConcurrentHashMap<UUID, Float>`, exactly as
  `PerkService` does, and invalidate on unlock, respec, race change and disconnect.
- Recompute the prior at most once every 40 ticks in the same server tick handler that accrues
  `playedTicks`; the damage hook must never trigger a recompute.
- A player with no race has no tree, so `commitment` is `0` and the prior is gear and renown only.

```java
package com.kindreds.threat;

import com.kindreds.Kindreds;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.ProgressionService;
import com.kindreds.progression.RenownService;
import com.kindreds.progression.UnlockService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single authority on how strong a player is, and the only class the rest of the mod asks.
 *
 * <p>Cached per player, because the prior walks the whole skill tree and the damage hook that reads
 * it runs on every blow struck in the world. {@code PerkService} solves the same problem the same
 * way; the cache is invalidated wherever the inputs change, and refreshed on a slow timer besides.
 */
public final class ThreatService {
    private ThreatService() {
    }

    private static final Map<UUID, Float> CACHE = new ConcurrentHashMap<>();
    private static final int REFRESH_TICKS = 40;
    private static int tickCounter;

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> invalidate(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % REFRESH_TICKS != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                KindredData data = KindredAttachment.get(player);
                data.threat().addPlayedTicks(REFRESH_TICKS);
                refresh(player, data);
            }
        });
    }

    public static void invalidate(UUID uuid) {
        CACHE.remove(uuid);
    }

    /** The player's threat, 0..100. Cheap: served from cache between refreshes. */
    public static float threatOf(ServerPlayerEntity player) {
        Float cached = CACHE.get(player.getUuid());
        if (cached != null) {
            return cached;
        }
        return refresh(player, KindredAttachment.get(player));
    }

    /** Threat as world difficulty, 0..1, with the server's curve applied. */
    public static float scaledFor(ServerPlayerEntity player) {
        if (Kindreds.CONFIG == null || !Kindreds.CONFIG.enableEnemyScaling) {
            return 0f;
        }
        return ThreatMath.scaled(threatOf(player), Kindreds.CONFIG.scalingCurveExponent());
    }

    /** As {@link #scaledFor}, adjusted by the player's record against this mob's family. */
    public static float scaledAgainst(ServerPlayerEntity player, LivingEntity mob) {
        if (Kindreds.CONFIG == null || !Kindreds.CONFIG.enableEnemyScaling) {
            return 0f;
        }
        KindredData data = KindredAttachment.get(player);
        float global = data.threat().competence();
        float family = data.threat().familyCompetence().getOrDefault(MobDanger.family(mob), global);
        float competence = ThreatMath.effectiveCompetence(global, family);
        float threat = ThreatMath.threat(data.threat().priorMark(), competence);
        return ThreatMath.scaled(threat, Kindreds.CONFIG.scalingCurveExponent());
    }

    private static float refresh(ServerPlayerEntity player, KindredData data) {
        ThreatState state = data.threat();

        // the live reading of declared power
        float commitment = commitmentOf(player, data);
        float gear = gearOf(player);
        float renown = Math.min(1f, RenownService.deedsForRace(data) / 4f);
        float live = ThreatMath.prior(commitment, gear, renown,
                Kindreds.CONFIG.weightCommitment, Kindreds.CONFIG.weightGear, Kindreds.CONFIG.weightRenown);

        // the mark: rises at once, falls only with played time
        float mark = ThreatMath.decayed(state.priorMark(), live,
                Kindreds.CONFIG.priorDecayPerHour, REFRESH_TICKS);
        state.setPriorMark(mark);
        state.setMaxHealthMark(Math.max(state.maxHealthMark(),
                (float) player.getAttributeValue(EntityAttributes.MAX_HEALTH)));

        float threat = ThreatMath.threat(mark, state.competence());
        CACHE.put(player.getUuid(), threat);
        return threat;
    }

    /**
     * Points a player has committed <b>plus those they could commit right now</b>, against the tree's
     * full cost. Both halves are exploit fixes: a fixed denominator so earning a deed cannot shrink
     * the fraction, and counting unspent points so banking them is not a way to stay invisible.
     */
    private static float commitmentOf(ServerPlayerEntity player, KindredData data) {
        SkillTree tree = UnlockService.treeFor(player).orElse(null);
        if (tree == null) {
            return 0f;   // no race yet: the prior is gear and renown only
        }
        int max = UnlockService.maxSpendable(tree);
        if (max <= 0) {
            return 0f;
        }
        int spent = UnlockService.totalPointsSpent(data, tree);
        int available = 0;
        for (String discipline : com.kindreds.data.Disciplines.ALL) {
            available += Math.max(0, ProgressionService.pointsAvailable(data, tree,
                    net.minecraft.util.Identifier.of("kindreds", discipline)));
        }
        return Math.min(1f, (spent + available) / (float) max);
    }

    /** Armour and weapon, normalised against a full-mithril reference. */
    private static float gearOf(ServerPlayerEntity player) {
        double armour = player.getAttributeValue(EntityAttributes.ARMOR);
        double damage = player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        float armourPart = (float) Math.min(1.0, armour / 25.0);     // 25 is about full mithril
        float damagePart = (float) Math.min(1.0, damage / 12.0);     // 12 is about a mithril sword
        return Math.min(1f, 0.6f * armourPart + 0.4f * damagePart);
    }
}
```

**`UnlockService.treeFor` does not exist yet, and there are already three private copies of that
lookup** — `ActiveAbilityService.resolveTree`, `CurseContextService.resolveTree` and
`PerkService.resolveTree`. Do **not** write a fourth. Add one shared helper and move the three onto it:

```java
    /** The skill tree of {@code player}'s current race, or empty if they have no race yet. */
    public static Optional<SkillTree> treeFor(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return Optional.empty();
        }
        return RaceAccess.getRace(player).flatMap(race -> {
            for (SkillTree tree : server.getRegistryManager().getOrThrow(KindredsRegistries.SKILL_TREE)) {
                if (tree.race().equals(race)) {
                    return Optional.of(tree);
                }
            }
            return Optional.empty();
        });
    }
```

This is the one piece of existing-code cleanup this plan asks for, and only because a fourth copy
would otherwise be added here.

- [ ] **Step 2: Register it**

In `src/main/java/com/kindreds/Kindreds.java`, beside `PerkEventHandlers.register();`:

```java
        com.kindreds.threat.ThreatService.register();
```

- [ ] **Step 3: Invalidate the cache where the inputs change**

Add `ThreatService.invalidate(player.getUuid());` to:
- `RequestUnlockC2S` after a successful unlock,
- `RespecC2S` after a respec,
- `BirthTraitService.refreshIfChanged` where it already calls `SyncKindredDataS2C.sendTo` on a race
  change.

- [ ] **Step 4: Verify**

Run: `./gradlew compileJava test`
Expected: BUILD SUCCESSFUL, 108 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(threat): the service that answers how strong a player is

Cached per player, because the prior walks the whole tree and the damage hook
that reads it runs on every blow struck in the world - the same problem
PerkService solves the same way. Invalidated on unlock, respec, race change
and disconnect, and refreshed on a 40-tick timer that also accrues the played
ticks the high-water decay is measured in."
```

---

## Task 6: Config and the settings screen

**Files:**
- Modify: `src/main/java/com/kindreds/config/KindredsConfig.java`
- Modify: `src/main/java/com/kindreds/config/Difficulty.java`
- Modify: `src/main/java/com/kindreds/command/KindredsCommand.java` (config keys)
- Modify: `src/main/java/com/kindreds/network/SyncConfigS2C.java`
- Modify: `src/main/java/com/kindreds/client/screen/KindredsSettingsScreen.java`
- Modify: `src/main/resources/assets/kindreds/lang/en_us.json` and `ru_ru.json`

**Interfaces:**
- Consumes: nothing.
- Produces: `KindredsConfig.scalingCurve` (String, one of `FEEL_STRONGER`/`EXACT_PACE`/`LONG_DEFEAT`),
  `KindredsConfig.scalingCurveExponent() -> float`, `weightCommitment`, `weightGear`,
  `weightRenown` (int), `priorDecayPerHour` (float), `maxDamageBonus` (int, percent),
  `xpBonus` (int, percent).

- [ ] **Step 1: Add the fields**

In `KindredsConfig.java`, beside `enableEnemyScaling`:

```java
    /** Enemy scaling is on by default now: the world answering a grown hero is the intended game. */
    public boolean enableEnemyScaling = true;
    /** FEEL_STRONGER | EXACT_PACE | LONG_DEFEAT - see the design spec §2.6. */
    public String scalingCurve = "FEEL_STRONGER";
    public int weightCommitment = 3;
    public int weightGear = 2;
    public int weightRenown = 1;
    /** How fast the high-water mark forgets, in threat points per hour of PLAYED time. */
    public float priorDecayPerHour = 2f;
    public int maxDamageBonus = 60;
    public int xpBonus = 50;

    /** The curve as an exponent on how much of a player's threat becomes world difficulty. */
    public float scalingCurveExponent() {
        return switch (scalingCurve) {
            case "EXACT_PACE" -> 1.0f;
            case "LONG_DEFEAT" -> 1.2f;
            default -> 0.8f;
        };
    }
```

- [ ] **Step 2: Set them from the difficulty presets**

In `Difficulty.applyTo(KindredsConfig)`, set `scalingCurve` per the spec §6:
`FIRESIDE` leaves `enableEnemyScaling = false`; `ROAD` → `FEEL_STRONGER`;
`LONG_DEFEAT` → `EXACT_PACE`; `DOOM` → `LONG_DEFEAT`.

- [ ] **Step 3: Expose them to the command and the client**

Add every new key to the `CONFIG_KEY_SUGGESTIONS` list and the `configSet` switch in
`KindredsCommand.java`, and to `SyncConfigS2C` so the settings screen can display them.

- [ ] **Step 4: Add the settings section, localized**

In `KindredsSettingsScreen`, add a section headed by `kindreds.settings.section.world_answers`.

**The layout must flow, not be fixed-height.** This screen has already printed one block over
another once, because a fixed offset assumed a tall window. Lay each row out from a running `y`, and
verify by screenshot at GUI scales 1–4 in Task 8.

Lang keys — **add to both `en_us.json` and `ru_ru.json`**, and remember `%%` for a literal percent:

| Key | en_us |
|---|---|
| `kindreds.settings.section.world_answers` | `The world answers` |
| `kindreds.settings.enemyScaling` | `Enemy scaling` |
| `kindreds.settings.scalingCurve` | `How the world keeps pace` |
| `kindreds.settings.curve.FEEL_STRONGER` | `You outgrow it` |
| `kindreds.settings.curve.EXACT_PACE` | `It keeps pace` |
| `kindreds.settings.curve.LONG_DEFEAT` | `It outgrows you` |
| `kindreds.settings.maxDamageBonus` | `Hardest they may hit` |
| `kindreds.settings.xpBonus` | `Extra xp from danger` |

- [ ] **Step 5: Verify**

Run: `./gradlew test`
Expected: PASS. `KindredsConfigTest` covers round-tripping; if it asserts a field count, update it.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(threat): the settings, and enemy scaling on by default

The flag has existed unread since the difficulty presets were written; it now
has the rules behind it and a section in the screen. Laid out from a running
y rather than fixed offsets - this screen has printed one block over another
once already."
```

---

## Task 7: The per-player effects

**Files:**
- Modify: `src/main/java/com/kindreds/ability/PerkEventHandlers.java` (the incoming-damage hook)
- Modify: `src/main/java/com/kindreds/progression/ProgressionService.java` (xp award)
- Create: `src/main/java/com/kindreds/threat/ThreatEvidence.java`

**Interfaces:**
- Consumes: `ThreatService.scaledAgainst`, `ThreatMath`, `MobDanger`, `ThreatState`.
- Produces: `ThreatEvidence.register()`.

- [ ] **Step 1: Replace the node-count damage scaling**

In `PerkEventHandlers`, replace the existing block:

```java
        if (com.kindreds.Kindreds.CONFIG != null && com.kindreds.Kindreds.CONFIG.enableEnemyScaling
                && source.getAttacker() instanceof Monster) {
            int nodes = KindredAttachment.get(victim).unlockedNodes().size();
            multiplier *= 1.0f + Math.min(0.5f, nodes * 0.005f);
        }
```

with:

```java
        // The world answers a grown hero. Scaled per victim, so two players fighting one troll each
        // meet the difficulty they have earned - which is what makes this safe in multiplayer.
        if (victim instanceof ServerPlayerEntity served
                && source.getAttacker() instanceof LivingEntity attacker
                && MobDanger.isInScope(attacker)) {
            float scaled = ThreatService.scaledAgainst(served, attacker);
            multiplier *= 1.0f + (Kindreds.CONFIG.maxDamageBonus / 100f) * scaled;
        }
```

- [ ] **Step 2: Scale the xp reward**

In `ProgressionService.awardXp`, multiply `baseXp` by `1 + (xpBonus/100) * scaled` where `scaled`
comes from `ThreatService.scaledFor(player)`. **The danger must pay**: a world that gets harder for
the same reward teaches players to stay small.

Note `awardXp` takes `KindredData`, not a player. Add an overload that takes the
`ServerPlayerEntity`, and leave the existing signature for callers that have no player.

- [ ] **Step 3: Write `ThreatEvidence`**

Listens for:
- `ServerLivingEntityEvents.AFTER_DAMAGE` — signature
  `(LivingEntity entity, DamageSource source, float baseDamageTaken, float damageTaken, boolean blocked)`.
  Accumulate `damageTaken` per player, **only when `source.getAttacker()` is a mob in scope** — never
  another player, never fall, lava or drowning, or the exploit is a friend beating you up, or a leap
  off a cliff mid-fight.
- `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY` — signature
  `(ServerWorld world, Entity entity, LivingEntity killedEntity)`. Close the fight, compute
  `hardship = accumulated / state.maxHealthMark()`, fold it with
  `ThreatMath.attackerWeight(MobDanger.of(killed), MobDanger.expectedAt(threat))`, and fold the
  per-family figure the same way.
- `ServerLivingEntityEvents.AFTER_DEATH` — signature `(LivingEntity entity, DamageSource source)`.
  On a player, fold a death **only if `source.getAttacker()` is a mob in scope**, weighted the same
  way. All three event names and signatures were verified against
  `fabric-entity-events-v1` 2.1.2 before this plan was written.

Every fold writes back to `KindredData.threat()` and calls `ThreatService.invalidate`.

- [ ] **Step 4: Register and verify**

Add `ThreatEvidence.register();` in `Kindreds.java`. Run `./gradlew compileJava test`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(threat): the per-player effects, and the evidence loop

Damage scales per victim rather than per mob, which is what makes it safe in
multiplayer: two players fighting one troll each meet the difficulty they have
earned. The xp reward scales with it, because a world that gets harder for the
same pay teaches players to stay small. Evidence counts only damage dealt by
mobs in scope - never another player, never a fall - or two friends could
soften both their worlds by hitting each other."
```

---

## Task 8: The rank readout, and proving it in game

**Files:**
- Modify: `src/main/java/com/kindreds/client/screen/KindredDeedsScreen.java`
- Modify: `src/main/java/com/kindreds/client/gametest/ScreenIterationTest.java`
- Modify: `src/main/java/com/kindreds/command/KindredsDoctor.java`
- Modify: lang files

**Interfaces:**
- Consumes: `ThreatRank`, the synced `ThreatState` on the client.
- Produces: nothing.

- [ ] **Step 1: Show the rank on the Deeds page**

Under the deed tally, add a line reading the rank from the synced state:

```java
        float threat = ThreatMath.threat(data.threat().priorMark(), data.threat().competence());
        Text rank = Text.translatable("kindreds.threat.regard",
                Text.translatable(ThreatRank.of(threat).translationKey()));
```

Lang, **both languages**:

| Key | en_us | ru_ru |
|---|---|---|
| `kindreds.threat.regard` | `The land's regard: %s` | `Взгляд земли: %s` |
| `kindreds.threat.rank.unnoticed` | `Unnoticed` | `Незамеченный` |
| `kindreds.threat.rank.watched` | `Watched` | `Под наблюдением` |
| `kindreds.threat.rank.marked` | `Marked` | `Отмеченный` |
| `kindreds.threat.rank.hunted` | `Hunted` | `Преследуемый` |
| `kindreds.threat.rank.shadow` | `The Shadow is upon you` | `Тень легла на вас` |
| `kindreds.threat.risen` | `%s has taken notice of you.` | `%s обращает на вас взор.` |
| `kindreds.threat.fallen` | `The land's regard for you fades to %s.` | `Взгляд земли слабеет: %s.` |

- [ ] **Step 2: Announce a rank change**

In `ThreatService.refresh`, when `ThreatRank.of(newThreat) != ThreatRank.of(oldThreat)`, send the
player `kindreds.threat.risen` or `kindreds.threat.fallen`. Crossing a rank is the story beat; the
number moving is not.

- [ ] **Step 3: Add a doctor check**

In `KindredsDoctor`, report the calling player's threat, its three components and its rank, so
`/kindreds doctor` can answer "why is the world like this".

- [ ] **Step 4: Screenshot it at every GUI scale**

In `ScreenIterationTest`, grant xp and a deed (already done), then screenshot the settings screen
and the Deeds page at scales 1–4. **Register only `ScreenIterationTest` in `fabric.mod.json` while
iterating, and restore all five before committing.**

Run: `./gradlew runClientGameTest`
Expected: no exception; `run/screenshots` contains the settings section with no overlapping text at
any scale.

- [ ] **Step 5: Full verification**

```bash
./gradlew test                 # expect 108+, zero failures
./gradlew runClientGameTest    # expect BUILD SUCCESSFUL, doctor all green
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(threat): the land's regard, as a name rather than a number

Five ranks on the Deeds page and a line of text when one is crossed - a
counter going from 46 to 47 is not a story beat, and being told the Enemy has
taken notice of you is. The exact figure stays in the doctor for anyone who
wants to know why the world is the way it is."
```

---

## Self-review against the spec

| Spec section | Covered by |
|---|---|
| §2.1 prior, fixed denominator, unspent points | Task 5 `commitmentOf`, Task 2 tests |
| §2.2 high-water marks, played-time decay | Task 1 `decayed`, Task 5 `refresh`, Task 2 tests |
| §2.3 evidence, asymmetric, attacker-weighted | Task 1 folds, Task 7 `ThreatEvidence` |
| §2.4 the floor | Task 1 `band`, Task 2 first test |
| §2.5 per-family | Task 3 `familyCompetence`, Task 5 `scaledAgainst`, Task 4 `family` |
| §2.6 the curve | Task 1 `scaled`, Task 6 `scalingCurveExponent` |
| §3 damage, detection, xp | Task 7 — **detection is deferred, see below** |
| §3a named rank, rank-change event | Task 8 |
| §6 settings | Task 6 |
| §7 persistence and sync | Task 3 |
| §11 hazards: cache, DeathHandler, raceless player | Task 5, Task 3, Task 5 `commitmentOf` |
| §12 exploit tests | Task 2 |

**One deliberate deferral:** the detection-range effect (§3) is *not* in this plan. It reuses
`AbilityApplier.setDynamicModifier` against `middle-earth:detection_range`, and the attribute is
clamped `[0.1, 1.0]` and buff-reversed, so it can only cancel a stealth advantage rather than exceed
the baseline. It is a small, self-contained addition and belongs with phase 2's mob work, where its
effect can be seen. Phase 1 delivers damage, xp and the readout — enough to play and to tune.

**Phases 2 and 3** (mob health at spawn, elites, escorts, the replacement ladder) get their own plans
once phase 1 has been played and its constants tuned against reality.
