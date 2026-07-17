# Middle-earth (Seven Stars / Jukoz) — System Mechanics: Factions, Races, Alignment, NPC Behavior

Scope: Fabric 1.21.8, mod `net.sevenstars.middleearth` + library mod `net.sevenstars.api` (Seven Stars API). Read-only research from decompiled sources (CFR) plus the public GitHub repo `Jukoz/middle-earth` (monorepo containing `middle-earth/`, `sevenstars-api/`, and `of-beasts-and-wild-things/` as sibling Gradle subprojects; ARR-licensed but browsable). All class names below are Yarn/Mojmap intermediary names (`class_XXXX`, `method_XXXX`) as produced by the decompiler — the source repo would have real names, but the repo's data-model files were not fully renderable through the GitHub HTML view during this session, so intermediary names are quoted as found.

Paths referenced (decompiled):
- Mod: `.../scratchpad/decompiled/me-full/net/sevenstars/middleearth/**`
- API: `.../scratchpad/decompiled/sevenstars-api/net/sevenstars/api/**`

---

## 1. How the data-driven system loads

Faction/Race/NPC data is **not** a custom JSON-loader — it rides entirely on **vanilla dynamic registries**, registered through Fabric's registry-sync API.

- `net.sevenstars.middleearth.registries.DynamicRegistriesME` (extends `net.sevenstars.api.registries.DynamicRegistries`) declares the registry keys:
  - `RACE = RegistryKey.of(..., "race")`
  - `FACTION = RegistryKey.of(..., "faction")`
  - `NPC = RegistryKey.of(..., "npc")`
  - plus `STRUCTURE_MANAGER_DATA`, `BIOME_EVENT`, `STRUCTURE_EVENT`, `TEXTURE_PRESETS`, several texture-material/pattern registries, `SPIDER_VARIANTS`, `GREAT_HORN_VARIANTS`.
- `DynamicRegistriesME.register()` calls `DynamicRegistries.registerSynced(KEY, CODEC)` (Fabric API, `net.fabricmc.fabric.api.event.registry.DynamicRegistries`) for every one of these — this is what makes the registry contents (i.e. every Faction/Race/NpcData object) **automatically sync from server to client on join/reload**, the same mechanism vanilla uses for worldgen dynamic registries (biomes, etc.). No custom sync packet is needed for the data itself.
- `DynamicRegistriesME.prepareBoostrap(RegistryBuilder)` wires each key to a `*Registry.bootstrap()` method (`FactionRegistry::bootstrap`, `RaceRegistry::bootstrap`, `NpcRegistry::bootstrap`, etc.) — this is the vanilla `RegistryBuilder.add(key, bootstrapFn)` pattern used for e.g. built-in biomes.
- **Where the actual JSON comes from**: the bootstrap methods construct the *default* Faction/Race/NpcData objects in pure Java (e.g. `net.sevenstars.middleearth.registries.content.factions.FactionRegistry` builds `GONDOR`, `ROHAN`, `DALE`, `LONGBEARDS` (+ subfaction `LONGBEARDS_EREBOR`), `LOTHLORIEN`, `WOODLAND_REALM`, `MORDOR`, `HOBGOBLIN_TRIBES` (+ subfaction `..._GUNDABAD`), `GOBLIN_TOWN`, `MORIA`, `ISENGARD`, `SHIRE`, `BRIGAND`, `WILD_GOBLINS`, each from a `*FactionPool` class, e.g. `GondorFactionPool.GONDOR`). `datageneration/providers/dynamic/{FactionProvider,RaceProvider,NpcProvider}` (each a `FabricDynamicRegistryProvider`) dump the *bootstrapped* registry contents to the datapack JSON tree at build/datagen time — i.e. `data/middle-earth/middle-earth/{faction,race,npc}/*.json` are **generated artifacts of the Java bootstrap pools**, not hand-authored source of truth. A server operator/datapack author could still override/extend them at the JSON level since dynamic registries always load from `data/<namespace>/<registry path>/*.json` at world load, but upstream the pools are Java code under `registries/content/{factions,races,npcs}/pools/*.java`.
- Codecs (`RecordCodecBuilder`) define the on-disk schema for each type — see §2/§3 below for exact fields.

**Cross-references between the three data types** (all via `Identifier`/`class_2960`, resolved lazily through the dynamic registry at use-time, not eagerly linked):
- `Faction` → holds `HashMap<NpcRank, List<Identifier>> npcDatasByRank` (the `npcs.ranks[]` JSON block: each entry `{rank: "MILITIA", pool: [npc_data_id, ...]}`) — this is the faction's NPC pool per rank.
- `Faction` → `getRaces(world)` walks every NPC id in every rank, looks up each `NpcData.getRace()`, and resolves via `RaceLookup` to build the faction's race list (used for GUI, e.g. faction-selection race preview).
- `NpcData` → holds a single `race` id and a single `faction` id (`raceId`, `factionId` fields on `NpcData`), plus `base_npc_texture` (→ `TEXTURE_PRESETS` registry), `gear` (`WeightedPool<WeightedGearData>`), `npc_attributes` (per `EntityCategories`), `combat_archetype`.
- `Race` → standalone (`base_attributes`, `category_based_attributes` per `EntityCategories`, `command_join`/`command_leave`); it does not reference Faction or NpcData back.
- So the chain is **Faction → (rank → NpcData ids) → NpcData → Race id**, resolved top-down when an NPC needs to be created or gear rolled (`Faction.getRandomGear(world, rank, race)`, `Faction.getPreviewGear(...)`).

---

## 2. Player state: race + faction storage & sync

**Storage: server-only `PersistentState`, not an Attachment API.**

- `net.sevenstars.middleearth.resources.persistent_datas.PlayerData` is a plain POJO: `faction` (Identifier), `spawn` (Identifier), `race` (Identifier), `posOrigin`/`dimensionOrigin` (origin point for "return to overworld"), `delversFearCountInSeconds` (an unrelated fear-debuff counter). No reputation/affinity field exists on `PlayerData`.
- `net.sevenstars.middleearth.resources.StateSaverAndLoader` (extends `class_18` = vanilla `PersistentState`) holds `HashMap<UUID, PlayerData> players`, saved as NBT under save-data type `"middle_earth_player_datas"`, attached to the **overworld** (`server.getWorld(World.OVERWORLD).getPersistentStateManager()`). This is the classic vanilla "world SavedData keyed by UUID" pattern (like vanilla's map-item or raid data), *not* Fabric's Data Attachment API — no `AttachmentType`/`AttachmentRegistry` usage was found anywhere in the mod (`grep -ri attachment` only hits unrelated "back attachment"/"cape attachment" cosmetic model classes).
- Access is centralized through `StateSaverAndLoader.getPlayerState(PlayerEntity)` (server-side only; lazily creates a `PlayerData` on first access) and the higher-level `PlayerDataService` (get/set faction, get/set race, get/set spawn, disposition lookup, origin aggregate) and `PlayerUtil` (`fetchFaction`, `fetchSpawn`, `isOfRace`).

**Sync client↔server: no bespoke S2C "here is your faction" packet.** The flow is:
- **C2S**: `network/packets/C2S/PacketSetAffiliation` (disposition+faction+spawn strings) and `PacketSetRace` (race string) are sent from the onboarding/affiliation GUI. Server-side `process()` calls `FactionUtil.updateFaction(player, faction, spawnId)` and `RaceUtil.updateRace(player, race, true)` respectively, which write into `PlayerData` via `PlayerDataService`. There's also `PacketSetSpawnData` for changing spawn within a faction.
- **S2C**: `PacketForceOnboardingScreen` / `PacketOnboardingResult` drive the onboarding UI (booleans: `havePlayerData`, `canChangeFaction`, `canReturnToOverworld`, a teleport-confirm delay, and the player's `AttributePool` snapshot) — notably `PacketOnboardingResult` does **not** transmit the faction/race identifier itself to the client. The client doesn't need to know its own faction id for gameplay because all diplomacy/targeting math (`shouldTarget`, the two `Target*DiplomacyGoal`s) runs **server-side only**, reading `StateSaverAndLoader`/`PlayerDataService` directly on the server. The client only needs the *dynamic registry contents* (Faction/Race/NpcData objects), which arrive for free via `DynamicRegistries.registerSynced`.
- Race attribute effects: `RaceUtil.updateRace` calls `previousRace.reverseAttributes(player)` then `race.applyPlayerAttributes(player)` — an `AttributePool` diff/apply, not persisted as a live buff that needs syncing beyond vanilla attribute sync.

**Bottom line: player faction/race/spawn is 100% server-authoritative, held in a per-world PersistentState keyed by UUID; the "sync" that matters for behavior is the read-only dynamic-registry sync of Faction/Race/NpcData definitions, not the player's personal selection.**

---

## 3. Alignment → NPC behavior (the key mechanism)

### Data model
- `net.sevenstars.middleearth.resources.datas.common.DispositionType` = `GOOD | NEUTRAL | EVIL` — a coarse alignment tag on a `Faction` (and, separately, on beast entities — see below). This is largely presentational/grouping (`FactionLookup.getFactionsByDisposition` groups joinable factions by this for the faction-select GUI) and is used once in actual AI logic (beast mount check, below).
- `net.sevenstars.middleearth.resources.datas.common.AffinityLevel` = `HOSTILE | NEUTRAL | FRIENDLY | ALLY` — this is the real behavioral lever.
- `net.sevenstars.middleearth.resources.datas.factions.data.InitialDiplomacy` — a `{faction: <id>, affinity: <AffinityLevel>}` pair. Every `Faction` carries a `List<InitialDiplomacy> initialDiplomacies` (JSON field `initial_diplomacy`, a **required, non-optional** codec field — every faction must declare its full diplomacy table).
  - `InitialDiplomacy.isHostileToward(Identifier faction)` returns true **only if** `faction.equals(this.factionId) && this.affinity == AffinityLevel.HOSTILE`.
  - `Faction.isHostileToward(Identifier otherFactionId)` loops its `initialDiplomacies` and returns true if *any* entry matches that faction id as `HOSTILE`.
  - This is a **static, per-faction, hand-authored table** — e.g. `GondorFactionPool` declares Gondor `ALLY` to itself, `FRIENDLY` to Lothlorien/Rohan/Shire/Longbeards(+Erebor)/Dale, and `HOSTILE` to Hobgoblin Tribes(+Gundabad), Goblin Town, Moria, Mordor, Isengard, Wild Goblins, Brigand. Nothing in code derives this automatically from `DispositionType` (GOOD/EVIL) — it is an explicit adjacency list per faction, so it must be authored symmetrically by hand on both sides to "feel" mutual (verified Gondor's file; the reciprocal declarations live in the other factions' own pools).
  - `NEUTRAL`/`FRIENDLY`/`ALLY` are all inert for combat purposes — only `HOSTILE` does anything in code. (These other affinity levels appear to be reserved for future features — e.g. trading/quest gating — not yet consumed by combat AI.)

### The targeting pipeline (three cooperating systems)
NPCs (`net.sevenstars.middleearth.entity.npcs.NpcEntity`, a `MobEntity`/`HostileEntity`-style class) use a **mixed classic-Goal + Brain/Sensor** targeting architecture:

1. **Classic goal selector** (`NpcEntity.initGoals()` i.e. `method_5959`): registers, among others,
   - `TargetPlayerDiplomacyGoal` (priority 3) — a `ActiveTargetGoal<PlayerEntity>` subclass. Its `canStart()` override: if the mob already has a *current* target (`method_5968()`) that is a live, non-spectator, non-creative player, look up the mob's own `Faction` via `FactionLookup.getFactionById(world, mob.getFactionIdentifier())` and the target player's faction via `PlayerUtil.fetchFaction(player)`; **if the player has no faction, or the mob's faction is not hostile toward the player's faction, `canStart()` returns false** (goal is aborted before the expensive nearest-target scan runs). Otherwise falls through to vanilla `ActiveTargetGoal` behavior.
   - `TargetNPCDiplomacyGoal` (priority 4) — identical pattern but for `NpcEntity` targets: reads `mob`'s faction and the target NPC's faction and only proceeds if `currentFaction.isHostileToward(npcEntity.getFactionIdentifier())`.
   - These two goals are a **filter on top of vanilla's `ActiveTargetGoal`** — vanilla still does the actual "find nearest matching entity" scan; these goals just gate *whether that scan is allowed to keep running* based on hostile diplomacy.
2. **Brain/Sensor system** (`NpcBrain`, registers a custom sensor `SensorsME.NPC_ATTACKABLES` → `net.sevenstars.middleearth.entity.ai.brain.sensor.NpcAttackablesSensor`, a `NearestVisibleLivingEntitySensor` subclass): its `matches(world, entity, target)` calls the static predicate **`NpcEntity.shouldTarget(NpcEntity npc, LivingEntity target)`**, feeding the vanilla `ATTACK_TARGET` memory (`MemoryModuleType.ATTACK_TARGET`, field `field_30243`). `NpcBrain.getAttackTarget()` reads that memory to drive the `FIGHT` activity (melee/ranged approach+attack tasks).

### `NpcEntity.shouldTarget(npc, target)` — the actual alignment→hostility rule
This single static method is the ground truth. Order of checks:
1. Special-cased always-hostile targets: `SnailEntity` (sibling mod `of_beasts_and_wild_things`), `class_1588` (vanilla Illusioner/Illager-type?), `SnowTrollEntity` → always return `true` (ignores faction entirely).
2. Otherwise, resolve the NPC's own `Faction` (`npc.getFaction()`); if it's `null`, return `false` (an NPC with no resolvable faction targets nothing via this path).
3. **Player check**: if `target` is a survival-mode `PlayerEntity`, fetch the player's faction via `StateSaverAndLoader.getPlayerState(player).getFaction()`.
   - **If the player has no faction assigned (never completed onboarding), the NPC is hostile to them unconditionally** (`return true`) — i.e. faction-less players are treated as hostile/fair-game by every NPC.
   - Else, hostile only if `npcFaction.isHostileToward(playerFactionId)`.
4. **NPC-vs-NPC check**: if `target` is another `NpcEntity`, resolve its faction; if that faction is null, or `npcFaction.isHostileToward(targetFaction.getId())`, return `true`. Additionally handles `SUBFACTION`: if the target's faction is a `FactionType.SUBFACTION`, also check hostility against its **parent faction** (`targetFaction.getParentFaction(world).getId()`) — so subfactions (e.g. Erebor under Longbeards, Gundabad under Hobgoblin Tribes) inherit the parent's diplomacy relationships from the *attacker's* perspective.
5. **Mounted/beast check**: if `target` is a ridden `AbstractHorseEntity` (`class_1496`), scan its passengers for `NpcEntity`s and apply the same faction/subfaction hostility check against any rider. If the mount itself is an `AbstractBeastEntity` (mod beast, e.g. warg/great horn), it is also targeted if `beast.getDisposition() != faction.getDisposition()` — this is the one place `DispositionType` (GOOD/NEUTRAL/EVIL), not `AffinityLevel`, directly drives targeting (a mismatch in coarse disposition, not the hostile-affinity table, makes a mounted beast attackable).
6. Otherwise `false`.

`NpcEntity.method_18395` (`canTarget`, vanilla hook) is overridden to `shouldTarget(this, target) && super.canTarget(target)`, so this predicate gates vanilla's built-in targeting too, in addition to the goal/sensor paths above — three separate call sites all funnel through the same static method, so the rule is applied consistently regardless of which subsystem initiates the target search.

### Does the player's faction determine who attacks them on sight? **Yes, deterministically and statically.**
- A player with **no faction** (hasn't onboarded) is attacked by every NPC that has a resolvable faction (step 3 above — explicit `return true` when `playerFaction == null`).
- A player **with** a faction is attacked only by NPCs whose `Faction.isHostileToward(playerFactionId)` is true per that NPC-faction's static `initial_diplomacy` table — e.g. joining Gondor makes Mordor/Isengard/Moria/Goblin-Town/Hobgoblin-Tribes/Wild-Goblins/Brigand NPCs hostile on sight, while Rohan/Lothlorien/Shire/Dale/Longbeards NPCs are not.
- Joining a faction, per se, does not make that faction's own NPCs friendly-only by omission — it's explicit: Gondor lists itself as `ALLY`, and non-hostile entries simply never trigger `isHostileToward`.

### Is there dynamic/decaying reputation? **No — confirmed absent.**
- There is no reputation counter anywhere in `PlayerData`, `AffiliationData`, `PlayerDataService`, or the goal/sensor code. The only stateful "counter" on `PlayerData` is `delversFearCountInSeconds`, an unrelated debuff-duration tracker (fear effect while delving), not a reputation/standing value.
- No method anywhere increments/decrements a per-player-per-faction standing (no `addReputation`, `lowerStanding`, decay tick, or "attacking a faction member angers the faction" propagation — Brain/Sensor and Goal hostility are recomputed fresh every tick purely from the **static** `initial_diplomacy` table plus the player's **currently assigned faction**, nothing else).
- The only way a player's relationship to a faction changes is by **switching their own faction assignment** via the onboarding/affiliation flow (`PacketSetAffiliation` → `FactionUtil.updateFaction`), which is a discrete, player-initiated identity swap, not an emergent reputation system. `AffiliationData` is a lightweight carrier record (`disposition`, `faction`, `spawnId`) used to compute a spawn coordinate — it has no reputation math either.
- **Conclusion: diplomacy/alignment is purely static, table-driven, and computed live from `(attacker's assigned faction) × (target's assigned faction) → initial_diplomacy lookup` every time a target check runs. There is no reputation/affinity that drifts based on player behavior in this codebase.**

---

## 4. NPC generation (spawning, faction/rank/race assignment, gear)

Two independent moments create the link between an `NpcEntity` instance and its faction/rank/race/gear:

1. **Faction → rank → NpcData resolution** happens at the **data level**, before any entity exists: `Faction.npcDatasByRank : HashMap<NpcRank, List<Identifier>>` is populated straight from the `npcs.ranks[]` JSON/bootstrap block. `NpcRank` = `CIVILIAN, MILITIA, SOLDIER, KNIGHT, VETERAN, LEADER` (an ascending hierarchy). `Faction.verifyData()` (called from every constructor) asserts that any non-empty, non-subfaction-only faction has at minimum `MILITIA, SOLDIER, KNIGHT, VETERAN, LEADER` pools present (a soft assertion — the check exists but its failure branch is an empty `if` block, i.e. currently a no-op / diagnostic stub, not an actual thrown exception).
   - `Faction.getRandomGear(world, rank, race)` and `Faction.getPreviewGear(world, race)` pick a random `NpcData` from the rank pool filtered to a specific race (`NpcDataLookup.getAllNpcDatasFromRace`) — used by GUI/preview and by spawn logic to pick a concrete `NpcData` given rank+race constraints.
   - `Faction.getRandomNpcDataIdentifier()` flattens *all* ranks into one list and picks uniformly at random — the entry point used when nothing more specific is known.
2. **Entity instantiation → data application** happens in `net.sevenstars.middleearth.entity.npcs.initializer.NpcEntityInitializer` + `NpcGenerator`:
   - `NpcEntity.tryToInitializeData()` (ticked every server tick via `method_5958`) calls `NpcEntityInitializer.shouldInitialize()` (true if the entity has no `NpcData` id yet, or its texture data hasn't been generated) then `initializeNpcEntity()`.
   - `NpcEntityInitializer.initializeForServer`: if the entity's `npcDataIdentifier` is the sentinel `RANDOM = "full_random"`, pick any random id from the entire `NPC` dynamic registry. Otherwise, if the currently-set NpcData id doesn't exist (typical case for a freshly-spawned vanilla-style spawn-egg/natural spawn with no id yet), it calls `findContextualizedNpcData(world, npc)`, which:
     - Looks at the block position's containing **structure** (via `structureStarts` at that chunk) and asks `BiomeEventDataLookup.findNpcDataForStructure(world, structureId, npc)`.
     - Falls back to **biome**-based lookup: `BiomeEventDataLookup.findNpcDataForBiome(world, biome, npc)`.
     - Either lookup returns a `BiomeEventData.ContextualizedBiomeData` bundling the chosen `NpcData` plus optional mount info (`hasMount()`, `mountArmor()`) — i.e. **structure/biome-tagged spawn tables (`BIOME_EVENT`/`STRUCTURE_EVENT` dynamic registries) are what actually decide which faction+race+rank an NPC becomes when it naturally spawns**, not a flat global weighted pool.
     - If a mount is specified, `generateMountData()` spawns and tames/saddles a horse or `AbstractBeastEntity`, mounting the NPC on it.
   - `NpcGenerator.generateCharacterTextures(world, npcDataId, npc)`: resolves the `NpcData` from the registry, calls `npc.setNpcData(npcData)` (which also sets `factionId` from `npcData.getFactionIdentifier()` and `npcCategory` from a random `EntityCategories` roll off the NPC's texture-preset pool), applies attributes (`npcData.applyAttributes(npc)` → resets then reapplies the resolved `Race`'s attribute pool, category-specific attributes layered on top), builds a full procedural skin/eyes/hair/clothing texture identity from `TexturePresetDataPool`, and equips gear via `NpcUtil.equipAll(npc, npcData.getGear())` — `NpcData.getGear()` rolls a `WeightedGearData` out of the NPC's `WeightedPool<WeightedGearData>` (`sevenstars.api.dtos.WeightedPool`/`WeightedItem`: linear weighted random — sum weights, roll `[0,sum)`, walk cumulative weight).
   - `npc.updateAttackType()` then re-derives the entity's melee/bow/crossbow attack goal based on its equipped main-hand item (bow → `CustomBowAttackGoal`, crossbow → `NpcCrossBowAttackGoal`, else melee).

So generation is a two-stage weighted/contextual pick: **(a)** structure/biome tag → `NpcData` (functionally "which faction+rank+race spawns here"), then **(b)** within that `NpcData`, a weighted gear roll and a random texture/appearance roll — faction and rank themselves are not independently re-rolled at spawn time, they come bundled inside whichever `NpcData` was selected in stage (a), and `NpcData` itself was authored under a specific `Faction`'s specific `NpcRank` pool (`GondorianNpcDataPool.SOLDIER`, `.VETERAN`, etc., referenced directly from `GondorFactionPool`'s rank map) at data-definition time.

---

## 5. SevenStarsAPI vs. the mod itself; sibling mods

- **`sevenstars-api`** is a thin, mostly-empty-at-registration-time Fabric library mod (`net.sevenstars.api.SevenStarsApi implements ModInitializer`, mod id `sevenstars-api`). Its `onInitialize()` just calls four registration stubs (`SchedulesAPI.registerModSchedules()`, `ActivitiesAPI.registerModActivities()`, `SensorsAPI.registerModSensors()`, `MemoryModulesAPI.registerModMemoryModules()`) which in the decompiled build are themselves near-empty (log a debug line; no actual Activity/Sensor/Schedule/MemoryModule constants are registered in the API module itself — those concrete ones, e.g. `SensorsME.NPC_ATTACKABLES`, live in the mod). What the API **does** provide as real, reusable code:
  - `net.sevenstars.api.dtos.{WeightedItem, WeightedPool, WeightedIdentifier}` — generic weighted-random-pool DTOs used throughout the mod's gear/npc/texture systems.
  - `net.sevenstars.api.registries.DynamicRegistries` — small helper (`register(context, lookup, key, element)`, `of(key, id)`) wrapping vanilla dynamic-registry bootstrap boilerplate; `DynamicRegistriesME` extends it.
  - `net.sevenstars.api.entity.ai.brain.{ActivitiesAPI, MemoryModulesAPI, SchedulesAPI, SensorsAPI}` — intended as shared Brain-AI building blocks/constants for addon authors (per the CurseForge description: "library for developers to manipulate entity behaviors" for Middle-earth addons), though in this decompiled build most of the actual custom sensors/memory-modules/activities are concretely defined in the mod (`SensorsME`, `MemoryModulesME`) rather than the API.
  - So: **API = shared plumbing/contracts (dynamic-registry helpers, weighted-pool DTOs, Brain-AI extension points) meant for addon developers; the mod = all actual content (Factions, Races, NpcData, the diplomacy/targeting logic, generation pipeline, textures, items, blocks, dimension).**
- **Sibling mods**: The GitHub monorepo (`Jukoz/middle-earth`) contains `of-beasts-and-wild-things/` as a sibling Gradle module alongside `middle-earth/` and `sevenstars-api/` — confirmed by a live compile-time dependency: `NpcEntity.java` directly imports `net.sevenstars.of_beasts_and_wild_things.entity.snail.SnailEntity` and hard-codes it into `shouldTarget()`'s always-hostile list. So "Of Beasts and Wild Things" is not a loosely-coupled optional addon in this build — it's a compiled dependency of the main mod. No reference to `middleearth_immersive_expansion` was found anywhere in the decompiled mod code, and GitHub search/README review found no mention of it either — it may not yet exist, be unreleased, or be a separate/renamed project not linked from this repo.

---

## 6. How the whole system fits together (engineer's narrative)

At world-load time, three vanilla **dynamic registries** (`race`, `faction`, `npc`, plus supporting ones for textures/structures/biome-events) are populated from JSON under `data/middle-earth/middle-earth/{race,faction,npc}/*.json`. Those JSON files are themselves generated (via `FabricDynamicRegistryProvider` datagen) from Java "pool" classes (`registries/content/{races,factions,npcs}/pools/*.java`) that are the actual hand-authored source of truth at build time. Because the registries are registered with `DynamicRegistries.registerSynced`, their entire contents replicate to every connecting client automatically — a client always has full knowledge of every Faction's diplomacy table, every Race's attribute pool, and every NpcData's gear/combat/texture config, with zero custom networking code required for that part. A `Faction` object cross-references `NpcData` ids per `NpcRank` (its recruitment pool), and each `NpcData` in turn references exactly one `Race` id and one texture-preset id — so the object graph is Faction → (rank-bucketed) NpcData → Race/Texture, resolved by identifier lookup through the registry at the moment it's needed, never eagerly linked as Java object references (this is what lets JSON/datapack overrides work at all).

Player identity (which faction/race a player belongs to) lives entirely server-side in a single `PersistentState` singleton (`StateSaverAndLoader`, saved with the overworld) mapping player UUID → a tiny `PlayerData` record `{faction, race, spawn, origin}`. There is deliberately no reputation field and no attachment/component sync channel for this — the player's faction membership is set only through an explicit onboarding/affiliation UI flow (`PacketSetAffiliation`/`PacketSetRace` C2S → `FactionUtil`/`RaceUtil` mutate `PlayerData` server-side), and every piece of combat AI reads that same server-side state live, every tick, rather than caching a client-visible copy. This works because all hostility decisions happen only on the server (goal `canStart()` checks, Brain sensors), so the client never actually needs to know "am I hostile to this NPC" — it just renders whatever `ATTACK_TARGET`/animation state the server already decided and replicated via normal entity tracking.

The alignment→behavior bridge is a single static predicate, `NpcEntity.shouldTarget(npc, target)`, consulted from three different subsystems (the classic `TargetPlayerDiplomacyGoal`/`TargetNPCDiplomacyGoal` goal-selector entries, the Brain/Sensor `NpcAttackablesSensor` feeding `ATTACK_TARGET`, and the vanilla `canTarget` hook) so that regardless of which AI pathway initiates a target scan, the same rule applies. That rule reduces to one lookup: does the *attacker's* `Faction.initial_diplomacy` table mark the *target's* faction (or, for subfactions, its parent) as `AffinityLevel.HOSTILE`? Faction-less players are hostile-by-default to every factioned NPC, which is what makes the onboarding flow effectively mandatory for safe play. Nothing in this pipeline is dynamic — there is no reputation decay, no "aggro the whole faction by killing one member" propagation, no attack-triggered demotion of standing; diplomacy is a static adjacency table authored once per faction and only ever changed wholesale when a player re-runs the affiliation flow to switch factions outright. An engineer building a compatible/competing mod should replicate this shape — dynamic-registry-backed Faction/Race/NpcData definitions with a required, symmetric-by-convention `initial_diplomacy` table, a single canonical `shouldTarget`-style predicate reused by every AI subsystem, and player alignment stored server-side only — while being free to add the reputation layer this codebase conspicuously omits.

---

## Key files (decompiled paths)

- `entity/goals/TargetNPCDiplomacyGoal.java`, `entity/goals/TargetPlayerDiplomacyGoal.java`
- `entity/ai/brain/sensor/NpcAttackablesSensor.java`, `entity/ai/brain/SensorsME.java`
- `entity/npcs/NpcEntity.java` (`shouldTarget`, `getFaction`, `canTarget`/`method_18395`)
- `entity/npcs/NpcBrain.java`
- `entity/npcs/data/NpcEntityDataHolder.java`
- `entity/npcs/initializer/{NpcGenerator,NpcEntityInitializer}.java`
- `resources/datas/factions/{Faction,FactionLookup,FactionUtil}.java`, `resources/datas/factions/data/InitialDiplomacy.java`
- `resources/datas/races/{Race,RaceLookup,RaceUtil}.java`
- `resources/datas/npcs/NpcData.java`
- `resources/datas/common/{DispositionType,AffinityLevel,FactionType,NpcRank}.java`
- `resources/persistent_datas/{PlayerData,PlayerDataService,AffiliationData}.java`
- `resources/StateSaverAndLoader.java`
- `network/packets/C2S/{PacketSetAffiliation,PacketSetRace}.java`, `network/packets/S2C/PacketOnboardingResult.java`
- `registries/DynamicRegistriesME.java`, `registries/content/factions/FactionRegistry.java`, `registries/content/factions/pools/GondorFactionPool.java` (concrete diplomacy example)
- `datageneration/providers/dynamic/{FactionProvider,RaceProvider,NpcProvider}.java`
- API: `sevenstars-api/.../SevenStarsApi.java`, `registries/DynamicRegistries.java`, `dtos/{WeightedItem,WeightedPool,WeightedIdentifier}.java`, `entity/ai/brain/{ActivitiesAPI,MemoryModulesAPI,SchedulesAPI,SensorsAPI}.java`

GitHub cross-reference: `https://github.com/Jukoz/middle-earth` (monorepo; `middle-earth/`, `sevenstars-api/`, `of-beasts-and-wild-things/` subprojects; ARR license; confirms the sibling-mod compile dependency and that `data/middle-earth/middle-earth/` datagen output exists in-tree, though faction/race/npc JSON subfolders were not fully enumerable through the GitHub web view in this session).
