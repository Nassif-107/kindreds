# Skill-Tree Design — meaningful, race-unique, zero filler

**Goal:** Re-author all 8 race skill trees so every node is a *real, noticeable* capability — a perk,
a vision, an active, a contextual power, or a substantial attribute — never a filler stat-bump. Each
race plays differently. Built on the perk engine (PerkDef + PerkService + handlers).

---

## The anti-filler rule (binding)
A node MUST be one of:
1. **A perk** (real mechanic — see palette).
2. **A vision** (`vision_unlock`) or **active** (`active`, with a real payload + cooldown).
3. **A contextual boon** (`contextual_boon` — power that wakes with place/time).
4. **A substantial attribute** — magnitude that is *felt*: e.g. `attack_damage`/`movement_speed`
   ≥ +8% (`add_multiplied_total` for attack_damage — `multiplied_base` is near-useless with weapons),
   `max_health` ≥ +2 (1 heart), `armor` ≥ +2, `mining_efficiency` ≥ +0.3, etc.
5. **A trade-off** (a strong boon + a `curse`).

NEVER: `+0.5` anything, or a node whose only content is a tiny stat. If a node feels skippable, cut
it or merge it. Fewer, stronger nodes beat many weak ones. Target ~24–34 nodes/race.

## Don't double-dip the base mod
Base `middle-earth` already sets per-race base stats (Dwarf 11♥ + slow + mining; Elf fall/snow;
Hobbit/Goblin/Snaga −attack; Uruk burn; Snaga reach; Goblin climb=100). Tree nodes ADD on top —
don't re-apply the same racial penalty/bonus; enhance new axes instead.

## Node JSON schema
```json
{ "id": "elf.archery.deadly_aim", "tier": 2, "pos": [-30, 30],
  "cost": { "discipline": "kindreds:archery", "points": 2 },
  "prereqs": ["elf.archery.keen_draw"],
  "exclusive_group": "elf.archery.spec",   // optional: nodes sharing a group lock each other (specializations)
  "abilities": [ { ... } ] }
```
- `pos`: lay branches in columns per discipline: x ∈ {-30, 0, 30}, y = tier*30 (0,30,60,90,120).
- `prereqs`: node ids required first (build a DAG, not a list).
- `exclusive_group`: two rival specialization tips share a group → picking one locks the other.
- Grand capstone: a node whose `prereqs` span **two disciplines** (convergent).

## Ability palette
**Perks** (`{"type":"perk","perk":"<id>","params":{...},"foe":"...","effect":{...}}`):
| perk | params | effect |
|---|---|---|
| `bane` | `bonus` | +bonus melee vs `foe` |
| `arrow_slaying` | `bonus` | +bonus bow damage vs `foe` |
| `mining_fortune` | `chance`,`amount` | chance to re-roll ore drops |
| `heal_on_kill` | `health`,`food` | restore on a kill |
| `strike_effect` | `chance`,`foe` + `effect` | inflict a status on a struck foe |
| `ally_aura` | `radius` + `effect` | buff nearby allied players |
| `war_pack` | `radius`,`per_ally`,`max` | +melee per nearby foe |
| `evasion` | `chance`,`reduction` | chance to shrug off part of a hit |

`foe` categories: `spider`, `undead`, `illager`, `dragon`, `orc`, `troll`, `goblin`, `any`.

**Other ability types:** `attribute` (op: `add_value`/`add_multiplied_base`/`add_multiplied_total`),
`status_effect` (permanent passive; use sparingly), `contextual_boon` (`when`:
`starlight`/`daylight`/`underground`/`darkness`/`deep_dark`/`dawn_dusk`/`low_health`), `vision_unlock`
(`keen_sight`, `stone_sense`), `active` (`ability_id`,`cooldown_ticks`,`effects[]`), `curse`.

**Valid attribute paths (1.21.8):** armor, armor_toughness, attack_damage, attack_knockback,
attack_speed, block_break_speed, block_interaction_range, burning_time, entity_interaction_range,
explosion_knockback_resistance, fall_damage_multiplier, gravity, jump_strength, knockback_resistance,
luck, max_absorption, max_health, mining_efficiency, movement_efficiency, movement_speed, oxygen_bonus,
safe_fall_distance, scale, sneaking_speed, step_height, submerged_mining_speed, sweeping_damage_ratio,
water_movement_efficiency. Base-mod: `middle-earth:climbing_strength`, `middle-earth:detection_range`
(0.1–1.0, lower=stealthier), `middle-earth:delvers_fear_strength`.

---

## Per-race blueprints
Each: disciplines → branch themes → signature nodes (mechanic) → specializations (exclusive) →
capstone. Fantasy in one line, then the nodes.

### ELF — *the deadly archer-warden of the woods* (archery, beast_lore, lore, song, survival)
- **Archery**: Keen Draw (+bow dmg%), Swift Nock (+draw/attack_speed), Piercing Shaft (arrow_slaying vs any), → **spec**: *Galadhrim Volley* (active: rapid multi-buff) **vs** *Black-fletched* (arrow_slaying vs orc/troll big bonus).
- **Beast_lore**: Wood-friend (tempt/follow), Spider-hunter (bane spider++ beyond birth), Silent Tread (sneaking_speed + detection lower).
- **Lore**: Star-glass (light_ward vision/active), Elf-sight (keen_sight radius++), Ancient Grace (contextual starlight regen/absorption).
- **Song**: Song of Healing (active: regen aura for allies via ally_aura), Rest of the Eldar (fatigue immunity already innate — enhance survival).
- **Survival**: Featherfall (safe_fall++), Fleet (movement contextual forest).
- **Capstone** (archery+lore): *Arrow of the Eldar* — active, big single-shot slaying + brief keen_sight.

### DWARF — *the unbreakable delver-smith* (combat, mining, runecraft, smithing)
- **Mining**: Deepening Fortune (mining_fortune tiers → chance/amount up), Vein-sense (stone_sense vision), Tireless Pick (mining_efficiency, submerged_mining_speed), Deep-Delver+ (contextual underground haste II).
- **Combat**: Axe-master (attack_damage%), Bane of Orcs (bane orc), Bane of Trolls (bane troll), Unmovable (knockback_resist + explosion_knockback_resist), → **spec**: *Berserker* (contextual low_health strength++ / heal_on_kill) **vs** *Ironbreaker* (armor + armor_toughness + resistance underground).
- **Smithing**: Forge-hardened (armor), Fireproof+ (burning_time down further).
- **Runecraft**: Rune of Warding (max_absorption contextual), Rune of Might (attack_damage contextual underground).
- **Capstone** (combat+mining): *Durin's Wrath* — active berserk (strength+resistance+speed).

### HOBBIT — *the unseen lucky survivor* (archery, beast_lore, song, stealth, survival)
- **Stealth**: Unseen (detection lower++), Slip Away (evasion chance), → **spec**: *Burglar* (luck++ + evasion) **vs** *Ghost* (invisibility active).
- **Survival**: Well-Provisioned (well-fed enhance / saturation contextual), Tough Little Folk (max_health +2, resilience).
- **Archery**: Conker-shot (arrow tweaks), Stone-thrower (thrown accuracy via attack).
- **Beast_lore**: Pony-friend (mount speed/tempt), Gentle (animals docile).
- **Song**: Merry Heart (ally_aura minor regen/resistance), Old Walking Song (movement).
- **Capstone** (stealth+survival): *Ringbearer's Resolve* — evasion big + resist harmful (already innate; enhance), brief invisibility on low_health.

### MEN — *the versatile captain* (archery, beast_lore, combat, leadership, smithing, song)
- **Leadership**: Rally (ally_aura strength), Banner (ally_aura resistance), Captain (war-horn active buff), → **spec**: *Warlord* (bigger ally_aura + self) **vs** *Standard-bearer* (wide-radius steady aura).
- **Combat**: Swordsman (attack_damage%), Shieldwall (armor + knockback_resist), Last Stand+ (contextual low_health enhance).
- **Archery**: Bowman of Dale, **Black Arrow** (arrow_slaying vs dragon/troll — Bard's dragon-slaying lineage, corrected to Men).
- **Smithing**: Master-smith (armor_toughness), Reforge (burning/durability flavor via attributes).
- **Beast_lore/Song**: Horse-lord (mount), Rohirric Song (ally movement).
- **Capstone** (leadership+combat): *Kingship* — grand ally_aura (strength+resist+speed) + self.

### URUK — *the elite day-marching shock-trooper* (combat, leadership, shadow, smithing)
- **Combat**: Brutal Blows (attack_damage%), Cleave (sweeping_damage_ratio), Bloodlust+ (heal_on_kill up), → **spec**: *Berserker Uruk* (war_pack big) **vs** *Black Guard* (armor + knockback).
- **Leadership**: Isengard Drill (war_pack radius/per_ally), Fear (strike_effect weakness on foe / ally_aura strength to orc-kin).
- **Shadow**: Sun-marcher (already sun-defiant; enhance daylight strength), Cruelty (strike_effect wither).
- **Smithing**: Steel of Isengard (armor + armor_toughness).
- **Capstone** (combat+leadership): *Fighting Uruk-hai* — war_pack max + heal_on_kill + strength.

### ORC — *the cruel night-raider* (beast_lore, combat, shadow, survival)
- **Combat**: Savage (attack_damage%), Bloodlust+ (heal_on_kill), Numbers (war_pack), → **spec**: *Reaver* (war_pack + heal_on_kill) **vs** *Brute* (max_health + knockback).
- **Shadow**: Night-strength+ (contextual darkness strength++), Cruel Blade (strike_effect poison/weakness), Dread (strike_effect on foe).
- **Survival**: Scavenger (heal/food on kill / foul-feeder enhance), Thick Hide (armor).
- **Beast_lore**: Warg-friend (mount/tame flavor), Pack-caller.
- **Capstone** (combat+shadow): *Spawn of Mordor* — night war_pack + heal_on_kill.

### GOBLIN — *the swarming tunnel-ambusher* (combat, mining, shadow, stealth)
- **Stealth**: Ceiling-lurker (climbing_strength++), Ambush (bane/strike from stealth), Skitter (detection lower + movement), → **spec**: *Wall-runner* (climbing huge + step_height) **vs** *Backstabber* (bane any from behind flavor / strike_effect).
- **Mining**: Tunnel-rat (mining_efficiency), Cave-fortune (mining_fortune).
- **Combat**: Swarm (war_pack), Nasty Bite (strike_effect poison).
- **Shadow**: Deep-dark strength (contextual), Cowardly Cunning (evasion in darkness flavor).
- **Capstone** (stealth+combat): *Goblin-town Horde* — war_pack + climbing + evasion.

### SNAGA — *the desperate poisoner-skirmisher* (archery, shadow, stealth, survival)
- **Stealth**: Beneath Notice (detection lower++), Coward's Speed (movement + evasion), → **spec**: *Skulker* (evasion + invisibility active) **vs** *Poisoner* (strike_effect poison++ + bane).
- **Archery**: Foul Darts (arrow strike-effect poison flavor via strike/attribute), Quick Loose (attack_speed).
- **Shadow**: Lash-driven (contextual darkness strength + heal_on_kill scraps), Filth (strike_effect wither low).
- **Survival**: Scrounger (heal/food on kill), Wretch's Endurance (resist).
- **Capstone** (stealth+shadow): *Slave-orc's Spite* — poison strike + evasion + darkness strength.

---

## Execution
1. Node reconcile is already in (tree attributes stick after base). ✓
2. Author each race's tree JSON per its blueprint (subagent per race), obeying schema + valid attrs +
   anti-filler rule + no double-dip.
3. Validate: `ResourceJsonLoadTest` (codec), `TreeAttributeIdsTest` (attr ids), `./gradlew build`.
4. Review each for filler / lore / balance; fix.
5. Update Codex/docs; playtest.
