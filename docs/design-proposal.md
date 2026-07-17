# Kindreds of Middle-earth — Skill-Tree Mod Design Proposal

**A lore-deep, per-race skill-tree & progression mod for the "Middle-earth - Age of Adventure" modpack (Fabric 1.21.8).**
Mod name: **Kindreds of Middle-earth** (mod id `kindreds`). Built on the base mod's races/factions (see `research/middle-earth-factions-races-study.md`); grounded in Tolkien canon (see `research/lore/*.md`).

> **Design creed:** power is *earned*, *specialized*, and *costly*. You become a legend by what you *do*, every strength has a lore-true weakness, and the world grows more dangerous as you grow stronger. Never easy-mode.

---

## 1. Balance philosophy (the rules that keep it hard)

1. **Earn by doing.** You gain skill in a discipline by *practising it* (mine → mining points; shoot → archery points; sneak → stealth points). Your **race scales the rate** (an Elf earns archery fast, a Dwarf earns mining fast) — race *nudges* you toward its fantasy without caging you.
2. **A hard ceiling.** Total points are capped low enough that you must *specialize*. You build one hero, not a god. **Mutually-exclusive branches** force real choices.
3. **Every buff has a cost.** Debuffs are lore-honest and meaningful (Elves uneasy in the deep dark; Dwarves risk dragon-sickness; Orcs dread the Sun; Rohirrim weak on foot). Optional **"curse nodes"** offer big power for a big drawback.
4. **Legends are earned by deeds.** The strongest capstones aren't bought — they're unlocked by *doing lore* (petrify a troll at dawn, forge mithril, reach Mount Doom). This also fills the base mod's **missing reputation system**.
5. **The world fights back.** Enemy difficulty **scales with your skill investment** — petrifying trolls, Nazgûl dread, the Balrog. Growing power meets growing danger.

---

## 2. The progression engine

- **Points:** per-discipline XP earned through activity (Combat, Archery, Mining, Stealth, Smithing, Survival, etc.), race-scaled gain. Spend points to unlock **nodes** on your race's tree.
- **Capstones (Legendary tier):** gated behind **lore-deed advancements** (reuse the base mod's advancement system), not points alone.
- **Respec:** costs a rare item (no free flip-flopping) — commitment matters.
- **Node types:** *passive* (grant Attribute modifiers — the base mod already uses attributes, so this is native) · *active* (keybind, cooldown) · *vision* (see §4) · *curse* (power + drawback).

---

## 3. The eight race fantasies

Each race is a *distinct playstyle*, not just bigger numbers. One-liner + signature buff + signature debuff + capstone:

| Race | Fantasy | Signature buff | Lore-true cost | Capstone (deed-gated) |
|---|---|---|---|---|
| **Elf** | Ambient ranger-survivor | Eyes of the Eldar (night-vision + outline foes), tireless, snow-walker, master archery | Deep-Dark Unease (weak in caves) | **Light of the Eldar** (Phial: blind evil, pierce dark) |
| **Man — Gondor** | Noble commander-healer | Hands of the King (athelas channel-heal), banners, valor vs fear | Mortal baseline (no innate resistances) | **The King Has Come** (banner+horn+heal ult) |
| **Man — Rohan** | Horse-lord | Mounted mastery: trample charge, fear immunity ahorse | **Zero bonuses on foot** — all power is mounted | **Ride of the Rohirrim** (dawn cavalry charge) |
| **Man — Dale** | Master archer | Bowyer perks, precision | Fire-vulnerable (Esgaroth burned) | **The Last Arrow** (one Smaug-slayer shot) |
| **Dwarf** | Deep-delver tank-smith | Stone-sense (ore through rock), fire-blood, forgemastery, grows stronger the deeper you dig | **Dragon-sickness** (hoard gold → Nausea + mob-draw) | **Durin's Bane-Slayer** (fire/knockback-immune fury) |
| **Hobbit** | Unseen burglar | Vanish (burst-invisibility), silent, stone-throw, fearless | Weak in stand-up melee, no heavy armor | **Thief of Erebor** (vanish + backstab) |
| **Orc / Goblin / Snaga** | Night-shadow predator | Dawnless-sight, night-power, pack tactics, cave-crawl, cannibal healing | **Dread of the Sun** (daylight morale/accuracy/speed penalty) | Orc **Servant of the Eye** · Goblin **Great Goblin's Horde** (summon) · Snaga **Whatever It Takes** (last-stand) |
| **Uruk** | Relentless sun-defying brute | Sun-Defiance (immune to daylight penalty), relentless chase, brute strength | Bred-thing tradeoffs (still cruel to allies) | **Fighting Uruk-hai** (unstoppable war-fury) |

*(Full cited ability catalogues + tiered branch sketches for every race are in `research/lore/{elves-and-men,dwarves-and-hobbits,evil-races}.md`.)*

---

## 4. The Vision system (signature pillar)

Skills change **how you see the world** — but only **one active "vision lens" at a time** (a real build choice; keeps the screen clean and balanced).
- **Dwarf Stone-sense** — ore/cavities glow through rock (reuses the proven see-through render layer). *Cheap.*
- **Elf Keen-sight** — brighten under stars, outline distant creatures friend(blue)/foe(red). *Moderate.*
- **Orc Dawnless-sight** — dark-red night-vision, but a harsh daylight bloom/desaturation (the sun-dread made *visible*). *Moderate.*
- **Hobbit Unseen** — fade toward invisible while sneaking + vignette. *Cheap.*
- **Ranger Tracking** — highlight mob trails/footprints. *Moderate.*
- **Wraith-sight** — desaturated shadow-world with glowing beings; tied to the Shadow system (power at a corruption cost). *Moderate.*
- **Tech:** default to shader-independent methods (see-through outlines, HUD tint/vignette overlays, status-effect brightness) so it's **Iris/Sodium-safe**; treat true post-process color-grading as an optional enhanced path. (Full tech in `research/lore/visual-vision-abilities.md`.)

---

## 5. The epic layers (what makes it *legendary*)

- **🔥 The Shadow / Corruption system.** A hidden meter. Wielding dark powers, carrying evil artifacts, or cruelty *corrupts* you — unlocking darker strength but risking permanent scars, drawing the Eye (Mordor hunts you), and warping vision toward the wraith-world. Good races resist & can cleanse; evil embrace it. Power with a soul-cost.
- **🔥 Deeds & Legend.** Re-enact lore to earn capstones and **titles** ("Dragon-slayer," "Elf-friend," "Bane of Durin"). Doubles as the **reputation layer the base mod lacks**.
- **🔥 The Fellowship bonus.** A diverse travelling party (Elf + Dwarf + Man + Hobbit…) grants shared synergy buffs — the more varied, the stronger (nine = peak). Mechanically rewards the story's core theme. Solo stays viable but harder.
- **🔥 Power tied to place & time.** Elves surge under starlight/in forests; Dwarves in the deeps/mountains; Orcs at night; Rohirrim on open plains at dawn. Leave your element and you're diminished — *where & when you fight* becomes a decision.
- **🔥 Song & horn magic.** Elven lulling songs, Dwarven war-chants, the Horn of the Mark — actives that shape a battlefield.
- **The world fights back.** Petrifying trolls, Nazgûl fear-aura + Black Breath (curable by king-gated athelas), the Balrog, Watcher in the Water, Shelob's Brood, "The Darkening" event.

---

## 6. The tree UX
A hand-built, **pannable/zoomable node canvas** (drag-pan, scroll-zoom): clear locked/available/owned coloring, prerequisite lines, hover tooltips with lore flavor, a build-clarity panel (your points, active vision, titles), a search, and a respec button. Amazing but simple — no clutter.

---

## 7. Tech foundation (brief)
- **Per-player skill data:** Fabric **Data Attachment API** (persistent, syncs, `copyOnDeath`) on the player — modern, clean.
- **Tree definitions:** a **data-driven dynamic registry** (mirrors the base mod's race/faction pattern) → datapack-extensible.
- **Buffs:** dynamic **AttributeModifiers** (+ custom attributes) & status effects — native to how the base mod already works.
- **Actives/vision:** keybind → C2S packet → server applies; rendering via the see-through layer + HUD overlays we've already proven.
- **Reading the player's race:** from the base mod's `sevenstars-api` / player data (compile-time accessor preferred, mixin fallback).

---

## 8. Phased roadmap (each phase is fun & playable on its own)

- **Phase 1 — The Foundation.** Skill-tree engine + data model, activity-based point earning, the tree-screen UX, and **2 complete race trees** (proposed: **Elf** + **Dwarf** — one passive/ambient, one active/utility) with real buffs *and* debuffs, plus **1 signature vision each** (Elf Keen-sight, Dwarf Stone-sense). Ship this and it's already great.
- **Phase 2 — All races + full Vision system.** Remaining 6 races' trees; the one-lens vision framework; place/time power.
- **Phase 3 — The epic layers.** Shadow/Corruption, Deeds & Legend/titles/reputation, the Fellowship bonus, Song magic.
- **Phase 4 — The world fights back.** Petrifying trolls, Nazgûl dread, bosses, The Darkening, difficulty scaling.

Each phase → its own spec → plan → build → play-test.

---

## 9. Locked decisions
1. **Race-locking → hybrid.** Core identity + capstone are **hard-locked** per race. A **few minor low-tier nodes are cross-trainable**, earned only via **reputation/deeds with a lore-friendly faction** (the "Elf-friend" mechanic). **Good↔Evil cross-training forbidden.** Identity stays sacred; a sliver of flexibility is the reward for legendary deeds.
2. **Death stakes → fully configurable, multiplayer-native.** Server-authoritative skill data, synced to clients → **works on MP servers natively**. Death penalty is a **server config**: default *keep skills*, options for *lose unspent points / lose a % / hardcore*. Plus config knobs: XP rates, enemy-scaling on/off, corruption on/off, Fellowship on/off. Chill and brutal servers both supported.
3. **Phase 1 → all 8 races.** Because once the engine + UX exist, a race tree is **authored data, not code**. Phase 1 = full engine + tree-screen UX + config/MP/death systems + a **focused first tree (~3 tiers, core buffs + real debuffs + signature vision) for every race**. Later phases deepen trees, add capstones, and layer the epic systems.
4. **Point granularity** → ~6–8 broad, readable disciplines (Combat, Archery, Mining, Stealth, Smithing, Survival, …), race-scaled gain.
5. **Name** → TBD (see candidates under discussion).

## 10. Architecture & code-quality principles (locked)
The mod must be **perfectly dynamic, clean, and future-maintainable**:
- **Data-driven / dynamic:** skill trees, nodes, abilities, and race bonuses are defined in **JSON via a synced dynamic registry** (mirrors the base mod's own pattern) — datapack-extensible. Adding or tuning a node/tree/whole race = **editing data, not code**. Abilities are composed from reusable **effect primitives** (grant-attribute, apply-status, unlock-vision, active-ability, curse) so new abilities are configuration, not new classes.
- **Clean modular architecture, single-responsibility packages:** `data/` (tree/node/ability model + registry) · `progression/` (XP, points, unlock rules) · `playerdata/` (Data Attachment save/sync) · `ability/` (effect primitives + appliers) · `vision/` (client render) · `client/screen/` (tree UI) · `network/` (packets) · `config/`. **Server-authoritative core, client presentation cleanly separated.** Each unit has a clear interface; you can understand/change one without breaking others.
- **Testable:** pure logic (point math, unlock/prereq rules, tree traversal, config parsing) is unit-tested; rendering/UI verified in-game.
- **Extensible / API:** expose a small addon API so other mods/datapacks can add races, trees, and abilities (as SevenStarsAPI does for the base mod).
- **Version-pinned to MC 1.21.8** (the rendering API changes at 1.21.9 — see `research/lore/vision-rendering-tech-1.21.8.md`).
- **Small, focused files**; follow the base mod's conventions where we interoperate.
