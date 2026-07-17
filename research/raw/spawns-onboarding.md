# Middle-earth (Seven Stars / Jukoz) — Starting Points & Onboarding Flow

Source jar: `Middle-earth-1.0.0-1.21.8-beta.jar`
Data: `data/middle-earth/middle-earth/faction/*.json` (16 files), `data/middle-earth/middle-earth/race/*.json` (8 files), `assets/middle-earth/lang/en_us.json`
Code: decompiled (CFR) `net.sevenstars.middleearth.{commands,gui.onboarding,network,resources.persistent_datas,resources.datas.factions,world.map}`

---

## 1. Starting-locations catalog (all spawns, grouped by faction)

All 16 faction JSON files were dumped and every `spawns.data[]` entry extracted. Two of the 16 files (`hobgoblin_tribes.json`, `longbeards.json`) are empty "parent" wrappers with no spawns of their own — their actual territory/spawns live in their one `SUBFACTION` child (`hobgoblin_tribes.gundabad`, `longbeards.erebor`). Two more (`brigand.json`, `wild_goblins.json`) are `NEUTRAL`, **`"joinable": false`** NPC-only bandit/monster factions with **no `spawns` block at all** — players can never be assigned to them and they never appear in the onboarding UI.

**Every single spawn entry in every faction file has `"dynamic": 1` (true) and `"y": 0.0`.** There are no fixed-Y ("custom") spawns anywhere in the shipped data — see §2 for what this means. Coordinates below are the raw `(x, z)` values as stored in the JSON (see §5 for the real-world scale factor).

Total: **37 spawn points** across **12 spawn-bearing faction/subfaction entries** (10 "top-level" playable choices, since Longbeards/Erebor and Hobgoblin Tribes/Gundabad are single-subfaction wrappers), covering **GOOD** and **EVIL** dispositions only (NEUTRAL has zero joinable factions with spawns).

### GOOD disposition

**Gondor** (`middle-earth:gondor`, selection order 0) — 9 spawns
| Name | id | (x, z) |
|---|---|---|
| Anórien | `gondor.anorien` | (1930, 1735) |
| Ringló Vale | `gondor.ringlo_vale` | (1530, 1730) |
| Lamedon | `gondor.lamedon` | (1625, 1800) |
| Ithilien | `gondor.ithilien` | (1975, 1700) |
| Lossarnach | `gondor.lossarnach` | (1895, 1792) |
| **Minas Tirith** | `gondor.minas_tirith` | (1945, 1785) |
| Dol Amroth | `gondor.dol_amroth` | (1500, 1930) |
| Lebennin | `gondor.lebennin` | (1715, 1955) |
| Pelargir | `gondor.pelargir` | (1875, 1960) |

**Rohan** (`middle-earth:rohan`, order 1) — 6 spawns
| Name | id | (x, z) |
|---|---|---|
| Edoras | `rohan.edoras` | (1525, 1600) |
| Eastemnet | `rohan.eastemnet` | (1715, 1575) |
| Westemnet | `rohan.westemnet` | (1525, 1525) |
| Aldburg | `rohan.aldburg` | (1600, 1660) |
| Helm's Deep | `rohan.helms_deep` | (1470, 1555) |
| The Wold | `rohan.the_wold` | (1675, 1475) |

**Dale** (`middle-earth:dale`, order 2) — 2 spawns
| Name | id | (x, z) |
|---|---|---|
| Dale Capital | `dale.capital` | (2021, 727) |
| Esgaroth | `dale.esgaroth` | (2007, 757) |

**Longbeards → Erebor** (parent `middle-earth:longbeards` order 3 / subfaction `middle-earth:longbeards.erebor`, own order 4) — 3 spawns
| Name | id | (x, z) |
|---|---|---|
| Ravenhill | `longbeards.erebor.ravenhill` | (2017, 722) |
| Iron Hills | `longbeards.erebor.iron_hills` | (2355, 725) |
| Iron Hills Spring | `longbeards.erebor.iron_hills_spring` | (2262, 782) |

**Lothlórien** (`middle-earth:lothlorien`, order 5) — 1 spawn
| Name | id | (x, z) |
|---|---|---|
| Cerin Amroth | `lothlorien.cerin_amroth` | (1614, 1215) |

**Woodland Realm** (`middle-earth:woodland_realm`, order 6) — 1 spawn
| Name | id | (x, z) |
|---|---|---|
| Elvenking's Halls | `woodland_realm.elvenkings_halls` | (1957, 766) |

**Shire** (`middle-earth:shire`, order 7) — 2 spawns
| Name | id | (x, z) |
|---|---|---|
| Hobbiton | `shire.hobbiton` | (933, 900) |
| Willowbottom | `shire.willowbottom` | (981, 970) |

GOOD subtotal: **24 spawns** across 7 selectable factions.

### EVIL disposition

**Mordor** (`middle-earth:mordor`, order 0) — 5 spawns
| Name | id | (x, z) |
|---|---|---|
| Gorgoroth | `mordor.gorgoroth` | (2161, 1717) |
| Black Gates | `mordor.black_gates` | (2010, 1608) |
| Dol Guldur | `mordor.dol_guldur` | (1793, 1210) |
| Núrn | `mordor.nurn` | (2345, 1915) |
| Minas Morgul | `mordor.minas_morgul` | (2029, 1770) |

**Hobgoblin Tribes → Gundabad** (parent `middle-earth:hobgoblin_tribes` order 1 / subfaction `middle-earth:hobgoblin_tribes.gundabad`, own order 2) — 3 spawns
| Name | id | (x, z) |
|---|---|---|
| Grey Mountains | `hobgoblin_tribes.gundabad.grey_mountains` | (1652, 640) |
| Mount Gram | `hobgoblin_tribes.gundabad.mount_gram` | (1401, 686) |
| Gundabad | `hobgoblin_tribes.gundabad.gundabad` | (1595, 640) |

**Goblin Town** (`middle-earth:goblin_town`, order 3) — 1 spawn
| Name | id | (x, z) |
|---|---|---|
| Goblin Town | `goblin_town.goblin_town` | (1583, 869) |

**Moria** (`middle-earth:moria`, order 4) — 3 spawns
| Name | id | (x, z) |
|---|---|---|
| West Gate | `moria.west_gate` | (1465, 1143) |
| East Gate | `moria.east_gate` | (1522, 1143) |
| Goblin Camp | `moria.goblin_camp` | (1546, 1115) |

**Isengard** (`middle-earth:isengard`, order 5) — 1 spawn
| Name | id | (x, z) |
|---|---|---|
| Orthanc | `isengard.orthanc` | (1402, 1467) |

EVIL subtotal: **13 spawns** across 5 selectable factions.

### NEUTRAL disposition — no spawns, not joinable

**Brigand** (`middle-earth:brigand`) and **Wild Goblins** (`middle-earth:wild_goblins`) are both `"disposition": "NEUTRAL"`, `"joinable": false`. Neither has a `spawns` block in its JSON. They exist purely to populate hostile roaming NPC camps/bandits (`brigand.thug`, `brigand.mercenary`, `wild_goblins.brute`, etc.) and are hard-excluded from the onboarding faction list (`OnboardingFactionScreenController.setupInitialDatas()` filters on `faction.isJoinable()`).

---

## 2. The `dynamic` flag on spawns

Every spawn's `dynamic: 1` flag is read by `SpawnData` (`resources/datas/factions/data/SpawnData.java`):

- **Constructor:** if `isDynamic == true`, the stored `y` is discarded and zeroed (`new class_243(x, 0.0, z)`), which is why every spawn's JSON `"y"` is `0.0` — it's a placeholder, never used.
- **`getBlockPos()`** (used to actually resolve a teleport destination): for a dynamic spawn it calls `MiddleEarthMapUtils.getWorldCoordinateFromInitialMap(x, z)` (scales the map-space x/z into real world x/z, see §5) and then `ModDimensions.getDimensionHeight(x, z)` — i.e. **the Y coordinate is computed at teleport time from the actual terrain height column** at that x/z in the Middle-earth dimension, rather than being a hardcoded value. This lets the spawn stay correct even if terrain is regenerated/edited.
- A non-dynamic ("custom") spawn would instead use the literal stored `(x, y, z)` verbatim (`"custom"` coordinate mode exists in the lang file — `spawn.middle-earth.coordinates_base.custom = "[x,y,z]"` vs `.dynamic = "[x,z]"` — confirming dynamic spawns are only ever displayed/stored as `[x, z]`). No faction in the shipped data actually uses a fixed-Y spawn; the mechanism exists in code but isn't currently exercised by content.
- `SpawnData.getWorldCoordinates()` (a simpler variant used by the `/middle_earth spawn tp middle_earth` self-teleport path) just multiplies the dynamic x/z by the map scale ratio without doing a height lookup — the actual height snap-to-surface happens downstream in `ModDimensions.teleportPlayerToMe(...)`.

---

## 3. The onboarding flow — how a new player picks race, faction, and spawn

### Trigger
- On `ServerPlayConnectionEvents.JOIN` (`event/ModEvents.java`), every player who doesn't already have the `middle-earth.received_starter_item` stat is given a **Player's Book** item (`ResourceItemsME.PLAYER_BOOK`) and the stat is set so it's only granted once.
- Right-clicking/using the Player's Book opens `PlayerBookScreen` (a lore/help book UI with several "chapters", one of which — *Getting Started* — explains: *"Use it and you'll be brought to the onboarding process to Middle-earth."*). Interacting with that chapter triggers the server command `/middle_earth onboarding try open` (or the book UI calls it directly), which server-side checks `PlayerDataService.playerPassedOnboarding(player)`:
  - If the player has **not** yet chosen a faction, the server sends `PacketForceOnboardingScreen(delay, attributeList)` down to the client.
  - `OnboardingScreenHandler.handle()` on the client checks: if the player is in the Overworld and has **no existing PlayerData** (`havePlayerData == false`), it opens `OnboardingFactionScreenController` **directly** (brand-new players skip straight to faction selection).
  - If the player **already has PlayerData** (an existing character/faction), instead it opens `OnboardingSelectionScreen`, a small "Continue as [character]" / "Reset Character" (config-gated by `ModServerConfigs.ENABLE_FACTION_RESET`) dialog. "Continue" sends `PacketTeleportToCurrentSpawn` (teleports to the already-assigned spawn); "Reset Character" reopens the full `OnboardingFactionScreenController` to redo everything.
- Admins can force-open it for any player via `/middle_earth onboarding open [player]` (op only) regardless of onboarding state, or `/middle_earth onboarding try open [player]` which only opens it if that player hasn't finished onboarding yet.

### The Onboarding Faction Screen (single combined GUI — no separate race/faction/spawn wizard pages)
`OnboardingFactionScreen` + `OnboardingFactionScreenController` implement **one screen** with several linked selectors, all updating a shared "current selection" state:

1. **Disposition** (`GOOD` / `EVIL` — `NEUTRAL` never appears since Brigand & Wild Goblins are non-joinable) — a "cycled selection widget" with left/right arrows.
2. **Faction** — cycles through the joinable factions of the selected disposition, pre-sorted by each faction's `faction_selection_order_index` (see the ordering in §1's tables). Selecting a faction resets subfaction/spawn/race to index 0.
3. **Subfaction** — only shown/enabled when the faction has subfactions (Longbeards→Erebor, Hobgoblin Tribes→Gundabad); both currently have exactly one subfaction so the arrows are effectively disabled, but the UI is generic.
4. **Spawn point** — cycles through that faction's (or subfaction's) `spawns.data[]` list, driving a **live interactive map widget** (`FactionSelectionMapWidget`) that shows markers for every spawn of the current faction and pans/zooms to the selected one (`moveToCurrentSpawn()`).
5. **Race** — cycles through the races that faction allows (`Faction.getRaces(world)`; races are `dwarf`, `elf`, `goblin`, `hobbit`, `human`, `orc`, `snaga`, `uruk` per `race/*.json` + lang keys). A live NPC preview model (`PlayableNpcPreviewWidget`) updates to show a random unit of the chosen faction/race, and hovering the race selector shows a `RaceStatTooltip` (race stat bonuses).
6. A **search bar** lets the player jump straight to any joinable faction or subfaction by name instead of cycling.
7. **NPC randomizer** button re-rolls just the preview model; **Full randomizer** button randomizes disposition → faction → subfaction → spawn → race all at once.
8. **Confirm** button — disabled for the first `ModServerConfigs.DELAY_ON_TELEPORT_CONFIRMATION` seconds (a countdown shown on the button, default reflects a short anti-spam/"read the choice" delay) via `currentDelay`.

So the selection order the UI encourages is **Disposition → Faction → (Subfaction) → Spawn point → Race**, but nothing is technically sequential/gated — the player can revisit and change any selector before hitting Confirm, and the search bar can jump straight to a faction.

### Commit (on pressing Confirm — `OnboardingFactionScreenController.confirmSelection()`)
Four client→server packets are fired in this order:
1. **Teleport packet** — `PacketTeleportToDynamicCoordinate(x, z, true)` if the chosen spawn `isDynamic()` (always true for shipped content), else `PacketTeleportToCustomCoordinate(x, y, z, true)`.
2. **`PacketSetRace(raceId)`** → server calls `RaceUtil.updateRace(player, race, true)`.
3. **`PacketSetAffiliation(dispositionName, factionId, spawnId)`** → server calls `FactionUtil.updateFaction(player, faction, spawnId)`, which also (per `PacketSetAffiliation.process`) consumes one **Starlight Phial** item from the player's off-hand if held (a one-time onboarding consumable, apparently).
4. **`PacketSetSpawnData(overworldX, overworldY, overworldZ)`** — records the player's current Overworld position as their "return" origin (`PlayerDataService.setOrigin` / `PlayerData.assignNewOrigin`), i.e. where `/... spawn tp overworld` will later send them back to.

The screen then closes (`method_25419()`). Server-side, `FactionUtil.updateFaction` and the teleport packet handlers move the player into the Middle-earth dimension (`ModDimensions.ME_WORLD_KEY`) at the resolved spawn block position and set their respawn anchor there (`class_3222.class_10766` respawn point, forced).

### Persistent storage
`PlayerData` (`resources/persistent_datas/PlayerData.java`), saved via `StateSaverAndLoader` (a `SavedData`-backed per-player NBT store, not a Fabric "attachment" API — plain custom NBT compound), holds:
```
faction            (ResourceLocation, e.g. "middle-earth:gondor")
spawn              (ResourceLocation, e.g. "middle-earth:gondor.minas_tirith")
race               (ResourceLocation, e.g. "middle-earth:human")
origin_pos         (int[3] block pos — Overworld return point)
dimensionOrigin    (ResourceLocation — which dimension the Overworld origin was recorded in)
delversFearCountInSeconds (int — unrelated mechanic)
```
`PlayerDataService.playerPassedOnboarding(player)` checks whether this record already has a faction assigned; that's the single gate used everywhere (onboarding screen logic, and every `/middle_earth spawn ...` / `/middle_earth race ...` command handler) to decide "has this player finished character creation."

---

## 4. Relevant commands

The command root is **`/middle_earth`** (registered as `ModCommands.BASE_COMMAND = "middle_earth"`, note underscore, not the `middle-earth` mod-id/namespace dashes used elsewhere). **Every single subcommand below — including plain `get` — requires permission level 2 (`source.method_9259(2)`, i.e. op/gamemaster).** There is no self-service player command for changing race/faction/spawn; ordinary players only ever do this through the onboarding GUI's packets (§3), which bypass command permission checks entirely by calling the service/util classes directly from the packet handler.

### `/middle_earth spawn ...` (`CommandSpawn.java`)
- `/middle_earth spawn [player] get overworld` — show a player's recorded Overworld return coordinate.
- `/middle_earth spawn [player] get middle_earth` — show a player's assigned Middle-earth spawn name + coords.
- `/middle_earth spawn [player] set overworld <x y z>` — set a player's Overworld return point.
- `/middle_earth spawn [player] set middle_earth <spawn_id>` — assign a specific spawn id (autocompleted from `AllAvailableSpawnSuggestionProvider`, restricted to the player's *current* faction's spawn list if one is set) as that player's Middle-earth spawn; fails with "no_faction" if the player hasn't onboarded yet.
- `/middle_earth spawn [player] reset overworld` / `reset middle_earth` — reset to defaults / re-derive from current faction.
- `/middle_earth spawn [player] tp overworld` — teleport back to the Overworld return point.
- `/middle_earth spawn [player] tp middle_earth <welcome_needed:bool>` — teleport to the player's *own* assigned Middle-earth spawn (fails if onboarding not passed).
- `/middle_earth tp [player] to <spawn_id> <welcome_needed:bool>` — **force**-teleport to *any* spawn by id regardless of faction (autocompleted from `AllSpawnSuggestionProvider`, which lists every spawn of every faction+subfaction in the game) — this is the "go to any named location" admin command.

### `/middle_earth race ...` (`CommandRace.java`)
- `/middle_earth race get [player]` — show current race.
- `/middle_earth race set [player] <race_id>` (autocompleted from `AllRaceSuggestionProvider`) — set race (calls `RaceUtil.updateRace`).
- `/middle_earth race reset [player]` — clear race.

### `/middle_earth faction ...` (`CommandFaction.java`)
- `/middle_earth faction get [player]` — show current faction.
- `/middle_earth faction clear [player]` — clear a player's faction (throws `NoFactionException`/`FactionIdentifierException` if invalid).
- `/middle_earth faction join [player] <faction_id> [spawn_id]` (autocompleted from `FactionSuggestionProvider` / `AllAvailableSpawnSuggestionProvider`) — force-assign a faction (and optionally a specific spawn); this is the command-line equivalent of what the onboarding screen's Confirm button does (`FactionUtil.updateFaction`), throwing `IdenticalFactionException` if the target already has that faction.
- `/middle_earth faction banner <faction_id>` — give yourself that faction's banner item.

### `/middle_earth onboarding ...` (`CommandOnboarding.java`)
- `/middle_earth onboarding open [player]` — force-open the onboarding screen for self/target regardless of state (op tool for testing/support).
- `/middle_earth onboarding try open [player]` — same, but only if that player has **not** finished onboarding yet (silently/with a warning message otherwise); this is what the Player's Book's "Getting Started" chapter effectively triggers for a normal player.

---

## 5. World scale and coordinate layout

The Middle-earth dimension is a large, purpose-built custom map, not a vanilla-generated world. Key constants from `world/map/MiddleEarthMapConfigs.java`:
```
REGION_SIZE   = 3000
MAP_ITERATION = 3
PIXEL_WEIGHT  = 4
FULL_MAP_SIZE = 3000 * 2^3 * 4 = 96,000   (blocks, one side of the dimension)
```
All the spawn coordinates stored in the faction JSON (and shown throughout §1) are given in a **reduced "initial map" coordinate space**, not raw block coordinates. `MiddleEarthMapUtils.getWorldCoordinateFromInitialMap(x, z)` scales them up by a ratio (`ratioX`/`ratioZ`, effectively **32** — i.e. `PIXEL_WEIGHT(4) × 2^MAP_ITERATION(8)`) to obtain the real in-dimension block position before the height lookup runs. So, e.g., Minas Tirith's stored `(1945, 1785)` corresponds to roughly real block coordinates **(≈62,240, ≈57,120)** in the actual ~96,000×96,000-block Middle-earth dimension — the numbers in §1 are best read as relative/map-space positions for comparing regions to each other, not literal `/tp` coordinates.

Reading the raw (map-space) coordinates as a rough layout sanity-check:
- **South** (high x, high z, ~1400–2350 x / ~1600–1960 z): Gondor's provinces, Dol Amroth, Pelargir, Lebennin, Mordor's Gorgoroth/Nurn/Minas Morgul, Rohan's southern holdings — Gondor and Mordor are neighbors as lore dictates (Minas Tirith 1945/1785 vs. Minas Morgul 2029/1770 vs. Black Gates 2010/1608 are all close together).
- **West-central** (~1400–1700 x / ~1100–1600 z): Rohan (Edoras, Helm's Deep, Westemnet), Isengard/Orthanc (1402, 1467) sitting right at Rohan's western edge, Moria's gates (~1465–1546, ~1115–1143), Lothlórien (1614, 1215) and Dol Guldur (1793, 1210) also in this central band.
- **North** (low z, ~640–900): Dale/Esgaroth and Erebor/Iron Hills cluster tightly around (2000–2360, 720–790) in the northeast; Gundabad/Grey Mountains/Mount Gram cluster around (1400–1650, 640–690) in the north; Goblin Town (1583, 869) and Woodland Realm/Elvenking's Halls (1957, 766) sit between/around them.
- **Far west** (lowest x, ~930–980 x / ~900–970 z): the Shire (Hobbiton, Willowbottom) — isolated in the northwest corner, consistent with its lore placement far from the other realms.

This overall geography (Shire far west; Gondor/Mordor south-east adjoining each other; Rohan/Isengard/Moria/Lothlórien central; Dale/Erebor/Gundabad/Woodland Realm/Goblin Town north) matches Tolkien's map layout, just compressed onto the mod's ~96,000-block square dimension with a 32:1 scale factor between the data files' map-space units and actual blocks.
