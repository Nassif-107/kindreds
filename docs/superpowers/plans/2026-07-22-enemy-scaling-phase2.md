# Enemy Scaling — Phase 2: Weight and Reward — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mobs arrive *heavier* and pay *better* as the group's threat grows — health scaling at spawn,
named elites with abilities and loot, bounded escorts, per-dimension pacing, the detection counter to
stealth, and the time-to-kill signal that finally feeds `foldFastKill`.

**Architecture:** A `MobEntity.initialize` mixin captures the `SpawnReason` (the one moment it exists)
into a persistent per-mob attachment; `ServerEntityEvents.ENTITY_LOAD` then runs `MobScaler` — health,
elite promotion, escorts — reading group threat from `ThreatService.scaledGroupAt`. `EliteMobs` owns
the elite pool (names, four abilities, loot). Everything spawn-side is **shared** (one decision per
mob); everything player-side (detection, TTK) stays per-player. `MobScaler` knows nothing about how
threat is derived; `ThreatEvidence` knows nothing about effects.

**Tech Stack:** Fabric 1.21.8, yarn mappings, Java 21, JUnit 5, Mixin, fabric-data-attachment-api-v1,
fabric-lifecycle-events-v1, Fabric client gametests.

**Spec:** `docs/superpowers/specs/2026-07-21-enemy-scaling-design.md` §3 (effects), §3a (per-family
voice), §4 (multiplayer), §6 (settings), §11 (hazards), §12 (testing). Phase 1 is merged: `ThreatMath`,
`ThreatService`, `ThreatEvidence`, `MobDanger`, `MiddleEarthFoes(Bridge)`, config, ranks — 172 tests.

## Global Constraints

- **A setting is exposed only if an operator could sensibly want it different AND no value of it can
  break an invariant.** Phase 2 exposes: `maxHealthBonus` (100), `eliteChance` (25), `escortChance`
  (30), `groupScalingPercent` (15), `dimensionMultiplierMiddleEarth` (1.0), `dimensionMultiplierOverworld`
  (0.75). **Internal, NOT config:** the escort hard bounds (max 2, same species, natural spawns only,
  80%-of-mob-cap suppression), the 128-block group radius, the +45% group-size cap, the detection
  counter's 0.9 span, the TTK yardstick. Every exposed numeric key gets range validation in
  `configSet` — a negative or absurd value must be rejected, not stored (the negative-`priorDecayPerHour`
  lesson).
- **Spawn effects are shared; player effects are per-player** (spec §4). `scaledGroup` uses the
  *strongest* nearby player — never the average — and a mob spawning with no player within 128 blocks
  uses the strongest player in that dimension, undecayed (the AFK-farm defence).
- **Idempotence across chunk reload is non-negotiable** (spec §11). `ENTITY_LOAD` fires on every
  reload; a mob must never be scaled twice. The attachment's `scaled` flag is the guard, and the
  attribute modifier uses one fixed id with remove-then-add (`AbilityApplier`'s proven pattern).
- **Never touch the spawner** (spec §10): no spawn weights, costs, caps, or conditions. Escorts are
  the only entity-adding effect and carry all four hard bounds.
- **Base-mod isolation:** any `net.sevenstars.*` reference lives ONLY in a bridge class guarded by
  `FabricLoader.isModLoaded("middle-earth")` + `catch (Throwable)` — the `MiddleEarthFoes` /
  `MiddleEarthFoesBridge` pattern. Base mod absent → phase 2 degrades to vanilla-only cleanly.
- **Everything the player reads is localized** in BOTH `en_us.json` and `ru_ru.json`. A literal `%`
  in a lang value must be `%%`. No user-facing English built in Java (doctor is the documented
  dev-English exception).
- **API facts verified against the real jars — use these, not memory:**
  - `ServerEntityEvents.ENTITY_LOAD` is in `net.fabricmc.fabric.api.event.lifecycle.v1` (lifecycle,
    NOT entity-events); callback `(Entity entity, ServerWorld world)`. It fires on first spawn AND
    every chunk reload, and carries no `SpawnReason`.
  - `SpawnReason` exists only at
    `MobEntity.initialize(ServerWorldAccess, LocalDifficulty, SpawnReason, EntityData)` — mixin there.
  - Data attachments work on ANY entity: `Entity implements AttachmentTarget`
    (`getAttached`/`setAttached`/`hasAttached`/`getAttachedOrCreate`), register with
    `AttachmentRegistry.createPersistent(Identifier, Codec)` exactly as `KindredAttachment` does.
  - `EntityAttributeModifier(Identifier, double, Operation)`;
    `EntityAttributeInstance.removeModifier(Identifier)` then `addPersistentModifier(...)`.
  - **Do NOT copy `ActiveAbilityHandlers.summonWolves`'s `EntityType.create(world, reason)` pattern
    for escorts** — that overload never calls `MobEntity.initialize`, so the mob spawns without its
    difficulty gear. Use `EntityType.spawn(ServerWorld, Consumer<T>, BlockPos, SpawnReason, boolean,
    boolean)` (creates, positions, initializes, consumer, adds via `spawnEntityAndPassengers`).
  - Mob cap: `world.getChunkManager().getSpawnInfo()` → `SpawnHelper.Info.getSpawningChunkCount()`
    and `.getGroupToCount().getInt(SpawnGroup.MONSTER)`; the cap formula is
    `SpawnGroup.MONSTER.getCapacity() * spawningChunkCount / 289` — **289 = 17×17 =
    `SpawnHelper.CHUNK_AREA`, which is package-private, so it is hardcoded with a heavy comment.**
  - Loot re-roll: `Entity.getLootTableKey()` → `Optional<RegistryKey<LootTable>>` (there is NO
    `getLootTable()` on `LivingEntity` in 1.21.8);
    `server.getReloadableRegistries().getLootTable(key)`;
    `new LootWorldContext.Builder(world).add(THIS_ENTITY, e).add(ORIGIN, e.getPos())
    .add(DAMAGE_SOURCE, src).addOptional(ATTACKING_ENTITY, src.getAttacker())
    .build(LootContextTypes.ENTITY)`; `table.generateLoot(ctx, e.getLootTableSeed(), consumer)`.
    THIS_ENTITY/ORIGIN/DAMAGE_SOURCE are REQUIRED by the ENTITY context type.
  - Detection: base mod registers `middle-earth:detection_range` on players — `ClampedEntityAttribute`,
    base 1.0, clamp **[0.1, 1.0]**, buff-reversed (lower = stealthier). Reach it through the existing
    `AbilityApplier.setDynamicModifier(ServerPlayerEntity, Identifier attributeId, String key,
    double amount, Operation)` — it resolves attributes generically via `Registries.ATTRIBUTE.getEntry`
    and silently no-ops if absent, so no new bridge is needed for it.
  - `ThreatMath.group(float strongestScaled, int players, float perPlayer, float cap)` and
    `ThreatMath.foldFastKill(float, ThreatTuning)` exist, are tested, and are currently **uncalled** —
    phase 2 gives both their callers.
- Gradle: `--no-daemon` always; `--rerun-tasks` when results may be stale; never trust the console
  summary — sum `build/test-results/test/*.xml` attributes by hand; never rebuild while a gametest
  client runs. Suite baseline **172 tests**; the count only goes up.
- Every anti-exploit test must be **proven to fail with its defence removed** before it counts.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/kindreds/threat/MobMark.java` | **Create.** The per-mob persistent attachment record: spawn reason, scaled/elite/escort state. One codec, one registration. |
| `src/main/java/com/kindreds/mixin/MobEntityInitializeMixin.java` | **Create.** Captures `SpawnReason` into the `MobMark` at the only moment it exists. |
| `src/main/java/com/kindreds/threat/MobScaler.java` | **Create.** The `ENTITY_LOAD` handler: early-outs, health scaling, elite roll, escort roll. Knows nothing about threat derivation. |
| `src/main/java/com/kindreds/threat/EliteMobs.java` | **Create.** The elite pool: names, four abilities, tick + on-hurt behaviour, loot bonus. |
| `src/main/java/com/kindreds/threat/ThreatService.java` | **Modify.** `scaledGroupAt(ServerWorld, BlockPos)`, dimension multiplier, detection counter in `refresh`. |
| `src/main/java/com/kindreds/threat/ThreatMath.java` | **Modify.** `foldFastKill` gains an `attackerWeight` parameter (the same weighting `foldHardship` got in the final phase-1 review). |
| `src/main/java/com/kindreds/threat/ThreatEvidence.java` | **Modify.** First-hit tracking → the TTK feeder for `foldFastKill`. |
| `src/main/java/com/kindreds/threat/MiddleEarthFoesBridge.java` | **Modify.** Adds the player-free `isHostileFactionMobAtSpawn` (EVIL-disposition check) for shared spawn effects. |
| `src/main/java/com/kindreds/threat/MiddleEarthFoes.java` | **Modify.** Guarded wrapper for the above. |
| `src/main/java/com/kindreds/threat/MobDanger.java` | **Modify.** `isScalableAtSpawn(Entity)` — the player-free scope used by shared effects. |
| `src/main/java/com/kindreds/config/KindredsConfig.java` | **Modify.** The six phase-2 dials. |
| `src/main/java/com/kindreds/command/KindredsCommand.java` | **Modify.** Keys + range validation. |
| `src/main/java/com/kindreds/network/SyncConfigS2C.java` | **Modify.** View rows the screen shows. |
| `src/main/java/com/kindreds/client/screen/KindredsSettingsScreen.java` | **Modify.** Three new display rows. |
| `src/main/java/com/kindreds/client/screen/KindredDeedsScreen.java` | **Modify.** Per-family voice lines (§3a). |
| `src/main/java/com/kindreds/command/KindredsDoctor.java` | **Modify.** Caps-within-clamps + attachment/mixin checks. |
| `src/main/java/com/kindreds/client/gametest/ScreenIterationTest.java` | **Modify.** Elite + scaled-mob + screenshots. |
| `src/main/resources/kindreds.mixins.json` | **Modify.** Register the new mixin. |
| `src/main/resources/assets/kindreds/lang/{en_us,ru_ru}.json` | **Modify.** Elite names, family voice lines, settings rows. |
| `src/test/java/com/kindreds/threat/{MobMarkTest,GroupThreatTest,EliteMobsTest,TtkTest}.java` | **Create.** Pure-core tests. |

Dependency order: Task 1 (config) → Task 2 (group) → Task 3 (mark + mixin + health) → Task 4 (elites)
→ Task 5 (escorts) → Task 6 (detection + TTK) → Task 7 (voice + doctor + gametest).

---

## Task 1: The phase-2 dials

**Files:**
- Modify: `src/main/java/com/kindreds/config/KindredsConfig.java`
- Modify: `src/main/java/com/kindreds/command/KindredsCommand.java`
- Modify: `src/main/java/com/kindreds/network/SyncConfigS2C.java`
- Modify: `src/main/java/com/kindreds/client/screen/KindredsSettingsScreen.java`
- Modify: `src/main/resources/assets/kindreds/lang/en_us.json`, `ru_ru.json`
- Test: `src/test/java/com/kindreds/config/KindredsConfigTest.java` (extend), `src/test/java/com/kindreds/config/DifficultyTest.java` (extend)

**Interfaces:**
- Consumes: nothing new.
- Produces: `KindredsConfig.maxHealthBonus` (int, 100), `eliteChance` (int, 25), `escortChance`
  (int, 30), `groupScalingPercent` (int, 15), `dimensionMultiplierMiddleEarth` (float, 1.0f),
  `dimensionMultiplierOverworld` (float, 0.75f). All are free dials no preset touches.

- [ ] **Step 1: Write the failing tests**

Extend `KindredsConfigTest` with a round-trip assertion for the new fields, and extend
`DifficultyTest`'s `sentinelConfig()` / `assertFreeDialsUntouched()` so every preset is proven not to
clobber them:

```java
    // KindredsConfigTest — add to the existing defaults/round-trip test:
    assertEquals(100, c.maxHealthBonus);
    assertEquals(25, c.eliteChance);
    assertEquals(30, c.escortChance);
    assertEquals(15, c.groupScalingPercent);
    assertEquals(1.0f, c.dimensionMultiplierMiddleEarth, 1e-6f);
    assertEquals(0.75f, c.dimensionMultiplierOverworld, 1e-6f);
```

```java
    // DifficultyTest — add to sentinelConfig():
    c.maxHealthBonus = 61; c.eliteChance = 62; c.escortChance = 63;
    c.groupScalingPercent = 64;
    c.dimensionMultiplierMiddleEarth = 0.65f; c.dimensionMultiplierOverworld = 0.66f;
    // and matching assertEquals lines in assertFreeDialsUntouched(...).
```

- [ ] **Step 2: Run to verify they fail** — `./gradlew test --no-daemon --rerun-tasks`; expect
compile failures on the missing fields.

- [ ] **Step 3: Add the fields** beside the phase-1 scaling block in `KindredsConfig`:

```java
    /** How much extra max health a mob may arrive with at full group threat. Percent. */
    public int maxHealthBonus = 100;
    /** Chance an in-scope mob is promoted to an elite at full group threat. Percent; 0 disables. */
    public int eliteChance = 25;
    /** Chance a scaled mob brings 1-2 escorts at full group threat. Percent; 0 disables. The escort
     * HARD BOUNDS (max 2, same species, natural spawns only, mob-cap suppression) are deliberately
     * not configurable - they are what keeps escorts from ever being a runaway population. */
    public int escortChance = 30;
    /** Extra group difficulty per additional nearby player, percent. The +45%% total cap is internal. */
    public int groupScalingPercent = 15;
    /** Difficulty pacing per dimension: the old world stays gentler than the new one. */
    public float dimensionMultiplierMiddleEarth = 1.0f;
    public float dimensionMultiplierOverworld = 0.75f;
```

- [ ] **Step 4: Command exposure with range validation.** Add all six to `CONFIG_KEYS`, the
`configSet` switch, and the `configList` dump in `KindredsCommand`, following the existing patterns.
Validation (the negative-`priorDecayPerHour` lesson — reject before store):

```java
    case "maxHealthBonus" -> c.maxHealthBonus = parsePercent(value, 0, 400);
    case "eliteChance" -> c.eliteChance = parsePercent(value, 0, 100);
    case "escortChance" -> c.escortChance = parsePercent(value, 0, 100);
    case "groupScalingPercent" -> c.groupScalingPercent = parsePercent(value, 0, 100);
    case "dimensionMultiplierMiddleEarth" -> c.dimensionMultiplierMiddleEarth = parseUnitRange(value, 0f, 2f);
    case "dimensionMultiplierOverworld" -> c.dimensionMultiplierOverworld = parseUnitRange(value, 0f, 2f);
```

`parsePercent(String, int min, int max)` / `parseUnitRange(String, float min, float max)` are small
private helpers that throw `IllegalArgumentException` with the offending bounds in the message so the
existing catch block reports them; write them once beside `parseBool`.

- [ ] **Step 5: Sync + screen.** Add `maxHealthBonus`, `eliteChance`, `escortChance` to
`SyncConfigS2C.View` + `snapshot()` (JSON blob — just new record components). Add three display rows
to `KindredsSettingsScreen`'s `WORLD_ANSWERS_ROWS` (`"maxHealthBonus"`, `"eliteChance"`,
`"escortChance"`) and their `worldAnswerValue` cases rendering `value + "%"` like the existing
`maxDamageBonus` row. The rows flow from the running `by` like everything else — no fixed Y.

- [ ] **Step 6: Lang, both files.**

| Key | en_us | ru_ru |
|---|---|---|
| `kindreds.settings.maxHealthBonus` | `Heaviest they may arrive` | `Насколько крепче они приходят` |
| `kindreds.settings.eliteChance` | `Champions among them` | `Избранные среди них` |
| `kindreds.settings.escortChance` | `They come accompanied` | `Они приходят не одни` |

- [ ] **Step 7: Run the suite** — `./gradlew test --no-daemon --rerun-tasks`; hand-sum the XML
(expect 172 + whatever Step 1 added, 0 failures). Validate both lang JSONs parse.

- [ ] **Step 8: Commit** — `git commit -m "feat(threat): the phase-2 dials, validated and displayed"`

---

## Task 2: Group threat — `scaledGroupAt`

**Files:**
- Modify: `src/main/java/com/kindreds/threat/ThreatService.java`
- Test: `src/test/java/com/kindreds/threat/GroupThreatTest.java` (create)

**Interfaces:**
- Consumes: `ThreatMath.group(float strongestScaled, int players, float perPlayer, float cap)`
  (exists, tested, uncalled), `ThreatService.scaledFor(ServerPlayerEntity)`.
- Produces: `ThreatService.scaledGroupAt(ServerWorld world, BlockPos pos) -> float` — the shared
  figure every spawn effect uses; `ThreatService.dimensionMultiplier(String dimensionNamespace) ->
  float` (package-private pure core).

Spec §4 rules this implements, verbatim: `scaledGroup = scaled(strongest nearby player) * (1 + 0.15 *
(nearbyPlayers - 1))`, capped at +45% for group size, "nearby" = 128 blocks at spawn time; a mob
spawning with no player in range uses the strongest player in that dimension, undecayed; strongest,
never average.

- [ ] **Step 1: Write the failing test** (`GroupThreatTest`, pure parts only — the world walk is
MC-bound):

```java
package com.kindreds.threat;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure core of shared spawn difficulty: strongest-not-average, the group bonus, the
 * per-dimension pacing. The 128-block player walk is Minecraft-bound and verified by review. */
class GroupThreatTest {

    @Test
    void strongestPlayerDrivesTheGroupNeverTheAverage() {
        // a veteran (0.9 scaled) among three newcomers (0.1): the group figure must lean on 0.9
        float group = ThreatService.groupOf(List.of(0.1f, 0.9f, 0.1f, 0.1f), 0.15f);
        assertEquals(0.9f * (1f + 3 * 0.15f), group, 0.001f,
                "average would be 0.3-ish; hiding a veteran behind newcomers must not soften mobs");
    }

    @Test
    void groupBonusIsCappedAtFortyFivePercent() {
        float group = ThreatService.groupOf(List.of(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f), 0.15f);
        assertEquals(1f * 1.45f, group, 0.001f, "8 extra players x 15% = 120%, but the cap is 45%");
    }

    @Test
    void dimensionMultiplierPacesOldWorldGentler() {
        assertEquals(0.75f, ThreatService.dimensionMultiplier("minecraft", 1.0f, 0.75f), 1e-6f);
        assertEquals(1.0f, ThreatService.dimensionMultiplier("middle-earth", 1.0f, 0.75f), 1e-6f);
        assertEquals(0.75f, ThreatService.dimensionMultiplier("some_other_mod", 1.0f, 0.75f), 1e-6f,
                "unknown dimensions pace like the old world, not the new one");
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile error, `groupOf`/`dimensionMultiplier` missing.

- [ ] **Step 3: Implement** in `ThreatService`:

```java
    /** The +45%% group-size cap is a bound, not a dial (spec §4) - it is what keeps a full server
     * from multiplying a mob past recognition. */
    private static final float GROUP_CAP = 0.45f;
    /** "Nearby" for a spawn decision, blocks (spec §4). */
    private static final double GROUP_RADIUS = 128.0;

    /** Pure core: the strongest figure carries the group bonus. Package-private for the unit test. */
    static float groupOf(List<Float> scaledValues, float perPlayer) {
        float strongest = 0f;
        for (float s : scaledValues) {
            strongest = Math.max(strongest, s);
        }
        return ThreatMath.group(strongest, scaledValues.size(), perPlayer, GROUP_CAP);
    }

    /** Pure core: middle-earth paces at its own multiplier; everywhere else is the old world. */
    static float dimensionMultiplier(String dimensionNamespace, float middleEarth, float overworld) {
        return "middle-earth".equals(dimensionNamespace) ? middleEarth : overworld;
    }

    /**
     * The SHARED difficulty for a mob entering the world at {@code pos} (spec §4): the strongest
     * player within 128 blocks carries the group bonus; a spawn with no player in range uses the
     * strongest player in the dimension, undecayed - an AFK farm 130 blocks out must not be a
     * difficulty switch. No players in the dimension at all -> 0 (an unwitnessed mob costs nothing).
     */
    public static float scaledGroupAt(ServerWorld world, BlockPos pos) {
        if (Kindreds.CONFIG == null || !Kindreds.CONFIG.enableEnemyScaling) {
            return 0f;
        }
        List<Float> near = new ArrayList<>();
        float strongestInDimension = 0f;
        double radiusSq = GROUP_RADIUS * GROUP_RADIUS;
        for (ServerPlayerEntity p : world.getPlayers()) {
            float s = scaledFor(p);
            strongestInDimension = Math.max(strongestInDimension, s);
            if (p.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= radiusSq) {
                near.add(s);
            }
        }
        float group = near.isEmpty()
                ? strongestInDimension
                : groupOf(near, Kindreds.CONFIG.groupScalingPercent / 100f);
        return group * dimensionMultiplier(world.getRegistryKey().getValue().getNamespace(),
                Kindreds.CONFIG.dimensionMultiplierMiddleEarth,
                Kindreds.CONFIG.dimensionMultiplierOverworld);
    }
```

Verify `ServerWorld.getPlayers()` (no-arg, returns `List<ServerPlayerEntity>`) and
`Entity.squaredDistanceTo(double,double,double)` against existing call sites before relying on them;
both are used elsewhere in this codebase.

- [ ] **Step 4: Run the tests** — expect the three new tests green, suite total up by 3.

- [ ] **Step 5: Commit** — `git commit -m "feat(threat): the group's threat, leaning on its strongest"`

---

## Task 3: The mob mark, the SpawnReason mixin, and health scaling

**Files:**
- Create: `src/main/java/com/kindreds/threat/MobMark.java`
- Create: `src/main/java/com/kindreds/mixin/MobEntityInitializeMixin.java`
- Create: `src/main/java/com/kindreds/threat/MobScaler.java`
- Modify: `src/main/java/com/kindreds/threat/MobDanger.java` (`isScalableAtSpawn`)
- Modify: `src/main/java/com/kindreds/threat/MiddleEarthFoes.java` + `MiddleEarthFoesBridge.java`
- Modify: `src/main/java/com/kindreds/Kindreds.java` (register `MobScaler`)
- Modify: `src/main/resources/kindreds.mixins.json`
- Test: `src/test/java/com/kindreds/threat/MobMarkTest.java` (create)

**Interfaces:**
- Consumes: `ThreatService.scaledGroupAt(ServerWorld, BlockPos)` (Task 2), the attachment API as
  `KindredAttachment` uses it, `AbilityApplier`'s remove-then-add idiom (pattern, not code).
- Produces: `MobMark` record with `spawnReason() -> String`, `scaled() -> boolean`,
  `eliteAbility() -> String` ("" = not elite), `eliteName() -> String`, `escort() -> boolean`,
  plus `MobMark.KEY : AttachmentType<MobMark>`, `MobMark.of(Entity) -> MobMark` (never null),
  `MobMark.set(Entity, MobMark)`, and with-er helpers `withScaled(boolean)`,
  `withElite(String ability, String name)`, `withEscort(boolean)`, `withSpawnReason(String)`.
  `MobScaler.register()`. `MobDanger.isScalableAtSpawn(Entity) -> boolean`.
  `MobScaler.SCALED_HEALTH_ID : Identifier` (`kindreds:scaled/max_health`).

- [ ] **Step 1: Write the failing test** (`MobMarkTest` — codec round trip and the with-ers):

```java
package com.kindreds.threat;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The per-mob mark must survive chunk unload byte-for-byte - a lost mark means a mob scaled twice
 * (compounding health) or an elite that forgets its name when you walk away (spec §11). */
class MobMarkTest {

    @Test
    void markSurvivesTheCodecRoundTrip() {
        MobMark mark = MobMark.DEFAULT.withSpawnReason("NATURAL").withScaled(true)
                .withElite("rally", "kindreds.elite.name.orc_kin.2").withEscort(false);
        JsonElement json = MobMark.CODEC.encodeStart(JsonOps.INSTANCE, mark).getOrThrow();
        MobMark back = MobMark.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(mark, back);
    }

    @Test
    void defaultMarkIsInertEverywhere() {
        assertEquals("", MobMark.DEFAULT.spawnReason());
        assertFalse(MobMark.DEFAULT.scaled());
        assertEquals("", MobMark.DEFAULT.eliteAbility());
        assertFalse(MobMark.DEFAULT.escort());
    }

    @Test
    void anOldWorldsMobDecodesFromAnEmptyObject() {
        // every field optionalFieldOf: a mob saved before phase 2 loads as DEFAULT, not a crash
        MobMark back = MobMark.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonObject()).getOrThrow();
        assertEquals(MobMark.DEFAULT, back);
    }
}
```

- [ ] **Step 2: Run to verify failure** — `MobMark` missing.

- [ ] **Step 3: Write `MobMark`:**

```java
package com.kindreds.threat;

import com.kindreds.Kindreds;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

/**
 * Everything phase 2 needs to remember about one mob, persisted on the entity itself so it survives
 * chunk unload: how it entered the world (the {@code SpawnReason} name, captured by
 * {@code MobEntityInitializeMixin} at the only moment it exists), whether it has already been scaled
 * (the idempotence guard - {@code ENTITY_LOAD} fires again on every reload), its elite identity, and
 * whether it is itself an escort (escorts never escort).
 *
 * <p>Immutable record with with-ers rather than a mutable bean: attachment reads hand out the stored
 * instance, and a mutable one shared between the netty thread and the server thread would be the
 * same CME hazard {@code ThreatState.copy()} exists to prevent.
 */
public record MobMark(String spawnReason, boolean scaled, String eliteAbility, String eliteName,
                      boolean escort) {

    public static final MobMark DEFAULT = new MobMark("", false, "", "", false);

    public static final Codec<MobMark> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("spawn_reason", "").forGetter(MobMark::spawnReason),
            Codec.BOOL.optionalFieldOf("scaled", false).forGetter(MobMark::scaled),
            Codec.STRING.optionalFieldOf("elite_ability", "").forGetter(MobMark::eliteAbility),
            Codec.STRING.optionalFieldOf("elite_name", "").forGetter(MobMark::eliteName),
            Codec.BOOL.optionalFieldOf("escort", false).forGetter(MobMark::escort)
    ).apply(i, MobMark::new));

    public static final AttachmentType<MobMark> KEY =
            AttachmentRegistry.createPersistent(Identifier.of(Kindreds.MOD_ID, "mob_mark"), CODEC);

    /** The stored mark, or {@link #DEFAULT} - never null, never creates storage for unmarked mobs. */
    public static MobMark of(Entity entity) {
        MobMark mark = entity.getAttached(KEY);
        return mark == null ? DEFAULT : mark;
    }

    public static void set(Entity entity, MobMark mark) {
        entity.setAttached(KEY, mark);
    }

    public boolean elite() {
        return !eliteAbility.isEmpty();
    }

    public MobMark withSpawnReason(String reason) { return new MobMark(reason, scaled, eliteAbility, eliteName, escort); }
    public MobMark withScaled(boolean s) { return new MobMark(spawnReason, s, eliteAbility, eliteName, escort); }
    public MobMark withElite(String ability, String name) { return new MobMark(spawnReason, scaled, ability, name, escort); }
    public MobMark withEscort(boolean e) { return new MobMark(spawnReason, scaled, eliteAbility, eliteName, e); }
}
```

- [ ] **Step 4: Run the tests** — the three `MobMarkTest` tests pass.

- [ ] **Step 5: The mixin.** Project convention (see `LivingEntityDamageMixin`): full descriptor in
the `method` string so the injector cannot pick a different overload.

```java
package com.kindreds.mixin;

import com.kindreds.threat.MobMark;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code SpawnReason} exists for exactly one call and is never stored by vanilla or surfaced by any
 * Fabric event in 0.136.1 (verified against every fabric-api module jar). This is the only moment a
 * mob can be told apart as naturally spawned vs. spawner/egg/summon vs. merely reloaded - so it is
 * captured here into the persistent {@link MobMark}, and {@code MobScaler} reads it from
 * {@code ENTITY_LOAD}, which fires in both cases but has no reason of its own.
 */
@Mixin(MobEntity.class)
public abstract class MobEntityInitializeMixin {

    @Inject(method = "initialize(Lnet/minecraft/world/ServerWorldAccess;Lnet/minecraft/world/LocalDifficulty;Lnet/minecraft/entity/SpawnReason;Lnet/minecraft/entity/EntityData;)Lnet/minecraft/entity/EntityData;",
            at = @At("TAIL"))
    private void kindreds$captureSpawnReason(ServerWorldAccess world, LocalDifficulty difficulty,
                                             SpawnReason reason, EntityData data,
                                             CallbackInfoReturnable<EntityData> cir) {
        MobEntity self = (MobEntity) (Object) this;
        MobMark.set(self, MobMark.of(self).withSpawnReason(reason.name()));
    }
}
```

Register in `kindreds.mixins.json`'s `"mixins"` array. Run `./gradlew compileJava --no-daemon` — a
wrong descriptor fails at build (mixin verify), which is the point of spelling it out.

- [ ] **Step 6: Player-free spawn scope.** In `MiddleEarthFoesBridge` add (all base-mod imports stay
in the bridge; verify `Faction.getDisposition()` and the `DispositionType` enum constant name `EVIL`
with `javap` against the base-mod jar before relying on them — they were observed in
`NpcEntity.shouldTarget`'s bytecode but not signature-verified):

```java
    /** Spawn-time scope has no player to ask, so NPCs gate on their faction's coarse disposition:
     * an EVIL-faction NPC arrives as an enemy of everyone. Finer per-player hostility still governs
     * the per-player paths through isHostileBaseMob. Wargs and trolls are hostile by construction. */
    static boolean isHostileFactionMobAtSpawn(Entity entity) {
        if (entity instanceof WargEntity || entity instanceof TrollEntity || entity instanceof CaveTrollEntity) {
            return true;
        }
        if (entity instanceof NpcEntity npc) {
            Identifier factionId = npc.getFactionIdentifier();
            if (factionId == null) return false;
            try {
                Faction faction = FactionLookup.getFactionById(entity.getWorld(), factionId);
                return faction != null && faction.getDisposition() == DispositionType.EVIL;
            } catch (FactionIdentifierException e) {
                return false;
            }
        }
        return false;
    }
```

`MiddleEarthFoes` wraps it with the identical isModLoaded + `catch (Throwable)` + once-latch guard as
`isHostileBaseMob`. `MobDanger` gains:

```java
    /** The player-free scope for SHARED spawn effects (health, elites, escorts): vanilla hostiles
     * plus the base mod's always-hostile beasts and EVIL-faction NPCs. Per-player effects keep using
     * {@link #isInScope(Entity, ServerPlayerEntity)} - a friendly NPC must never arrive pre-scaled. */
    public static boolean isScalableAtSpawn(Entity entity) {
        return entity instanceof Monster || MiddleEarthFoes.isHostileFactionMobAtSpawn(entity);
    }
```

- [ ] **Step 7: `MobScaler` — health scaling.**

```java
package com.kindreds.threat;

import com.kindreds.Kindreds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.Identifier;

/**
 * The spawn-time half of the world's answer: a mob entering the world is weighed once against the
 * group's threat (never re-weighed - reloads are recognised by the {@link MobMark#scaled()} flag)
 * and arrives heavier, possibly promoted, possibly accompanied. Knows nothing about how threat is
 * derived; that is {@link ThreatService#scaledGroupAt}'s job.
 *
 * <p>{@code ENTITY_LOAD} fires for every entity including item frames and dropped items (spec §11),
 * so the early-outs come before anything that touches config values, registries or player lists.
 */
public final class MobScaler {
    private MobScaler() {
    }

    /** One fixed id + remove-then-add = idempotent across any code path that re-runs (the
     * AbilityApplier lesson: a persistent modifier re-added under a fresh id compounds every
     * chunk cycle). */
    public static final Identifier SCALED_HEALTH_ID = Identifier.of(Kindreds.MOD_ID, "scaled/max_health");

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof MobEntity mob)) {
                return;                                      // item frames, arrows, armour stands
            }
            if (Kindreds.CONFIG == null || !Kindreds.CONFIG.enableEnemyScaling) {
                return;
            }
            MobMark mark = MobMark.of(mob);
            if (mark.scaled()) {
                return;                                      // chunk reload - already weighed
            }
            if (mark.spawnReason().isEmpty()) {
                return;   // loaded from a pre-phase-2 save, or never initialize()d: never retro-scale
            }
            if (!MobDanger.isScalableAtSpawn(mob)) {
                return;
            }
            float scaledGroup = ThreatService.scaledGroupAt(world, mob.getBlockPos());
            applyHealth(mob, scaledGroup);
            // Elite and escort rolls are appended here by Tasks 4 and 5.
            MobMark.set(mob, MobMark.of(mob).withScaled(true));
        });
    }

    /** Health x (1 + maxHealthBonus * scaledGroup), then top up: raising max health does not raise
     * current health, and a mob must not arrive looking pre-damaged (spec §11). */
    private static void applyHealth(MobEntity mob, float scaledGroup) {
        double bonus = (Kindreds.CONFIG.maxHealthBonus / 100.0) * scaledGroup;
        if (bonus <= 0) {
            return;
        }
        EntityAttributeInstance health = mob.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (health == null) {
            return;
        }
        float before = mob.getMaxHealth();
        health.removeModifier(SCALED_HEALTH_ID);
        health.addPersistentModifier(new EntityAttributeModifier(SCALED_HEALTH_ID, bonus,
                EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        mob.setHealth(mob.getHealth() + (mob.getMaxHealth() - before));
    }
}
```

Register `MobScaler.register();` in `Kindreds.java` beside `ThreatEvidence.register()`.

- [ ] **Step 8: Verify** — `./gradlew compileJava --no-daemon` then the full suite; totals up by 3
(MobMarkTest), 0 failures.

- [ ] **Step 9: Commit** — `git commit -m "feat(threat): mobs arrive weighed - the mark, the reason, the health"`

---

## Task 4: Elites

**Files:**
- Create: `src/main/java/com/kindreds/threat/EliteMobs.java`
- Modify: `src/main/java/com/kindreds/threat/MobScaler.java` (the promotion roll)
- Modify: `src/main/java/com/kindreds/Kindreds.java` (register)
- Modify: both lang files
- Test: `src/test/java/com/kindreds/threat/EliteMobsTest.java` (create)

**Interfaces:**
- Consumes: `MobMark` (Task 3), `MobDanger.family(LivingEntity)`, the loot recipe from Global
  Constraints, `ServerLivingEntityEvents.AFTER_DAMAGE` / `AFTER_DEATH` (already used by
  `ThreatEvidence` — copy those exact registrations).
- Produces: `EliteMobs.register()`, `EliteMobs.promote(MobEntity, Random) -> MobMark`
  (pure-ish: picks ability + name, returns the mark to store; caller applies), ability ids
  `"aura"`, `"rally"`, `"swift"`, `"bulwark"`, and `EliteMobs.abilityFor(String id)` used by the doctor.

Design decisions locked here:
- **Promotion roll** happens inside `MobScaler`'s handler after `applyHealth`:
  `chance = eliteChance/100 * scaledGroup`; roll `world.getRandom().nextFloat() < chance`; skip if
  `mark.escort()` (an escort can be scaled but never promoted — two rolls on one spawn event would
  double the intended rate).
- **Name**: `kindreds.elite.name.<family>.<1..3>` chosen at promotion, applied as
  `mob.setCustomName(Text.translatable(key))` + `setCustomNameVisible(true)`. The KEY is stored in
  the mark (`eliteName`) so a reloaded elite re-applies it if the custom name was somehow lost.
- **Abilities** (exactly four, spec §3):
  - `aura` — every 40 ticks, players within 4 blocks get Slowness I for 60 ticks.
  - `rally` — on being hurt by a player, every `MobEntity` of the same `MobDanger.family` within
    12 blocks has `setTarget(attacker)` called.
  - `swift` — on being hurt, the elite gets Speed I for 100 ticks.
  - `bulwark` — while above half health, Resistance I (re-applied on the same 40-tick cadence).
- **Elite tick** without scanning the world: `EliteMobs` keeps a
  `Map<ServerWorld, Set<MobEntity>> LIVE` populated from the same `ENTITY_LOAD` (any mob whose mark
  is elite) and pruned via `ServerEntityEvents.ENTITY_UNLOAD` and on `!mob.isAlive()`. The 40-tick
  work runs inside `ServerTickEvents.END_SERVER_TICK` alongside the existing threat timer cadence.
- **Loot bonus** on `AFTER_DEATH` when `MobMark.of(entity).elite()`: re-roll the mob's own loot
  table once (the exact `getLootTableKey()`/`LootWorldContext` recipe in Global Constraints — those
  parameters are REQUIRED), dropping each generated stack at the corpse via
  `entity.dropStack(world, stack)`; then a 15% roll for one item from the **item tag**
  `kindreds:elite_bounty` (create `data/kindreds/tags/item/elite_bounty.json` with
  `{"values": [{"id": "minecraft:diamond", "required": false}]}` — a datapack extension point, and
  race-craft materials join it in a later feature; if the tag is empty or missing, skip silently).

- [ ] **Step 1: Write the failing test** (`EliteMobsTest` — the pure selection):

```java
package com.kindreds.threat;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class EliteMobsTest {

    @Test
    void promotionPicksARealAbilityAndAFamilyName() {
        // seeded Random: deterministic pick, and the name key embeds the mob's own family
        MobMark mark = EliteMobs.choose("orc_kin", new Random(42));
        assertTrue(java.util.List.of("aura", "rally", "swift", "bulwark").contains(mark.eliteAbility()));
        assertTrue(mark.eliteName().startsWith("kindreds.elite.name.orc_kin."),
                "an orc champion must not carry a troll's name");
    }

    @Test
    void everyAbilityIsReachable() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            seen.add(EliteMobs.choose("undead", r).eliteAbility());
        }
        assertEquals(java.util.Set.of("aura", "rally", "swift", "bulwark"), seen,
                "a pool entry no roll can reach is a dead ability");
    }
}
```

(`EliteMobs.choose(String family, Random) -> MobMark` is the pure core: picks 1-of-4 ability and
1-of-3 name key for the family, returns `MobMark.DEFAULT.withElite(ability, nameKey)` for the caller
to merge. `promote(...)` wraps it with the entity.)

- [ ] **Step 2: Run to verify failure.** Compile error.

- [ ] **Step 3: Implement `EliteMobs`** per the locked decisions above. The registrations copy
`ThreatEvidence`'s exact event signatures. `MobScaler` gains, after `applyHealth`:

```java
            if (!mark.escort() && Kindreds.CONFIG.eliteChance > 0
                    && world.getRandom().nextFloat() < (Kindreds.CONFIG.eliteChance / 100f) * scaledGroup) {
                MobMark promoted = EliteMobs.choose(MobDanger.family(mob), new java.util.Random(world.getRandom().nextLong()));
                mark = mark.withElite(promoted.eliteAbility(), promoted.eliteName());
                EliteMobs.dress(mob, mark);   // name + visibility + LIVE registration
            }
```

(then the single `MobMark.set(mob, mark.withScaled(true))` at the end of the handler stores
everything — restructure the Task 3 handler so `mark` is a local threaded through, set once.)

- [ ] **Step 4: Lang, both files.** 18 name keys, three per family:

| Key | en_us | ru_ru |
|---|---|---|
| `kindreds.elite.name.trolls.1` | `Gravemaw` | `Могильная Пасть` |
| `kindreds.elite.name.trolls.2` | `Stonehide` | `Каменная Шкура` |
| `kindreds.elite.name.trolls.3` | `Old Grinder` | `Старый Жернов` |
| `kindreds.elite.name.spiders.1` | `Broodmother` | `Мать Выводка` |
| `kindreds.elite.name.spiders.2` | `Silkfang` | `Шёлковый Клык` |
| `kindreds.elite.name.spiders.3` | `Nightspinner` | `Ночная Пряха` |
| `kindreds.elite.name.wargs.1` | `Packlord` | `Вожак Стаи` |
| `kindreds.elite.name.wargs.2` | `Winterjaw` | `Зимняя Челюсть` |
| `kindreds.elite.name.wargs.3` | `Redmuzzle` | `Красная Морда` |
| `kindreds.elite.name.orc_kin.1` | `Skullkeeper` | `Хранитель Черепов` |
| `kindreds.elite.name.orc_kin.2` | `Iron-Tooth` | `Железный Зуб` |
| `kindreds.elite.name.orc_kin.3` | `Whipmaster` | `Хозяин Плети` |
| `kindreds.elite.name.undead.1` | `Barrow-Wight` | `Умертвие Курганов` |
| `kindreds.elite.name.undead.2` | `Gravecaller` | `Зовущий из Могил` |
| `kindreds.elite.name.undead.3` | `The Unresting` | `Неупокоенный` |
| `kindreds.elite.name.other.1` | `The Marked One` | `Отмеченный` |
| `kindreds.elite.name.other.2` | `Shadowtouched` | `Тронутый Тенью` |
| `kindreds.elite.name.other.3` | `The Grim` | `Мрачный` |

- [ ] **Step 5: Run the suite** (+2 tests), validate both lang JSONs parse, `compileJava` green.

- [ ] **Step 6: Commit** — `git commit -m "feat(threat): champions among them - named elites with teeth and loot"`

---

## Task 5: Escorts

**Files:**
- Modify: `src/main/java/com/kindreds/threat/MobScaler.java`
- Test: `src/test/java/com/kindreds/threat/MobScalerEscortTest.java` (create)

**Interfaces:**
- Consumes: `MobMark`, `EntityType.spawn(ServerWorld, Consumer<T>, BlockPos, SpawnReason, boolean,
  boolean)` (the overload that runs `initialize` — NOT the bare `create`), `World.isSpaceEmpty(Entity)`,
  the mob-cap recipe from Global Constraints.
- Produces: the escort roll inside the `ENTITY_LOAD` handler; `MobScaler.escortBudget(int current,
  int capacity, int spawningChunks) -> int` (pure core for the cap suppression, unit-testable).

The four HARD BOUNDS, restated from spec §3 — these are structure, not dials:
1. at most **2** escorts per mob, same `EntityType`, and an escort never rolls escorts
   (`mark.escort()` guard);
2. **natural spawns only**: `"NATURAL".equals(mark.spawnReason())` — never SPAWNER, SPAWN_EGG,
   BREEDING, COMMAND, or reload;
3. suppressed entirely at ≥ **80%** of the dimension's monster cap;
4. escorts are ordinary mobs in every other respect — they scale (their own `ENTITY_LOAD` runs
   `applyHealth`), they despawn normally, they are never promoted (Task 4's guard).

- [ ] **Step 1: Write the failing test:**

```java
package com.kindreds.threat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The 80%%-cap suppression is what keeps escorts from ever being a runaway population (spec §3).
 * cap = capacity * spawningChunks / 289 - 289 is SpawnHelper.CHUNK_AREA (17x17), package-private in
 * vanilla, hardcoded here and guarded by this test's arithmetic. */
class MobScalerEscortTest {

    @Test
    void escortsStopAtEightyPercentOfTheMonsterCap() {
        // 70 capacity * 289 spawning chunks / 289 = cap 70; 80% = 56
        assertEquals(0, MobScaler.escortBudget(56, 70, 289), "at 80%% exactly, no escorts");
        assertEquals(0, MobScaler.escortBudget(69, 70, 289), "nearly full, no escorts");
        assertEquals(2, MobScaler.escortBudget(20, 70, 289), "a quiet night can be crowded");
    }

    @Test
    void anEmptySpawningAreaHasNoBudget() {
        assertEquals(0, MobScaler.escortBudget(0, 70, 0), "no spawning chunks -> cap 0 -> no escorts");
    }
}
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement.** The pure core:

```java
    /** How many escorts the dimension can absorb right now: 0 at or past 80%% of the monster cap,
     * else up to 2. cap = capacity * spawningChunks / 289 (17x17 = SpawnHelper.CHUNK_AREA - that
     * constant and the cap check itself are package-private in vanilla, so the formula is
     * reproduced here; if a Minecraft update changes CHUNK_AREA this number silently drifts, which
     * is why the doctor asserts it against a sane range rather than trusting it forever). */
    static int escortBudget(int currentMonsters, int capacity, int spawningChunks) {
        int cap = capacity * spawningChunks / 289;
        return currentMonsters >= 0.8 * cap ? 0 : 2;
    }
```

The roll, appended to the `ENTITY_LOAD` handler after the elite roll:

```java
            if (!mark.escort() && "NATURAL".equals(mark.spawnReason())
                    && Kindreds.CONFIG.escortChance > 0
                    && world.getRandom().nextFloat() < (Kindreds.CONFIG.escortChance / 100f) * scaledGroup) {
                spawnEscorts(mob, world);
            }
```

```java
    private static void spawnEscorts(MobEntity leader, ServerWorld world) {
        SpawnHelper.Info info = world.getChunkManager().getSpawnInfo();
        if (info == null) {
            return;
        }
        int budget = escortBudget(info.getGroupToCount().getInt(SpawnGroup.MONSTER),
                SpawnGroup.MONSTER.getCapacity(), info.getSpawningChunkCount());
        int wanted = Math.min(budget, 1 + world.getRandom().nextInt(2));   // 1-2, budget-capped
        for (int i = 0; i < wanted; i++) {
            BlockPos pos = leader.getBlockPos().add(
                    world.getRandom().nextInt(5) - 2, 0, world.getRandom().nextInt(5) - 2);
            // The 6-arg spawn runs initialize() (difficulty gear) and the consumer BEFORE adding, so
            // the escort flag is set before its own ENTITY_LOAD fires - it scales, but never rolls
            // escorts or promotion of its own.
            leader.getType().spawn(world,
                    escort -> MobMark.set(escort, MobMark.of(escort).withEscort(true)),
                    pos, SpawnReason.NATURAL, false, false);
        }
    }
```

Space check: before spawning, build the candidate via the same `EntityType`'s dimensions —
`world.isSpaceEmpty(leader.getDimensions(leader.getPose()).getBoxAt(pos.getX() + 0.5, pos.getY(),
pos.getZ() + 0.5))` (leader and escort share the type, so the leader's box is the escort's box);
skip that position on failure rather than searching. Verify `EntityDimensions.getBoxAt(double,
double, double)` exists before relying on it (it is used by vanilla spawn logic; if the name differs
in yarn, use `Box.of` from the type's width/height — say which you used in the report).

- [ ] **Step 4: Run the suite** (+2), `compileJava` green.

- [ ] **Step 5: Commit** — `git commit -m "feat(threat): they come accompanied - bounded escorts, never a runaway"`

---

## Task 6: Detection counter and the time-to-kill feeder

**Files:**
- Modify: `src/main/java/com/kindreds/threat/ThreatService.java` (detection in `refresh`)
- Modify: `src/main/java/com/kindreds/threat/ThreatMath.java` (`foldFastKill` weighting)
- Modify: `src/main/java/com/kindreds/threat/ThreatEvidence.java` (first-hit tracking, the fold)
- Test: `src/test/java/com/kindreds/threat/ThreatMathTest.java` (extend),
  `src/test/java/com/kindreds/threat/ThreatExploitTest.java` (extend),
  `src/test/java/com/kindreds/threat/TtkTest.java` (create)

**Interfaces:**
- Consumes: `AbilityApplier.setDynamicModifier(ServerPlayerEntity, Identifier, String, double,
  Operation)` (generic over attributes, no-ops if the attribute is absent — no new bridge needed),
  `MobDanger.of/expectedAt`, `ThreatMath.attackerWeight`.
- Produces: `ThreatMath.foldFastKill(float competence, float attackerWeight, ThreatTuning t)`
  (REPLACES the 2-arg form — it has no production callers yet, so no deprecation dance),
  `ThreatEvidence`'s TTK bookkeeping, `ThreatService`'s detection modifier under key
  `"threat/detection"`.

**Detection** (spec §3): the attribute is clamped `[0.1, 1.0]` with base 1.0 and is buff-reversed —
raising it can only cancel a stealth build's advantage, never exceed baseline; the clamp itself is
the bound. In `ThreatService.refresh`, alongside the existing per-refresh work:

```java
        // Threat erodes stealth: a positive modifier drags a stealth-lowered detection_range back
        // toward its 1.0 baseline. The attribute's own [0.1, 1.0] clamp is the cap - at full threat
        // the counter (+0.9) cancels the deepest stealth build exactly, and can never exceed
        // baseline. setDynamicModifier resolves the attribute generically and no-ops when the base
        // mod is absent.
        float scaled = ThreatMath.scaled(threat, Kindreds.CONFIG.scalingCurveExponent());
        AbilityApplier.setDynamicModifier(player, DETECTION_RANGE_ID, "threat/detection",
                0.9 * scaled, EntityAttributeModifier.Operation.ADD_VALUE);
```

with `private static final Identifier DETECTION_RANGE_ID = Identifier.of("middle-earth", "detection_range");`
(an Identifier is data, not a class reference — no bridge/isolation concern).

**TTK** (spec §2.3, the raise-only row currently marked deferred): `ThreatEvidence` learns when a
fight *started*:

- A new in-memory `ConcurrentHashMap<UUID, Engagement> ENGAGEMENTS` where
  `record Engagement(UUID mob, long firstHitTick) {}` — one per player, current fight only.
- In `AFTER_DAMAGE`, a NEW branch beside the existing victim-side one: when the damaged entity is a
  mob and `source.getAttacker()` is a `ServerPlayerEntity` and `MobDanger.isInScope(mob, player)` —
  if the player's engagement is empty or names a different mob, store
  `new Engagement(mob.getUuid(), world.getTime())`. (First hit wins; re-hits don't reset it.)
- In `AFTER_KILLED_OTHER_ENTITY`, before the accumulator fold: if the player's engagement names the
  killed mob, `ttk = world.getTime() - firstHitTick`; expected =
  `TTK_BASE_TICKS * clamp01(danger / expectedAt(threat))` with
  `private static final long TTK_BASE_TICKS = 160;` (8 seconds for an at-level mob — internal
  yardstick, not config); if `ttk < expected / 2`, fold
  `state.setCompetence(ThreatMath.foldFastKill(state.competence(),
  ThreatMath.attackerWeight(danger, MobDanger.expectedAt(threat)), tuning))`. Clear the engagement
  on kill, on player death, and on disconnect (the same handler that clears `ACCUMULATED_DAMAGE`).

`foldFastKill` becomes (mirroring `foldHardship`'s weighting exactly):

```java
    /** A fast kill is evidence of strength - raise-only (a slow kill proves nothing, it can be
     * staged), and weighted by how dangerous the victim actually was: one-shotting a provoked hen
     * proves as little as taking five minutes over it. */
    public static float foldFastKill(float competence, float attackerWeight, ThreatTuning t) {
        return band(competence + t.riseRate() * 0.05f * clamp01(attackerWeight), t);
    }
```

- [ ] **Step 1: Write the failing tests.**

```java
    // ThreatMathTest — replaces the existing foldFastKill tests' call shape:
    @Test
    void fastKillsOfTrivialMobsProveNothing() {
        float c = 1.0f;
        for (int i = 0; i < 100; i++) {
            c = ThreatMath.foldFastKill(c, 0.003f, ThreatTuning.DEFAULTS);  // chicken-danger weight
        }
        assertTrue(c < 1.01f, "100 trivial fast kills must not meaningfully raise competence, got " + c);
    }

    @Test
    void fastKillsOfRealThreatsCount() {
        float once = ThreatMath.foldFastKill(1.0f, 1.0f, ThreatTuning.DEFAULTS);
        assertEquals(1.0f + 0.10f * 0.05f, once, 0.0001f);
    }
```

```java
    // TtkTest — the pure decision:
    @Test
    void onlyGenuinelyFastKillsQualify() {
        assertTrue(ThreatEvidence.isFastKill(60, 160, 1.0f));    // 3s vs 8s expected at full weight
        assertFalse(ThreatEvidence.isFastKill(100, 160, 1.0f));  // 5s vs 8s: not under half
        assertFalse(ThreatEvidence.isFastKill(1, 160, 0.0f));    // trivial mob: expected collapses to 0
    }
```

(`ThreatEvidence.isFastKill(long ttkTicks, long baseTicks, float dangerRatio)` is the extracted pure
core: `ttkTicks < (long) (baseTicks * clamp01(dangerRatio)) / 2`.)

**Prove the weighting defends**: temporarily drop `clamp01(attackerWeight)` from `foldFastKill`, run
`fastKillsOfTrivialMobsProveNothing`, observe it FAIL (100 unweighted folds = +0.5, over the 1.01
threshold before banding even bites), restore, re-run green. Both outputs go in the report.

- [ ] **Step 2: Run to verify failures** (compile + the deliberate defence-removal check).

- [ ] **Step 3: Implement** all three pieces above. Update the existing `foldFastKillOnlyEverRaisesCompetence`
test to the 3-arg signature with weight 1.0.

- [ ] **Step 4: Run the suite**, hand-summed; `compileJava` for the MC-bound parts.

- [ ] **Step 5: Commit** — `git commit -m "feat(threat): stealth erodes and speed testifies - detection counter and the TTK signal"`

---

## Task 7: The family's voice, the doctor, and proof in game

**Files:**
- Modify: `src/main/java/com/kindreds/client/screen/KindredDeedsScreen.java`
- Modify: `src/main/java/com/kindreds/command/KindredsDoctor.java`
- Modify: `src/main/java/com/kindreds/client/gametest/ScreenIterationTest.java`
- Modify: both lang files
- Test: gametest run + screenshots

**Interfaces:**
- Consumes: the synced `ThreatState.familyCompetence()` on the client, `ThreatRank`, `MobMark`,
  `MobScaler.SCALED_HEALTH_ID`.
- Produces: nothing new.

- [ ] **Step 1: Per-family voice lines (§3a).** On the Deeds page under the rank line, for each of
the five named families whose synced per-family competence diverges from global by more than 0.1:
competence ≥ global + 0.1 → `kindreds.family.mastered.<family>`; ≤ global − 0.1 →
`kindreds.family.feared.<family>`. At most three lines, strongest divergences first, flowing from the
running Y. Lang, both files:

| Key | en_us | ru_ru |
|---|---|---|
| `kindreds.family.mastered.trolls` | `The trolls no longer trouble you.` | `Тролли вас больше не тревожат.` |
| `kindreds.family.mastered.spiders` | `The spiders shy from your step.` | `Пауки сторонятся вашего шага.` |
| `kindreds.family.mastered.wargs` | `The wargs give your trail a wide berth.` | `Варги обходят ваш след стороной.` |
| `kindreds.family.mastered.orc_kin` | `The orc-kind whisper your name in fear.` | `Орочье племя со страхом шепчет ваше имя.` |
| `kindreds.family.mastered.undead` | `The restless dead grow quiet before you.` | `Беспокойные мертвецы затихают перед вами.` |
| `kindreds.family.feared.trolls` | `The trolls have your measure.` | `Тролли знают вам цену.` |
| `kindreds.family.feared.spiders` | `The spiders have your measure.` | `Пауки знают вам цену.` |
| `kindreds.family.feared.wargs` | `The wargs have your measure.` | `Варги знают вам цену.` |
| `kindreds.family.feared.orc_kin` | `The orc-kind have your measure.` | `Орочье племя знает вам цену.` |
| `kindreds.family.feared.undead` | `The dead have your measure.` | `Мёртвые знают вам цену.` |

- [ ] **Step 2: Doctor checks.** In `KindredsDoctor` (dev-English per its documented exception):
`maxDamageBonus`/`maxHealthBonus`/`eliteChance`/`escortChance` within [0,400]/[0,100]; the detection
counter span 0.9 within the attribute's [0.1,1.0] clamp width when the base mod is present; the
`MobEntityInitializeMixin` merged onto `MobEntity` (the same merged-handler-method check the doctor
already does for other mixins); `MobMark.KEY` registered.

- [ ] **Step 3: Gametest.** In `ScreenIterationTest` (registered alone in `fabric.mod.json` while
iterating, ALL FIVE restored before commit — the standing hazard): set `eliteChance` to 100 and
`maxHealthBonus` to 100 via the command, summon a zombie via `/summon` — note COMMAND spawns still
initialize, so the mark exists but the escort roll correctly skips —, assert via
`/kindreds doctor` output that the mob carries `kindreds:scaled/max_health` and a custom name;
screenshot the Deeds page (rank + family lines) and the settings screen at GUI scales 1–4.
Run `./gradlew runClientGameTest --no-daemon`; expect BUILD SUCCESSFUL, no overlap/cutoff in the
four settings screenshots (scroll fix holds with three added rows).

- [ ] **Step 4: Full verification.** `./gradlew test --no-daemon --rerun-tasks` (hand-summed totals,
zero failures), `compileJava`, both lang files parse, all five gametest entrypoints present.

- [ ] **Step 5: Commit** — `git commit -m "feat(threat): the families speak, the doctor knows, the screenshots prove it"`

---

## Self-review against the spec

| Spec item | Covered by |
|---|---|
| §3 mob max health ×(1+1.0·scaledGroup), shared, at spawn | Task 3 |
| §3 elite promotion 25%, names/abilities/loot | Task 4 |
| §3 escorts 30%, all four hard bounds | Task 5 |
| §3 detection toward the 1.0 clamp, per-player | Task 6 |
| §3 rewards: elites drop better | Task 4 (re-roll + bounty tag) |
| §2.3 TTK raise-only, now fed | Task 6 |
| §3a per-family voice on the Deeds page | Task 7 ✅ |
| §4 group resolution, strongest, 128 blocks, +45% cap, AFK-farm rule | Task 2 |
| §6 `maxHealthBonus`/`eliteChance`/`escortChance`/`groupScaling`/`dimensionMultiplier` | Task 1 |
| §11 ENTITY_LOAD early-outs, idempotence, health top-up, elite persistence | Tasks 3–4 |
| §12 gametest: scaled mob, named elite, screenshots | Task 7 ✅ |
| §12 doctor: caps within clamps | Task 7 ✅ |

**Explicitly deferred to phase 3:** species replacement (`replacementChance`), the tag mechanism and
the boss carve-out, the family tags. **Not in any phase (spec non-goals):** spawn-rule changes, boss
scaling, PvP scaling.

**Known honest limits:** elite abilities, escorts and detection are Minecraft-bound — unit tests
cover their pure cores (selection, budget, TTK decision) and the gametest covers presence; behaviour
richness is verified in play. The `289` mob-cap constant is a hardcoded package-private vanilla
value — commented at the definition and sanity-checked by the doctor.
