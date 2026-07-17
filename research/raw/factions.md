# Middle-earth (Fabric 1.21.8, Seven Stars / Jukoz) — Factions & Alignment System

Deep-dive study of the mod's faction/diplomacy system. Sources:
- Data: `data/middle-earth/middle-earth/faction/*.json` (16 files: 14 top-level factions + 2 subfactions) inside
  `Middle-earth-1.0.0-1.21.8-beta.jar`, plus `assets/middle-earth/lang/en_us.json`.
- Code: decompiled classes under `net.sevenstars.middleearth.resources.datas.*`,
  `net.sevenstars.middleearth.registries.content.factions.*`,
  `net.sevenstars.middleearth.datageneration.providers.dynamic.FactionProvider`,
  `net.sevenstars.middleearth.commands.custom.CommandFaction`.

All 16 faction JSON files were read in full (brigand, dale, goblin_town, gondor, hobgoblin_tribes + hobgoblin_tribes.gundabad,
isengard, longbeards + longbeards.erebor, lothlorien, mordor, moria, rohan, shire, wild_goblins, woodland_realm).

---

## 1. Faction profiles

Each profile lists: id / display name / real-world realm represented / disposition / faction_type / joinable /
faction_selection_order_index / npc rank ladder + pools / banner. Lore quotes are from
`description.middle-earth.<id>.description_0` in the lang file (only present for the 12 "story" factions shown in the
onboarding screen; brigand, wild_goblins and the hobgoblin_tribes parent have no description string).

### Gondor — `middle-earth:gondor`
- Display name: **Gondor**. Represents: the South-kingdom of the Dúnedain (Men of the West), Minas Tirith/Dol Amroth etc.
- disposition: **GOOD** · faction_type: **FACTION** · joinable: **true** · selection order index: **0**
- Lore: "A last bastion for the Men of the West, the Kings of Gondor long stood watch over the neighbouring darkness. Though their line is broken, the stalwart Gondorians stand strong and fight to keep Mordor at bay."
- NPC ranks:
  - LEADER: `gondor.leader`
  - KNIGHT: `gondor.knight`
  - VETERAN: `gondor.veteran`, `gondor.king_guard`, `gondor.citadel_guard`, `gondor.fountain_guard`
  - CIVILIAN: `gondor.peasant`
  - MILITIA: `gondor.militia`
  - SOLDIER: `gondor.soldier`
- Banner: base **white**; patterns — gray `middle-earth:cloth`, black `minecraft:gradient`, white `middle-earth:tree` (the White Tree of Gondor).
- Spawns (9, all `dynamic: true`): anorien, ringlo_vale, lamedon, ithilien, lossarnach, minas_tirith, dol_amroth, lebennin, pelargir.

### Rohan — `middle-earth:rohan`
- Display name: **Rohan**. Represents: the Riddermark, Kingdom of the Rohirrim (horse-lords, line of Eorl).
- disposition: **GOOD** · faction_type: **FACTION** · joinable: **true** · selection order index: **1**
- Lore: "Rohan, also called the Riddermark, is a kingdom of Men renowned for its skilled horse-lords and cavalry... Though once strong allies with Gondor, Rohan now faces growing internal and external threats."
- NPC ranks:
  - LEADER: `rohan.horse_lord`, `rohan.eorling_marshal`
  - KNIGHT: `rohan.knight`
  - VETERAN: `rohan.knight`, `rohan.royal_guard`
  - CIVILIAN: `rohan.militia`
  - MILITIA: `rohan.militia`
  - SOLDIER: `rohan.soldier`
- Banner: base **white**; patterns — green `middle-earth:cloth`, lime `minecraft:gradient`, white `middle-earth:horse_head`.
- Spawns (6, all dynamic): edoras, eastemnet, westemnet, aldburg, helms_deep, the_wold.

### Dale — `middle-earth:dale`
- Display name: **Dale**. Represents: the rebuilt Kingdom of Dale (Men, Bard's line) near Erebor/Lake-town (Esgaroth).
- disposition: **GOOD** · faction_type: **FACTION** · joinable: **true** · selection order index: **2**
- Lore: "Dale is a flourishing kingdom of Men located near Erebor, rebuilt after its destruction by Smaug. Known for skilled archers and trade, it is ruled by Bard's descendants and maintains close ties with the Dwarves of Erebor."
- NPC ranks:
  - LEADER: `dale.sergeant`
  - KNIGHT: `dale.knight`, `dale.elite_archer`
  - VETERAN: `dale.veteran`
  - CIVILIAN: `dale.peasant`
  - MILITIA: `dale.militia`
  - SOLDIER: `dale.soldier`, `dale.archer`
- Banner: base **white**; patterns — blue `middle-earth:cloth`, blue `minecraft:gradient`, yellow `middle-earth:bell`.
- Spawns (2, dynamic): `dale.capital`, `dale.esgaroth` (Lake-town).

### Longbeards — `middle-earth:longbeards` (parent) / Erebor — `middle-earth:longbeards.erebor` (subfaction)
- **Longbeards** (parent, id `middle-earth:longbeards`): display **Longbeards** — Durin's Folk, the dwarven clan as a whole (Khazad-dûm/Erebor). disposition **GOOD**, faction_type **FACTION**, joinable **true**, selection order index **3**. Has no banner/npcs/spawns/initial_diplomacy of its own — it is a pure grouping node with `"subfaction": ["middle-earth:longbeards.erebor"]` and `"initial_diplomacy": []`.
  - Lore (on the parent id): "The Longbeards, descended from Durin the Deathless, are the most noble of the Dwarven clans... their realms include Erebor and Khazad-dûm."
- **Erebor** (`middle-earth:longbeards.erebor`): display name **Erebor**, the Lonely Mountain kingdom of Durin's Folk (King Dáin Ironfoot). disposition **GOOD**, faction_type **SUBFACTION**, joinable **true**, selection order index **4**, `"parent_faction": "middle-earth:longbeards"`.
  - Lore: "Erebor, or the Lonely Mountain, is the Dwarven kingdom of Durin's Folk. Reclaimed from Smaug by Thorin Oakenshield, it is now a prosperous center of wealth and craftsmanship, ruled by King Dáin Ironfoot. It forms a crucial alliance with the neighboring kingdom of Dale."
  - NPC ranks: LEADER `longbeards.erebor.leader`; KNIGHT `longbeards.erebor.elite`; VETERAN `longbeards.erebor.veteran`, `longbeards.erebor.gatewarden`; CIVILIAN `longbeards.erebor.peasant`, `longbeards.erebor.miner`; MILITIA `longbeards.erebor.militia`; SOLDIER `longbeards.erebor.soldier`, `longbeards.erebor.archer`.
  - Banner: base **white**; patterns — blue `middle-earth:cloth`, gray `minecraft:gradient_up`, white `middle-earth:dwarf_crown`.
  - Spawns (3, dynamic): ravenhill, iron_hills, iron_hills_spring.

### Lothlórien — `middle-earth:lothlorien`
- Display name: **Lothlórien**. Represents: the Golden Wood, Elven realm of Galadriel and Celeborn.
- disposition: **GOOD** · faction_type: **FACTION** · joinable: **true** · selection order index: **5**
- Lore: "Lothlórien, the Golden Wood, is an enchanted Elven realm ruled by Galadriel and Celeborn... one of the last strongholds of the Elves in Middle-earth."
- NPC ranks: LEADER `lothlorien.lord`; KNIGHT `lothlorien.knight`; VETERAN `lothlorien.guard`; CIVILIAN `lothlorien.sentinel`; MILITIA `lothlorien.ranger`; SOLDIER `lothlorien.warrior`.
- Banner: base **white**; patterns — yellow `middle-earth:cloth`, yellow `minecraft:gradient`, white `middle-earth:star_and_leaf`.
- Spawns (1, dynamic): `lothlorien.cerin_amroth`.

### Woodland Realm — `middle-earth:woodland_realm`
- Display name: **Woodland Realm**. Represents: the Elvenking's (Thranduil's) halls in Mirkwood/Eryn Galen.
- disposition: **GOOD** · faction_type: **FACTION** · joinable: **true** · selection order index: **6**
- Lore: "Eryn Galen, or Greenwood was once a vibrant forest... the Sylvan elves of the woodland realm reigning as the greatest among them. Now, through foul sorcery the land has been twisted into Mirkwood..."
- NPC ranks: LEADER `woodland_realm.commander`; KNIGHT/VETERAN/CIVILIAN/MILITIA all use `woodland_realm.ranger`; SOLDIER `woodland_realm.warrior`.
- Banner: base **white**; patterns — green `middle-earth:cloth`, gray `minecraft:gradient_up`, brown `middle-earth:elk`.
- Spawns (1, dynamic): `woodland_realm.elvenkings_halls`.

### Shire — `middle-earth:shire`
- Display name: **Shire**. Represents: the Hobbits' homeland in the northwest of Middle-earth.
- disposition: **GOOD** · faction_type: **FACTION** · joinable: **true** · selection order index: **7**
- Lore: "The Shire is a peaceful land inhabited by Hobbits... Known for its pastoral beauty and the simple, unadventurous lives of its people."
- NPC ranks: LEADER/KNIGHT/VETERAN/SOLDIER all use `shire.shirriff`; CIVILIAN `shire.peasant`; MILITIA `shire.militia`.
- Banner: base **white**; patterns — lime `middle-earth:cloth`, lime `minecraft:gradient`, yellow `minecraft:circle`, brown `middle-earth:pipe`.
- Spawns (2, dynamic): hobbiton, willowbottom.

*(Free-peoples roster = the 8 profiles above: Gondor, Rohan, Dale, Longbeards/Erebor, Lothlórien, Woodland Realm, Shire — all GOOD.)*

### Mordor — `middle-earth:mordor`
- Display name: **Mordor**. Represents: Sauron's realm (Barad-dûr, Gorgoroth, the Nazgûl).
- disposition: **EVIL** · faction_type: **FACTION** · joinable: **true** · selection order index: **0**
- Lore: "In the land of Mordor, where the shadows lie... The Black Legions await the orders of The Dark Lord in Barad-dûr with it's watchful Great Eye. The Nazgûls are seeking the One Ring and ready for the upcoming war."
- NPC ranks: LEADER `mordor.captain`, `mordor.black_numenorean`; KNIGHT `mordor.warrior`, `mordor.veteran`; VETERAN `mordor.veteran`; CIVILIAN `mordor.snaga`; MILITIA `mordor.militia`; SOLDIER `mordor.warrior`.
- Banner: base **black**; patterns — gray `middle-earth:cloth`, black `minecraft:gradient`, black `minecraft:triangle_bottom`, orange `middle-earth:small_circle`, red `middle-earth:eye_of_sauron`.
- Spawns (5, dynamic): gorgoroth, black_gates, dol_guldur, nurn, minas_morgul.

### Isengard — `middle-earth:isengard`
- Display name: **Isengard**. Represents: Saruman's Orthanc/Isengard, the "Hand" of the White Wizard turned traitor.
- disposition: **EVIL** · faction_type: **FACTION** · joinable: **true** · selection order index: **5**
- Lore: "Once a fortress of Númenor, the keys of the black tower of Orthanc passed to the White Wizard Saruman... Tempted by dark power, he weaves deceit and plots war from his seat in Isengard upon the neighbouring free peoples."
- NPC ranks: LEADER `isengard.leader`; KNIGHT `isengard.berserker`, `isengard.orthanc_guard`; VETERAN `isengard.veteran`; CIVILIAN `isengard.snaga`; MILITIA `isengard.warrior`; SOLDIER `isengard.soldier`, `isengard.scout`.
- Banner: base **white**; patterns — gray `middle-earth:cloth`, black `minecraft:gradient`, white `middle-earth:hand` (the White Hand of Saruman).
- Spawns (1, dynamic): `isengard.orthanc`.

### Moria — `middle-earth:moria`
- Display name: **Moria**. Represents: Khazad-dûm fallen to the Balrog/Orcs — "Moria" as it's now known.
- disposition: **EVIL** · faction_type: **FACTION** · joinable: **true** · selection order index: **4**
- Lore: "Khazad-Dûm, mansion of the Longbeards, spanning from the east range to the west. However, the dwarves dug too deep. Now in ruin, the ancient halls are home to Goblins, Trolls, and nameless things. With them the city earned a new name, Moria."
- NPC ranks: LEADER `moria.chief`; KNIGHT `moria.rider`; VETERAN `moria.veteran`; CIVILIAN `moria.goblin`; MILITIA `moria.militia`, `moria.scout`; SOLDIER `moria.warrior`.
- Banner: base **black**; patterns — black `middle-earth:cloth`, red `middle-earth:screeching_skull` (only 2 layers, the sparsest banner of any faction).
- Spawns (3, dynamic): west_gate, east_gate, goblin_camp.

### Goblin Town — `middle-earth:goblin_town`
- Display name: **Goblin Town**. Represents: the Northern Orcs' underground warren in the Misty Mountains (the Great Goblin's realm from The Hobbit).
- disposition: **EVIL** · faction_type: **FACTION** · joinable: **true** · selection order index: **3**
- Lore: "Goblin-Town was a dwelling of the Northern Orcs in the Misty Mountains. A network of branching caves and tunnels carved by the goblins with many entrances like the Front Porch. They often left their homes to raid the Anduin and plunder homes."
- NPC ranks: LEADER `goblin_town.veteran`; KNIGHT `goblin_town.rider`; VETERAN `goblin_town.veteran`; CIVILIAN `goblin_town.goblin`; MILITIA `goblin_town.scout`; SOLDIER `goblin_town.warrior`.
- Banner: base **black**; patterns — brown `middle-earth:cloth`, gray `middle-earth:cloth_gradient`, white `middle-earth:goblin_skull`.
- Spawns (1, dynamic): `goblin_town.goblin_town`.

### Hobgoblin Tribes — `middle-earth:hobgoblin_tribes` (parent) / Gundabad — `middle-earth:hobgoblin_tribes.gundabad` (subfaction)
- **Hobgoblin Tribes** (parent, id `middle-earth:hobgoblin_tribes`): display **Hobgoblin Tribes**. disposition **EVIL**, faction_type **FACTION**, joinable **true**, selection order index **1**. Like Longbeards, it is a bare grouping node: no banner/npcs/spawns, `"initial_diplomacy": []`, `"subfaction": ["middle-earth:hobgoblin_tribes.gundabad"]`.
- **Gundabad** (`middle-earth:hobgoblin_tribes.gundabad`): display name **Gundabad**. Represents: Mount Gundabad, the ancient Orc/Hobgoblin stronghold of the Grey Mountains, "allying with Sauron's forces."
  - disposition: **EVIL** · faction_type: **SUBFACTION** · joinable: **true** · selection order index: **2** · `"parent_faction": "middle-earth:hobgoblin_tribes"`.
  - Lore (on the subfaction id): "The Goblins of the Misty Mountains are a warlike race of Orcs inhabiting the caves and tunnels beneath the mountains. Once scattered, they now rebuild their strength, preying on travelers and allying with Sauron's forces."
  - NPC ranks: LEADER `hobgoblin_tribes.gundabad.leader`; KNIGHT `hobgoblin_tribes.gundabad.warrior`; VETERAN `hobgoblin_tribes.gundabad.veteran`; CIVILIAN `hobgoblin_tribes.gundabad.goblin`; MILITIA `hobgoblin_tribes.gundabad.militia`; SOLDIER `hobgoblin_tribes.gundabad.scout`.
  - Banner: base **white**; patterns — brown `middle-earth:cloth`, black `minecraft:gradient`, light_gray `minecraft:triangle_bottom`, gray `minecraft:triangles_bottom`, red `middle-earth:evil_eye` (5 layers — the most elaborate banner of any faction).
  - Spawns (3, dynamic): grey_mountains, mount_gram, gundabad.

*(Evil-realm roster above = Mordor, Isengard, Moria, Goblin Town, Hobgoblin Tribes/Gundabad — all EVIL, joinable, with lore/spawns/NPCs.)*

### Wild Goblins — `middle-earth:wild_goblins`
- Display name: **Wild Goblins**. Represents: unaligned goblin raiders (no fixed lore realm; no description string in lang).
- disposition: **NEUTRAL** · faction_type: **FACTION** · joinable: **false** · selection order index: **1**
- No `spawns` block (not selectable via onboarding; presumably a hostile-mob-only faction).
- NPC ranks: LEADER `wild_goblins.brute`; KNIGHT `wild_goblins.warrior`; VETERAN `wild_goblins.brute`; CIVILIAN `wild_goblins.gatherer`; MILITIA `wild_goblins.gatherer`; SOLDIER `wild_goblins.gatherer`, `wild_goblins.warrior`, `wild_goblins.scout`, `wild_goblins.rider`.
- Banner: base **black**; patterns — gray `minecraft:gradient_up`, red `minecraft:cross`, white `minecraft:skull` — identical banner layout to Brigand.

### Brigand — `middle-earth:brigand`
- Display name: **Brigand**. Represents: outlaws/bandits (Men gone rogue, e.g. the "Shirriff, open up!" dungeon advancement), not tied to a single LOTR army.
- disposition: **NEUTRAL** · faction_type: **FACTION** · joinable: **false** · selection order index: **0**
- No `spawns` block; not selectable via onboarding (matches wild_goblins as the game's two "wandering hostile" NPC factions).
- NPC ranks: LEADER/KNIGHT/VETERAN all `brigand.chieftain`; CIVILIAN `brigand.thug`; MILITIA `brigand.thug`, `brigand.thief`; SOLDIER `brigand.mercenary`.
- Banner: base **black**; patterns — gray `minecraft:gradient_up`, red `minecraft:cross`, white `minecraft:skull`.

---

## 2. GOOD vs EVIL roster (disposition)

The `DispositionType` enum (code) has **three** values: `GOOD`, `NEUTRAL`, `EVIL` — not a strict binary. Across the 16
faction files the exact disposition strings found are `"GOOD"`, `"EVIL"`, and `"NEUTRAL"` (all upper-case in JSON).

**GOOD (8):** Gondor, Rohan, Dale, Longbeards (+ Erebor subfaction), Lothlórien, Woodland Realm, Shire.

**EVIL (6):** Mordor, Isengard, Moria, Goblin Town, Hobgoblin Tribes (+ Gundabad subfaction).

**NEUTRAL (2):** Brigand, Wild Goblins — both `joinable: false`, both lacking `spawns`/lore descriptions, i.e. treated as
"wandering hostile" NPC pools rather than actual playable civilizations.

Counting parent+subfaction pairs as one "realm" each: 7 GOOD realms, 5 EVIL realms, 2 NEUTRAL (non-joinable) groups —
14 realms total, 16 registry entries (2 of them being subfactions).

---

## 3. Diplomacy matrix (initial_diplomacy / affinity)

### Affinity levels
The `AffinityLevel` enum (code) defines **four** values: `HOSTILE`, `NEUTRAL`, `FRIENDLY`, `ALLY`. However, across all
16 shipped `initial_diplomacy` lists, only **three** of the four ever actually appear: `HOSTILE`, `FRIENDLY`, `ALLY`.
`NEUTRAL` is a valid, loadable enum value but is **not used by any shipped faction's data** — every faction pair in the
JSON is explicitly either ALLY (self), FRIENDLY, or HOSTILE; there is no "unaligned/no-opinion" pairing shipped.

Every faction (except the two bare parent nodes, `longbeards` and `hobgoblin_tribes`, whose `initial_diplomacy` is `[]`)
lists an affinity entry for **every other top-level/subfaction id**, including itself (self-affinity is always `ALLY`).

### Blocs
The relationship map is a clean two-bloc structure, with the Free Peoples and the servants of Sauron/Saruman each
being mutually FRIENDLY internally and uniformly HOSTILE to the other bloc — plus two independent-evil factions
(Brigand, Wild Goblins) that are HOSTILE to literally everyone, including each other's fellow evils and their own kind
of neutral (each declares HOSTILE to all 15 other ids, ALLY only to itself).

**Free Peoples bloc** (Gondor, Rohan, Dale, Longbeards/Erebor, Lothlórien, Woodland Realm, Shire): every pairing between
two of these is `FRIENDLY` (self is `ALLY`). Every one of them lists `HOSTILE` toward all of: hobgoblin_tribes,
hobgoblin_tribes.gundabad, goblin_town, moria, mordor, isengard, wild_goblins, brigand.

**Sauron/Saruman ("evil realms") bloc** (Hobgoblin Tribes/Gundabad, Goblin Town, Moria, Mordor, Isengard): every pairing
between two of these is `FRIENDLY` (self `ALLY`). Every one of them lists `HOSTILE` toward all 7 Free Peoples factions
**and** toward Wild Goblins and Brigand. (E.g. Mordor is FRIENDLY to Gundabad/Goblin Town/Moria/Isengard but HOSTILE to
Wild Goblins and Brigand; same pattern for Isengard, Moria, Goblin Town, Gundabad.)

**Independent evils / neutrals** (Brigand, Wild Goblins): each is `HOSTILE` toward all 15 other registry ids (both
blocs) and `ALLY` only to itself. They are not FRIENDLY with each other or with the "evil realms" bloc, despite sharing
NEUTRAL disposition and (for Wild Goblins) an evil-coded playstyle — mechanically they are lone-wolf hostile factions.

Concrete example rows (Mordor's `initial_diplomacy`):
```
ALLY     mordor (self)
FRIENDLY hobgoblin_tribes, hobgoblin_tribes.gundabad, goblin_town, moria, isengard, wild_goblins... 
```
Wait — checking precisely: Mordor's json lists `wild_goblins` as **FRIENDLY** (not hostile) — this is the one exception
to "independent evils are hostile to everyone." Rechecking all 16 files for `wild_goblins` pairings:
- gondor/rohan/dale/longbeards/erebor/lothlorien/woodland_realm/shire → HOSTILE to wild_goblins
- hobgoblin_tribes.gundabad → HOSTILE to wild_goblins
- goblin_town → HOSTILE to wild_goblins
- moria → HOSTILE to wild_goblins
- **mordor → FRIENDLY to wild_goblins** (the one asymmetric exception in the whole dataset)
- isengard → FRIENDLY to wild_goblins
- brigand → HOSTILE to wild_goblins
- wild_goblins → ALLY to itself, HOSTILE to everyone else (including mordor and isengard back)

So the diplomacy graph is **not always symmetric**: Mordor and Isengard both declare Wild Goblins FRIENDLY, but Wild
Goblins declares HOSTILE back toward literally every other faction including Mordor and Isengard. Brigand is the only
faction that is HOSTILE toward Wild Goblins from both sides (mutual hostility) and is never FRIENDLY-declared by anyone.
This means "affinity" is a **per-faction, one-directional opinion list**, not a symmetric relationship table — each
faction's json is its own view of everyone else, and the two sides need not match.

Full HOSTILE/FRIENDLY summary per top-level id (self excluded):
| Faction | FRIENDLY with | HOSTILE toward |
|---|---|---|
| gondor | rohan, dale, longbeards, longbeards.erebor, lothlorien, woodland_realm(not listed! see note), shire | hobgoblin_tribes(+gundabad), goblin_town, moria, mordor, isengard, wild_goblins, brigand |
| rohan | gondor, dale, longbeards(+erebor), lothlorien, shire | same evil+neutral list |
| dale | gondor, rohan, longbeards(+erebor), lothlorien, shire | same |
| longbeards.erebor | gondor, rohan, dale, lothlorien, shire, longbeards(ally) | same |
| lothlorien | gondor, rohan, dale, longbeards(+erebor), shire | same |
| woodland_realm | gondor, rohan, dale, longbeards(+erebor), lothlorien, shire | same |
| shire | gondor, rohan, dale, longbeards(+erebor), lothlorien | same |
| mordor | hobgoblin_tribes(+gundabad), goblin_town, moria, isengard, **wild_goblins** | all 7 good factions, brigand |
| isengard | hobgoblin_tribes(+gundabad), goblin_town, moria, mordor, **wild_goblins** | all 7 good factions, brigand |
| moria | hobgoblin_tribes(+gundabad), goblin_town, mordor, isengard | all 7 good factions, wild_goblins, brigand |
| goblin_town | hobgoblin_tribes(+gundabad), moria, mordor, isengard | all 7 good factions, wild_goblins, brigand |
| hobgoblin_tribes.gundabad | goblin_town, moria, mordor, isengard, hobgoblin_tribes(ally) | all 7 good factions, wild_goblins, brigand |
| wild_goblins | (none — ALLY self only) | all 15 other ids |
| brigand | (none — ALLY self only) | all 15 other ids |

Note: `gondor.json` does not include an explicit `woodland_realm` line in its `initial_diplomacy` list (verified — its
16-entry list covers lothlorien, gondor(self), rohan, shire, longbeards, longbeards.erebor, dale, then the 8
evil/neutral ids; `woodland_realm` is simply absent from Gondor's own list, unlike every other Free Peoples faction
which does list it). This looks like a data-entry omission in the shipped `gondor.json` rather than an intentional
snub — every other Good faction's list is internally consistent.

---

## 4. Code semantics — disposition / affinity / faction_type / dynamic

Source: `net.sevenstars.middleearth.resources.datas.common.{DispositionType,AffinityLevel,FactionType,NpcRank}`,
`resources.datas.factions.{Faction,FactionLookup,FactionUtil}`, `resources.datas.factions.data.{InitialDiplomacy,SpawnData,SpawnDataHandler,BannerData}`.

- **`DispositionType`** — `enum { GOOD, NEUTRAL, EVIL }`. Purely a classification tag on the `Faction` object
  (`Faction.getDisposition()` / `getDispositionString()`), parsed via `DispositionType.valueOf(disposition.toUpperCase())`
  from the JSON `disposition` field. Used by `FactionLookup.getFactionsByDisposition(world, dispositionType)`, which
  filters all **joinable, top-level (`FactionType.FACTION`)** factions by disposition — this is what powers the
  Good/Evil grouping in the faction-selection/onboarding UI (`OnboardingFactionScreen*`). It also drives a lang key:
  `getName()` resolves `"disposition.middle-earth.<good|neutral|evil>"` (lang has "Good"/"Neutral"/"Evil").

- **`AffinityLevel`** — `enum { HOSTILE, NEUTRAL, FRIENDLY, ALLY }`. Used only inside `InitialDiplomacy` (one entry per
  target faction id). `InitialDiplomacy.isHostileToward(class_2960 faction)` returns true only if the stored affinity
  for that specific target id equals `HOSTILE` — i.e. mechanically, the only affinity value actually consumed by game
  logic surfaced in decompiled code is the HOSTILE check (`Faction.isHostileToward(playerFaction)` iterates its
  `initialDiplomacies` list and returns true if any entry both matches the id and is HOSTILE). This is presumably used
  to decide whether NPCs of faction A attack a player who is in faction B (guard/patrol aggro), and/or whether players
  of hostile factions can't enter each other's territory/spawns. FRIENDLY/ALLY/NEUTRAL are stored and serializable but
  no additional branching logic on them was found in the read classes (they read as "reserved for future
  diplomacy tiers" beyond the currently-implemented hostile/not-hostile check).

- **`FactionType`** — `enum { FACTION, SUBFACTION }`. `FACTION` = a top-level, independently selectable faction (or, for
  `longbeards`/`hobgoblin_tribes`, a bare "umbrella" grouping with no gameplay content of its own — no banner/npcs/spawns,
  empty `initial_diplomacy`). `SUBFACTION` = a faction with a `parent_faction` id (Erebor under Longbeards, Gundabad
  under Hobgoblin Tribes) that supplies the actual playable content (banner, npc pools, spawns, its own diplomacy list).
  `FactionLookup.getFactionsByDisposition` explicitly filters `faction.getFactionType() != FactionType.FACTION` out —
  meaning **subfactions are excluded** from the disposition-grouped listing helper, even though they're independently
  joinable; the UI selection screen presumably iterates joinable factions directly (parents + subfactions) rather than
  solely via that disposition helper, since Erebor/Gundabad do have their own `faction_selection_order_index` and
  `joinable: true`.
  `Faction.verifyData()` enforces (best-effort, exceptions swallowed) that any `FACTION` with no subfactions, or any
  `SUBFACTION`, must define the 5 core NPC ranks (MILITIA, SOLDIER, KNIGHT, VETERAN, LEADER) and a banner — i.e. bare
  umbrella factions (which have subfactions) are exempt from needing their own banner/NPCs, but the real leaf realms
  are expected to have all five.

- **`dynamic`** (on each `spawns.data[]` entry, e.g. `"dynamic": 1`/`true`) — boolean flag on `SpawnData`. When `true`,
  the raw `x`/`z` coordinates stored in the faction JSON are treated as coordinates on the mod's **abstracted/scaled
  overview map** (`MiddleEarthMapConfigs`/`MiddleEarthMapUtils`) rather than literal world block coordinates: at load
  time (`SpawnData` constructor) the `y` is forced to `0`, and at read time `getWorldCoordinateBlockPos()` multiplies
  x/z by `ratio = MiddleEarthMapConfigs.FULL_MAP_SIZE / 3000`, while `getBlockPos()` instead runs the coordinates
  through `MiddleEarthMapUtils.getInstance().getWorldCoordinateFromInitialMap(x, z)` plus
  `ModDimensions.getDimensionHeight(...)` to resolve the real in-world block position (including terrain height) at
  runtime. Every spawn in every shipped faction file has `"dynamic": 1` (true) — i.e. all shipped spawns use the
  scaled/derived-height system rather than fixed absolute coordinates.

- **NpcRank ladder** — `enum { CIVILIAN, MILITIA, SOLDIER, KNIGHT, VETERAN, LEADER }` (that's the enum declaration
  order; the task's suggested LEADER→KNIGHT→VETERAN→CIVILIAN→MILITIA→SOLDIER order is simply how each JSON's `ranks`
  array happens to be sorted — the enum itself runs low-to-high as civilian→militia→soldier→knight→veteran→leader).
  Faction JSON `npcs.ranks[]` is a list of `{rank, pool:[npc ids...]}`; `Faction`'s constructor parses each into a
  `HashMap<NpcRank, List<Identifier>> npcDatasByRank`. Unknown/malformed rank strings are silently swallowed
  (`try { NpcRank.valueOf(rankName) } catch (Exception e) {}` — empty catch), so a typo'd rank name in a faction JSON
  would just vanish rather than crash. `Faction.getRandomGear` / `getPreviewGear` / `getRandomNpcDataIdentifier` pick a
  random npc-data id from the appropriate rank pool(s) to determine spawned-NPC appearance/gear at runtime.

- **Loading/registration pipeline**: `Faction` is a **dynamic registry** (`DynamicRegistriesME.FACTION`,
  `class_5321<class_2378<Faction>>`), not a normal Fabric datapack "static" registry object — it has a full `Codec`
  (built with `RecordCodecBuilder`, fields: `id`, `faction_selection_order_index`, `joinable`, `disposition`,
  `faction_type`, optional `parent_faction`, optional `subfaction` list, optional `npcs`/`banner`/`spawns` NBT blobs,
  optional `command_join`/`command_leave` (max 5 entries each), and a required `initial_diplomacy` list of
  `InitialDiplomacy` — this exactly matches every field seen in the 16 JSON files). `FactionRegistry.bootstrap(context)`
  is the actual **code-side data source**: it registers 15 `class_5321<Faction>` keys (all top-level factions except a
  standalone Gundabad/Erebor key — those are derived via `MiddleEarth.of(parent.getPath(), "erebor"/"gundabad")`) using
  hand-written `*FactionPool` classes (`GondorFactionPool.GONDOR`, `MordorFactionPool.MORDOR`, etc. under
  `registries.content.factions.pools`) which construct in-memory `Faction` Java objects (disposition/type/banner/spawns/
  npcs/diplomacy all as Java code, not JSON, in the pool classes) — **this is the authoritative in-code definition**.
  `FactionProvider` (`extends FabricDynamicRegistryProvider`) is purely a **datagen exporter**: its `configure()` just
  dumps whatever is in the `DynamicRegistriesME.FACTION` dynamic registry (i.e., whatever `FactionRegistry.bootstrap`
  built) out to the `data/middle-earth/middle-earth/faction/*.json` files using the `Faction.CODEC` — this is exactly
  why the shipped JSONs are a 1:1 encoded mirror of the pool classes' Java-side definitions (which is also why the
  `*FactionPool.java` decompiled sources are the "real" data model and the JSONs are their generated/served form).
  At runtime, the dynamic registry loaded from the (JSON) datapack layer is what the game actually reads — `Faction`'s
  first constructor (taking raw `String`/`Optional<NbtCompound>` args + `Codec`) is the datapack-deserialization path,
  while the second constructor (taking already-typed `DispositionType`/`FactionType`/`BannerData`/etc.) is the one the
  `*FactionPool` classes call directly in code, and also auto-assigns `factionSelectionOrderIndex` per-disposition using
  a static `FactionSelectionOrderIndexPerDisposition` map if not explicitly given (though the shipped JSON always has an
  explicit `faction_selection_order_index`, so this appears to be a fallback/dev-convenience path).
  `CommandFaction` exposes `/middle-earth faction get|clear|join <id> [spawn_id]|banner <id>` (and a `/<mod> faction
  <player> ...` admin variant) which resolve ids through `FactionLookup.getFactionById` and delegate join/leave side
  effects (including running the faction's `command_join`/`command_leave` string lists) to `FactionUtil.updateFaction`.

---

## 5. Sub-faction parent/child relationships

- `middle-earth:hobgoblin_tribes` (FACTION, EVIL, bare umbrella, `initial_diplomacy: []`) → child
  `middle-earth:hobgoblin_tribes.gundabad` (SUBFACTION, EVIL, `parent_faction: "middle-earth:hobgoblin_tribes"`,
  full banner/npcs/spawns/diplomacy).
- `middle-earth:longbeards` (FACTION, GOOD, bare umbrella, `initial_diplomacy: []`) → child
  `middle-earth:longbeards.erebor` (SUBFACTION, GOOD, `parent_faction: "middle-earth:longbeards"`, full
  banner/npcs/spawns/diplomacy).

In both cases the **parent id is only a grouping/registry node** — it has no banner, no npc pools, no spawns, and an
empty diplomacy list — while all actual playable content lives on the child `SUBFACTION` entry. Both parents declare
`"subfaction": ["<child id>"]`, and both children declare the reciprocal `"parent_faction"`. Both parent and child are
independently `joinable: true` with their own `faction_selection_order_index` (Hobgoblin Tribes=1, Gundabad=2;
Longbeards=3, Erebor=4), meaning the onboarding/selection screen presents Gundabad and Erebor as separate choices
alongside (not nested under) their parent ids — the parent id mainly exists so other code (or future additional
subfactions) can group/query "all Longbeards" or "all Hobgoblin Tribes" as a family via `Faction.getSubFactions()` /
`getSubfactionById()` / `getParentFaction()`.

---

## File
Findings written to: `C:/dev/minecraft_mods/new-mod/research/raw/factions.md`
