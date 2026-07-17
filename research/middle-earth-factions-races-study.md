# Middle-earth Mod — Deep Study: Factions, Races, Alignment & Onboarding

**Mod:** `Middle-earth` 1.0.0-1.21.8-beta by **Seven Stars / Jukoz** (Fabric, MC 1.21.8)
**Namespace:** `middle-earth` · **Base package:** `net.sevenstars.middleearth`
**Library dep:** `sevenstars-api` (`net.sevenstars.api`) · **Hard sibling dep:** `of-beasts-and-wild-things` (compiled in, not optional)
**GitHub (ARR, browsable):** https://github.com/Jukoz/middle-earth (monorepo: `middle-earth/`, `sevenstars-api/`, `of-beasts-and-wild-things/`)

> Source of this study: full CFR decompile of the mod + SevenStarsAPI, all shipped `data/middle-earth/middle-earth/{faction,race,npc}/*.json`, the lang file, and the GitHub repo. Detailed per-topic notes live in `research/raw/{factions,races,spawns-onboarding,system-mechanics}.md`.

---

## TL;DR

- **14 realms / 16 registry entries.** Factions carry a `disposition`: **7 GOOD** (Gondor, Rohan, Dale, Longbeards→Erebor, Lothlórien, Woodland Realm, Shire), **5 EVIL** (Mordor, Isengard, Moria, Goblin Town, Hobgoblin Tribes→Gundabad), **2 NEUTRAL** non-joinable NPC-only (Brigand, Wild Goblins).
- **Alignment is a static, hand-authored per-faction diplomacy table** (`initial_diplomacy`, affinities ALLY/FRIENDLY/HOSTILE, + unused NEUTRAL). Only **HOSTILE** does anything in code. **There is NO dynamic reputation** — killing faction members never changes standing; the only way relations change is switching your own faction.
- **8 playable races.** For a **player**, only `base_attributes` apply (gender/`SHARED` pools are NPC-only). Player stats are fully deterministic. Big spread: Dwarf tanky+mining, Elf reach+snow-immune+fall-resist, Goblin wall-climb, Hobbit/Orc fast-sneak, Snaga huge mining reach but 6 HP, Uruk full-size brute, Human pure vanilla.
- **Players choose via an onboarding GUI** (triggered by a "Player's Book" on first join): one screen cycling **Disposition → Faction → (Subfaction) → Spawn → Race** with a live map + NPC preview, then Confirm fires 4 packets that teleport them into the custom Middle-earth dimension and persist their choice.
- **37 named starting locations** across 12 factions, stored as scaled map-space coords (×32 → real blocks) in a ~96,000-block custom dimension.
- **Everything is data-driven via vanilla dynamic registries** (`race`/`faction`/`npc`), auto-synced server→client. The JSON is datagen output of Java "pool" classes; datapacks can override it.

---

## 1. System architecture (how it loads & connects)

- Three **vanilla dynamic registries** — `middle-earth:race`, `middle-earth:faction`, `middle-earth:npc` (+ texture/structure/biome-event registries) — declared in `registries/DynamicRegistriesME` and registered with Fabric's **`DynamicRegistries.registerSynced(KEY, CODEC)`**. This auto-replicates all Faction/Race/NpcData to every client on join/reload — **no custom sync packet needed** for the definitions.
- The **authoritative source is Java** "pool" classes under `registries/content/{factions,races,npcs}/pools/*.java` (e.g. `GondorFactionPool.GONDOR`, `RacePools`). `*Registry.bootstrap()` builds them; `datageneration/providers/dynamic/{Faction,Race,Npc}Provider` (FabricDynamicRegistryProvider) **datagen-dump** them to the shipped `data/.../{faction,race,npc}/*.json`. So the JSON files are generated mirrors — but datapacks can still override them at load.
- **Object graph (resolved lazily by id):** `Faction → (NpcRank → NpcData ids) → NpcData → {Race id, texture preset, weighted gear}`. `Race` is standalone (attributes only), never references back.

---

## 2. Factions & Alignment

### 2.1 Roster by disposition (`DispositionType = GOOD | NEUTRAL | EVIL`)

| Realm | id | Disposition | Type | Joinable | Sel. order | Represents |
|---|---|---|---|---|---|---|
| Gondor | `middle-earth:gondor` | GOOD | FACTION | ✓ | 0 | South-kingdom of the Dúnedain (Minas Tirith) |
| Rohan | `middle-earth:rohan` | GOOD | FACTION | ✓ | 1 | Riddermark, horse-lords |
| Dale | `middle-earth:dale` | GOOD | FACTION | ✓ | 2 | Rebuilt Men-kingdom by Erebor |
| Longbeards *(umbrella)* | `middle-earth:longbeards` | GOOD | FACTION | ✓ | 3 | Durin's Folk (grouping node, no content) |
| └ Erebor | `middle-earth:longbeards.erebor` | GOOD | SUBFACTION | ✓ | 4 | The Lonely Mountain (Dáin) |
| Lothlórien | `middle-earth:lothlorien` | GOOD | FACTION | ✓ | 5 | Galadriel's Golden Wood |
| Woodland Realm | `middle-earth:woodland_realm` | GOOD | FACTION | ✓ | 6 | Thranduil's halls (Mirkwood) |
| Shire | `middle-earth:shire` | GOOD | FACTION | ✓ | 7 | Hobbit homeland |
| Mordor | `middle-earth:mordor` | EVIL | FACTION | ✓ | 0 | Sauron's realm (Barad-dûr) |
| Hobgoblin Tribes *(umbrella)* | `middle-earth:hobgoblin_tribes` | EVIL | FACTION | ✓ | 1 | grouping node, no content |
| └ Gundabad | `middle-earth:hobgoblin_tribes.gundabad` | EVIL | SUBFACTION | ✓ | 2 | Mount Gundabad orcs |
| Goblin Town | `middle-earth:goblin_town` | EVIL | FACTION | ✓ | 3 | Misty Mountains goblins |
| Moria | `middle-earth:moria` | EVIL | FACTION | ✓ | 4 | Fallen Khazad-dûm |
| Isengard | `middle-earth:isengard` | EVIL | FACTION | ✓ | 5 | Saruman's Orthanc |
| Brigand | `middle-earth:brigand` | NEUTRAL | FACTION | ✗ | 0 | Outlaw bandits (NPC-only) |
| Wild Goblins | `middle-earth:wild_goblins` | NEUTRAL | FACTION | ✗ | 1 | Unaligned goblin raiders (NPC-only) |

**Sub-faction pattern:** the parent (`longbeards`, `hobgoblin_tribes`) is a bare umbrella — no banner/npcs/spawns, empty diplomacy, `"subfaction":[child]`. The child SUBFACTION (`.erebor`, `.gundabad`) holds all real content + `"parent_faction"`. Both parent and child are independently joinable with their own selection order (so the GUI shows them side-by-side, not nested).

### 2.2 NPC rank ladder

`NpcRank = CIVILIAN → MILITIA → SOLDIER → KNIGHT → VETERAN → LEADER` (ascending). Each faction JSON's `npcs.ranks[]` maps a rank to a pool of NPC-data ids (e.g. Gondor VETERAN = `king_guard`, `citadel_guard`, `fountain_guard`, `veteran`). Leaf realms are expected to define MILITIA/SOLDIER/KNIGHT/VETERAN/LEADER (soft check, currently a no-op stub).

### 2.3 Diplomacy (`AffinityLevel = HOSTILE | NEUTRAL | FRIENDLY | ALLY`)

- Each faction ships a **required `initial_diplomacy` list** = its own one-directional opinion of every other faction (self = ALLY). **Only HOSTILE is consumed by code** (`Faction.isHostileToward(id)`); FRIENDLY/ALLY/NEUTRAL are stored but inert (reserved for future features). NEUTRAL is never used in shipped data.
- **Two clean blocs:**
  - **Free Peoples** (7 GOOD): mutually FRIENDLY, uniformly HOSTILE to all evil + both neutrals.
  - **Sauron/Saruman evils** (Mordor, Isengard, Moria, Goblin Town, Gundabad): mutually FRIENDLY, HOSTILE to all Free Peoples **and** to Wild Goblins + Brigand.
  - **Lone-wolf neutrals** (Brigand, Wild Goblins): HOSTILE to *everyone* (ally only self).
- **Asymmetry is possible** (it's a per-faction opinion list, not a shared matrix): Mordor & Isengard declare Wild Goblins **FRIENDLY**, but Wild Goblins declares HOSTILE back to everyone. **Data quirk:** `gondor.json` is missing its `woodland_realm` diplomacy line (all other Good factions list it) — looks like an authoring omission.

### 2.4 Banners
Each real faction has a `banner` (base color + layered patterns using custom `middle-earth:*` + vanilla patterns), e.g. Gondor = white + White Tree; Mordor = black + Eye of Sauron; Isengard = White Hand; Rohan = horse head. Brigand & Wild Goblins share an identical skull banner.

---

## 3. Races & Strengths

### 3.1 The precedence rule (critical)
A `Race` has `base_attributes.pool` and `category_based_attributes.{SHARED,MALE,FEMALE}`. **Players only ever get `base_attributes`** (`Race.applyPlayerAttributes` → base pool only). The `SHARED/MALE/FEMALE` pools apply **only to NPC citizens** (`Race.applyNpcAttributes`, layered base→SHARED→gender, last-write-wins since `apply()` *sets* the base value). Players have **no gender field** at all. So player race stats are **fully deterministic** (all base values are fixed, never randomized).

### 3.2 Effective **player** stats (what you get on `/race set`)

| Race | Scale | Health | Move | Entity reach | Block reach | Attack | Sneak | Fall dmg | Signature perk |
|---|---|---|---|---|---|---|---|---|---|
| **Dwarf** | 0.81 | 22 (11♥) | 0.09 (−10%) | 2.75 | 4.5 | 1.0 | 0.3 | 1.0 | +0.2 mining_efficiency |
| **Elf** | 1.06 | 20 (10♥) | 0.1 | **3.25 (best)** | 4.5 | 1.0 | 0.3 | **0.75 (−25%)** | powdered-snow immune; +0.3 move_efficiency |
| **Goblin** | 0.75 | 14 (7♥) | 0.105 (+5%) | 2.5 | 4.5 | −10% net | 0.3 | 1.0 | **climbing_strength 100 (wall-cling)**; +0.2 mining |
| **Hobbit** | **0.66 (smallest)** | 14 (7♥) | 0.1 | 2.5 | 4.5 | −20% net | **0.435 (+45%)** | 0.9 (−10%) | detection_range 0.8 (stealthier) |
| **Human** | 1.0 | 20 (10♥) | 0.1 | 3.0 | 4.5 | 1.0 | 0.3 | 1.0 | none — pure vanilla baseline |
| **Orc** | 0.79 | 16 (8♥) | 0.1 | 2.75 | 4.5 | 1.0 | **0.435 (+45%)** | 1.0 | none (durable + stealthy hybrid) |
| **Snaga** | 0.71 | **12 (6♥, lowest)** | 0.105 (+5%) | 2.5 | **5.5 (+1.0, best)** | −20% net | 0.3 | 1.0 | best mining/build reach |
| **Uruk** | 1.0 | 18 (9♥) | 0.105 (+5%) | 3.0 | 4.5 | 1.0 | 0.3 | 1.0 | burning_time 0.7 (−30% fire) |

*(Attack "−X% net" = a `total_damage ADD_MULTIPLIED_TOTAL` modifier on top of base attack_damage.)*

### 3.3 Advantages / disadvantages (plain English)
- **Dwarf** — tankiest (11♥) + free mining speed; slower on foot (−10%), shorter reach.
- **Elf** — longest reach, snow-immune, −25% fall damage, moves freely on soul-sand/honey; no player drawback (its low 14-HP number is NPC-only). One of the strongest all-round picks.
- **Goblin** — small, fast, **climbs walls**, mining bonus; squishy (7♥), −10% damage, short reach.
- **Hobbit** — stealth kit: +45% sneak, harder to detect, smallest hitbox, −10% fall; but 7♥ and **−20% damage**.
- **Human** — no bonuses, no penalties; the neutral baseline.
- **Orc** — 8♥ + big +45% sneak with **no** damage/HP penalty; otherwise average.
- **Snaga** — glass-digger: **6♥ + −20% damage** but **+1.0 block reach (5.5)** and +5% speed.
- **Uruk** — full-size brute (no shrink), 9♥, +5% speed, −30% burn; no utility perk.

### 3.4 Custom `middle-earth:` attributes (`entity/EntityAttributesME`)
- `powdered_snow_immunity` (Elf) — walk on powder snow like leather boots.
- `climbing_strength` (Goblin base 100) — cling to walls for N ticks before touching ground (`PlayerEntityMixin.isClimbing`).
- `detection_range` (Hobbit 0.8, "lower=better") — stealth stat; registered + tooltip'd, but its concrete AI-detection consumer wasn't located (possibly partial).
- `width_scale` — NPC-only girth (build silhouette), never on players.
- `delvers_fear_strength` — a "fear of the dark/deep" debuff timer; **wired but unused** (no race sets it; all default 0). Reserved future content (Dwarf-fear-of-deep style).
- Tag `is_buff_reversed` = {detection_range, scale, fall_damage_multiplier, burning_time} → cosmetic tooltip color inversion only.

---

## 4. Starting Points (37 spawns)

Coords are **map-space** (the JSON's `x,z`); real block pos ≈ ×**32** in the ~96,000-block Middle-earth dimension. All shipped spawns are `dynamic:true` → Y is computed from terrain height at teleport time.

**GOOD (24):**
- **Gondor (9):** Anórien (1930,1735) · Ringló Vale (1530,1730) · Lamedon (1625,1800) · Ithilien (1975,1700) · Lossarnach (1895,1792) · **Minas Tirith (1945,1785)** · Dol Amroth (1500,1930) · Lebennin (1715,1955) · Pelargir (1875,1960)
- **Rohan (6):** Edoras (1525,1600) · Eastemnet (1715,1575) · Westemnet (1525,1525) · Aldburg (1600,1660) · Helm's Deep (1470,1555) · The Wold (1675,1475)
- **Dale (2):** Dale Capital (2021,727) · Esgaroth (2007,757)
- **Erebor (3):** Ravenhill (2017,722) · Iron Hills (2355,725) · Iron Hills Spring (2262,782)
- **Lothlórien (1):** Cerin Amroth (1614,1215)
- **Woodland Realm (1):** Elvenking's Halls (1957,766)
- **Shire (2):** Hobbiton (933,900) · Willowbottom (981,970)

**EVIL (13):**
- **Mordor (5):** Gorgoroth (2161,1717) · Black Gates (2010,1608) · Dol Guldur (1793,1210) · Núrn (2345,1915) · Minas Morgul (2029,1770)
- **Gundabad (3):** Grey Mountains (1652,640) · Mount Gram (1401,686) · Gundabad (1595,640)
- **Goblin Town (1):** Goblin Town (1583,869)
- **Moria (3):** West Gate (1465,1143) · East Gate (1522,1143) · Goblin Camp (1546,1115)
- **Isengard (1):** Orthanc (1402,1467)

**Geography (map-space):** Shire far NW; Gondor & Mordor adjoin in the SE; Rohan/Isengard/Moria/Lothlórien central; Dale/Erebor/Gundabad/Woodland/Goblin-Town in the N — matching Tolkien's map, compressed 32:1.

---

## 5. Onboarding Flow (how a player picks)

1. **Trigger:** on first join a player gets a **Player's Book** item (once, gated by a stat). Its *Getting Started* chapter runs `/middle_earth onboarding try open` → if no faction yet, server sends `PacketForceOnboardingScreen` → client opens **`OnboardingFactionScreenController`**. Returning players instead get a small *Continue / Reset Character* dialog (reset gated by `ENABLE_FACTION_RESET` config).
2. **The screen (one combined GUI, not a wizard):** cyclable selectors for **Disposition (GOOD/EVIL)** → **Faction** (sorted by selection order) → **Subfaction** (if any) → **Spawn** (drives a live pannable map widget) → **Race** (drives a live NPC preview + `RaceStatTooltip`). Plus a **search bar**, a preview re-roll, a **full randomizer**, and a **Confirm** button with an anti-spam countdown.
3. **Commit (Confirm):** 4 C2S packets in order — teleport (`PacketTeleportToDynamicCoordinate`) · `PacketSetRace` · `PacketSetAffiliation(disposition,faction,spawn)` (also consumes a *Starlight Phial* from off-hand if held) · `PacketSetSpawnData` (records Overworld return origin). Server moves the player into `ModDimensions.ME_WORLD_KEY` at the resolved spawn and sets their forced respawn there.

---

## 6. Alignment → NPC behavior (the mechanic)

- **One canonical predicate: `NpcEntity.shouldTarget(npc, target)`**, consulted by three subsystems (classic `TargetPlayerDiplomacyGoal`/`TargetNPCDiplomacyGoal`, the Brain `NpcAttackablesSensor`, and the vanilla `canTarget` hook) — so the rule is uniform.
- **Rule:** resolve attacker NPC's `Faction`; hostile iff the target's faction (or, for a SUBFACTION target, its **parent**) is `HOSTILE` in the attacker's `initial_diplomacy`. **A player with no faction (not onboarded) is hostile to every factioned NPC** → onboarding is effectively mandatory for safe play. A few entities are hard-coded always-hostile (mod Snail, vanilla `class_1588`, Snow Troll). Mounted beasts are targeted if `beast.disposition != faction.disposition` (the one place coarse GOOD/EVIL drives targeting).
- **No dynamic reputation** — confirmed absent. No standing counter, no "kill one, anger all," no decay. Relations change **only** by switching your own faction. (Diplomacy recomputed live each tick from static tables.)

---

## 7. Player state & sync
- Stored **server-side only** in a per-world `PersistentState` (`StateSaverAndLoader`, keyed by UUID) as `PlayerData{faction, race, spawn, origin(pos+dim), delversFearCount}`. **No** Attachment API, **no** reputation field.
- **No S2C packet ever tells the client its own faction/race** — all hostility math is server-side; the client only needs the (auto-synced) dynamic-registry definitions. `PlayerDataService.playerPassedOnboarding` (has-faction?) is the universal "character created" gate.

---

## 8. Commands (all require **op / permission level 2** — players use the GUI, not commands)
Root: **`/middle_earth`** (underscore).
- `spawn [player] get|set|reset|tp overworld|middle_earth ...` — manage/teleport a player's return point & Middle-earth spawn. `set middle_earth <spawn_id>` restricted to current faction's spawns.
- `tp [player] to <spawn_id> <welcome>` — force-teleport to **any** named spawn (admin "goto").
- `race get|set|reset [player] <race_id>` — change race (`RaceUtil.updateRace`).
- `faction get|clear|join [player] <faction_id> [spawn_id]|banner <faction_id>` — change faction (`FactionUtil.updateFaction`).
- `onboarding open|try open [player]` — force / conditional open of the onboarding screen.

---

## 9. Data schemas (for building compatible content)

**Faction JSON** (`Faction.CODEC`): `id`, `faction_selection_order_index`, `joinable`, `disposition` (GOOD/NEUTRAL/EVIL), `faction_type` (FACTION/SUBFACTION), optional `parent_faction`, optional `subfaction[]`, optional `banner`, optional `npcs.ranks[]` (`{rank, pool[]}`), optional `spawns.data[]` (`{id, coordinates{x,y,z}, dynamic}`), optional `command_join[]`/`command_leave[]` (≤5), **required** `initial_diplomacy[]` (`{faction, affinity}`).

**Race JSON:** `type`, `id`, `base_attributes.pool[]` (player+NPC), `category_based_attributes.{SHARED,MALE,FEMALE}.pool[]` (NPC only), `command_join`/`command_leave`. Attribute element = `{id, value}` or `{id, min, max}` (+ optional `modifiers[]`).

**NpcData JSON:** single `race` id + `faction` id + `base_npc_texture` + weighted `gear` + `npc_attributes` + `combat_archetype`.

**Registration:** vanilla dynamic registry + `DynamicRegistries.registerSynced`; content authored as Java pools, datagen-exported to JSON, datapack-overridable.

---

## 10. Notable quirks & gaps (opportunities)
- **No reputation system** — the codebase "conspicuously omits" it (their words in effect). A dynamic standing/reputation layer is the obvious extension point.
- `gondor.json` missing its `woodland_realm` diplomacy entry (authoring bug).
- `AffinityLevel.NEUTRAL` and FRIENDLY/ALLY behaviors are **unused in code** — only HOSTILE matters. FRIENDLY/ALLY could gate trading/quests/assistance.
- `delvers_fear_strength` attribute is fully wired but **no race uses it** (unused mechanic).
- `detection_range` (Hobbit) — set + tooltip'd but its AI consumer wasn't found (possibly partial).
- NPC spawning is **structure/biome-tagged** (`BIOME_EVENT`/`STRUCTURE_EVENT` registries decide which faction/rank/race spawns where), not a flat global pool.
- **Of Beasts and Wild Things is a hard compiled dependency** (imported directly in `NpcEntity`). `middleearth_immersive_expansion` was not referenced anywhere in code.

---

## 11. Key source files (decompiled)
- Diplomacy/targeting: `entity/npcs/NpcEntity.java` (`shouldTarget`), `entity/goals/{TargetPlayerDiplomacyGoal,TargetNPCDiplomacyGoal}.java`, `entity/ai/brain/sensor/NpcAttackablesSensor.java`
- Data models/enums: `resources/datas/factions/{Faction,FactionLookup,FactionUtil}.java`, `resources/datas/factions/data/InitialDiplomacy.java`, `resources/datas/races/{Race,RaceUtil,RaceLookup}.java`, `resources/datas/npcs/NpcData.java`, `resources/datas/common/{DispositionType,AffinityLevel,FactionType,NpcRank}.java`
- Player state: `resources/persistent_datas/{PlayerData,PlayerDataService,AffiliationData}.java`, `resources/StateSaverAndLoader.java`
- Registration/content: `registries/DynamicRegistriesME.java`, `registries/content/factions/pools/*FactionPool.java`, `registries/content/races/RacePools.java`, `datageneration/providers/dynamic/*Provider.java`
- Attributes: `entity/EntityAttributesME.java`, mixins `PlayerEntityMixin`/`ServerPlayerEntityMixin`/`PowderSnowBlockMixin`
- Onboarding/commands: `gui/onboarding/**`, `commands/custom/{CommandSpawn,CommandRace,CommandFaction}.java`, `network/packets/C2S/{PacketSetRace,PacketSetAffiliation}.java`
- World scale: `world/map/{MiddleEarthMapConfigs,MiddleEarthMapUtils}.java`

*Raw agent notes: `research/raw/{factions,races,spawns-onboarding,system-mechanics}.md`.*
