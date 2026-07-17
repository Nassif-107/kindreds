# Skill-Tree Systems & UX — Design Research for Middle-earth's Racial Progression Mod

**Scope:** a companion Fabric mod that adds a per-race, per-faction skill tree to the "Middle-earth" modpack (MC 1.21.8). Goal: deep, extensible, buffs-*and*-minor-debuffs progression that makes the game harder but more fun, with an outstanding, simple UX. This doc synthesizes web research on (1) classic game skill-tree design, (2) the Minecraft mod ecosystem's prior art, (3) Fabric 1.21.8 technical building blocks, and (4) "harder but more fun" difficulty levers, then makes concrete recommendations grounded in the base mod's existing architecture (see `middle-earth-factions-races-study.md`).

---

## 0. TL;DR recommendation

- **Point source:** primary = **activity/skill-use XP** (PMMO-style: mine ore → mining points, shoot arrows → archery points), secondary = **advancement/lore-deed unlocks** reserved for capstone/keystone nodes (kill a troll, reach Mordor, forge mithril — reusing the base mod's shipped advancements). Skip flat character-level XP as a *primary* gate; it can exist only as a small multiplier.
- **Race/faction shapes point gain,** it doesn't gate it: an Elf earns Archery XP faster than a Dwarf, mirroring how the base mod already differentiates races via `base_attributes` — but a Dwarf *can* still grind archery, just slower. This is itself an "interesting choice."
- **Tree shape:** per-race **branching tree** (not a PoE-scale web) — 3-4 thematic branches per race, each ending in a capstone gated behind a lore deed, plus one **shared cross-race "Deeds of Middle-earth" constellation** (Grim Dawn Devotion-style) fed by exploration/advancement milestones and usable by any race/faction.
- **Passive/active mix:** most nodes are passive `AttributeModifier`/`StatusEffect` grants (the base mod already models races this way); a handful of capstone nodes unlock **keybind-activated actives** with cooldowns, implemented via Fabric's networking payload system.
- **Data model:** **Fabric Data Attachment API** (`AttachmentType`/`AttachmentRegistry`, Fabric API's `fabric-data-attachment-api-v1` module) attached directly to `ServerPlayerEntity`, persistent + optionally synced — *not* a hand-rolled `PersistentState`, and *not* reading the base mod's `StateSaverAndLoader` internals directly (see §5.3 for the recommended cross-mod read pattern).
- **GUI:** custom vanilla `Screen` + `DrawContext` (not owo-ui, not Cloth Config) for the pannable/zoomable tree canvas — Cloth Config is kept only for the mod's plain settings menu, matching the base mod's own "reach settings without ModMenu" pattern from its latest commit.
- **Tree definition:** JSON via a **custom dynamic registry synced with `DynamicRegistries.registerSynced`**, mirroring exactly how the base mod already ships `race`/`faction`/`npc` as datagen-exported, datapack-overridable JSON.
- **Difficulty levers:** costed (not free) respec, mutually-exclusive vocation branches per race, optional "curse nodes" (real drawback for a stronger or cheaper buff), lore-gated capstones, and mob-difficulty scaling keyed off *skill-tree investment* (not raw playtime) so the tree's own power doesn't trivialize the world.

---

## 1. Great skill-tree design patterns (from top games)

### 1.1 Layout: web vs. branches vs. board

Path of Exile's passive tree is a dense **web**, explicitly "not designed to be completed, but to reward focus" — efficient builds path through small filler nodes to anchor a small number of Notables and a single Keystone rather than trying to grab everything ([Maxroll: passive tree for beginners](https://maxroll.gg/poe/getting-started/passive-skill-tree-for-beginners), [Switchblade Gaming](https://www.switchbladegaming.com/path-of-exile-2/passive-tree-guide/)). It maximizes emergent build diversity but is expensive to design/balance and can overwhelm newcomers — the [PoE Wiki entry on Keystones/Notables](https://www.poewiki.net/wiki/Passive_skill) documents an entire vocabulary just to keep the web legible.

Borderlands instead uses a strict **branching** model: 3 branches per class tree, each culminating in a capstone, with only one capstone equipped at a time ([BorderlandsHQ skill tree guide](https://borderlandshq.com/skill-tree-guide/)). This is far more readable at a glance and scales to a small dev team.

Grim Dawn layers **two systems**: class Mastery trees (choose 2 of 10) for build identity, plus a wholly separate **Devotion constellation web** — earned from world exploration (restoring shrines), spent on a shared star-map independent of class; completing a constellation grants an affinity bonus gating access to higher constellations ([Grim Dawn Wiki: Devotion](https://grimdawn.fandom.com/wiki/Devotion), [Crate forums](https://forums.crateentertainment.com/t/devotions-a-how-to-on-maximizing-them/49384)).

**Takeaway for Middle-earth:** a full PoE-scale web is out of scope for a modded project (8 races × huge web = unbounded authoring/balance cost). The right scope is Borderlands' **branching tree per race** (readable, cheap to author, still supports specialization) **plus** a Grim-Dawn-style **shared constellation layer** fed by lore deeds/exploration that any race can spend into — this reuses the "second layer" pattern to multiply build diversity without doubling the cost of authoring 8 separate deep trees.

### 1.2 Tiered "power nodes" give a tree a skyline

PoE, Borderlands, and Grim Dawn all separate small incremental stat nodes from rare build-defining Keystones/Capstones/Celestial Powers that change *how* a build plays, not just its numbers ([PoE Wiki](https://www.poewiki.net/wiki/Passive_skill), [BorderlandsHQ](https://borderlandshq.com/skill-tree-guide/)). A tree of only "+2% damage" nodes reads as filler. **Apply:** each race branch should have ~4-6 minor passive nodes leading to one capstone that adds a new mechanic (an active ability, a wall-climb, a stance-swap) — not just a bigger number.

### 1.3 Synergy taxes vs. flexible layers

Diablo 2's skill synergies (points in one skill boosting a related skill) forced heavy investment in supporting skills, discouraging hybrid builds and making respec nearly mandatory for viable play ([d2tomb](https://www.d2tomb.com/synergies.shtml), [Diablo Fandom](https://diablo.fandom.com/wiki/Synergies)). Diablo 3 swung to the opposite extreme — no tree, just swappable runes unlocked by level, prioritizing flexibility over identity ([Diablo Fandom: Skill Runes](https://diablo.fandom.com/wiki/Skill_Runes)). Diablo 4 sits in between: a class tree for identity plus a separate Paragon board/glyph layer for late-game tuning ([Maxroll: Paragon Boards](https://maxroll.gg/d4/resources/paragon-boards)). **Apply:** avoid D2-style hard synergy taxes (they read as busywork); a small race tree doesn't need a second numeric-tuning layer at all — the shared constellation layer already fills that role narratively instead.

### 1.4 Skyrim: constellation theming + activity-based points reinforce each other

Perks are literal constellations per skill, and points are earned by leveling skills *from doing the skill* (swing a sword to level One-Handed) rather than a flat character-level pool ([UESP: Skills](https://en.uesp.net/wiki/Skyrim:Skills), [Elder Scrolls Fandom: Perks](https://elderscrolls.fandom.com/wiki/Perks_(Skyrim))). This is architecturally identical to activity-based systems like PMMO (§2), and the visual theming (a glowing constellation as you fill it in) directly reinforces "this is what makes an Elf an Elf." This is the strongest single precedent for Middle-earth's design: **race identity, activity-based points, and constellation-style visuals are the same idea wearing three hats.**

### 1.5 Meta-progression must feel earned, not bought

Hades' Mirror of Night gates permanent power behind narrative/resource gates (Chthonic Keys) and ties refunds to a real resource cost, not a free reset ([Hades Fandom: Mirror of Night](https://hades.fandom.com/wiki/Mirror_of_Night), [TheGamer](https://www.thegamer.com/hades-mirror-of-night-roguelite-progression/)). PoE's respec (Orb of Regret) is a scarce, tradeable currency, not a menu button ([PoE Wiki: Orb of Regret](https://pathofexile.fandom.com/wiki/Orb_of_Regret)) — this friction is exactly what makes initial allocation feel weighty rather than provisional (see §6.1 for the concrete respec recommendation).

### 1.6 The load-bearing principle: "interesting choices"

Sid Meier's framing — a decision is interesting only when no option is strictly dominant, the player has enough information to weigh real trade-offs, and consequences are legible before committing ([GDC 2012 recap](https://www.gamedeveloper.com/design/gdc-2012-sid-meier-on-how-to-see-games-as-sets-of-interesting-decisions), [Designer Notes: Sid's Rules](http://www.designer-notes.com/game-developer-column-5-sids-rules/)). Concretely: avoid "trap" nodes that are strictly worse than an adjacent alternative for the same cost; prefer mutually-exclusive branch picks over purely additive nodes; every node tooltip must make its trade-off legible *before* the point is spent. This principle governs every other recommendation in this document — if a node/branch/respec cost has an obviously-correct answer, delete or rebalance it.

---

## 2. Earning points: comparing the options

| Model | How it works | Lore fit | "Harder but more fun"? | Verdict |
|---|---|---|---|---|
| **XP/level (flat)** | Kill/mine/explore → generic XP → character level → points | Generic, race-agnostic; disconnects mechanic from fantasy | Neutral — just a grind timer, easy to trivialize by AFK-farming | Not primary |
| **Activity/skill-use (PMMO-style)** | Mining grants Mining XP, archery grants Archery XP, per-skill levels unlock perks | **Excellent** — "an Elf becomes an archer by loosing arrows" is the exact fantasy | Good — rewards sustained playstyle commitment, not idle grinding | **Primary** |
| **Achievement/advancement-based** | Complete a (possibly the base mod's own) advancement → unlock/grant points | Excellent for capstones/lore beats (kill a troll, reach Mordor, forge mithril) | Good — makes big power spikes feel like earned deeds, not stat breakpoints | **Secondary (capstones only)** |
| **Quest/deed-based** | Custom quest chain grants points | Strong flavor, but highest authoring cost across 8 races × good/evil | Good but expensive to scale | Reserve for unique per-faction moments, not a general driver |
| **Hybrid** | Combination of the above | — | — | **Recommended composite** |

### 2.1 Project MMO (PMMO) — the primary model's closest real-world analog

PMMO is the canonical activity-based system: mining ore grants Mining XP, landing arrows grants Archery XP, swimming grants Swimming XP, etc. Per-skill XP drives per-skill levels, which unlock perks (faster mining, higher jump, extra reach, more damage) and can gate item/block use by level ([PMMO — CurseForge](https://www.curseforge.com/minecraft/mc-mods/project-mmo)). It ships a HUD skill-level overlay and is config/CraftTweaker-extensible with a bridge addon for cross-mod compatibility. This is the strongest existing proof that "you become an archer by doing archery" works well in Minecraft specifically.

### 2.2 Reskillable — gating as a difficulty lever, not a UX model

Reskillable uses flat XP-per-skill (not activity-typed the way PMMO is) to gate equipment by level — e.g., you cannot use a Diamond Pickaxe until level 16 Mining — and unlocks passive traits ([Reskillable — CurseForge](https://www.curseforge.com/minecraft/mc-mods/reskillable)). Its lesson is about *denying* vanilla power until earned (a genuine difficulty lever, see §6) more than about tree UX.

### 2.3 Origins/Apoli — the best precedent for *race-shaped*, datapack-driven power

Origins is "very data-driven, allowing you to add, modify and remove origins as you like," with per-origin powers defined entirely in JSON ([Origins/Apoli wiki](https://github.com/apace100/origins-fabric/wiki)). Its power taxonomy splits cleanly into **modifier powers** (Modify Damage Taken, Modify Break Speed, Modify Jump — attribute-modifier wrappers), **action powers** (Action On Block Break, Action On Land, Action Over Time — event-triggered hooks), **conditional/restriction powers** (Effect Immunity, Prevent Sleep, Prevent Item Use), and **active powers** (Active Self, Elytra Flight, Phasing — keybind-triggered). This maps almost exactly onto the passive/active split this project needs (§3), and its JSON condition system (AND/OR/entity/block conditions) is a good template for node-prerequisite logic. Its "Orb of Origin" respec item is a clean, lore-friendly, *physical-item* respec pattern worth borrowing directly (see §6.1).

### 2.4 Ecosystem UX note (DawnCraft and RPG-modpack composition)

Large Fabric/Forge RPG packs like DawnCraft typically bolt together a combat mod (Epic Fight — already conceptually adjacent to this project's Better Combat/Combat Roll) with a separate leveling/skill mod rather than building one bespoke system. The recurring community complaint about this pattern is **system overload** — multiple unrelated HUDs, XP bars, and menus competing for attention. The actionable UX constraint (carried into §4): a bespoke skill tree should have **exactly one entry point** (one key, one screen) and should visually *reuse* the base mod's existing race/faction identity (banners, race icons) rather than inventing a parallel identity system.

*(Note on sourcing: Daripher's "SkillTree" mod and "Puffish Skills" are referenced in the Minecraft modding community as PoE-style pannable/zoomable node-tree mods with locked/available/owned node states and JSON-defined trees, but their specific API details could not be independently re-verified in this research pass — treat them as a UX existence-proof ("a literal zoomable node web is a shippable pattern in Fabric/Forge") rather than a source of confirmed implementation details.)*

### 2.5 Recommendation: primary + secondary, scaled by race

**Primary = activity-based skill-use XP** (PMMO-style) drives the bulk of minor/branch nodes — it is the only model that makes the tree feel like it's *about* your race's fantasy rather than a level-up afterthought. **Secondary = advancement/deed-based unlocks**, reserved for capstones and the shared lore constellation, reusing the base mod's shipped faction/entry advancements directly (kill-a-troll, reach-Mordor, forge-mithril). Flat character level should exist only as a **minor multiplier** on activity-XP gain (a small overall-level bonus), never as a sole gate.

**Race/faction-scaled gain rates — yes, and it's low-cost to add:** apply a per-race multiplier table to each activity-XP category, exactly mirroring how the base mod already differentiates races via `base_attributes` (Race JSON `base_attributes.pool`). Suggested first pass, keyed to the existing per-race stat spread documented in the base-mod study:

- **Elf** → +Archery/+Stealth XP rate (matches best entity reach + move_efficiency perk)
- **Dwarf** → +Mining/+Smithing XP rate (matches existing `mining_efficiency` attribute bonus)
- **Hobbit** → +Sneak/Farming XP rate (matches +45% sneak, detection_range perk)
- **Orc/Uruk** → +Melee/Endurance XP rate (matches HP/no-penalty profile)
- **Goblin/Snaga** → +Mining/Climbing XP rate (matches `climbing_strength`/best block reach)

This reuses an established data pattern instead of inventing a new one. A Dwarf *can* still become a master archer — just visibly slower than an Elf — which is itself an "interesting choice" (§1.6): grinding against your race's grain is possible, but costs more time, so racial identity is reinforced through pacing rather than a hard wall.

---

## 3. Passive vs. active abilities

Mirror the Origins/Apoli split (§2.3) directly:

- **Passive nodes** = `EntityAttributeModifier` grants (+block reach, +mining speed, −fall damage, resistance to an environmental hazard) and/or `StatusEffect` applications (permanent haste, permanent night vision underground for a Dwarf branch, etc.). These slot naturally alongside the base mod's existing `EntityAttributesME` custom attributes (`climbing_strength`, `powdered_snow_immunity`, `detection_range`) — a passive node can grant more of the *same* custom attribute the base mod already defines, or a new one this mod registers.
- **Active nodes** (reserved for capstones) = a keybind-triggered ability with a cooldown and optionally a resource cost (hunger, a charge meter, or a held-item requirement for flavor — e.g., an Elf capstone active only usable with a bow in hand). Implemented as: client detects a keypress → sends a C2S packet → server validates cooldown/prerequisites/resource → server applies the effect and optionally fires an S2C packet back to sync a cooldown-ring HUD element. See §5.4 for the concrete Fabric API pattern.

**Debuff nodes** (§6.3) use the same two primitives in reverse — a negative `AttributeModifier` (e.g., `ADD_MULTIPLIED_TOTAL` on max health) or a mild recurring `StatusEffect` (e.g., short Mining Fatigue outside your race's home biome) paired with a stronger positive modifier elsewhere in the same node, so the choice reads as a trade-off rather than a punishment.

---

## 4. UX — the most important part

### 4.1 What makes a skill-tree screen feel amazing (survey findings)

Drawing on the design patterns in §1 and the Minecraft-specific precedents in §2:

- **Node states must be instantly readable at a glance**: locked (dim/grayscale), available (highlighted/pulsing outline — "you could spend a point here right now"), owned (filled/lit, ideally with the race's accent color), and — a state most trees skip — **affordable-but-blocked** (you have the points but a prerequisite isn't met; show *why* right on hover, not buried in a separate tooltip).
- **Prerequisites drawn as literal connecting lines/edges**, not text-only — the PoE/Grim-Dawn convention of edges lighting up once both endpoints are owned/available gives instant visual feedback on "where can I go next."
- **Tooltips must state the trade-off, not just the buff** — per §1.6, if a node has a debuff component, the tooltip shows both halves side by side (green buff / red drawback), never hides the drawback in fine print.
- **Search/filter bar** — even a modest 4-branch-per-race tree benefits from a text filter (node name/keyword) once multiple races' trees exist side by side, per the base mod's own onboarding screen precedent (it already ships a search bar for faction/race selection).
- **Build preview / "what would this look like"** — hovering an unspent node should preview its effect on your current stats (e.g., "+2.0 → 4.5 block reach") rather than making the player do the math.
- **A single, obvious respec button/flow**, with its cost shown up front (§6.1) — never a hidden or accidental reset.
- **Minimal clutter**: exactly one screen, opened by exactly one key, matching the "one entry point" lesson from §2.4 — resist the temptation to add a second HUD/overlay for skill XP; fold a small XP notification into the existing action-bar/toast system instead.
- **Mouse-driven pan/zoom** (drag to pan, scroll to zoom) is the de facto standard across every reference tree (PoE, Grim Dawn, Daripher's SkillTree/Puffish-Skills-style MC mods) — no controller support needed for a MC modpack context, but scroll-zoom + drag-pan must feel smooth and should recenter/zoom-to-fit on open.
- **Visual theming per race** (§1.4) — each race's tree should read as a distinct constellation/shape (matching its lore identity: Dwarf tree shaped like a mine-shaft/mountain, Elf tree shaped like a star-constellation, Orc/Uruk tree jagged and angular) rather than 8 reskins of the same grid. This is cheap relative to its payoff in "build identity" feel (§1.6).

### 4.2 GUI framework recommendation for 1.21.8: vanilla `Screen` + `DrawContext`

Three options exist in the modpack's ecosystem: vanilla `Screen`/`DrawContext`, **owo-ui** (a declarative Fabric UI toolkit), and **Cloth Config** (already present in the pack, per the task context, but purpose-built for flat settings forms — lists of toggles/sliders — not freeform pannable canvases).

**Recommendation: hand-rolled vanilla `Screen` + `DrawContext`**, for three reasons:
1. **Cloth Config is the wrong tool for a pannable/zoomable node canvas** — it's a declarative *list/form* framework; forcing a 2D drag-pan-zoom graph into it fights the framework. Keep Cloth Config exactly where the base mod already uses it (this mod's own plain settings menu — reach/HUD-scale-style options, matching the base mod's most recent commit "reach settings without ModMenu"), not for the tree screen itself.
2. **owo-ui** is a reasonable declarative alternative for flat panels, but a pannable/zoomable graph with custom edge-line rendering, hover-highlight state machines, and per-node hit-testing is most naturally expressed as direct immediate-mode drawing (`DrawContext.fill`, `drawTexture`, `drawText`, plus custom `Matrix4f` transform pushes for pan/zoom) inside a single custom `Screen` subclass — this is exactly the pattern every PoE-style Minecraft skill-tree mod in the ecosystem (§2.3-2.4) already uses. It avoids taking on an additional UI-library dependency for a screen type that toolkit doesn't specially support anyway.
3. **Full control over rendering performance and camera math** matters here — an 8-race tree with a shared constellation layer could have 150+ nodes on screen; hand-rolled immediate-mode rendering with simple frustum culling (skip nodes outside the current pan/zoom viewport) is straightforward and fast, whereas retrofitting culling into a declarative layout system is not.

**Concrete shape:** one `Screen` subclass holding a camera struct (`offsetX, offsetY, zoom`), a per-race tree-graph data structure (nodes + edges, built once from the synced dynamic registry, §5.6), mouse-drag updates the offset, scroll-wheel updates zoom (clamped to a sane range), and a `Matrix4f`/`MatrixStack` push wraps the node/edge rendering so hit-testing can be done in either screen-space (inverse-transform the mouse) or world-space (transform each node once and cache). Node tooltip rendering reuses vanilla `DrawContext.drawTooltip`-style helpers for consistency with the rest of Minecraft's UI language, so it doesn't feel like a bolted-on third-party widget.

---

## 5. Fabric 1.21.8 technical building blocks

### 5.1 Dynamically granting/removing `AttributeModifier`s and `StatusEffect`s

Confirmed against Yarn 1.21.8 mappings: `EntityAttributeModifier` is keyed by `Identifier` (not the pre-1.21 `UUID`), constructed as `new EntityAttributeModifier(Identifier.of(MOD_ID, "node_name"), value, EntityAttributeModifier.Operation.ADD_VALUE)` with three operations available — `ADD_VALUE`, `ADD_MULTIPLIED_BASE`, `ADD_MULTIPLIED_TOTAL`. Use a **stable, deterministic Identifier per node** (e.g. `middle-earth-skills:node/<race>/<node_id>`) so it can be looked up and removed later without tracking UUIDs yourself.

`EntityAttributeInstance` (obtained via `player.getAttributeInstance(attribute)`) exposes exactly the methods needed for a skill tree's grant/revoke lifecycle:
- `addPersistentModifier(modifier)` — grants a modifier that **is serialized** (survives relog) — use this for owned skill nodes.
- `addTemporaryModifier(modifier)` — **not serialized** — reserved for short-lived active-ability buffs (§3), not permanent nodes.
- `removeModifier(Identifier id)` (returns boolean) and `hasModifier(Identifier id)` — use `hasModifier` as a guard before `addPersistentModifier` to avoid duplicate-modifier errors on respec/relog races.
- `overwritePersistentModifier(modifier)` — useful if a node's magnitude changes between mod versions/datapack reloads without needing a manual remove-then-add.

Custom attributes register the same way the base mod's `EntityAttributesME` already does (`Registry.register`/`registerForHolder` against `Registries.ATTRIBUTE`), so new skill-tree-specific attributes (if any are needed beyond what `EntityAttributesME` already exposes) follow an established in-pack precedent rather than a novel one. `StatusEffect` application uses the ordinary `LivingEntity#addStatusEffect(new StatusEffectInstance(...))` call, unchanged from prior versions.

### 5.2 Per-player persistent data: Data Attachment API over hand-rolled `PersistentState`

Recommendation: **Fabric API's Data Attachment API** (`fabric-data-attachment-api-v1`, stabilized module), not a second custom `PersistentState`. Confirmed directly from the Fabric API source (`net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry`/`AttachmentType`):

- `AttachmentRegistry.createPersistent(Identifier id, Codec<A> codec)` registers an attachment that survives server restarts using a supplied `Codec` for (de)serialization — exactly what's needed for `{nodesOwned: Set<Identifier>, skillXp: Map<Identifier,Long>}`-shaped skill data.
- `AttachmentType` is implemented via mixin on `Entity`, `BlockEntity`, `ServerLevel`, and `ChunkAccess` — attaching to `ServerPlayerEntity` (an `Entity`) means the data is **stored with the player's own entity NBT**, not a separate keyed store, so it persists automatically across sessions with zero extra load/save plumbing.
- `Builder.copyOnDeath()` explicitly declares that the attachment should survive death/respawn — set this, since skill points obviously should not reset on death.
- `Builder.syncWith(StreamCodec, AttachmentSyncPredicate)` (referenced from `AttachmentType`'s Javadoc) provides built-in client sync — use this instead of hand-rolling an S2C packet just to tell the client its own owned nodes, mirroring what the base mod *doesn't* currently do for race/faction (per the study: "no S2C packet ever tells the client its own faction/race" — an explicit gap this project shouldn't repeat, since the skill-tree UI needs to render owned/locked state client-side).
- `GlobalAttachments` (same package) is available if any *server-wide* (not per-player) skill-tree state is ever needed (e.g., a world-wide difficulty-scaling counter, §6.5).

This is a strictly better fit than a bespoke `PersistentState` for this project: it's officially maintained Fabric API surface (not a hand-rolled NBT format to version yourself), it colocates with the entity it describes, and it comes with sync built in.

### 5.3 Reading the base mod's race/faction (cross-mod data read pattern)

The base mod stores `PlayerData{faction, race, spawn, origin, delversFearCount}` in its own `StateSaverAndLoader` `PersistentState`, keyed by UUID, accessed internally through a `PlayerDataService` class (per the study's decompile notes). Four options, ranked:

1. **(Recommended) Soft/hard compile-time dependency on the base mod's public API surface.** Since the base mod ships a library dependency (`sevenstars-api`) specifically to expose shared functionality, check whether `PlayerDataService` (or an equivalent accessor) is `public` and exposed through that API jar. If so, add the base mod (and/or `sevenstars-api`) as a compile-time dependency and call its public getter (e.g., `PlayerDataService.getRace(player)`/`getFaction(player)`) directly — this is the most maintainable option because it uses the *actual authoritative source of truth* with no duplication, and automatically stays correct if the base mod changes its internal storage format.
2. **Mixin into the base mod's packet handlers** (`PacketSetRace`, `PacketSetAffiliation`) to observe race/faction changes as they happen server-side, mirroring the value into this mod's own attachment (§5.2) as a read-only cache. This is the fallback if option 1's accessor turns out to be package-private or otherwise unavailable — it's more brittle (breaks if the base mod renames/restructures its packet classes across updates) but doesn't require the base mod to expose anything it doesn't already have.
3. **Reflection directly into the base mod's `PersistentState`.** Works today (since the fields are shown in the decompile), but is the most fragile: obfuscation-independent since Fabric mods ship unobfuscated (Yarn-mapped) intermediary names stay stable, but any refactor of the base mod's internal class/field layout silently breaks this mod with no compile-time warning. Use only as a last resort or short-term prototyping shim.
4. **Command/scoreboard mirroring** (issue `/middle_earth race get` and parse output, or watch a scoreboard objective) — technically possible but needlessly indirect and latency-prone versus options 1-2; not recommended.

**Practical guidance:** start with option 1 (check what `sevenstars-api` actually exposes — this is a five-minute check against the existing decompile already on hand) since the base mod's own architecture note ("Library dep: sevenstars-api ... Hard sibling dep") suggests the authors *intend* other mods to consume shared functionality through that jar. Fall back to option 2 only if nothing suitable is public.

### 5.4 Keybind-activated actives: Fabric Networking + KeyBindingHelper

Confirmed pattern for 1.21.8:

- **Registration:** `KeyBindingHelper.registerKeyBinding(new KeyBinding("key.middle-earth-skills.activate", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, category))` during client mod init.
- **Detection:** `ClientTickEvents.END_CLIENT_TICK.register(client -> { while (keyBinding.wasPressed()) { /* send packet */ } })` — `wasPressed()` (Yarn's name for the consume-click check) fires once per physical press even across multiple ticks, avoiding repeat-fire bugs.
- **Networking (post-1.20.5 rewrite, current for 1.21.8):** define a `record ActivateSkillPayload(Identifier nodeId) implements CustomPayload` with a `CustomPayload.Id<ActivateSkillPayload>` and a `PacketCodec`, register it via `PayloadTypeRegistry.playC2S().register(ID, CODEC)` on **both** client and server before attaching a handler (registration must happen before handler attachment, per Fabric API's own Javadoc). Send with `ClientPlayNetworking.send(new ActivateSkillPayload(nodeId))`; receive server-side with `ServerPlayNetworking.registerGlobalReceiver(ActivateSkillPayload.ID, (payload, context) -> { /* validate cooldown/resource server-side, apply effect */ })`.
- **Cooldown/resource validation must happen server-side**, never trust the client — check a server-held cooldown timestamp (stored in the same attachment as §5.2) before applying the active's effect, and reject silently (or send a small S2C "on cooldown" toast payload) if not ready.
- **Optional S2C sync:** a matching `ServerPlayNetworking`→`ClientPlayNetworking` payload can push remaining-cooldown-ticks to the client for a HUD cooldown-ring, registered the same way in the `S2C` direction of `PayloadTypeRegistry`.

### 5.5 Advancement-trigger hooks for achievement-based unlocks

Two viable approaches, in order of preference:

1. **(Recommended, zero-mixin, fully datapack-driven) Use vanilla's own advancement `rewards.function` field.** Every advancement JSON already supports a `"rewards": {"function": "namespace:path"}` block that runs a datapack function the moment the advancement is granted. Since the base mod's faction/lore advancements are themselves data (JSON, datapack-overridable per the study), a companion datapack (or this mod's own bundled datapack) can **add or override** a `rewards.function` on the base mod's existing advancements — calling a custom command this mod registers (e.g., `/skilltree unlock <player> <node_id>`) with zero mixins and zero coupling to the base mod's Java internals. This is the most maintainable and most "datapack-driven" option, directly in the spirit of both mods' existing architecture.
2. **(Fallback, code-side) Mixin into `PlayerAdvancements#award` (Yarn: `PlayerAdvancementTracker#grantCriterion`)** to observe every criterion grant server-side and check whether it matches a configured lore-deed trigger. Fabric API's own `fabric-events-interaction-v0` module ships exactly this kind of mixin already (for an unrelated FakePlayer edge case), confirming the injection point is stable and commonly used — but note fabric-api does **not** currently expose a public `AdvancementGrantEvent`-style callback for general mod use, so this mod would need to ship its own mixin rather than subscribing to an existing Fabric API event. Use this only where option 1's `rewards.function` approach is insufficient (e.g., needing to gate on *progress toward* an advancement rather than its completion).

### 5.6 Datapack-driven, extensible tree definition

Mirror the base mod's own proven pattern exactly: define a **custom dynamic registry** (e.g., `middle-earth-skills:skill_node`) with a `Codec`/`RecordCodecBuilder`-defined record (`id`, `race`, `branch`, `tier`, `prerequisite_ids[]`, `cost`, `point_source` {activity-skill + threshold, or advancement id}, `attribute_modifiers[]`, optional `active_ability` block, optional `curse` block for debuff nodes), registered and synced with **`DynamicRegistries.registerSynced(KEY, CODEC)`** — the identical Fabric-provided mechanism the base mod already uses for `race`/`faction`/`npc`. This gets datapack overridability, automatic server→client sync on join/reload, and zero custom packet code **for free**, exactly as the base mod's own architecture notes describe ("no custom sync packet needed for the definitions"). Author content as Java "pool" classes datagen-exported to JSON (again mirroring the base mod's `*Provider`/`FabricDynamicRegistryProvider` pattern) so the shipped JSON remains a generated, datapack-overridable mirror rather than the sole source of truth — keeping this mod's authoring workflow indistinguishable from the base mod's own, which minimizes the learning curve for anyone maintaining both.

---

## 6. Making it harder-but-more-fun: difficulty levers

### 6.1 Respec cost as friction, not punishment

Diablo 2 shipped with **zero** respec for a decade — resets only arrived via a one-time quest reward plus the repeatable **Token of Absolution**, itself requiring farmed Hell-difficulty boss essences ([Diablo Wiki: Respecialization](https://diablo2.diablowiki.net/Respecialization); [Token of Absolution](https://diablo-archive.fandom.com/wiki/Token_of_Absolution_(Diablo_II))) — bad early choices genuinely stung. Path of Exile sits at the opposite, friendlier pole: Orbs of Regret refund one point each and are farmable/tradeable, not a hard wall ([PoE Wiki: Orb of Regret](https://pathofexile.fandom.com/wiki/Orb_of_Regret)), and community pressure has repeatedly pushed the developer toward *cheaper* respec because full walls feel bad ([PoE forum thread](https://webcdn.pathofexile.com/forum/view-thread/3606178)).

**Recommendation:** respec should be **possible but costly and lore-gated**, not free and not walled off — a crafted/quested consumable item (an "Orb of Origin"-style item per §2.3, flavored as e.g. a "Rune of Unmaking" obtainable from a late-game recipe or a faction-capital vendor, not raw gold) that refunds either one node or the whole tree. This gives commitment weight (players think before spending) without punishing experimentation into total build-lock.

### 6.2 Mutually-exclusive branches as commitment

Path of Exile's Ascendancy system is the clean modern analog to Diablo 2's pre-respec-patch hard commitment: each class picks one of several sub-classes, each with a distinct, non-overlapping capstone kit, expensive to reverse. **Apply:** give each race 2-3 mutually exclusive "vocation" branches (e.g., Dwarf: Miner vs. Forge-Guard vs. Berserker; Elf: Warden vs. Loremaster vs. Windrider) so two players of the same race genuinely diverge, with the same respec friction from §6.1 gating a branch swap.

### 6.3 Meaningful debuffs — curse nodes as a choice, not a tax

Darkest Dungeon pairs every hero with both positive Virtues and negative Afflictions rolled from the same stress mechanic, and lets players actively manage (not just suffer) quirks via the Sanitarium — removing a negative quirk costs less than locking in a positive one, which is itself a resource decision ([Game Developer: Affliction System deep dive](https://www.gamedeveloper.com/design/game-design-deep-dive-i-darkest-dungeon-s-i-affliction-system); [Darkest Dungeon Quirks wiki](https://darkestdungeon.wiki.gg/wiki/Quirks_(Darkest_Dungeon))). **Apply:** make some nodes explicit "curse nodes" — take a real drawback (e.g., −10% max health, or a recurring debuff outside your racial homeland biome) in exchange for a cheaper or stronger buff elsewhere in the same node. Always optional, always clearly labeled buff/drawback side-by-side in the tooltip (§4.1) — the player opts into risk rather than having it imposed.

### 6.4 Lore-gated capstones make power feel like a deed

Elden Ring's Great Runes drop only from named demigod bosses and require a Divine Tower visit to activate — two are a hard gate into the endgame capital, making power inseparable from narrative accomplishment ([Fextralife: Great Runes](https://eldenring.wiki.fextralife.com/Great+Runes); [GamesRadar explainer](https://www.gamesradar.com/elden-ring-great-runes-rune-arcs/)). **Apply directly** (this mirrors the brief's own examples): gate each race's capstone node behind a specific advancement/deed — kill a named troll, plant a banner in Mordor, forge mithril — rather than a point threshold alone, via §5.5's `rewards.function` mechanism, so the capstone is a story beat players will talk about, not a stat breakpoint.

### 6.5 Scale the world's teeth alongside the player's claws

Because the maintained Fabric mod ecosystem's Scaling Health equivalent has moved to **Silent's Power Scale**, which generalizes any attribute (mob or player) via a fully configurable expression combining per-player and per-location terms ([Modrinth: Silent's Power Scale](https://modrinth.com/mod/silents-power-scale); [legacy CurseForge: Scaling Health](https://www.curseforge.com/minecraft/mc-mods/scaling-health)), and **Scaling Mob Difficulty** offers a simpler time-based ramp on mob HP/damage/speed/armor-pierce ([Modrinth: Scaling Mob Difficulty](https://modrinth.com/mod/scaling-mob-difficulty)) — **apply this by keying enemy scaling to total skill-tree points spent, not raw player level or playtime.** Since the base mod already differentiates GOOD/EVIL NPC ranks (civilian → leader) by structure/biome tags, hook a `Silent's Power Scale`-style expression (or a lightweight bespoke equivalent) off the player's own skill-tree investment so the world's difficulty tracks *this mod's* power grants specifically — this attributes "harder" directly to the tree's own existence, rather than punishing ordinary exploration or grinding that has nothing to do with it.

### 6.6 Underlying philosophy

Sid Meier's "games are a series of interesting decisions" (§1.6) is the master rule underneath every lever above: a decision is interesting exactly when no option is a strict dominant/strictly-better pick and the player has enough information to weigh real trade-offs ([GDC 2012 recap](https://www.gamedeveloper.com/design/gdc-2012-sid-meier-on-how-to-see-games-as-sets-of-interesting-decisions); [Designer Notes: Sid's Rules](http://www.designer-notes.com/game-developer-column-5-sids-rules/)). If any node, branch, or respec cost in the shipped tree has an obviously-correct answer, it should be deleted or rebalanced — every decision point in the Middle-earth skill tree should trade something real (HP, a mutually-exclusive branch, a costed respec currency, a lore deed's time investment) against something real, so picking is the fun, not just the payoff.

---

## 7. Summary table: recommended stack

| Concern | Recommendation |
|---|---|
| Point source (primary) | Activity/skill-use XP (PMMO-style), race-scaled gain rates |
| Point source (secondary) | Advancement/lore-deed unlocks for capstones + shared constellation |
| Tree shape | Per-race branching tree (3-4 branches, capstone per branch) + shared cross-race "Deeds of Middle-earth" constellation |
| Passive nodes | `EntityAttributeInstance.addPersistentModifier`/`StatusEffectInstance`, keyed by stable per-node `Identifier` |
| Active nodes | Keybind (`KeyBindingHelper` + `ClientTickEvents`) → C2S `CustomPayload` (`PayloadTypeRegistry.playC2S`) → server validation → effect + optional S2C cooldown sync |
| Per-player data store | Fabric Data Attachment API (`AttachmentRegistry.createPersistent`, `copyOnDeath()`, `syncWith(...)`), not a hand-rolled `PersistentState` |
| Reading base mod's race/faction | Compile-time dependency on `sevenstars-api`'s public accessor (preferred) → mixin-observed packet mirror (fallback) → reflection (last resort) |
| Advancement hooks | Datapack `rewards.function` on (overridden) base-mod advancements (preferred, zero-mixin) → mixin into `PlayerAdvancements#award` (fallback) |
| Tree data model | Custom dynamic registry + `DynamicRegistries.registerSynced`, Java-pool-authored/datagen-exported JSON, datapack-overridable — mirrors base mod's own race/faction/npc pattern exactly |
| GUI | Hand-rolled vanilla `Screen` + `DrawContext`, mouse drag-pan/scroll-zoom, node-state color coding, edge-line prerequisites, buff/drawback tooltips; Cloth Config stays reserved for flat settings only |
| Respec | Costed consumable item (not free, not free), refunds a node or the whole tree |
| Difficulty levers | Mutually-exclusive vocation branches, optional curse nodes, lore-gated capstones, world-difficulty scaling keyed to skill-tree investment specifically |

---

## Sources

- Path of Exile: [Maxroll passive tree guide](https://maxroll.gg/poe/getting-started/passive-skill-tree-for-beginners), [Switchblade Gaming](https://www.switchbladegaming.com/path-of-exile-2/passive-tree-guide/), [PoE Wiki: Passive skill](https://www.poewiki.net/wiki/Passive_skill), [PoE Wiki: Orb of Regret](https://pathofexile.fandom.com/wiki/Orb_of_Regret), [PoE forum thread on respec cost](https://webcdn.pathofexile.com/forum/view-thread/3606178)
- Diablo: [d2tomb: Synergies](https://www.d2tomb.com/synergies.shtml), [Diablo Fandom: Synergies](https://diablo.fandom.com/wiki/Synergies), [Diablo Fandom: Skill Runes](https://diablo.fandom.com/wiki/Skill_Runes), [Maxroll: D4 Paragon Boards](https://maxroll.gg/d4/resources/paragon-boards), [Diablo Wiki: Respecialization](https://diablo2.diablowiki.net/Respecialization), [Token of Absolution](https://diablo-archive.fandom.com/wiki/Token_of_Absolution_(Diablo_II))
- Skyrim: [UESP: Skills](https://en.uesp.net/wiki/Skyrim:Skills), [Elder Scrolls Fandom: Perks](https://elderscrolls.fandom.com/wiki/Perks_(Skyrim))
- Grim Dawn: [Grim Dawn Wiki: Devotion](https://grimdawn.fandom.com/wiki/Devotion), [Crate Entertainment forums](https://forums.crateentertainment.com/t/devotions-a-how-to-on-maximizing-them/49384)
- Borderlands: [BorderlandsHQ skill tree guide](https://borderlandshq.com/skill-tree-guide/)
- Hades: [Hades Fandom: Mirror of Night](https://hades.fandom.com/wiki/Mirror_of_Night), [TheGamer: Mirror of Night](https://www.thegamer.com/hades-mirror-of-night-roguelite-progression/)
- Elden Ring: [Fextralife: Great Runes](https://eldenring.wiki.fextralife.com/Great+Runes), [GamesRadar: Great Runes explainer](https://www.gamesradar.com/elden-ring-great-runes-rune-arcs/)
- Darkest Dungeon: [Game Developer: Affliction System deep dive](https://www.gamedeveloper.com/design/game-design-deep-dive-i-darkest-dungeon-s-i-affliction-system), [Darkest Dungeon Wiki: Quirks](https://darkestdungeon.wiki.gg/wiki/Quirks_(Darkest_Dungeon))
- Design philosophy: [GDC 2012: Sid Meier on interesting decisions](https://www.gamedeveloper.com/design/gdc-2012-sid-meier-on-how-to-see-games-as-sets-of-interesting-decisions), [Designer Notes: Sid's Rules](http://www.designer-notes.com/game-developer-column-5-sids-rules/), [Critical-Gaming: Interesting Choices](https://critical-gaming.com/blog/2011/4/12/interesting-choices-interesting-gameplay-pt1.html)
- Minecraft progression mods: [Project MMO — CurseForge](https://www.curseforge.com/minecraft/mc-mods/project-mmo), [Reskillable — CurseForge](https://www.curseforge.com/minecraft/mc-mods/reskillable), [Origins — Modrinth](https://modrinth.com/mod/origins), [Origins/Apoli wiki — GitHub](https://github.com/apace100/origins-fabric/wiki)
- Difficulty scaling mods: [Silent's Power Scale — Modrinth](https://modrinth.com/mod/silents-power-scale), [Scaling Health — CurseForge](https://www.curseforge.com/minecraft/mc-mods/scaling-health), [Scaling Mob Difficulty — Modrinth](https://modrinth.com/mod/scaling-mob-difficulty)
- Fabric API (verified against source, `FabricMC/fabric-api` tag `26.2`): [`fabric-data-attachment-api-v1` — `AttachmentRegistry.java`](https://github.com/FabricMC/fabric-api/blob/26.2/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentRegistry.java), [`AttachmentType.java`](https://github.com/FabricMC/fabric-api/blob/26.2/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentType.java), [`AttachmentTarget.java`](https://github.com/FabricMC/fabric-api/blob/26.2/fabric-data-attachment-api-v1/src/main/java/net/fabricmc/fabric/api/attachment/v1/AttachmentTarget.java), [`fabric-events-interaction-v0` `PlayerAdvancementsMixin.java`](https://github.com/FabricMC/fabric-api/blob/HEAD/fabric-events-interaction-v0/src/main/java/net/fabricmc/fabric/mixin/event/interaction/PlayerAdvancementsMixin.java), Yarn 1.21.8 mappings (`EntityAttributeInstance` method list, `maven.fabricmc.net`), [Fabric Wiki: Key Bindings](https://wiki.fabricmc.net/tutorial:keybinds)
