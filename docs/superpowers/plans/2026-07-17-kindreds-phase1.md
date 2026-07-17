# Kindreds of Middle-earth — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Phase 1 foundation of Kindreds of Middle-earth — a data-driven, server-authoritative per-race skill-tree engine with activity-based progression, a lore-themed tree UI, a Vision framework (Elf Keen-sight + Dwarf Stone-sense), full config/MP/death systems, and a branch-structured first tree for all 8 races — playable and balanced.

**Architecture:** Fabric 1.21.8 **client+server** mod. Server-authoritative: progression/unlock/ability logic on the server; client renders UI + vision and sends intents via packets. Content (disciplines, trees, nodes, abilities, themes) is defined in **JSON via synced dynamic registries**. Per-player state uses the **Data Attachment API**. Pure logic is unit-tested (JUnit); MC-bound code is compile-checked + play-tested.

**Tech Stack:** Java 21, Fabric Loom `1.11-SNAPSHOT`, Yarn `1.21.8+build.1`, Fabric Loader `0.16.14`, Fabric API `0.130.0+1.21.8`, Gson, JUnit 5. Soft interop with base mod `middle-earth` / `sevenstars-api`.

## Global Constraints
- **Minecraft 1.21.8** (pinned — render API changes at 1.21.9). **Java 21.** **Environment `"*"`** (client+server).
- **Mod id `kindreds`; base package `com.kindreds`.**
- **Server-authoritative:** all progression/unlock/ability logic runs server-side; client sends intents (open/unlock/activate/vision) as packets and renders only.
- **Data-driven:** disciplines/trees/nodes/abilities/themes are JSON in synced dynamic registries. Adding content = editing data.
- **Phase-1 disciplines (exact ids):** `kindreds:combat, kindreds:archery, kindreds:mining, kindreds:stealth, kindreds:smithing, kindreds:survival, kindreds:lore`.
- **8 races (base-mod ids):** `middle-earth:{elf,human,dwarf,hobbit,orc,uruk,snaga,goblin}`.
- **Gradle daemon rule:** run gradle as a single foreground `--no-daemon --console=plain` invocation; never background/concurrent (avoids the Loom lock deadlock seen previously). Do NOT run `runClient` in automation — in-game checks are user-run.
- **Forward-compat:** `AbilityDef` is sealed/extensible; `KindredData` reserves `titles[]`, `corruption`, `codexEntries[]`; effect `when` context parsed now. Don't design around these — design against interfaces.
- Full spec: `docs/superpowers/specs/2026-07-17-kindreds-phase1-design.md`. Reuse patterns from the shipped Mithril mod (`C:/Users/basma/Desktop/middle_earth`): gradle setup, `HudElementRegistry`, `DataAttachment`, `CustomPayload` networking, the NO_DEPTH_TEST see-through `RenderLayer`.

## File Structure
Per spec §3 (`com.kindreds.{data,progression,playerdata,ability,vision,client.screen,network,config,command,api}`). Each file one responsibility; small and focused.

---

### Task 1: Project scaffold (client+server, builds, loads)

**Files:** Create `settings.gradle`, `gradle.properties`, `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, `src/main/resources/fabric.mod.json`, `src/main/resources/kindreds.mixins.json`, `src/main/resources/kindreds.client.mixins.json`, `src/main/resources/assets/kindreds/lang/en_us.json`, `src/main/java/com/kindreds/Kindreds.java`, `src/main/java/com/kindreds/KindredsClient.java`.

**Interfaces:** Produces `Kindreds` (`ModInitializer`, `MOD_ID="kindreds"`, `LOGGER`) and `KindredsClient` (`ClientModInitializer`).

- [ ] **Step 1: `gradle.properties`** (verified working on this machine's JDK 23 via the Mithril build)
```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
minecraft_version=1.21.8
yarn_mappings=1.21.8+build.1
loader_version=0.16.14
loom_version=1.11-SNAPSHOT
mod_version=0.1.0
maven_group=com.kindreds
archives_base_name=kindreds
fabric_version=0.130.0+1.21.8
```

- [ ] **Step 2: `settings.gradle`**
```groovy
pluginManagement { repositories { maven { name='Fabric'; url='https://maven.fabricmc.net/' }; gradlePluginPortal() } }
plugins { id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0' }
rootProject.name = 'kindreds'
```

- [ ] **Step 3: `build.gradle`** — note the base-mod jars are added `modCompileOnly` from the instance for race interop (Task 8).
```groovy
plugins { id 'fabric-loom' version "${loom_version}"; id 'java' }
version = project.mod_version; group = project.maven_group
base { archivesName = project.archives_base_name }
repositories { mavenCentral() }
def instMods = "C:/Users/basma/curseforge/minecraft/Instances/Middle-earth - Age of Adventure/mods"
dependencies {
    minecraft "com.mojang:minecraft:${minecraft_version}"
    mappings "net.fabricmc:yarn:${yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${fabric_version}"
    // Compile-only interop with the base Middle-earth mod (present in the instance, not on Maven):
    modCompileOnly files("${instMods}/SevenStarsAPI-1.0.0-1.21.8-beta.jar")
    modCompileOnly files("${instMods}/Middle-earth-1.0.0-1.21.8-beta.jar")
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
}
test { useJUnitPlatform() }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) }; withSourcesJar() }
tasks.withType(JavaCompile).configureEach { options.release = 21 }
processResources { inputs.property "version", project.version; filesMatching("fabric.mod.json") { expand "version": project.version } }
```

- [ ] **Step 4: `gradle/wrapper/gradle-wrapper.properties`** → Gradle **8.14** (runs on JDK 23). Bootstrap the wrapper exactly as the Mithril mod did (download the 8.14 distribution to scratchpad, run `gradle wrapper --gradle-version 8.14`), since `gradle` isn't on PATH.

- [ ] **Step 5: `fabric.mod.json`**
```json
{ "schemaVersion": 1, "id": "kindreds", "version": "${version}", "name": "Kindreds of Middle-earth",
  "environment": "*",
  "entrypoints": { "main": ["com.kindreds.Kindreds"], "client": ["com.kindreds.KindredsClient"] },
  "mixins": ["kindreds.mixins.json", {"config":"kindreds.client.mixins.json","environment":"client"}],
  "depends": { "fabricloader": ">=0.16.0", "minecraft": "~1.21.8", "java": ">=21", "fabric-api": "*" },
  "suggests": { "middle-earth": "*" } }
```

- [ ] **Step 6:** empty mixin configs (`kindreds.mixins.json` package `com.kindreds.mixin`, `kindreds.client.mixins.json` package `com.kindreds.mixin.client`, both `compatibilityLevel: JAVA_21`, empty arrays). Lang file with mod name + a `key.category.kindreds`.

- [ ] **Step 7:** `Kindreds.java` (`onInitialize` logs `"[Kindreds] initialized"`) and `KindredsClient.java` (`onInitializeClient` logs).

- [ ] **Step 8:** Build: `./gradlew build --no-daemon --console=plain` → `BUILD SUCCESSFUL`, jar in `build/libs/`.

- [ ] **Step 9:** Commit `feat: scaffold Kindreds Fabric 1.21.8 client+server mod`.

---

### Task 2: Server config (death penalty, rates, presets)

**Files:** Create `src/main/java/com/kindreds/config/KindredsConfig.java`, `config/DeathPenalty.java`; Test `src/test/java/com/kindreds/config/KindredsConfigTest.java`.

**Interfaces:** Produces `KindredsConfig` (fields below; `static load(Path)`, `save(Path)`, `applyPreset(String)`), enum `DeathPenalty { KEEP, LOSE_UNSPENT, LOSE_PERCENT, HARDCORE }`, and `int pointsLostOnDeath(int unspent, double totalProgress)` pure helper.

- [ ] **Step 1: Failing test** — defaults, round-trip, preset, and death math.
```java
package com.kindreds.config;
import org.junit.jupiter.api.*; import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*; import static org.junit.jupiter.api.Assertions.*;
class KindredsConfigTest {
  @Test void defaultsRoundTripAndPreset(@TempDir Path dir) {
    Path f = dir.resolve("k.json");
    KindredsConfig c = KindredsConfig.load(f);
    assertEquals(DeathPenalty.KEEP, c.deathPenalty);
    assertTrue(c.enableVision); assertEquals(1.0, c.xpRateGlobal, 1e-9);
    assertTrue(Files.exists(f));
    c.applyPreset("legendary");
    assertEquals(DeathPenalty.LOSE_PERCENT, c.deathPenalty);
    c.save(f);
    assertEquals(DeathPenalty.LOSE_PERCENT, KindredsConfig.load(f).deathPenalty);
  }
  @Test void deathMath() {
    KindredsConfig c = new KindredsConfig();
    c.deathPenalty = DeathPenalty.LOSE_UNSPENT;
    assertEquals(5, c.pointsLostOnDeath(5, 100.0));
    c.deathPenalty = DeathPenalty.KEEP;
    assertEquals(0, c.pointsLostOnDeath(5, 100.0));
  }
}
```
- [ ] **Step 2:** Run `./gradlew test --no-daemon --console=plain --tests "*KindredsConfigTest*"` → FAIL (missing classes).
- [ ] **Step 3:** Implement `DeathPenalty` enum and `KindredsConfig` (Gson pretty; fields: `DeathPenalty deathPenalty=KEEP; double deathPercent=0.25; double xpRateGlobal=1.0; int pointSoftCap=60; String respecItem="minecraft:amethyst_shard"; int respecCost=1; boolean enableVision=true; boolean enableCurses=true; boolean allowCrossTraining=true; boolean enableEnemyScaling=false;`). `load` returns defaults + writes when absent, tolerant of parse errors. `applyPreset("casual"|"normal"|"legendary")` sets a bundle. `pointsLostOnDeath` implements KEEP→0, LOSE_UNSPENT→unspent, LOSE_PERCENT→round(totalProgress*deathPercent) mapped to points, HARDCORE→Integer.MAX_VALUE (sentinel "wipe").
- [ ] **Step 4:** Run test → PASS.
- [ ] **Step 5:** Commit `feat: server config with death-penalty + presets (tested)`.

---

### Task 3: Data model, codecs & synced registries

**Files:** Create `data/Discipline.java`, `data/SkillTree.java`, `data/SkillNode.java`, `data/Theme.java`, `data/ability/AbilityDef.java` (sealed) + subtypes `AttributeMod.java`, `StatusEffectDef.java`, `VisionUnlock.java`, `ActiveAbilityDef.java`, `CurseDef.java`, `data/KindredsRegistries.java`; resources `data/kindreds/discipline/*.json` (7), `data/kindreds/skill_tree/{elf,dwarf}.json` (stubs), `data/kindreds/theme/{elf,dwarf}.json`. Test `src/test/java/com/kindreds/data/CodecRoundTripTest.java`.

**Interfaces:** Produces record types with public `Codec<T>` fields (`Discipline.CODEC`, `SkillTree.CODEC`, `SkillNode.CODEC`, `AbilityDef.CODEC` dispatch by `type`, `Theme.CODEC`), and `KindredsRegistries` with `RegistryKey<Registry<Discipline>> DISCIPLINE` and `RegistryKey<Registry<SkillTree>> SKILL_TREE`, registered via `DynamicRegistries.registerSynced(KEY, CODEC)` in a `register()` called from `Kindreds.onInitialize`.

- [ ] **Step 1: Failing test** — codec round-trips for a Discipline and a small SkillTree with one AttributeMod node.
```java
package com.kindreds.data;
import com.mojang.serialization.JsonOps; import com.google.gson.*; import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CodecRoundTripTest {
  @Test void disciplineRoundTrips() {
    Discipline d = new Discipline("Archery", 0x8FBC6C);
    JsonElement j = Discipline.CODEC.encodeStart(JsonOps.INSTANCE, d).result().orElseThrow();
    Discipline back = Discipline.CODEC.parse(JsonOps.INSTANCE, j).result().orElseThrow();
    assertEquals(d, back);
  }
  // + a SkillTree with one node holding an AttributeMod ability round-trips (assert node id + ability type survive).
}
```
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the records + Codecs. `AbilityDef` is a `sealed interface` with a `Codec` that dispatches on a `type` string field (`"attribute"|"status_effect"|"vision_unlock"|"active"|"curse"`) via `Codec.STRING.dispatch(...)` or a `MapCodec` registry — keep it extensible (new types add without editing consumers). `SkillNode` = `{id, tier, pos(int[2]), cost(discipline id + points), prereqs(List<String>), abilities(List<AbilityDef>), deedAdvancement(Optional<Identifier>), exclusiveGroup(Optional<String>)}`. `SkillTree` = `{race(Identifier), theme(Identifier), nodes(List<SkillNode>)}`. Verify exact 1.21.8 Codec/RegistryKey/DynamicRegistries names against decompiled sources (reuse the base mod's `DynamicRegistriesME` pattern — it's in the scratchpad decompile).
- [ ] **Step 4:** Write the 7 discipline JSONs + minimal elf/dwarf tree+theme JSON stubs (real trees authored in Task 12).
- [ ] **Step 5:** Run test → PASS; `./gradlew build --no-daemon` → BUILD SUCCESSFUL (registries load).
- [ ] **Step 6:** Commit `feat: data model, codecs, synced registries (tested)`.

---

### Task 4: Per-player data (Data Attachment) + sync

**Files:** Create `playerdata/KindredData.java`, `playerdata/KindredAttachment.java`, `network/SyncKindredDataS2C.java`; Test `src/test/java/com/kindreds/playerdata/KindredDataTest.java`.

**Interfaces:** Produces `KindredData` (mutable: `Object2IntMap<Identifier> disciplineXp`, `Set<String> unlockedNodes`, `Identifier activeVisionLens`, `Set<String> titles`, `int corruption`, `Object2LongMap<String> cooldowns`; `Codec`/`PacketCodec`; pure helpers `addXp`, `pointsIn(disciplineLevelCurve)`, `hasNode`). `KindredAttachment.TYPE` (`AttachmentRegistry.createPersistent(...).syncWith(SyncKindredDataS2C...)` or manual S2C), and `KindredAttachment.get(player)` / `set(player,data)`. `SyncKindredDataS2C` payload.

- [ ] **Step 1: Failing test** (pure `KindredData` — no MC bootstrap needed; use plain fields):
```java
// addXp accumulates per discipline; hasNode reflects unlockedNodes; codec/paccket round-trip of a populated KindredData.
```
- [ ] **Step 2:** FAIL. **Step 3:** Implement `KindredData` (+ Codec + PacketCodec). `KindredAttachment` uses `AttachmentRegistry.createPersistent(Identifier.of("kindreds","player"), KindredData.CODEC)`; `copyOnDeath` is applied conditionally (Task 11 reads config; default builder without copyOnDeath, we copy manually per config). Register `SyncKindredDataS2C` (`PayloadTypeRegistry.playS2C().register(...)`, `ServerPlayNetworking.send`). Verify Attachment API names against fabric-api 0.130.0.
- [ ] **Step 4:** PASS + build. **Step 5:** Commit `feat: player skill data attachment + sync (tested)`.

---

### Task 5: Progression engine (XP→points, race-scaling) — pure, TDD

**Files:** Create `progression/ProgressionService.java`, `progression/LevelCurve.java`, `progression/RaceScaling.java`; Test `src/test/java/com/kindreds/progression/ProgressionTest.java`.

**Interfaces:** Produces `LevelCurve.levelForXp(long xp)`, `xpForLevel(int lvl)`; `RaceScaling.multiplier(Identifier race, Identifier discipline)` (data-backed map, default 1.0); `ProgressionService.awardXp(KindredData, Identifier race, Identifier discipline, long baseXp, double globalRate)` (applies race scaling + global rate, mutates data) and `int pointsAvailable(KindredData, Identifier discipline)` / `int pointsSpent(...)`.

- [ ] **Step 1: Failing test** — curve monotonic & invertible; race scaling (Elf archery ×1.5, mining ×0.6); awardXp respects scaling+rate; points = level − spent.
- [ ] **Step 2:** FAIL. **Step 3:** Implement (a simple rising curve, e.g. `xpForLevel = base*lvl^1.5`; RaceScaling loaded from a `data/kindreds/race_scaling/*.json` or a hardcoded default table for P1 with a data override hook). **Step 4:** PASS. **Step 5:** Commit `feat: progression engine + race scaling (tested)`.

---

### Task 6: Unlock rules + ability application

**Files:** Create `progression/UnlockService.java`, `ability/AbilityApplier.java`, `ability/CurseService.java`, `network/RequestUnlockC2S.java`, `network/UnlockResultS2C.java`; Test `src/test/java/com/kindreds/progression/UnlockServiceTest.java`.

**Interfaces:** Produces `UnlockService.canUnlock(KindredData, SkillTree, nodeId, availablePointsFn, deedFn) -> UnlockResult(ok|reason)` (checks points-in-discipline, all prereqs owned, exclusiveGroup free, deedAdvancement earned) and `applyUnlock(...)` (mutates data). `AbilityApplier.apply(ServerPlayerEntity, AbilityDef, nodeId)` / `removeAll(player, nodeId)` (AttributeModifier keyed by node id; status effects; curses via `CurseService`).

- [ ] **Step 1: Failing test** (pure `canUnlock`): insufficient points → fail; missing prereq → fail; exclusive conflict → fail; deed not earned → fail; all satisfied → ok. Use fake point/deed suppliers.
- [ ] **Step 2:** FAIL. **Step 3:** Implement `UnlockService` (pure rules) + `AbilityApplier` (MC-bound: `player.getAttributeInstance(attr).addPersistentModifier(new EntityAttributeModifier(Identifier "kindreds:node/<id>", amount, op))`; verify 1.21.8 attribute-modifier API). Wire `RequestUnlockC2S` → server validates via UnlockService → applies → `SyncKindredDataS2C`.
- [ ] **Step 4:** PASS + build. **Step 5:** Commit `feat: unlock rules + ability application (rules tested)`.

---

### Task 7: Activity hooks (earn by doing)

**Files:** Create `progression/ActivityHooks.java` (+ any mixins under `com.kindreds.mixin` for events not covered by fabric-api events). Modify `Kindreds.java` (register).

**Interfaces:** `ActivityHooks.register()` wires: `PlayerBlockBreakEvents.AFTER`→Mining (scaled by block hardness); bow-release/arrow-hit→Archery; `ServerLivingEntityEvents`/attack→Combat; sneak-tick + sneak-kill→Stealth; crafting/smithing result→Smithing; `eat`/biome-change/distance→Survival; advancement grant→Lore. Each calls `ProgressionService.awardXp` with the player's race (Task 8) + config rate; then `SyncKindredDataS2C`.

- [ ] Acceptance: **compile clean**; in-game (user): mining/shooting/etc. increases the right discipline XP at race-scaled rates (verify via `/kindreds inspect`). No unit test (MC events). Commit.

---

### Task 8: RaceAccess — read the base-mod race (de-risk early)

**Files:** Create `playerdata/RaceAccess.java` (+ optional `mixin/MiddleEarthRaceMirrorMixin.java`).

**Interfaces:** `RaceAccess.getRace(ServerPlayerEntity) -> Optional<Identifier>` (a `middle-earth:*` race id).

- [ ] **Step 1:** Implement primary path against the decompiled base-mod API (scratchpad: `net.sevenstars.middleearth.resources.persistent_datas.PlayerDataService` / `StateSaverAndLoader.getPlayerState(player).getRace()`), compiled via the `modCompileOnly` jars from Task 1. Guard all calls in try/catch (the base mod may be absent → return `Optional.empty()`).
- [ ] **Step 2:** If the base-mod fields are inaccessible, add a client/server mixin or reflection fallback that mirrors the race into our `KindredData` when the base mod syncs it. Document what worked.
- [ ] Acceptance: compile clean; in-game (user): `/kindreds inspect` shows the player's Middle-earth race. Commit `feat: read base-mod race (RaceAccess)`.

---

### Task 9: `/kindreds` command (inspect/admin) + active abilities

**Files:** Create `command/KindredsCommand.java`, `ability/ActiveAbilityService.java`, `network/ActivateAbilityC2S.java`; Modify `KindredsClient` (active-ability keybinds).

**Interfaces:** `/kindreds inspect [player]` (show race, disciplines/points, unlocked nodes), `/kindreds grantxp <discipline> <amount>`, `/kindreds reload` (config), `/kindreds respec` (op). `ActiveAbilityService.activate(player, abilityId)` (check cooldown/cost from `KindredData`, apply effect, set cooldown).

- [ ] Acceptance: compile + in-game. Commit.

---

### Task 10: Vision framework + Stone-sense + Keen-sight

**Files:** Create `vision/VisionManager.java`, `vision/SeeThroughLayer.java`, `vision/lens/StoneSenseLens.java`, `vision/lens/KeenSightLens.java`, `vision/overlay/HudTintOverlay.java`, `network/SetVisionLensC2S.java`; Modify `KindredsClient` (register keybind + world-render + hud).

**Interfaces:** `VisionManager` holds the client's active lens (one at a time), a toggle keybind (cycle among unlocked lenses), and `boolean irisActive()` detection. `SeeThroughLayer` = the Mithril NO_DEPTH_TEST line layer (copy `LocatorRenderLayers`). `StoneSenseLens.render(WorldRenderContext)` outlines target ore/cavity blocks in a radius through rock (reuse Mithril scanner+layer); `KeenSightLens.render(...)` outlines nearby living entities tinted by faction (friend blue/foe red via base-mod diplomacy) + a gamma lift. `HudTintOverlay` = `HudElementRegistry` full-screen tint (for later lenses; Iris-safe).

- [ ] Acceptance: compile clean; **detect Iris and disable outline lenses gracefully** when a shaderpack is active (per `research/lore/vision-rendering-tech-1.21.8.md`); register pipeline once; frustum+distance cull. In-game (user): equip Dwarf → Stone-sense shows ore through rock; Elf → Keen-sight outlines mobs; toggling swaps the single active lens; works without Iris, degrades with. Commit `feat: vision framework + stone-sense + keen-sight`.

---

### Task 11: The lore-themed tree UI

**Files:** Create `client/screen/SkillTreeScreen.java`, `client/screen/TreeRenderer.java`, `client/screen/NodeTooltip.java`, `client/screen/ThemeAssets.java`, `network/OpenTreeC2S.java`, `network/RespecC2S.java`; assets `assets/kindreds/textures/gui/**` (placeholder themed art for elf/dwarf: background, frame, node states, discipline icons); Modify `KindredsClient` (open keybind).

**Interfaces:** `SkillTreeScreen(SkillTree tree, KindredData data, Theme theme)` — a `Screen` with: pannable (drag) / zoomable (scroll) node **canvas** (left ~75%) drawing the themed background/frame, edges (theme edge_style), and nodes in `locked/available/owned` states from `ThemeAssets`; a **side panel** (right ~25%) with race crest, 7 discipline gauges (points spent/available), active vision lens, titles, and a **respec** button (consumes config `respecItem`); `NodeTooltip` renders a lore-flavored ability card on hover; clicking an available node sends `RequestUnlockC2S`. Open via keybind → `OpenTreeC2S` → server replies `SyncKindredDataS2C` → client opens the screen. All draws via `DrawContext` (verify 1.21.8 signatures against the Mithril HUD work: `Matrix3x2fStack`, `drawTexture(RenderPipeline,...)`, `fill`, `fillGradient`, `drawText`).

- [ ] Acceptance: compile clean. In-game (user): press keybind → themed tree opens (Elf starlit / Dwarf stone), pan+zoom work, node states/tooltips correct, clicking an available node unlocks it (stat changes apply), discipline gauges + respec work, screen is readable and *not* a default grid. This is the big client task — split into sub-commits (canvas+pan/zoom; theming; nodes+tooltips; side panel+respec; network wiring) if helpful. Commit(s) `feat: lore-themed skill-tree screen`.

---

### Task 12: Author the 8 race trees + themes (data)

**Files:** Create/replace `data/kindreds/skill_tree/{elf,dwarf,human,hobbit,orc,uruk,snaga,goblin}.json` (map to `middle-earth:*` races), `data/kindreds/theme/*.json`, `data/kindreds/race_scaling/*.json`, and the themed art referenced.

- [ ] Author **Elf** and **Dwarf** trees exactly per spec §12 (trunk + 2–3 branches, tiered deed-capstones). Author the other 6 from the branch sketches in `research/lore/{elves-and-men,dwarves-and-hobbits,evil-races}.md` (each: trunk with signature vision, a curse, 2–3 branches, deed-gated capstones; Rohan mounted-conditional; Orc/Goblin/Snaga carry contextual Dread-of-the-Sun; Uruk Sun-Defiance). Set race_scaling per race.
- [ ] Acceptance: registries load all 8 trees; in-game (user): each race, when selected, gets its themed tree with working buffs/debuffs/vision. Commit `feat: author 8 race first-trees + themes`.

---

### Task 13: Death penalty, MP validation, polish & deploy

**Files:** Create `playerdata/DeathHandler.java`; Modify `Kindreds.java`.

- [ ] Wire `ServerPlayerEvents.COPY_FROM`/`AFTER_RESPAWN` to apply `KindredsConfig.deathPenalty` (KEEP copies data; LOSE_* / HARDCORE mutate per `pointsLostOnDeath`). Verify config presets end-to-end.
- [ ] `./gradlew clean test build --no-daemon` → all tests pass, BUILD SUCCESSFUL. Deploy jar to the instance `mods/`. In-game (user, incl. a 2nd player for MP): independent per-player data; death penalty behaves per config; race read works; `/spark` shows no notable cost. Write `README.md`. Commit `docs: readme; deploy; final Phase-1 verification`.

---

## Self-Review
**Spec coverage:** engine/data (T3), progression (T5), unlock+abilities (T6), activity (T7), player-data/sync (T4), race read (T8), actives+command (T9), vision framework+2 lenses (T10), themed UX (T11), 8 race trees (T12), config/death/MP (T2/T13). All spec §1 in-scope items map to tasks.
**Placeholder scan:** the "verify against decompiled 1.21.8" notes (T3/T4/T6/T11) are real API-verification steps (as in the Mithril build), not deferred work; tree *content* authoring (T12) is data guided by the cited lore docs, not vague. No TBD gaps.
**Type consistency:** `KindredData` fields (disciplineXp/unlockedNodes/activeVisionLens/titles/corruption/cooldowns) consistent across T4/T5/T6/T9/T13; `AbilityDef` sealed subtypes consistent T3/T6/T10; discipline ids + race ids per Global Constraints; `ProgressionService.awardXp` / `UnlockService.canUnlock` signatures stable across their consumers.
