# Mechanic Engine — Design Spec

**Goal:** Replace filler stat-bump skill nodes with *real, lore-grounded mechanics* the player
performs or triggers — driven by a flexible, data-first perk system so new abilities are added by
writing JSON + one small handler, never by touching core code.

**Status:** design locked; execution gated on base-mod recon (per-race stats + `climbing_strength`
ceiling) so numbers stack correctly and don't double-apply.

---

## The core problem this fixes
Today a skill node can only grant: an attribute, a status effect, a vision, an active buff, a curse,
a contextual buff, or a (handler-less) bane. That palette is *passive* — so every node reads as
"+0.5 something." The engine adds a general mechanism for **reactive/active game mechanics**.

## Architecture: one new ability type + a handler registry

### `PerkDef` — the single flexible ability type
```
{ "type": "perk", "perk": "<perk_id>", "params": { "<k>": <float>, ... },
  "foe": "<optional foe category>", "effect": { <optional StatusEffectDef> } }
```
- `perk` (string) — dispatches to a registered handler.
- `params` (`Codec.unboundedMap(STRING, FLOAT)`, default `{}`) — tunables (chance, amount, radius…).
- `foe` (optional string) — foe category for combat perks (`spider`, `undead`, `illager`, `orc`,
  `dragon`, `troll`, `any`).
- `effect` (optional `StatusEffectDef`) — payload for strike/aura perks.

Adding `PerkDef` to the sealed `AbilityDef` touches the four exhaustive switches **once**
(`AbilityApplier.apply`/`removeNode` = no-op like vision; `NodeTooltip.describe`/`flavor` = render
from perk id + params). After that, **every future perk is a handler + JSON — no core edits.**
This is the "so we can change many things" requirement.

`BaneDef` is folded in: `bane` becomes a perk id (`{"perk":"bane","foe":"spider","params":{"bonus":0.25}}`).
Remove the now-redundant `BaneDef` type (no data uses it yet) to keep one path.

### `PerkService` — resolution + event hooks (server-side)
- **Handler registry:** `Map<String, PerkHandler>` registered at init. A `PerkHandler` exposes which
  event(s) it wants and the logic.
- **Ownership resolution:** for a player, collect all `PerkDef`s from (a) unlocked skill-tree nodes
  and (b) their race's birth traits. Cache per-player; invalidate on unlock / respec / join /
  race-change (reuse the same signals `BirthTraitService` already listens to).
- **Prefer Fabric events over mixins** (crash-safety). Mapping:

| Mechanic family | Hook (no mixin) | Example perks |
|---|---|---|
| Bonus damage / on-hit effect vs foe | `AttackEntityCallback` (+ top-up damage / apply effect to victim) | Bane, orc poisoned blades, Elf's foe-bane |
| Extra drops when mining | `PlayerBlockBreakEvents.AFTER` | Dwarf **Delver's Fortune** (ore multiply), stone-eyes |
| On-kill reward | `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY` | Orc/Uruk **Bloodlust** (heal/food on kill), leadership champion, shadow deeds |
| Bow/arrow bonus vs foe | damage-source check in `ServerLivingEntityEvents.AFTER_DAMAGE` (source is arrow + player) | Men's **Black Arrow of Dale** (dragon/troll slaying), Elf precise volley |
| Ally aura / war-horn / passive tick | `PerkService` tick (1s, like `RacialNatureService`) | Men leadership aura, Fellowship gifts, Star-glass mob-ward |
| Passive capability flags | client/server tick already patterned (`WayfarerIceGrip`) | no-ice-slide, water-breath, step-assist, fall-threshold |

Mixins reserved ONLY if a mechanic truly can't be done via events (e.g. exact pre-mitigation damage
scaling); each such case gets its 1.21.8 target verified before writing.

## Perk catalogue (initial, lore-grounded — NO filler)
- `bane` — +`bonus`% melee damage vs `foe`. (Elf vs spawn of Shelob; Dwarf vs orc; etc.)
- `mining_fortune` — `chance` to drop `extra` of a mined ore/deepslate (Dwarf Delver's Fortune).
- `heal_on_kill` — restore `health`/`food` on a kill, optional cap (Orc/Uruk Bloodlust, cruelty).
- `strike_effect` — apply `effect` to the victim on melee hit, `chance` (poisoned/cursed blades).
- `arrow_slaying` — +`bonus`% bow damage vs `foe` (Men Black Arrow — dragon/troll; the dragon-slayer
  lineage of Dale/Bard, corrected from Dwarves).
- `ally_aura` — grant `effect` to allied players within `radius` (Men leadership; Fellowship synergy).
- `light_ward` — emit/keep light and cow undead nearby (Elf Star-glass / Phial of Galadriel).
- `sure_footed` — passive flags bundle (no-ice-slide already live for Men; extend as needed).

## Two flagged corrections (must honour when tuning)
1. **Base-mod stacking.** The base `middle-earth` mod already applies some racial stats in code
   (recon in progress). Our birth traits **add on top**. Before finalizing every `max_health` /
   movement / damage number, subtract what the base already applies so totals match the intended
   design (e.g. if base already gives Hobbit −hearts, our file must not remove them again).
2. **Climb way higher.** `middle-earth:climbing_strength` is a base-mod attribute (goblin already has
   `+2.0`, plus tree nodes). Recon will return its max/ceiling and unit; raise goblin's birth value
   (and the `wall_crawler`/`ceiling_lurker` tree nodes) toward the top of that range so goblins scale
   sheer walls dramatically, not weakly.

## Execution order (subagent-driven)
1. `PerkDef` type + sealed wiring + tooltip rendering + fold in `bane`; unit-test the codec + switches.
2. `PerkService` registry + ownership resolution + cache invalidation; unit-test resolution.
3. Event handlers, one family per task (mining, kill, melee, arrow, aura), each with tests where pure.
4. Re-author all 8 trees onto real perks — delete filler stat nodes; keep only meaningful ones.
   Rebase birth-trait numbers against base-mod recon; raise climbing.
5. Whole-branch review + playtest guide update.
