# Kindreds Phase 2 — "Blood, Craft & Doom" (Design Spec)

Status: APPROVED direction (user picked forks 2026-07-18). Build in shippable chunks; deploy each for playtest.

## Center of gravity
Everything is **Lord of the Rings / The Hobbit lore first**. Every race trait, tree branch,
node, ability, and title MUST be named from Tolkien (people, places, houses, artifacts, events)
so fans instantly recognize and nostalgize. Mechanics serve the lore, not the reverse.
Keep it HARD, BALANCED (every plus paired with a real minus), replayable, and highly configurable.

## What Phase 1 actually shipped (audit 2026-07-18)
- Race identity = **XP-rate scaling only** (RaceScaling). No innate birth traits exist in code.
- 77 nodes total (~9/race), max tier 3. Only Elf/Dwarf/Men fork (once each); other 5 are 2 straight
  lines + a curse leaf. `exclusive_group` and multi-prereq (convergence) are UNUSED.
- 18 "active" abilities are all self-buff status bundles — no AoE/ally/projectile mechanics.
- No active loadout/limit; keybind fires "first unlocked active".
- 6 of 8 vision lenses are data-only (no renderer). Only keen_sight + stone_sense render.
- Dead config knobs: pointSoftCap, respecItem/Cost, allowCrossTraining, enableEnemyScaling.

## Decisions (locked)
- **Sequencing:** Foundations first. Chunk order below. Playtest each chunk.
- **Race-screen birth-trait warning:** Companion **Lore Codex** screen + tooltips (do NOT mixin the
  base mod's race screen — stay standalone so base-mod updates can't break us).
- **Birth traits:** meaningful with genuine trade-offs; fully configurable.
- **Active loadout:** "Attunements" — start 1 slot, unlock up to ~3; assign which unlocked actives fill them.
- **Node count:** ~25–35 nodes/race, real branching.

## Pillars
1. **Birth Traits** — innate per-race pluses AND minuses applied at race selection, independent of
   nodes, reversible on race-change, configurable. Reuse the existing AbilityDef system (attribute/
   status/vision/curse) as the trait payload. Lore examples: Elf (keen-eyed, sleepless, +1 heart,
   hardy — vs Shadow-frail, poor brute trades); Dwarf (darkvision, fire-hardy, stubborn — vs slow,
   poor archery, gold-touched); Hobbit (unseen, lucky, corruption-resistant — vs weak heavy melee);
   Men (versatile, faster learning — no innate resistances = Gift of Men); Orc/Goblin/Snaga
   (night-strong, dark-mending, darkvision — vs sun-dread, fractious); Uruk (sun-defiant, strong,
   tireless — vs no finesse).
2. **Lore Codex + tooltips** — standalone screen browsing every race's birth pluses/minuses and full
   skill info, live. Opened from a button/key; also warns before you commit a race.
3. **Branching trees (deep, lore)** — shared racial trunk → 3 vocation branches that fork, with
   mutually-exclusive specialization capstones (exclusive_group) and convergent hybrid nodes
   (multi-prereq). ~25–35 nodes/race, tiers to 5. **EVERY one of the 8 races must truly branch**
   (no straight lines). Effects must be meaningful (not trivial +0.1 filler) and imaginative but
   canon. Every node lore-named so fans recognize/nostalgize.
4. **General trunk (cross-race)** — shared Might/Endurance/Craft/Wayfaring branches everyone can
   access (Pufferfish-style general skills) for common ground + build variety.
5. **Richer effects** — more varied passives; turn actives into REAL mechanics (AoE ally buffs,
   charged shots, war-horn rout); finish/scope the 6 missing vision lenses.
6. **Active loadout (Attunements)** — slot system (1→3), assign actives, keybind fires selected slot.
7. **Configuration** — wire every dead knob; add per-discipline XP rates, birth-trait & loadout
   toggles; in-game **Cloth Config** settings screen (pack ships cloth-config) + keep server JSON.
8. **Visual language** — distinct passive/active/vision/curse iconography + better tree art.

## Chunk plan (each ends deployable + playtested)
- **CHUNK 1 (Foundations):** Birth traits engine + 8 race birth-trait data (lore-named) + apply on
  join/race-change + config toggle; Lore Codex screen; Config wiring (dead knobs) + Cloth Config
  settings screen. Surface birth traits in /kindreds inspect for quick verification.
- **CHUNK 2 (Trees):** Rebuild all 8 trees to ~25–35 nodes with forks/exclusive/hybrids + General
  trunk + more/ varied passives. All lore-named.
- **CHUNK 3 (Actives):** Attunement loadout + real active mechanics + remaining vision lenses.
- **CHUNK 4 (Polish):** iconography, tree art, balance pass.

## Birth-trait technical approach (Chunk 1)
- New synced dynamic registry `kindreds:birth_trait`, data at
  `data/kindreds/kindreds/birth_trait/<race>.json`: `{ race, traits:[AbilityDef...],
  lore:{ pluses:[str], minuses:[str] } }`. Reuse AbilityDef codec for `traits`.
- Apply via existing AbilityApplier, tagged nodeId `"birth:<race>"` so modifiers are reversible.
- Track applied birth race in KindredData; on join/race-change, reverse old + apply new.
- Config: `enableBirthTraits` (+ future intensity). All trait numbers data-driven.
- Codex reads the same registry (pluses/minuses lore) so data + UI never desync.
