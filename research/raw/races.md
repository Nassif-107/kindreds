# Middle-earth (Seven Stars / Jukoz, Fabric 1.21.8) — Race System Deep Dive

Sources:
- Data (authoritative, extracted from the running jar):
  `Middle-earth-1.0.0-1.21.8-beta.jar!/data/middle-earth/middle-earth/race/{dwarf,elf,goblin,hobbit,human,orc,snaga,uruk}.json`
  `!/assets/middle-earth/lang/en_us.json`
  `!/data/middle-earth/tags/attribute/is_buff_reversed.json`
  `!/data/middle-earth/tags/block/climbing_attribute_unallowed_blocks.json`
- Decompiled code (CFR), base package `net.sevenstars.middleearth`:
  `resources/datas/races/Race.java`, `RaceUtil.java`, `RaceStatTooltip.java`
  `resources/datas/attributes/AttributePool.java`, `AttributePoolElement.java`
  `resources/datas/common/RaceType.java`, `EntityCategories.java`
  `registries/content/races/RacePools.java`, `RaceRegistry.java`
  `datageneration/providers/dynamic/RaceProvider.java`
  `entity/EntityAttributesME.java`
  `commands/custom/CommandRace.java`
  `network/packets/C2S/PacketSetRace.java`
  `resources/persistent_datas/{PlayerDataService,PlayerData}.java`
  `mixin/PlayerEntityMixin.java`, `mixin/ServerPlayerEntityMixin.java`, `mixin/PowderSnowBlockMixin.java`

---

## 0. THE HEADLINE FINDING — precedence resolution

**For a real player, `category_based_attributes` (`SHARED`/`MALE`/`FEMALE`) are never applied. Only `base_attributes.pool` reaches a player.** This is not a guess — it's structural in the code:

- `Race.applyPlayerAttributes(class_1657 player)` (`Race.java:134-136`):
  ```java
  public void applyPlayerAttributes(class_1657 playerEntity) {
      this.baseAttributePool.apply((class_1309)playerEntity);
  }
  ```
  This is the **only** attribute-application method ever called for players — from `PlayerDataService.setRace()` (`PlayerDataService.java:118-127`, which calls `newRace.applyPlayerAttributes(player)`), which is itself the only path reached by `RaceUtil.updateRace()` → which is called by `/race set` and `/race player <p> set` (`CommandRace.java`) and by the client "choose race" UI packet `PacketSetRace.process()`. There is no code path anywhere that calls `categoryBasedAttributePool` for a player.
- Compare with `Race.applyNpcAttributes(NpcEntity npcEntity)` (`Race.java:150-155`):
  ```java
  public void applyNpcAttributes(NpcEntity npcEntity) {
      AttributePool.reverse((class_1309)npcEntity);
      this.baseAttributePool.apply((class_1309)npcEntity);
      this.categoryBasedAttributePool.get(EntityCategories.SHARED).apply((class_1309)npcEntity);
      this.categoryBasedAttributePool.get(npcEntity.getNpcCategory()).apply((class_1309)npcEntity);
  }
  ```
  This layered method (reset → base → SHARED → gender) is **only** used for `NpcEntity` (the mod's non-player town/faction citizens), never for the player object.
- The in-game race-change tooltip preview (`RaceStatTooltip.draw`, `RaceStatTooltip.java:61`) also diffs the player's current attributes only against `race.getBaseAttributePool().getPool()` — further confirming category pools are deliberately excluded from anything player-facing.

**Mechanically why "last write wins":** `AttributePool.apply()` (`AttributePool.java:81-98`) does `attributeInstance.method_6203()` (clear existing modifiers) then `attributeInstance.method_6192(element.getValue())` — i.e. it **sets** (overwrites) the attribute's base value, it does not add to it. So when NPCs run base→SHARED→gender in sequence, each later pool that redefines an attribute id **completely replaces** the earlier value for that id; pools don't stack. Only the explicit per-element `modifiers` list (e.g. Goblin/Hobbit/Snaga's attack-damage penalty) stacks additively/multiplicatively on top of whatever base value was just set, because those are separate `AttributeModifier`s layered onto the attribute instance, not competing base-value writes.

**Practical upshot:** the `SHARED`/`MALE`/`FEMALE` numbers in each race JSON only matter for the mod's spawned NPC townsfolk (their health/speed/reach/scale/build), and for NPCs the numbers explicitly **override** anything the base pool set for the same attribute id. For a player who runs `/race set elf`, gender is not even tracked — a player Elf simply gets the `base_attributes` values, full stop. This resolves the example in the brief: Elf `base max_health=20` **wins** for a player (they have 10 hearts); the `SHARED max_health=14` only ever applies to an NPC Elf citizen (7 hearts), and only because `applyNpcAttributes` runs the SHARED pool after the base pool for NPCs specifically.

Because every attribute in every race's `base_attributes.pool` is a fixed `value` (never `min`/`max`) in these 8 files, a player's race stats are **completely deterministic** — no per-character randomization for players. (Randomization via `min`/`max`, resolved in `AttributePoolElement.getValue()` with `new Random().nextDouble(min,max)`, is only ever attached to `category_based_attributes` entries — i.e., again, NPC-only.)

---

## 1. Custom `middle-earth:` attributes

Defined in `entity/EntityAttributesME.java` (`register(name, default, min, max, tracked)`):

| Attribute id | Default | Min | Max | Synced | What it does |
|---|---|---|---|---|---|
| `middle-earth:powdered_snow_immunity` | 0.0 | 0.0 | 1.0 | yes | Boolean-ish flag (`!=0` = true). Checked in `mixin/PowderSnowBlockMixin.java:53` (`canWalkOnPowderSnow` inject): if the entity's living-attribute container has this attribute and its value `!= 0.0`, the entity is treated as immune to sinking/freezing in powder snow — the same effect leather boots normally grant, but baked into the race. |
| `middle-earth:delvers_fear_strength` | 0.0 | 0.0 | ∞ | yes | A "fear of the dark/deep" timer. In `mixin/ServerPlayerEntityMixin.java:170-193`, every 5 ticks: if this value > 0 **and** current light level < 3 **and** the player is not under open sky, a per-player "delvers fear" second-counter increments; once that counter exceeds the attribute value (in seconds), the player is given indefinite Darkness + Blindness + the mod's custom `ENSHROUDED` status effect. **Registered on the player attribute container but not set by any of the 8 current race files** (all default to 0.0, so the `> 0.0` guard never triggers for any current race) — i.e. it's a wired-up but currently unused/reserved mechanic, presumably intended as a Dwarven "fear of the deep"-style debuff for future content. |
| `middle-earth:climbing_strength` | 0.0 | 0.0 | ∞ | yes | Lets a humanoid cling to a wall like a vanilla spider/ladder. `mixin/PlayerEntityMixin.java:120-134` injects into `isClimbing()`: while the entity is not swimming, not on the ground, and pressed against a wall (`PlayerUtil.isAgainstWall`), a `climbDistance` tick-counter increments; as long as `climbDistance < climbing_strength`, `isClimbing()` returns `true` (so the entity clings/slow-falls against the wall instead of dropping). Reset to 0 once the entity touches ground. Only **Goblin** sets this (base 100, NPC SHARED 80) — it is the mechanical basis of goblins' wall-crawling ability. There's also an (empty, currently unpopulated) block tag `middle-earth:climbing_attribute_unallowed_blocks` presumably meant to blacklist certain blocks from being climbable this way. |
| `middle-earth:detection_range` | 1.0 | 0.1 | 1.0 | yes | Registered on the player attribute container (`PlayerEntityMixin.java:117`) and given a display name/tooltip entry ("Detection Range"), and it's tagged `is_buff_reversed` (lower = better). Set by **Hobbit only** (base 0.8). No additional runtime consumer of the value was found elsewhere in the decompiled mixins searched (only registration + tooltip diffing) — it reads as a stealth/"harder to notice" stat consistent with hobbit lore, wired into the attribute system but its concrete AI-detection consumer wasn't located in this codebase pass (may live in obfuscated mob-brain/sensor code not textually matched, or be a partially-implemented feature). |
| `middle-earth:width_scale` | 1.0 | 0.1 | 2.0 | yes | Independent horizontal (girth) scale multiplier, separate from `minecraft:scale` (which scales overall size/height too). Used to make e.g. male Dwarves stockier (width_scale up to 1.10) vs. female Dwarves slimmer (up to 1.03) at a similar height `scale`. **Only ever appears inside `MALE`/`FEMALE` category pools — i.e. it is NPC-only** (never touches a player, per §0), used for varying the build/silhouette of spawned NPC citizens of each race and gender. |

Additionally, `minecraft:attack_damage` entries for Goblin/Hobbit/Snaga carry an explicit modifier block:
```json
"modifiers": [{ "id": "middle-earth:total_damage", "operation": "ADD_MULTIPLIED_TOTAL", "value": -0.1 (or -0.2) }]
```
`ADD_MULTIPLIED_TOTAL` is vanilla Minecraft's attribute-modifier operation that multiplies the attribute's fully-resolved total by `(1 + value)`. A value of `-0.1`/`-0.2` therefore means "final attack damage total is multiplied by 0.9 / 0.8" — i.e. a flat **-10%** (Goblin) or **-20%** (Hobbit, Snaga) reduction to whatever damage the entity would otherwise deal (weapon damage included), layered on top of setting the base `attack_damage` value itself (Goblin's `attack_damage` base value is even set to a nominal `1.0` before the -10% is applied). The lang key `attribute.modifiers.total_damage` = "Add Total Damage Multiplied" is just the tooltip label for this modifier.

The tag `data/middle-earth/tags/attribute/is_buff_reversed.json` lists 4 attribute ids whose tooltip coloring is inverted (a **decrease** shows as an improvement): `middle-earth:detection_range`, `minecraft:scale`, `minecraft:fall_damage_multiplier`, `minecraft:burning_time`. This is purely cosmetic (drives green/red arrows in `RaceStatTooltip`), it does not change how the attribute functions.

---

## 2. Vanilla attribute glossary (as used by these races)

| Attribute id | Plain-English meaning | Vanilla player default |
|---|---|---|
| `minecraft:scale` | Overall size/height multiplier of the model & hitbox. | 1.0 |
| `minecraft:max_health` | Max HP (÷2 = hearts). | 20.0 (10 hearts) |
| `minecraft:movement_speed` | Walking speed multiplier. | 0.1 |
| `minecraft:attack_damage` | Base unarmed/melee damage attribute (weapons add on top). | 1.0 |
| `minecraft:entity_interaction_range` | Reach for interacting with/attacking entities. | 3.0 |
| `minecraft:block_interaction_range` | Reach for mining/placing/using blocks. | 4.5 |
| `minecraft:sneaking_speed` | Speed multiplier applied while sneaking. | 0.3 |
| `minecraft:fall_damage_multiplier` | Multiplier on fall damage taken (lower = less damage). | 1.0 |
| `minecraft:movement_efficiency` | Reduces the movement-speed penalty from difficult terrain (soul sand, honey, etc.); 1.0 = immune to that penalty. | 0.0 |
| `minecraft:mining_efficiency` | Flat mining-speed bonus, like a built-in partial Efficiency enchant. | 0.0 |
| `minecraft:step_height` | Height the entity can walk up without jumping. | 0.6 |
| `minecraft:burning_time` | Multiplier on how long the entity stays on fire once ignited. | 1.0 |

---

## 3. Per-race attribute breakdown

For each race: `base_attributes` (→ applies to a **player** of that race, deterministically, per §0), then `category_based_attributes.SHARED` / `MALE` / `FEMALE` (→ NPC-only, layered as base→SHARED→gender, last write wins per attribute id).

### Dwarf (`middle-earth:dwarf`, display "Dwarf")
- **base_attributes (→ PLAYER stats)**
  - `scale` 0.81 — 81% normal height/size.
  - `max_health` 22.0 — **11 hearts** (+1 heart over vanilla).
  - `entity_interaction_range` 2.75 — reach reduced from vanilla 3.0.
  - `movement_speed` 0.09 — slightly slower than vanilla 0.1 (-10%).
  - `minecraft:mining_efficiency` 0.2 — built-in mining-speed bonus (partial free Efficiency).
  - *(no attack_damage/reach-block/etc. overrides — those stay vanilla)*
- **category_based_attributes (NPC only)**
  - SHARED: `scale` 0.81 (redundant with base), `entity_interaction_range` 1.75 (NPC reach much shorter), `movement_speed` 0.27 (NPC dwarves move ~3× faster than a player dwarf's 0.09).
  - MALE: `scale` random 0.75–0.81, `width_scale` random 1.05–1.10 (stockier/wider build).
  - FEMALE: `scale` random 0.73–0.79, `width_scale` random 1.00–1.03 (slimmer build).

### Elf (`middle-earth:elf`, display "Elf")
- **base_attributes (→ PLAYER stats)**
  - `scale` 1.06 — taller than normal (106%).
  - `max_health` 20.0 — **10 hearts**, vanilla default (no change).
  - `entity_interaction_range` 3.25 — longer reach than vanilla (+0.25).
  - `movement_speed` 0.1 — vanilla default (no change).
  - `middle-earth:powdered_snow_immunity` 1.0 — **immune to powdered snow** (no sinking/freezing, like leather boots).
  - `fall_damage_multiplier` 0.75 — **25% less fall damage**.
  - `movement_efficiency` 0.3 — reduced speed penalty on soul sand/honey/etc.
- **category_based_attributes (NPC only)**
  - SHARED: `max_health` 14.0 (NPC elves are **7 hearts**, much squishier than a player elf's 10), `entity_interaction_range` 1.75 (short NPC reach), `movement_speed` 0.3 (NPC elves move 3× faster than the base 0.1).
  - MALE: `scale` random 1.02–1.06, `width_scale` random 0.93–0.97 (slightly narrower than average).
  - FEMALE: `scale` random 1.00–1.03, `width_scale` random 0.91–0.94.

### Goblin (`middle-earth:goblin`, display "Goblin")
- **base_attributes (→ PLAYER stats)**
  - `scale` 0.75 — small (75% height).
  - `max_health` 14.0 — **7 hearts**.
  - `attack_damage` 1.0, with modifier `total_damage` `ADD_MULTIPLIED_TOTAL -0.1` — **net -10% total damage** dealt.
  - `entity_interaction_range` 2.5 — shortened reach.
  - `movement_speed` 0.105 — **+5%** over vanilla.
  - `mining_efficiency` 0.2 — mining-speed bonus.
  - `middle-earth:climbing_strength` 100.0 — can cling to a wall pressed against it for up to 100 ticks (5s) before needing to touch ground to reset (see §1) — **wall-climbing ability**.
- **category_based_attributes (NPC only)**
  - SHARED: `attack_damage` 0.9 (lower nominal base than the player's 1.0, before its own -10% wouldn't apply since goblin NPC attack modifier is inherited from base pool's modifier list — note: the modifier itself is attached to the base pool's element, and SHARED redefines only the base value 0.9 for the attribute id, while the earlier-applied modifier from the base pool call remains attached since `method_6203()`/clear happens per `apply()` call, so the SHARED apply-call would actually clear then re-add its own (empty) modifier list — meaning NPC goblins’ SHARED entry has **no attack modifier**, only the flat 0.9 value), `entity_interaction_range` 1.5, `movement_speed` 0.315, `climbing_strength` 80.0 (weaker wall-cling than a player goblin's 100).
  - MALE: `scale` random 0.72–0.76, `width_scale` random 0.96–1.02. *(No FEMALE pool defined for Goblin — only base + SHARED + MALE.)*

### Hobbit (`middle-earth:hobbit`, display "Hobbit")
- **base_attributes (→ PLAYER stats)**
  - `max_health` 14.0 — **7 hearts**.
  - `attack_damage` 1.0, modifier `total_damage` `ADD_MULTIPLIED_TOTAL -0.2` — **net -20% total damage**.
  - `entity_interaction_range` 2.5 — shortened reach.
  - `movement_speed` 0.1 — vanilla default.
  - `sneaking_speed` 0.435 — **+45%** faster than vanilla sneaking (0.3) — hobbits sneak almost at normal speed.
  - `fall_damage_multiplier` 0.9 — 10% less fall damage.
  - `middle-earth:detection_range` 0.8 — 20% harder to be detected (stealthier; lower = better per the reversed-buff tag).
  - `scale` 0.66 — **very small**, 66% height — the shortest playable race.
- **category_based_attributes (NPC only)**
  - SHARED: `entity_interaction_range` 1.75, `movement_speed` 0.3 (NPC hobbits move 3× faster than the player's 0.1).
  - MALE: `scale` random 0.63–0.68, `width_scale` random 0.95–1.02.
  - FEMALE: `scale` random 0.62–0.66, `width_scale` random 0.94–1.00.

### Human (`middle-earth:human`, display "Human")
- **base_attributes: EMPTY POOL (`"pool": []`)** — a player Human gets **zero attribute changes**: full vanilla stats across the board (scale 1.0, 20 HP/10 hearts, 0.1 speed, 3.0/4.5 reach, 1.0 attack, no immunities, no bonuses). Humans are the mechanically-neutral baseline race.
- **category_based_attributes (NPC only)**
  - SHARED: `movement_speed` 0.3, `entity_interaction_range` 1.75.
  - MALE: `scale` random 0.97–1.01, `width_scale` random 0.96–1.00.
  - FEMALE: `scale` random 0.95–1.00, `width_scale` random 0.97–1.01.

### Orc (`middle-earth:orc`, display "Orc")
- **base_attributes (→ PLAYER stats)**
  - `scale` 0.79 — 79% height.
  - `max_health` 16.0 — **8 hearts**.
  - `entity_interaction_range` 2.75 — shortened reach.
  - `movement_speed` 0.1 — vanilla default.
  - `sneaking_speed` 0.435 — same fast-sneak bonus as Hobbit (+45%).
- **category_based_attributes (NPC only)**
  - SHARED: `attack_damage` 1.0 (explicit, no modifier), `entity_interaction_range` 1.75, `movement_speed` 0.3.
  - MALE: `scale` random 0.75–0.80, `width_scale` random 0.95–1.02. *(No FEMALE pool.)*

### Snaga (`middle-earth:snaga`, display "Snaga")
- **base_attributes (→ PLAYER stats)**
  - `scale` 0.71 — small.
  - `max_health` 12.0 — **6 hearts — the lowest HP of any playable race**.
  - `attack_damage` 1.0, modifier `total_damage` `ADD_MULTIPLIED_TOTAL -0.2` — **net -20% total damage**.
  - `entity_interaction_range` 2.5 — shortened entity reach.
  - `block_interaction_range` 5.5 — **increased block/mining reach** (vanilla survival default 4.5) — the only race with a boosted block reach.
  - `movement_speed` 0.105 — +5% speed.
- **category_based_attributes (NPC only)**
  - SHARED: `entity_interaction_range` 1.5, `movement_speed` 0.315.
  - MALE: `scale` random 0.68–0.72, `width_scale` random 0.96–1.01. *(No FEMALE pool.)*

### Uruk (`middle-earth:uruk`, display "Uruk")
- **base_attributes (→ PLAYER stats)**
  - `scale` 1.0 — vanilla-sized.
  - `max_health` 18.0 — **9 hearts**.
  - `entity_interaction_range` 3.0 — vanilla default.
  - `movement_speed` 0.105 — +5% speed.
  - `minecraft:burning_time` 0.7 — **burns 30% less long** once set on fire.
- **category_based_attributes (NPC only)**
  - SHARED: `scale` 1.0, `max_health` 22.0 (NPC uruks are tankier, 11 hearts, vs. the player's 9), `entity_interaction_range` 1.75, `movement_speed` 0.27, `step_height` 0.7 (can step up slightly higher terrain than vanilla's 0.6).
  - MALE: `width_scale` random 0.95–1.01. *(No FEMALE pool; no MALE `scale` override — Uruk NPCs use the SHARED `scale` 1.0.)*

---

## 4. Comparison table — effective PLAYER stats (what actually matters when you `/race set`)

All numbers below are exactly the `base_attributes.pool` values (deterministic, no randomization, per §0). Blank = unchanged from vanilla default.

| Race | Size (scale) | Health (hearts) | Move speed | Entity reach | Block reach | Attack dmg | Sneak speed | Fall dmg ×  | Special |
|---|---|---|---|---|---|---|---|---|---|
| Dwarf | 0.81 | 22.0 (11) | 0.09 (-10%) | 2.75 | vanilla 4.5 | vanilla 1.0 | vanilla 0.3 | vanilla 1.0 | +0.2 mining_efficiency |
| Elf | 1.06 | 20.0 (10) | 0.1 (vanilla) | 3.25 (longest) | vanilla 4.5 | vanilla 1.0 | vanilla 0.3 | **0.75 (-25%)** | Powdered-snow immune; +0.3 movement_efficiency |
| Goblin | 0.75 | 14.0 (7) | 0.105 (+5%) | 2.5 | vanilla 4.5 | 1.0 × 0.9 (**-10% net**) | vanilla 0.3 | vanilla 1.0 | +0.2 mining_efficiency; climbing_strength 100 (wall-cling) |
| Hobbit | **0.66 (smallest)** | 14.0 (7) | 0.1 (vanilla) | 2.5 | vanilla 4.5 | 1.0 × 0.8 (**-20% net**) | **0.435 (+45%)** | 0.9 (-10%) | detection_range 0.8 (stealthier) |
| Human | 1.0 (vanilla) | 20.0 (10, vanilla) | vanilla 0.1 | vanilla 3.0 | vanilla 4.5 | vanilla 1.0 | vanilla 0.3 | vanilla 1.0 | none — pure vanilla baseline |
| Orc | 0.79 | 16.0 (8) | 0.1 (vanilla) | 2.75 | vanilla 4.5 | vanilla 1.0 | **0.435 (+45%)** | vanilla 1.0 | none |
| Snaga | 0.71 | **12.0 (6, lowest)** | 0.105 (+5%) | 2.5 | **5.5 (+1.0, highest)** | 1.0 × 0.8 (**-20% net**) | vanilla 0.3 | vanilla 1.0 | none |
| Uruk | 1.0 (vanilla) | 18.0 (9) | 0.105 (+5%) | 3.0 (vanilla) | vanilla 4.5 | vanilla 1.0 | vanilla 0.3 | vanilla 1.0 | burning_time 0.7 (-30% fire duration) |

(For reference, the corresponding NPC-only figures after base→SHARED→gender layering differ substantially for several races — most notably Elf NPCs drop to 14 HP/7 hearts and both Elf and Hobbit/Human/Dwarf/Orc/Goblin/Snaga NPCs get a flat ~0.27–0.315 movement speed and ~1.5–1.75 entity reach regardless of what the player-facing base pool says, per §3.)

---

## 5. Advantages / disadvantages (player-facing, plain English)

- **Dwarf** — Stocky (0.81 scale) and tanky (11 hearts, most HP alongside none higher), with a built-in mining-speed bonus (great for a fantasy "master miner" archetype). Downsides: 10% slower on foot and shorter combat reach (2.75) than a human/uruk; no attack or defensive bonuses otherwise.
- **Elf** — Tall (1.06 scale) with the **longest reach in the game (3.25)**, immune to powdered snow, takes 25% less fall damage, and moves normally through difficult terrain (soul sand/honey) thanks to movement_efficiency. Vanilla health and speed. Essentially a "ranger" kit: better positioning/reach and better mobility/survivability with no combat-damage penalty — one of the strongest all-around picks with no real drawback baked in for players (the seemingly weak SHARED `max_health=14` never applies to a player, only to NPC elves).
- **Goblin** — Small and fast (0.75 scale, +5% speed), can cling to walls for several seconds (climbing_strength 100) letting it scale surfaces other races can't, and gets a mining-speed bonus. Downsides: squishy (7 hearts) and deals 10% less damage overall, plus reduced combat reach (2.5).
- **Hobbit** — The stealth/utility race: dramatically faster sneaking (+45%), harder to be detected (detection_range 0.8), 10% less fall damage, and the smallest hitbox in the game (0.66 scale, easier to hide/slip through gaps). Downsides: lowest-but-one health (7 hearts), a hefty **-20% damage penalty**, and the shortest reach tier (2.5) alongside Goblin/Snaga.
- **Human** — Perfectly vanilla in every combat/movement stat (no bonuses, no penalties) — the "no downside, no upside" baseline race, useful if you want undiluted vanilla balance or don't want to commit to a race's tradeoffs.
- **Orc** — A durability/mobility hybrid: 8 hearts (more than Goblin/Hobbit/Snaga) plus the same big +45% sneak-speed bonus as Hobbit, with no attack or fall-damage penalties at all. Reach and speed are otherwise unremarkable (vanilla speed, 2.75 reach).
- **Snaga** — Has the single **lowest HP pool in the game (6 hearts)** and a **-20% damage penalty**, but uniquely gets **+1.0 block interaction range (5.5)** — the best miner/builder reach of any race — plus a small +5% speed bonus. A glass-cannon-adjacent "digger" archetype: bad in melee, best reach for mining/placing blocks at a distance.
- **Uruk** — The most physically imposing race with no size penalty (1.0 scale, vanilla-sized, unlike every other non-Human/Elf race which is shrunk), 9 hearts (second-highest after Dwarf), +5% speed, and **30% shorter burn duration** if set on fire. No combat or reach penalties. A strong "frontline brute" pick with almost no drawbacks relative to Human, at the cost of no special utility (no reach/stealth/climb/immunity bonus).

---

## 6. How a race is assigned to a player

1. **Command path** — `/middle-earth race set <race_id>` (self) or `/middle-earth race player <player> set <race_id>` (admin, requires permission level 2), registered in `CommandRace.register()` (`CommandRace.java:63-67`), with race-id autocompletion via `AllRaceSuggestionProvider`. Handlers `setRace`/`setTargetRace` (`CommandRace.java:104-145`) look up the `Race` object via `RaceLookup.getRace(world, raceId)` and call `RaceUtil.updateRace(player, race, true)`.
2. **Packet path (client race-picker UI)** — the client sends `PacketSetRace` (`network/packets/C2S/PacketSetRace.java`), a simple `{race: String}` payload; the server handler (`process()`, lines 50-61) resolves the `Race` via `RaceLookup.getRace(...)` and again calls `RaceUtil.updateRace(player, race, true)`.
3. **`RaceUtil.updateRace(player, race, shouldHeal)`** (`RaceUtil.java:22-38`) is the single choke point both paths funnel through:
   - Looks up the player's *previous* race from `PlayerDataService.getPlayerRace(...)`; if one exists, calls `previousRace.reverseAttributes(player)` (→ `AttributePool.reverse(player)`, which resets every registered attribute back to its vanilla/engine default) and clears the stored race (`PlayerDataService.setRace(player, world, null)`).
   - Calls `RaceUtil.reset(player)` (another full `AttributePool.reverse`) as a safety net.
   - If a new race was supplied, `PlayerDataService.setRace(player, world, race.getId())` — this both **persists** the race id and immediately calls `newRace.applyPlayerAttributes(player)` to push the new `base_attributes.pool` onto the player (`PlayerDataService.java:118-127`).
   - If `shouldHeal` is true (always true from both command and packet paths), the player is healed to full (`player.method_6025(player.method_6063())`) after the swap.
4. **Storage on the player** — `PlayerData` (`resources/persistent_datas/PlayerData.java`) holds a single field `private class_2960 race;` (a `ResourceLocation`/identifier pointing at the race's registry id, e.g. `middle-earth:elf`), set via `assignNewRace(class_2960 raceId)`. `PlayerData` is the mod's per-player persistent-state object, obtained through `StateSaverAndLoader.getPlayerState(player)` (world/player-attached persistent NBT state, separate from vanilla player NBT) and also holds faction, spawn, and origin-dimension data. On login, `RaceUtil.initializeRace(class_3222 player)` re-reads the stored race id and re-applies its attributes (so the race persists across sessions/relogs without needing to re-run any command).
5. **Race data itself is a datapack-driven dynamic registry entry** (not hardcoded per-player) — `RaceRegistry.bootstrap()` (`RaceRegistry.java:34-44`) registers the 8 `Race` objects built in `RacePools.java` into the dynamic registry `DynamicRegistriesME.RACE`; `RaceProvider` (a `FabricDynamicRegistryProvider`) is what serializes those registered entries out to the `data/middle-earth/middle-earth/race/*.json` files read in §3 above — i.e. the JSON files in the jar and the Java constants in `RacePools.java` are two views of the exact same data, confirmed identical field-for-field during this investigation.

There is **no gender field anywhere on the player** (`PlayerData` has no gender/category property) — gender/`EntityCategories` only exists as a property of spawned `NpcEntity` instances (`entityDataHolder.getNpcCategory()` in `entity/npcs/NpcEntity.java`), which is exactly why `MALE`/`FEMALE` pools are structurally NPC-only and can never be reached for a player character.
