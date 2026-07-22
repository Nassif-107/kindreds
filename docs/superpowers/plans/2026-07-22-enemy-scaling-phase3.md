# Enemy Scaling — Phase 3: Composition — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change what a player *meets*, not just how it behaves: the tag mechanism replaces the
hardcoded scope gate (bosses finally carved out, datapack-extensible scope and families), and the
species-replacement ladder swaps an arriving mob for a tougher member of its own family — a zombie
for a warg, a warg for a cave troll — lore-true and spawner-untouched.

**Architecture:** `ScaledFamilies` owns the tag lookups and the ladder table; `MobDanger` re-routes
`family()` and the scope checks through it (string-match fallback retained for un-tagged mobs);
`MobScaler` gains the replacement step between health scaling and the elite roll. The orc-kin ladder
rides the base mod's own public `NpcEntityBuilder`, behind the established bridge-isolation pattern.

**Tech Stack:** Fabric 1.21.8, yarn mappings, Java 21, JUnit 5, datapack entity-type tags, Fabric
client gametests.

**Spec:** `docs/superpowers/specs/2026-07-21-enemy-scaling-design.md` §5.1 (scope as tags), §5.2
(families and ladders), §3 (species replacement row), §12 (testing, doctor). Prerequisite: **phase 2
is merged and play-verified** — replacement reads `MobMark`, `scaledGroupAt`, and `MobScaler`'s
handler structure.

## Global Constraints

- **Replacement changes how hard a family is delivered, never what a region offers** (spec §2.5 hard
  constraint): the replacement chance derives from `scaledGroup` ONLY. Per-family competence must
  never feed composition — a world that stopped sending wargs because you got good at wargs would
  feel arranged. This is an anti-design-rot invariant with its own test.
- **Replacement stays inside the family** (spec §3): a mob may only be swapped for a tougher member
  of its own family ladder, one rung at a time, and never when the replacement would not fit the
  space.
- **Never touch the spawner** (spec §10). Replacement discards the arriving mob and spawns its
  substitute at the same position — the spawner's where/when/how-often decisions are untouched.
- **A setting is exposed only if no value of it breaks an invariant.** Phase 3 exposes:
  `replacementChance` (35, %; 0 disables). Internal: the ladders themselves, the one-rung rule, the
  space-fit rule.
- **Tags are the extension surface** (spec §5.1): `kindreds:never_scaled` (bosses, tamed handled in
  code), `kindreds:scaled_extra`, and `kindreds:family/<name>`. All base-mod entries use
  `{"id": "...", "required": false}` so a missing base mod never breaks tag loading.
- **Base-mod isolation:** every `net.sevenstars.*` reference lives in a bridge guarded by
  `isModLoaded("middle-earth")` + `catch (Throwable)` — the established pattern. Base mod absent →
  the vanilla ladders still work; the base-mod rungs and the orc-kin ladder degrade to no-ops.
- **Localized in BOTH lang files**; `%%` for a literal percent; doctor stays dev-English.
- **API facts verified against the real jars — use these, not memory:**
  - Entity-type tag files: `data/kindreds/tags/entity_type/<name>.json`, shape
    `{"values": ["minecraft:zombie", {"id": "middle-earth:warg", "required": false}]}` — the
    optional-entry object form is real (`TagEntry.createOptional`).
  - Code-side membership is on the ENTITY, not the type: `entity.isIn(TagKey.of(
    RegistryKeys.ENTITY_TYPE, Identifier.of("kindreds", "never_scaled")))`.
  - Resolving a maybe-absent entity type: `Registries.ENTITY_TYPE.getOptionalValue(Identifier)`
    (verify exact yarn name — `getOrEmpty` in some versions; use whichever exists, confirmed by
    javap, and note it in the report).
  - Spawning a replacement: `EntityType.spawn(ServerWorld, Consumer<T>, BlockPos, SpawnReason,
    boolean, boolean)` — runs `initialize` (difficulty gear) and the consumer before adding. Never
    the bare `create(World, SpawnReason)`, which skips `initialize` entirely.
  - Space check: `World.isSpaceEmpty(Box)` with the REPLACEMENT type's dimensions
    (`EntityType.getDimensions()` — a cave troll must not be stuffed into a zombie-sized cave).
  - Discarding the original: `Entity.discard()`.
  - The base mod's NPC variants are all one `EntityType`; a specific variant is spawned via the
    fully PUBLIC `new NpcEntityBuilder(World, BlockPos).withNpcData(Identifier).build()` (returns
    the `NpcEntity`, does NOT add it — caller calls `world.spawnEntity(npc)`), variant data via
    `NpcDataLookup.getNpcData(World, Identifier)` /
    `getAllNpcDatasFromRace(World, List<Identifier>, Identifier)`. The concrete `NpcData` ids for
    the goblin→orc→uruk rungs are a DATAPACK-CONTENT question resolved in Task 3 by listing
    `data/middle-earth/**/npcs/*.json` from the installed jar — never guessed.
  - One untraced detail, to smoke-test in Task 3 before relying on it: whether
    `NpcEntityBuilder.build()`'s internal `tryToInitializeData()` is safe to call before the entity
    is in the world (it appeared self-contained in bytecode, but was not proven).
- Gradle: `--no-daemon` always; `--rerun-tasks` when stale; hand-sum `build/test-results/test/*.xml`;
  never rebuild while a gametest client runs. Baseline: phase 2's final count; it only goes up.
- Every anti-exploit/invariant test must be **proven to fail with its defence removed**.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/kindreds/threat/ScaledFamilies.java` | **Create.** Tag keys, family resolution (tag-first, string fallback), the ladder table, `nextRung`. |
| `src/main/resources/data/kindreds/tags/entity_type/never_scaled.json` | **Create.** Boss carve-out. |
| `src/main/resources/data/kindreds/tags/entity_type/scaled_extra.json` | **Create.** Datapack opt-in, empty by default. |
| `src/main/resources/data/kindreds/tags/entity_type/family/{trolls,spiders,wargs,orc_kin,undead}.json` | **Create.** The five families. |
| `src/main/java/com/kindreds/threat/MobDanger.java` | **Modify.** `family()` via tags; scope consults `never_scaled`/`scaled_extra`. |
| `src/main/java/com/kindreds/threat/MobScaler.java` | **Modify.** The replacement step. |
| `src/main/java/com/kindreds/threat/MiddleEarthFoes.java` + `MiddleEarthFoesBridge.java` | **Modify.** `spawnNpcVariant` for the orc-kin ladder. |
| `src/main/java/com/kindreds/config/KindredsConfig.java` + command + sync + screen | **Modify.** `replacementChance`. |
| `src/main/java/com/kindreds/command/KindredsDoctor.java` | **Modify.** Tags resolve, ladders resolve, no type in two families. |
| `src/main/resources/assets/kindreds/lang/{en_us,ru_ru}.json` | **Modify.** One settings row. |
| `src/test/java/com/kindreds/threat/ScaledFamiliesTest.java` | **Create.** Ladder + invariant tests. |

Dependency order: Task 1 (tags + scope re-route) → Task 2 (ladder + replacement) → Task 3 (orc-kin)
→ Task 4 (doctor + gametest + docs).

---

## Task 1: The tags, and the scope gate learns to read them

**Files:**
- Create: the seven tag JSON files listed above
- Create: `src/main/java/com/kindreds/threat/ScaledFamilies.java`
- Modify: `src/main/java/com/kindreds/threat/MobDanger.java`
- Test: `src/test/java/com/kindreds/threat/ScaledFamiliesTest.java` (create),
  `src/test/java/com/kindreds/threat/MobDangerTest.java` (extend)

**Interfaces:**
- Consumes: phase 1's `MobDanger.isInScope(Entity, ServerPlayerEntity)` /
  `isScalableAtSpawn(Entity)` / `family(LivingEntity)` / package-private `family(String)`.
- Produces: `ScaledFamilies.NEVER_SCALED`, `SCALED_EXTRA` (`TagKey<EntityType<?>>`),
  `ScaledFamilies.FAMILY_TAGS : Map<String, TagKey<EntityType<?>>>` (keys are exactly the five
  family names), `ScaledFamilies.familyOf(Entity) -> Optional<String>` (tag membership only),
  and the ladder surface Task 2 fills in.

Tag contents (every base-mod id as an optional entry — the exact base-mod entity ids MUST be read
from the installed jar's `assets/middle-earth/lang/en_us.json` `entity.middle-earth.*` keys /
registry rather than trusted from this plan; the names below are the audit's best knowledge and the
implementer verifies each):

```json
// never_scaled.json — spec §5.1's exclusion list, finally real. Tamed creatures and friendly NPCs
// stay code-side checks (they are per-instance, not per-type).
{ "values": ["minecraft:wither", "minecraft:ender_dragon", "minecraft:warden", "minecraft:elder_guardian"] }
```
```json
// scaled_extra.json — a server's datapack can push any entity type into scope without touching code.
{ "values": [] }
```
```json
// family/trolls.json
{ "values": [
  {"id": "middle-earth:stone_troll", "required": false},
  {"id": "middle-earth:cave_troll", "required": false},
  {"id": "middle-earth:snow_troll", "required": false},
  {"id": "middle-earth:petrified_troll", "required": false}
] }
```
```json
// family/spiders.json
{ "values": ["minecraft:spider", "minecraft:cave_spider",
  {"id": "middle-earth:shelobite_larva", "required": false},
  {"id": "middle-earth:shelobite_scuttler", "required": false},
  {"id": "middle-earth:spawn_of_shelob", "required": false}
] }
```
```json
// family/wargs.json
{ "values": ["minecraft:wolf", {"id": "middle-earth:warg", "required": false}] }
```
```json
// family/orc_kin.json
{ "values": [{"id": "middle-earth:npc", "required": false}] }
```
```json
// family/undead.json
{ "values": ["minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:skeleton",
  "minecraft:stray", "minecraft:zombie_villager", "minecraft:wither_skeleton", "minecraft:phantom"] }
```

- [ ] **Step 1: Write the failing tests.** The tag lookups need a live registry, so the unit tests
cover the pure fallback contract and the re-routed precedence logic via the string path; tag
membership itself is proven by the doctor + gametest in Task 4:

```java
package com.kindreds.threat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScaledFamiliesTest {

    @Test
    void everyFamilyTagKeyExistsUnderTheFamilyNamespace() {
        // the doctor iterates this map to prove the datapack files resolve; a missing entry here
        // means a family the ladder can name but the doctor never checks
        assertEquals(java.util.Set.of("trolls", "spiders", "wargs", "orc_kin", "undead"),
                ScaledFamilies.FAMILY_TAGS.keySet());
        ScaledFamilies.FAMILY_TAGS.forEach((name, tag) ->
                assertEquals("kindreds", tag.id().getNamespace()));
        assertEquals("family/wargs", ScaledFamilies.FAMILY_TAGS.get("wargs").id().getPath());
    }
}
```

```java
    // MobDangerTest — the fallback survives the re-route:
    @Test
    void stringFallbackStillClassifiesUntaggedMods() {
        // a third-party mod's "forest_troll" has no tag membership; the substring fallback keeps it
        assertEquals("trolls", MobDanger.family("forest_troll"));
        assertEquals("other", MobDanger.family("butterfly"));
    }
```

- [ ] **Step 2: Run to verify failure** — `ScaledFamilies` missing.

- [ ] **Step 3: Write `ScaledFamilies`** (tag surface only; ladder lands in Task 2):

```java
package com.kindreds.threat;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import java.util.Map;
import java.util.Optional;

/**
 * The datapack-facing edge of scope (spec §5.1): which types may never scale, which extra types a
 * server pulls in, and which family a type belongs to. Tags rather than code so a server datapack
 * can extend all three without a build - the hardcoded gate phase 1 shipped was always a stand-in
 * for this file.
 */
public final class ScaledFamilies {
    private ScaledFamilies() {
    }

    public static final TagKey<EntityType<?>> NEVER_SCALED = tag("never_scaled");
    public static final TagKey<EntityType<?>> SCALED_EXTRA = tag("scaled_extra");

    /** Family name -> its membership tag. Iterated by the doctor; keys are the canonical names
     * {@code MobDanger.family} already returns. */
    public static final Map<String, TagKey<EntityType<?>>> FAMILY_TAGS = Map.of(
            "trolls", tag("family/trolls"),
            "spiders", tag("family/spiders"),
            "wargs", tag("family/wargs"),
            "orc_kin", tag("family/orc_kin"),
            "undead", tag("family/undead"));

    private static TagKey<EntityType<?>> tag(String path) {
        return TagKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of("kindreds", path));
    }

    /** Tag-resolved family, or empty when no tag claims the type (the string fallback then rules). */
    public static Optional<String> familyOf(Entity entity) {
        for (Map.Entry<String, TagKey<EntityType<?>>> e : FAMILY_TAGS.entrySet()) {
            if (entity.isIn(e.getValue())) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Re-route `MobDanger`.**

```java
    /** Family, tag-first (spec §5.2) with the phase-1 substring match kept as a fallback so a
     * third-party mob with an orc-ish id still buckets sensibly. */
    public static String family(LivingEntity entity) {
        return ScaledFamilies.familyOf(entity)
                .orElseGet(() -> family(Registries.ENTITY_TYPE.getId(entity.getType()).getPath()));
    }
```

Scope, both gates — `never_scaled` is checked FIRST (a tagged boss is out even though it is a
`Monster`), `scaled_extra` adds a player-free inclusion:

```java
    public static boolean isInScope(Entity entity, ServerPlayerEntity player) {
        if (entity.isIn(ScaledFamilies.NEVER_SCALED)) {
            return false;
        }
        return entity instanceof Monster
                || entity.isIn(ScaledFamilies.SCALED_EXTRA)
                || (entity instanceof MobEntity mob && mob.getTarget() == player && !isOwnedPet(entity))
                || MiddleEarthFoes.isHostileBaseMob(entity, player);
    }

    public static boolean isScalableAtSpawn(Entity entity) {
        if (entity.isIn(ScaledFamilies.NEVER_SCALED)) {
            return false;
        }
        return entity instanceof Monster
                || entity.isIn(ScaledFamilies.SCALED_EXTRA)
                || MiddleEarthFoes.isHostileFactionMobAtSpawn(entity);
    }
```

Update `MobDanger`'s class javadoc and spec §5.1's "until the tag mechanism lands" paragraphs — the
tag mechanism has now landed; bosses are OUT of scope from this commit.

- [ ] **Step 5: Run the suite** — new tests green, all existing scope/exploit tests still green
(the phase-1 exploit suite must not notice the re-route).

- [ ] **Step 6: Commit** — `git commit -m "feat(threat): scope becomes data - tags for never, extra, and family"`

---

## Task 2: The ladder, and replacement at spawn

**Files:**
- Modify: `src/main/java/com/kindreds/threat/ScaledFamilies.java` (the ladder)
- Modify: `src/main/java/com/kindreds/threat/MobScaler.java` (the replacement step)
- Modify: `src/main/java/com/kindreds/config/KindredsConfig.java`, `KindredsCommand.java`,
  `SyncConfigS2C.java`, `KindredsSettingsScreen.java`, both lang files (`replacementChance`)
- Test: `src/test/java/com/kindreds/threat/ScaledFamiliesTest.java` (extend),
  `src/test/java/com/kindreds/config/{KindredsConfigTest,DifficultyTest}.java` (extend)

**Interfaces:**
- Consumes: `MobMark` (phase 2), `ThreatService.scaledGroupAt`, `EntityType.spawn(...)` 6-arg,
  `World.isSpaceEmpty(Box)`, `Entity.discard()`.
- Produces: `ScaledFamilies.nextRung(String family, Identifier currentType) -> Optional<Identifier>`,
  `KindredsConfig.replacementChance` (int, 35), and the `composed` guard: a replacement mob's mark is
  created with `withEscort(true)`'s sibling `withComposed(true)` — **add a `composed` boolean field
  to `MobMark`** (codec `optionalFieldOf("composed", false)`, a with-er, default false) meaning
  "this mob was placed by composition; never re-compose it".

The ladders (spec §5.2, one rung at a time, vanilla rungs always present, base-mod rungs skipped
when unresolvable):

```java
    /** family -> the rungs, weakest to strongest, by entity id. The NEXT rung of the current type
     * is the only legal replacement (spec §3: "a tougher member of its own family", singular).
     * orc_kin is deliberately absent - its variants share one EntityType and ride the base mod's
     * NpcData system instead (see the bridge's spawnNpcVariant). */
    private static final Map<String, List<Identifier>> LADDERS = Map.of(
            "trolls", List.of(Identifier.of("middle-earth", "stone_troll"),
                    Identifier.of("middle-earth", "cave_troll"),
                    Identifier.of("middle-earth", "snow_troll")),
            "spiders", List.of(Identifier.of("middle-earth", "shelobite_larva"),
                    Identifier.of("middle-earth", "shelobite_scuttler"),
                    Identifier.of("middle-earth", "spawn_of_shelob")),
            "wargs", List.of(Identifier.of("minecraft", "wolf"),
                    Identifier.of("middle-earth", "warg")),
            "undead", List.of(Identifier.of("minecraft", "zombie"),
                    Identifier.of("minecraft", "husk"),
                    Identifier.of("minecraft", "drowned")));

    /** The one rung up, or empty at the top, off the ladder, or for an unknown family. */
    public static Optional<Identifier> nextRung(String family, Identifier currentType) {
        List<Identifier> ladder = LADDERS.get(family);
        if (ladder == null) {
            return Optional.empty();
        }
        int i = ladder.indexOf(currentType);
        return (i < 0 || i == ladder.size() - 1) ? Optional.empty() : Optional.of(ladder.get(i + 1));
    }
```

- [ ] **Step 1: Write the failing tests:**

```java
    @Test
    void theLadderClimbsOneRungAndStopsAtTheTop() {
        assertEquals(Optional.of(Identifier.of("minecraft", "husk")),
                ScaledFamilies.nextRung("undead", Identifier.of("minecraft", "zombie")));
        assertEquals(Optional.of(Identifier.of("minecraft", "drowned")),
                ScaledFamilies.nextRung("undead", Identifier.of("minecraft", "husk")));
        assertEquals(Optional.empty(),
                ScaledFamilies.nextRung("undead", Identifier.of("minecraft", "drowned")),
                "the top rung never climbs");
        assertEquals(Optional.empty(),
                ScaledFamilies.nextRung("undead", Identifier.of("minecraft", "creeper")),
                "off the ladder means no replacement, not a default rung");
        assertEquals(Optional.empty(),
                ScaledFamilies.nextRung("other", Identifier.of("minecraft", "zombie")));
    }

    /** THE composition invariant (spec §2.5): per-family competence must never feed what spawns.
     * The chance function's signature is the defence - it TAKES no competence. This test pins the
     * arithmetic so the signature can't quietly grow a competence parameter without failing here.
     * Never delete as redundant. */
    @Test
    void replacementChanceDerivesFromGroupThreatAlone() {
        assertEquals(0.35f * 0.5f, MobScaler.replacementChance(0.5f, 35), 0.0001f);
        assertEquals(0f, MobScaler.replacementChance(0.8f, 0), 0.0001f, "0 disables");
    }
```

- [ ] **Step 2: Run to verify failure.**

- [ ] **Step 3: Implement.** `MobScaler.replacementChance(float scaledGroup, int configPercent)` is
the trivial pure core (`configPercent / 100f * scaledGroup`). The replacement step goes FIRST in the
`ENTITY_LOAD` handler chain — before health/elite/escort, because the substitute mob's own
`ENTITY_LOAD` will run all of those for its own body:

```java
            if (!mark.escort() && !mark.composed() && "NATURAL".equals(mark.spawnReason())
                    && world.getRandom().nextFloat() < replacementChance(scaledGroup, Kindreds.CONFIG.replacementChance)) {
                if (tryReplace(mob, world)) {
                    return;   // the original is gone; its substitute takes it from here
                }
            }
```

```java
    /** Swap the arriving mob for the next rung of its own family ladder - same position, spawner
     * untouched. False when there is no rung, the type is not installed (base mod absent), or the
     * bigger body would not fit the space (a cave troll is never stuffed into a zombie's cave). */
    private static boolean tryReplace(MobEntity mob, ServerWorld world) {
        String family = MobDanger.family(mob);
        Optional<Identifier> rung = ScaledFamilies.nextRung(family,
                Registries.ENTITY_TYPE.getId(mob.getType()));
        if (rung.isEmpty()) {
            return false;
        }
        Optional<EntityType<?>> type = Registries.ENTITY_TYPE.getOptionalValue(rung.get());
        if (type.isEmpty()) {
            return false;                            // base-mod rung, base mod absent
        }
        BlockPos pos = mob.getBlockPos();
        Box body = type.get().getDimensions().getBoxAt(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (!world.isSpaceEmpty(body)) {
            return false;
        }
        Entity spawned = type.get().spawn(world,
                e -> MobMark.set(e, MobMark.of(e).withComposed(true)),
                pos, SpawnReason.NATURAL, false, false);
        if (spawned == null) {
            return false;
        }
        mob.discard();
        return true;
    }
```

(Verify `Registries.ENTITY_TYPE.getOptionalValue` vs `getOrEmpty` and
`EntityDimensions.getBoxAt(double,double,double)` with javap before use — note which names were real
in the report. The generic capture on `EntityType<?>.spawn`'s consumer needs a helper method with a
type parameter if the wildcard fights you — do that rather than raw-typing.)

Config: `replacementChance = 35`, validated `parsePercent(value, 0, 100)`, command keys, `View` row,
screen row, lang both files:

| Key | en_us | ru_ru |
|---|---|---|
| `kindreds.settings.replacementChance` | `Grimmer kin take their place` | `Их место занимают мрачные сородичи` |

`DifficultyTest`: add `replacementChance` to the sentinel + untouched assertions. `MobMarkTest`: the
codec round-trip gains the `composed` field.

- [ ] **Step 4: Prove the invariant test discriminates.** Temporarily thread
`state`-free `scaledGroup * competence`-style corruption into `replacementChance` (multiply by 0.9f)
and watch `replacementChanceDerivesFromGroupThreatAlone` fail; restore; both outputs in the report.

- [ ] **Step 5: Run the suite** — hand-summed, zero failures; `compileJava` green.

- [ ] **Step 6: Commit** — `git commit -m "feat(threat): grimmer kin - the family ladder replaces, one rung, spawner untouched"`

---

## Task 3: The orc-kin ladder rides NpcData

**Files:**
- Modify: `src/main/java/com/kindreds/threat/MiddleEarthFoesBridge.java` + `MiddleEarthFoes.java`
- Modify: `src/main/java/com/kindreds/threat/MobScaler.java` (orc-kin branch in `tryReplace`)
- Modify: `src/main/java/com/kindreds/threat/ScaledFamilies.java` (the NPC rank ladder)
- Test: `src/test/java/com/kindreds/threat/ScaledFamiliesTest.java` (extend)

**Interfaces:**
- Consumes: the base mod's `NpcEntityBuilder(World, BlockPos).withNpcData(Identifier).build()` +
  `world.spawnEntity(npc)`, `NpcEntity.getNpcData`-equivalent (the data holder's id — the bridge
  reads the CURRENT variant id off the arriving NPC via `NpcEntityDataHolder`; verify the public
  accessor with javap: the audit saw `getFactionId()` on the holder — find the sibling npc-data id
  accessor the same way).
- Produces: `MiddleEarthFoes.replaceNpcWithVariant(MobEntity current, ServerWorld world) ->
  boolean` (guarded; reads the arriving NPC's current npc-data id, resolves the next rung via
  `ScaledFamilies.nextNpcRung`, spawns the variant, discards the original, marks the new one
  composed); `ScaledFamilies.nextNpcRung(Identifier currentNpcDataId) -> Optional<Identifier>`.

- [ ] **Step 1: Enumerate the real NpcData ids.** From the installed jar (READ-ONLY):
`unzip -l ".../Middle-earth-1.0.0-1.21.8-beta.jar" | grep "npcs/.*json"` then extract and read the
hostile factions' entries (mordor, isengard, moria, goblin_town, wild_goblins, hobgoblin_tribes).
Build the rank ladder from what actually exists — the expectation from the phase-1 audit is ranks
like `snaga`/`militia` → `warrior` → `veteran`/`uruk` → `captain`/`black_numenorean` per faction.
The ladder maps each rank id to the next WITHIN ITS OWN FACTION (a Mordor snaga becomes a Mordor
warrior, never an Isengard one — composition must not change allegiance). Record the discovered
table in the task report AND as the `NPC_LADDER` map's inline comments.

- [ ] **Step 2: Write the failing test** (shape only — the ids come from Step 1):

```java
    @Test
    void npcLadderClimbsWithinItsOwnFaction() {
        // ids filled in from the Step-1 enumeration; the assertion that matters:
        ScaledFamilies.NPC_LADDER.forEach((from, to) -> {
            assertEquals(factionOf(from), factionOf(to),
                    "a replacement must never change allegiance: " + from + " -> " + to);
        });
    }
    // factionOf: the faction segment of the npc-data id path, e.g. "mordor/snaga" -> "mordor" -
    // match the real id structure found in Step 1.
```

- [ ] **Step 3: Implement.** `ScaledFamilies.NPC_LADDER : Map<Identifier, Identifier>` (flat
one-rung map — simpler than nested lists and the invariant test iterates it directly).
`tryReplace` gains an orc-kin branch BEFORE the entity-type path:

```java
        if ("orc_kin".equals(family)) {
            return MiddleEarthFoes.replaceNpcWithVariant(mob, world);
        }
```

The bridge method: read the arriving NPC's current npc-data id off its data holder; look up
`nextNpcRung`; `NpcDataLookup.getNpcData(world, next)` to confirm it resolves (catch the checked
exception family per the established pattern); **smoke-test concern from the recipe:** call
`new NpcEntityBuilder(world, pos).withNpcData(next).build()` and null-check the result — if
`tryToInitializeData()` proves to need a live-world context and returns a broken entity, log once
(the `warnedOnce` latch pattern), return false, and the orc-kin ladder degrades to a no-op — the
plan's fallback, decided on evidence, not assumed. On success: set the composed mark on the new NPC,
`world.spawnEntity(npc)`, `mob.discard()`, return true.

- [ ] **Step 4: Run the suite**, `compileJava` (the bridge is compile-only verified — say so in the
report; the gametest in Task 4 cannot spawn base-mod NPCs in a vanilla test world, so the orc-kin
path's runtime proof is the user's in-game pass).

- [ ] **Step 5: Commit** — `git commit -m "feat(threat): the orc-kin ladder - rank climbs within its own banner"`

---

## Task 4: Doctor, gametest, and the docs come true

**Files:**
- Modify: `src/main/java/com/kindreds/command/KindredsDoctor.java`
- Modify: `src/main/java/com/kindreds/client/gametest/ScreenIterationTest.java`
- Modify: `docs/superpowers/specs/2026-07-21-enemy-scaling-design.md` (§5.1 — the tag mechanism has landed)
- Test: gametest run

**Interfaces:** consumes everything above; produces nothing new.

- [ ] **Step 1: Doctor checks** (dev-English, informational + problems where hard):
  - every `ScaledFamilies.FAMILY_TAGS` tag resolves against the loaded registry (report each family's
    member count; zero members for a family with a ladder = a problem line);
  - `never_scaled` is non-empty (an empty carve-out means bosses scale again — a problem);
  - no entity type appears in two family tags (iterate the registry once; overlap = problem — the
    first tag would win silently in `familyOf`);
  - every `LADDERS` rung id resolves in `Registries.ENTITY_TYPE` OR is a `middle-earth:*` id while
    the base mod is absent (report as "skipped rung", not a problem);
  - every `NPC_LADDER` target resolves via the bridge when the base mod is present;
  - `replacementChance` within [0, 100].

- [ ] **Step 2: Gametest.** In `ScreenIterationTest` (register alone while iterating, restore ALL
FIVE entrypoints before commit): set `replacementChance` to 100 via the command, `/summon
minecraft:zombie` repeatedly near the test player — COMMAND spawns don't replace (natural-only), so
instead assert the pure path in-game via the doctor's ladder lines; then the real check: run
`/kindreds doctor` and assert its output contains every family tag resolving and no problems.
Screenshot the settings screen at scales 1–4 (the new `replacementChance` row must not reintroduce
overflow — the scroll fix should absorb it; the screenshots prove it). Expect BUILD SUCCESSFUL.
*(Natural-spawn replacement itself cannot be forced deterministically in a gametest without spawner
surgery the spec forbids — it is verified in play; the gametest proves the machinery: tags resolve,
doctor green, UI intact.)*

- [ ] **Step 3: Docs.** Spec §5.1: replace the "until the tag mechanism lands" caveats — the tag is
now the gate's first check, bosses are out, `scaled_extra` is live; note the string-match fallback
retained for untagged third-party mobs. §3's replacement row: mark shipped.

- [ ] **Step 4: Full verification** — suite hand-summed, `compileJava`, both lang files parse, five
gametest entrypoints present, gametest BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** — `git commit -m "feat(threat): composition proven - tags resolve, the doctor knows, bosses stand apart"`

---

## Self-review against the spec

| Spec item | Covered by |
|---|---|
| §5.1 scope as tags, `never_scaled` (bosses out), `scaled_extra` | Task 1 |
| §5.2 family tags seeded from base-mod kinds | Task 1 |
| §5.2 replacement ladders per family | Task 2 (types), Task 3 (orc-kin) |
| §3 species replacement 35%, shared, at spawn, space-fit, lore-true | Task 2 |
| §2.5 composition never reads per-family competence | Task 2's invariant test |
| §10 spawner untouched | Tasks 2–3 (discard + same-position spawn only) |
| §12 doctor: tags resolve, ladder targets exist | Task 4 |
| §12 gametest: machinery + screenshots | Task 4 |
| §12 "mastering one family does not change what spawns" | Task 2's invariant test (chance signature) |

**Honest limits:** natural-spawn replacement and the orc-kin variant swap cannot be forced in the
gametest world (no spawner surgery, no base-mod NPCs there) — the doctor proves the data resolves and
the unit tests prove the ladder logic; the swap itself is the user's in-game verification, watched
for specifically: a zombie region at high threat should start delivering husks/drowned, a Mordor
snaga camp should start delivering warriors, and a boss fight should no longer show scaled damage.
