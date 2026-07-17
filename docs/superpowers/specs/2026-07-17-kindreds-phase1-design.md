# Kindreds of Middle-earth — Phase 1 Design Spec

**Mod:** Kindreds of Middle-earth (`kindreds`) · **Target:** Minecraft **1.21.8**, Fabric, **client + server** · **Java 21**
**Depends:** Fabric API; interoperates with the base **Middle-earth** mod (`middle-earth` / `sevenstars-api`) to read a player's race.
**Design authority:** `docs/design-proposal.md` + `research/middle-earth-factions-races-study.md` + `research/lore/*.md`.

> **Phase 1 delivers a complete, playable, balanced foundation:** the skill-tree *engine*, the *progression* system, an *awesome lore-themed* tree UI, the *config/MP/death* systems, and a **focused first tree (~3 tiers) for all 8 races** — each with real buffs, real debuffs, and its **signature vision**. Deeper tiers, the Shadow/Corruption/Deeds/Fellowship epic layers, and the tougher-world enemies are **later phases**.

---

## 1. Scope

**In Phase 1**
- Data-driven skill-tree engine (trees/nodes/abilities/disciplines/themes defined in JSON via a synced dynamic registry).
- Activity-based, race-scaled progression → per-discipline points → unlock nodes; lore-deed (advancement) gating for capstones.
- Per-player persistent, server-authoritative skill data (Fabric Data Attachment) with client sync.
- Ability **effect primitives**: grant-attribute-modifier, apply-status-effect, unlock-vision, active-ability (keybind + cooldown), curse.
- The **tree screen UX** — lore-themed per race, pannable/zoomable, node states, tooltips, discipline gauges, titles, respec.
- The **Vision framework** + 2 signature visions shipped (Elf **Keen-sight**, Dwarf **Stone-sense**), Iris-aware.
- **Config** (server-authoritative): death penalty, XP rates, feature toggles.
- **First tree for all 8 races** (Elf & Dwarf fully specced here; the other 6 authored as data during build, guided by `research/lore/*.md`).

**Deferred (later phases):** deeper tiers & all capstones, full vision set + place/time power (P2); Shadow/Corruption, Deeds/Legend titles at scale, Fellowship bonus, song magic (P3); tougher-world enemies, bosses, The Darkening, enemy scaling (P4).

---

## 2. Global constraints
- MC **1.21.8** (pinned — the render API changes at 1.21.9; see `research/lore/vision-rendering-tech-1.21.8.md`).
- Fabric Loader ≥ 0.16, Fabric API, Java 21. `environment: "*"` (client + server).
- Mod id **`kindreds`**; base package **`com.kindreds`**.
- **Server-authoritative:** all progression, unlock, and ability logic runs on the server; the client renders UI/vision and sends intents (open screen, request-unlock, activate-ability) as packets. Prevents cheating and makes MP correct by construction.
- **Data-driven & extensible:** adding/tuning a discipline, node, ability, tree, or theme = editing JSON, not code. A small **addon API** lets other mods/datapacks register content.
- **Clean modular packages** (§3). Small, focused files. Pure logic unit-tested.

---

## 3. Architecture

```
com.kindreds
├─ Kindreds.java                 (common init: registries, attachments, config, network, commands)
├─ KindredsClient.java           (client init: keybinds, screens, vision renderers, S2C handlers)
├─ data/                         DATA MODEL + REGISTRY (server+client, synced)
│   ├─ Discipline.java           record: id, displayName, theme color, icon
│   ├─ SkillTree.java            record: raceId, theme, List<SkillNode>, root(s)
│   ├─ SkillNode.java            record: id, tier, position(x,y), cost{discipline,points},
│   │                                    prereqs[], List<AbilityDef>, deedAdvancement?(capstone), exclusiveGroup?
│   ├─ ability/AbilityDef.java   sealed: AttributeMod | StatusEffect | VisionUnlock | ActiveAbility | Curse
│   ├─ Theme.java                record: background tex, frame style, accent color, node sprites, font, sfx
│   └─ KindredsRegistries.java   dynamic registries (DISCIPLINE, SKILL_TREE) + registerSynced
├─ progression/                  ENGINE (server)
│   ├─ ProgressionService.java   award XP for activity, XP→level→points, race-scaled rates, caps
│   ├─ UnlockService.java        validate + apply a node unlock (points, prereqs, exclusivity, deed gate)
│   ├─ ActivityHooks.java        event/mixin hooks: block-break→Mining, bow-shoot→Archery, kill→Combat, sneak→Stealth, craft→Smithing, eat/explore→Survival, discovery→Lore
│   └─ RaceScaling.java          per-race discipline gain multipliers (data-driven)
├─ playerdata/                   PLAYER STATE (server-authoritative, synced)
│   ├─ KindredData.java          per-player: disciplineXp{}, unlockedNodes[], activeVisionLens, titles[], cooldowns{}
│   ├─ KindredAttachment.java    AttachmentRegistry.createPersistent(copyOnDeath-per-config, syncWith)
│   └─ RaceAccess.java           read base-mod race (sevenstars-api accessor; mixin fallback)
├─ ability/                      EFFECT APPLICATION (server)
│   ├─ AbilityApplier.java       apply/remove AbilityDef → AttributeModifiers / StatusEffects / vision flags
│   ├─ ActiveAbilityService.java keybind-triggered actives: cost, cooldown, effect
│   └─ CurseService.java         curse nodes: net buff + drawback
├─ vision/                       CLIENT RENDER (Vision framework + Phase-1 lenses)
│   ├─ VisionManager.java        one active "lens" at a time; toggle keybind; Iris detection
│   ├─ SeeThroughLayer.java      reuse mithril NO_DEPTH_TEST RenderLayer (outlines through terrain)
│   ├─ lens/StoneSenseLens.java  Dwarf: ore/cavity outlines + underground brightness
│   ├─ lens/KeenSightLens.java   Elf: friend/foe entity outlines + starlight gamma
│   └─ overlay/HudTintOverlay.java  HudElementRegistry full-screen tint/vignette (Iris-safe)
├─ client/screen/                THE UX
│   ├─ SkillTreeScreen.java      pannable/zoomable canvas, node states, tooltips, side panel
│   ├─ TreeRenderer.java         draws themed background/frame/nodes/edges per Theme
│   ├─ NodeTooltip.java          lore-flavored ability card
│   └─ ThemeAssets.java          resolves per-race Theme textures/colors/fonts/sfx
├─ network/                      PACKETS
│   ├─ C2S: OpenTreeC2S, RequestUnlockC2S, SetVisionLensC2S, ActivateAbilityC2S, RespecC2S
│   └─ S2C: SyncKindredDataS2C, UnlockResultS2C, VisionStateS2C
├─ config/KindredsConfig.java    server config (death penalty, XP rates, toggles) — JSON, hot-reloadable
├─ command/KindredsCommand.java  /kindreds (admin: grant points, set race read, reload, inspect)
└─ api/KindredsAPI.java          addon hooks: register discipline/tree/ability-type/theme
```

**Data flow:** activity (server event) → `ProgressionService` awards discipline XP → points. Player opens tree (keybind → `OpenTreeC2S`) → server sends `SyncKindredDataS2C` → `SkillTreeScreen` renders. Player clicks unlock → `RequestUnlockC2S` → `UnlockService` validates (points/prereqs/exclusivity/deed) → applies via `AbilityApplier` → `SyncKindredDataS2C` back. Actives: keybind → `ActivateAbilityC2S` → `ActiveAbilityService`. Vision: keybind → `SetVisionLensC2S` (server records) + client `VisionManager` renders.

---

## 4. Data model & JSON schemas (data-driven)

**Discipline** (`data/kindreds/discipline/*.json`)
```json
{ "id": "kindreds:archery", "name": "Archery", "color": "#8FbC6C", "icon": "kindreds:textures/gui/discipline/archery.png" }
```
Phase-1 disciplines: `combat, archery, mining, stealth, smithing, survival, lore`.

**Skill tree** (`data/kindreds/skill_tree/*.json`) — one per race, synced dynamic registry.
```json
{
  "race": "middle-earth:elf",
  "theme": "kindreds:theme/elf",
  "nodes": [
    {
      "id": "elf.keen_sight",
      "tier": 1,
      "pos": [0, -2],
      "cost": { "discipline": "kindreds:archery", "points": 1 },
      "prereqs": [],
      "abilities": [ { "type": "vision_unlock", "lens": "kindreds:keen_sight" } ]
    },
    {
      "id": "elf.deep_dark_unease",
      "tier": 1, "pos": [1, -1],
      "cost": { "discipline": "kindreds:lore", "points": 1 },
      "prereqs": ["elf.keen_sight"],
      "abilities": [
        { "type": "attribute", "attribute": "minecraft:max_health", "op": "add_value", "amount": 2 },
        { "type": "curse", "when": "in_deep_dark",
          "effect": { "type": "attribute", "attribute": "middle-earth:delvers_fear_strength", "op": "add_value", "amount": 20 } }
      ]
    }
  ]
}
```

**Ability primitives** (`AbilityDef`, sealed):
- `attribute` — `{attribute, op(add_value|add_multiplied_base|add_multiplied_total), amount}` → an `AttributeModifier` applied while the node is owned. (The base mod already drives races via attributes; native fit.)
- `status_effect` — `{effect, amplifier, permanent|contextual}` (e.g. Night-Vision while underground).
- `vision_unlock` — `{lens}` → makes a Vision lens available to equip.
- `active_ability` — `{id, keybind, cooldown, cost{discipline_points?|hunger?}, effect}` → keybind-triggered.
- `curse` — `{when(context), effect}` → applies a drawback (optionally only in a context) alongside the node's buff.

**Theme** (`data/kindreds/theme/*.json`) — makes the UX lore-themed **as data**:
```json
{ "id": "kindreds:theme/elf",
  "background": "kindreds:textures/gui/tree/elf_starlit_parchment.png",
  "frame": "kindreds:textures/gui/frame/elf_mallorn.png",
  "accent": "#CFe8FF", "edge_style": "vine",
  "node": { "locked": "...leaf_dim.png", "available": "...leaf_glow.png", "owned": "...leaf_lit.png" },
  "font": "kindreds:tengwar_ui", "open_sfx": "kindreds:ui.elf_open", "unlock_sfx": "kindreds:ui.elf_unlock" }
```

---

## 5. Progression engine

- **Earn by doing:** `ActivityHooks` award discipline XP on the relevant server event (block-break→Mining scaled by block hardness; bow release + hit→Archery; melee kill/hit→Combat; sneak-time + sneak-kills→Stealth; craft/smith→Smithing; eat/biome-discovery/travel→Survival; enter new region / lore advancement→Lore).
- **Race scaling (`RaceScaling`, data-driven):** each race has per-discipline multipliers (Elf archery ×1.5, mining ×0.6; Dwarf mining ×1.6, archery ×0.7; …). Race *nudges*, doesn't cage.
- **XP → points:** each discipline levels on its own XP curve; each level grants **1 point in that discipline**. Points are **per-discipline** (spend archery points on archery-gated nodes) — reinforces build identity.
- **Hard ceiling:** XP curve + a soft global soft-cap (config) ensure you can't max every tree; you specialize.
- **Unlock rules (`UnlockService`):** node requires enough points in its `cost.discipline`, all `prereqs` owned, not blocked by an `exclusiveGroup` you've already chosen, and — for capstones — the `deedAdvancement` earned. All validated server-side.
- **Capstones = deeds:** the top node of each tree requires a specific base-mod/our advancement (e.g. Dwarf `Durin's Bane-Slayer` ← "kill a Balrog/troll" advancement). Reuses the advancement system; also seeds the reputation/legend layer (P3).

---

## 6. Player data, sync & death

- **`KindredData`** per player: `Map<discipline,xp>`, `Set<nodeId>` unlocked, `activeVisionLens`, `Set<title>`, `Map<abilityId,cooldownEnd>`.
- **Storage:** Fabric **Data Attachment API** (`AttachmentRegistry.createPersistent`, `.syncWith(...)`) on `ServerPlayerEntity` — persists, auto-syncs to the owning client. `copyOnDeath` is **config-driven** (see §7).
- **Sync:** authoritative on server; `SyncKindredDataS2C` on join, unlock, respec, death. The client keeps a read-only mirror for the UI.
- **Death penalty (config):** `keep` (default; copyOnDeath) · `lose_unspent_points` · `lose_percent(p)` of discipline XP · `hardcore` (wipe). Applied server-side on `ServerPlayerEvents.AFTER_RESPAWN`/copy.

---

## 7. Config (server-authoritative, MP-ready)
`config/kindreds-server.json`, hot-reloadable via `/kindreds reload`:
```
deathPenalty: "keep" | "lose_unspent" | "lose_percent" | "hardcore"   (default "keep")
deathPercent: 0.25
xpRateGlobal: 1.0            (scales all discipline gain)
pointSoftCap: <int>         (specialization ceiling)
respecItem: "minecraft:...", respecCost: 1
enableVision: true
enableCurses: true
allowCrossTraining: true    (the hybrid race-lock cross-nodes)
enableEnemyScaling: false   (P4 hook; off in P1)
```
On a server, the server's config governs everyone; single-player uses the same file. All gameplay effects read config server-side.

---

## 8. Ability application
- **Passives (`attribute`, `status_effect`):** `AbilityApplier` adds a uniquely-id'd `AttributeModifier` (or a permanent effect) when a node is owned; removes it on respec/loss. Modifiers keyed by node id so they're idempotent and cleanly reversible.
- **Actives (`ActiveAbilityService`):** keybind → `ActivateAbilityC2S` → server checks cooldown/cost → applies effect (AoE, self-buff, projectile, heal) → sets cooldown in `KindredData` → client HUD shows cooldown.
- **Curses:** net-positive nodes with a drawback; contextual curses (`when`) apply only in context (e.g. Elf deep-dark unease only below a Y/light threshold).
- **Contextual (place/time) hook** exists in the schema (`when`) but Phase-1 uses it only for the shipped debuffs; full place/time power is P2.

---

## 9. Vision framework (Phase 1)
- **One active lens at a time** (`VisionManager`): a keybind cycles/toggles your equipped lens among the visions you've unlocked; server records it (`SetVisionLensC2S`) so it persists.
- **Shipped lenses:** **Elf Keen-sight** (friend/foe entity outlines within range via `SeeThroughLayer` + a gentle starlight gamma lift) and **Dwarf Stone-sense** (target-block/ore + cavity outlines through rock, reusing the proven mithril NO_DEPTH_TEST layer, + underground brightness).
- **Tech (per verified `vision-rendering-tech-1.21.8.md`):** shader-independent methods only in P1 — see-through outline layer (proven), HUD-overlay tint/gamma (Iris-safe), lightmap/gamma brightness. **Detect Iris and degrade gracefully** (disable outline lenses under an active shaderpack, keep HUD/gamma). Register pipelines once; frustum+distance cull; restore GL state.
- Post-process color-grade lenses (wraith-world, etc.) are **P2** (enhanced path).

---

## 10. The UX — a lore-themed artifact (not a default grid)

**Core idea:** the skill screen is not a generic node grid — it's **an illuminated page of your people's lore**, themed per race *from data* (§4 Theme), so an Elf and an Orc open visibly different worlds.

**Open:** keybind (default e.g. `K`) or a craftable themed item later. Opening plays the race's `open_sfx` and a brief page-turn/unfurl animation.

**Layout (one clean screen):**
- **Left ~75% — the Tree Canvas.** A **pannable (drag) / zoomable (scroll)** canvas over the race's themed **background** (Elf: starlit parchment with a faint mallorn silhouette; Dwarf: carved stone wall lit by forge-blue; Orc: scorched iron & bone; Hobbit: a homely map of the Shire; etc.). Nodes are **themed sprites** (Elf = glowing leaves/stars; Dwarf = cut gems/anvils; Rohan = horse-brasses; Mordor = eyes/embers). **Edges** drawn in the theme's `edge_style` (Elf vines, Dwarf carved runic channels, Orc sinew). **Node states:** *locked* (etched/dim), *available* (pulsing in the race's accent color), *owned* (fully lit, subtle idle shimmer). Capstone nodes are larger, ornate, and **sealed** until their deed is done (shown as a wax-seal/lock with the deed named).
- **Right ~25% — the Character Page (side panel).** Styled as a page of the same book: the **race crest** at top (read from the base mod's faction/race), **discipline gauges** (7 themed meters showing points spent/available, each in its discipline color), the **active Vision lens** icon, a small **heraldic list of earned Titles**, and a **respec** action ("Unlearn the old ways" — consumes the config `respecItem`, with a confirm).
- **Node tooltip / card (`NodeTooltip`):** on hover, a themed card: the ability's **lore name** in the race font, a short **Tolkien-flavored description** (from the lore docs, cited), then the **mechanical effect(s)**, the **cost** (discipline + points), **prereqs**, and any **curse/tradeoff** in a warning tint. Clicking an *available* node unlocks it with a satisfying flash + `unlock_sfx`; clicking a *sealed* capstone shows the deed you must perform.
- **Polish:** subtle ambient race music/hum while open; the accent color tints the frame glow; hovering an owned node faintly traces its prerequisite chain. **No vanilla-default widgets** — buttons, gauges, and frames are all custom, themed art. Fully mouse-driven; readable at a glance; search box to jump to a node by name.

**Accessibility & perf:** everything is 2D `DrawContext` draws (1.21.8 HUD/GUI); themed textures are atlas'd; the canvas culls off-screen nodes; a "reduce motion" respects the vanilla distortion/animation setting.

**Themeability = data:** because each tree references a `Theme` JSON (textures/colors/fonts/sfx/node-sprites/edge-style), new races or reskins are **art + JSON**, no code — satisfying the clean/dynamic mandate.

---

## 11. Reading the base-mod race
`RaceAccess` resolves the player's race from the base Middle-earth mod. **Preferred:** compile-time soft-dependency on `sevenstars-api` / the mod's `PlayerDataService` public accessor. **Fallback:** a mixin that observes the base mod's race server-state (`StateSaverAndLoader.getPlayerState`) or its race S2C, mirrored into our data. If no race is set (player hasn't onboarded), the tree screen shows a gentle "Choose your people first (use the Player's Book)" prompt and no tree.

---

## 12. Example trees (the authoring pattern) — fully specced

### 12a. ELF — first tree (theme: starlit mallorn; identity: ambient ranger-survivor)
*Discipline lean: Archery ×1.5, Survival ×1.2, Mining ×0.6. Signature vision: Keen-sight.*

| Node | Tier | Discipline cost | Prereq | Effect (buff) | Tradeoff |
|---|---|---|---|---|---|
| **Eyes of the Eldar** | 1 | Archery 1 | — | Unlock **Keen-sight** vision (friend/foe outline + starlight gamma) | — |
| **Light Step** | 1 | Survival 1 | — | Powdered-snow + freeze immunity; no slow on snow/ice (attr) | — |
| **Tireless** | 2 | Survival 2 | Light Step | No sprint-hunger drain; immune to Mining Fatigue aboveground | — |
| **Elven Marksman** | 2 | Archery 3 | Eyes of the Eldar | Bows draw ~20% faster; arrows fly truer/farther (attr + active-ish) | — |
| **Deep-Dark Unease** *(curse)* | 2 | Lore 1 | Eyes of the Eldar | +2 max health (1 heart) | In deep dark (low light, not under sky): `delvers_fear_strength` debuff builds (uses base mod's dormant attribute) |
| **Woodland Grace** | 3 | Survival 3 | Tireless | +move speed & silent movement in forest biomes (contextual) | Weaker (no bonus) in caves/wastes |
| **Starlit Aim** *(capstone, deed-gated)* | 3 | Archery 5 | Elven Marksman + **deed: "Slay 100 foes by bow"** | Active: a charged shot with bonus damage + brief Glowing on the target | Long cooldown |

### 12b. DWARF — first tree (theme: carved rune-stone, forge-blue; identity: deep-delver tank-smith)
*Discipline lean: Mining ×1.6, Smithing ×1.4, Combat ×1.1, Archery ×0.7. Signature vision: Stone-sense.*

| Node | Tier | Discipline cost | Prereq | Effect (buff) | Tradeoff |
|---|---|---|---|---|---|
| **Aulë's Inheritance** | 1 | Mining 1 | — | Unlock **Stone-sense** vision (ore/cavity outlines through rock + underground brightness) | — |
| **Stone-hard** | 1 | Combat 1 | — | +2 max health (1 heart) + minor knockback resistance (attr) | — |
| **Deep-Delver** | 2 | Mining 3 | Aulë's Inheritance | No Mining Fatigue; mining speed rises the deeper you are (contextual attr) | — |
| **Fire-blood** | 2 | Survival 2 | Stone-hard | Fire/lava resistance tier (reduced fire damage + shorter burn) | — |
| **Dragon-sickness** *(curse)* | 2 | Lore 1 | — | +mining_efficiency (faster mining) | Carrying large amounts of gold/gems → Nausea + increased mob aggro until spent |
| **Forgemaster** | 3 | Smithing 4 | Deep-Delver | Better tool/armor repair & durability; unlock a dwarf-only smithing recipe (P1 stub) | — |
| **Unbroken Will** *(capstone, deed-gated)* | 3 | Combat 5 | Stone-hard + **deed: "Survive a fight below Y-0 against N foes"** | Active war-fury: temporary knockback immunity + damage resistance while standing ground | Rooted (reduced move speed) during the effect |

*(The other 6 races — Gondor, Rohan, Dale, Hobbit, Orc/Goblin/Snaga, Uruk — get an equivalent ~3-tier first tree authored as JSON during the build, following this exact pattern and the branch sketches in `research/lore/{elves-and-men,dwarves-and-hobbits,evil-races}.md`. Each ships: a signature vision (Orc Dawnless-sight, Hobbit Unseen, etc.), a lore buff set, at least one curse/tradeoff, and a deed-gated capstone. Rohan's tree is mounted-conditional; Orc/Goblin/Snaga carry the Dread-of-the-Sun contextual debuff; Uruk gets Sun-Defiance.)*

---

## 13. Testing & verification
- **Unit-tested (pure logic, JUnit):** XP→level→points curves; `UnlockService` rules (points/prereqs/exclusivity/deed gating); race-scaling math; config parsing; death-penalty calculations; tree/registry codec round-trips.
- **In-game (user play-test):** the tree screen renders themed & readable; unlocking applies real attribute/effect changes; disciplines gain from the right activities at race-scaled rates; visions render (and degrade under Iris); death penalty behaves per config; MP: a second player has independent data; respec refunds & removes modifiers cleanly.
- Build/deploy: jar → instance `mods/`; verify it loads alongside the base mod and reads race correctly.

---

## 14. Phase 1 build order (each step independently testable)
1. Scaffold (Fabric 1.21.8 client+server, deps, mod id) + config.
2. Data model + registries + codecs (Discipline, SkillTree, Node, AbilityDef, Theme) + example JSON; **unit-test codecs**.
3. Player data (Attachment) + sync packets + `/kindreds` inspect command.
4. Progression engine (ActivityHooks, ProgressionService, RaceScaling) — **unit-test curves/scaling**; verify XP gain in-game.
5. UnlockService + AbilityApplier (attribute/status/curse) — **unit-test rules**; verify a node changes stats in-game.
6. Active abilities + cooldowns.
7. Vision framework + Stone-sense + Keen-sight (reuse mithril layer; Iris-aware).
8. The tree screen UX (canvas, themes, nodes, tooltips, side panel, respec) — the big client piece.
9. RaceAccess (read base-mod race).
10. Author the 8 race first-trees as JSON (Elf & Dwarf per §12; others from lore docs).
11. Death-penalty config paths + MP validation.
12. Polish, spark perf check, deploy, play-test.

---

## 15. Open items (decide during/after Phase 1)
- Exact XP curves & race-scaling numbers (tune in play-test).
- Final keybinds (open tree, cycle vision, active abilities) — avoid pack conflicts (base mod, Xaero).
- Whether cross-training nodes appear in Phase 1 or Phase 3 (schema supports them now; content can wait).
- Art pipeline for the themed UI assets (placeholder art in P1, polished later).
