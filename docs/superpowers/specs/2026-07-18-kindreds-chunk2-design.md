# Kindreds Phase 2 — CHUNK 2: The Great Trees, New Disciplines & the Visual Overhaul (Design Spec)

Status: DESIGN. Lore-first, MC 1.21.8 / Fabric, data-driven. Extends the existing engine
(AbilityDef sealed dispatch, SkillNode, Discipline/SkillTree/Theme synced registries,
ActiveAbilityService, VisionManager). Nothing here is implemented yet.

> Center of gravity: everything is Lord of the Rings / The Hobbit canon, named with deep cuts fans
> will recognize, and *mechanically* creative in Minecraft — abilities and visuals "never thought of,"
> not "+X stat." Trees are HUGE (30–45 nodes/race), truly branching, tiers to 5–6.

---

## 0. What exists today (baseline to extend)

- **7 disciplines:** combat, archery, mining, stealth, smithing, survival, lore. Points earned per
  discipline via activity hooks; each level = 1 point in that discipline.
- **AbilityDef subtypes:** `attribute`, `status_effect`, `vision_unlock`, `active` (self status
  bundle), `curse` (contextual/uncond.), `contextual_boon`. Dispatched by `"type"`.
- **SkillNode:** `id, tier, pos[x,y], cost{discipline,points}, prereqs[], abilities[],
  deed_advancement?, exclusive_group?`. Multi-prereq (convergence) and `exclusive_group`
  (fork-and-block) are supported but UNUSED today.
- **Trees:** ~9 nodes/race, shallow (tier ≤3), mostly linear. This chunk replaces them.
- **Vision:** `VisionManager` + `SeeThroughLayer` (a NO_DEPTH_TEST render layer) + `KeenSightLens`/
  `StoneSenseLens` draw a flat outline box → the "ugly box" problem. Only 2 of 8 lenses render.
- **Actives:** all 18 are self-applied vanilla status bundles. No real mechanics. No loadout.

---

## 1. TREE FRAMEWORK (applies to all 8 races)

### 1.1 Shape

Every race tree is: **Roots (shared trunk, tier 1) → three Vocation Branches that fork (tiers 2–4)
→ mutually-exclusive Specialization Capstones (tier 5, `exclusive_group`) → one convergent Grand
Capstone (tier 6, multi-prereq, deed-gated).** Target **30–45 nodes**.

```
                         [Grand Capstone]  (tier 6, requires 2 of 3 branch capstones + a Deed)
                        /        |         \
        [Spec Cap A1|A2]  [Spec Cap B1|B2]  [Spec Cap C1|C2]   (tier 5, each pair exclusive_group)
             |  \  /  |         |                 |
           [ branch A nodes ] [ branch B ]   [ branch C ]      (tiers 2–4, each branch forks once)
             \        |        /
              [ Root nodes: 3–4 shared tier-1 unlocks ]        (tier 1)
```

- **Fork-and-block:** at tier 5 each branch offers TWO capstones sharing one `exclusive_group`
  (pick one, the other locks) → replayable identity choices.
- **Convergence:** tier-4 "hybrid" nodes and the tier-6 grand capstone list **multiple prereqs**
  from different branches, rewarding cross-branch investment (and mirroring the mixed-discipline
  cost the trunk already spends).
- **Discipline spread:** a race's branches lean on its favored disciplines (Elf → archery/lore/
  survival; Dwarf → mining/smithing/combat; etc.), but the trunk pulls from 2–3 so no build is
  single-discipline.
- **Deeds:** tier-5/6 capstones are `deed_advancement`-gated (the existing sealed-node mechanic),
  authored as advancements (kill X, mine Y, reach place Z).

### 1.2 Node budget per race (target)

| Tier | Count | Role |
|---|---|---|
| 1 Roots | 3–4 | cheap identity unlocks (1–2 pts) |
| 2 | 6–9 | branch entries + first fork |
| 3 | 8–12 | branch mid, minor passives + 1st actives |
| 4 | 6–9 | hybrids (multi-prereq), vision unlocks |
| 5 Spec capstones | 6 (3 pairs) | exclusive_group, deed-gated, signature actives |
| 6 Grand capstone | 1 | convergent, deed-gated, race-defining |

= **34–41 nodes/race**, ≈ **290 nodes** across 8 races.

---

## 2. NEW & SPECIAL DISCIPLINES (beyond the 7)

Add as new `Discipline` registry entries + activity hooks. Not every race uses every one; a
discipline a race can't train has no branch (the tab shows "no path," already handled).

| Discipline | Earned by (activity hook) | Favored races | Fantasy |
|---|---|---|---|
| **Song** (Music of Power) | playing note blocks / jukeboxes near mobs; being near allies; using the new Song actives | Elf, Human (Rohan/minstrels), Hobbit | The Ainulindalë — reality was sung. Lúthien out-sang Sauron; Finrod duelled him in song. Buffs, charms, wards via melody. |
| **Beast-lore** | taming/breeding animals, riding, being near tamed mobs | Elf (animal-friend), Human (Rohan horse), Orc-kin (wargs) | Radagast, Huan, Mearas, wargs, the eagles. |
| **Runecraft / Inscription** | using an anvil/enchanting, writing (lecterns/books), mining at night (moon-runes) | Dwarf, Elf, Human (Gondor) | Cirth, Angerthas, moon-letters, Ithildin, the Doors of Durin, warding runes. |
| **Leadership / Kingship** | leading (allies nearby), defeating champions, holding ground | Human (Gondor/Rohan), Uruk (captains) | Aragorn, Théoden, Boromir; the Oath of Eorl; banners and horns. |
| **Woodcraft** | in forests: foraging, tree-felling, planting, moving unseen through leaves | Elf, Hobbit | Lothlórien, Mirkwood, the Old Forest, Ents' domain. |
| **Shadow / Corruption** (opt-in, dark) | killing the innocent, wielding cursed gear, being in the Dark; feeds the reserved `corruption` field | Orc-kin (natural), any (fallen) | The One Ring, Morgul, the Nine, dragon-sickness. Power with escalating cost. (Ties to Phase-3 Corruption.) |
| **Seafaring** (stretch) | on/under water, boats | Human (Númenor), Elf (Teleri) | Númenor, the Grey Havens, Eärendil. |

Recommended first wave: **Song, Runecraft, Beast-lore, Leadership** (each unlocks vivid actives).
Woodcraft/Shadow/Seafaring can be a later pass.

---

## 3. VISUAL-EFFECTS OVERHAUL  ⭐ (the priority)

The current lens = a flat box outline via one NO_DEPTH_TEST layer. Replace with a **layered,
themed, data-driven VFX system**. Three cooperating layers:

### 3.1 World highlight layer (replaces the "box")

Instead of a single axis-aligned outline, render targets with **a themed glow silhouette + motion**:

- **Silhouette glow, not a box:** draw the entity/block *outline by its actual model* using a
  see-through pass, then bloom it. Technique: render target into a mask, then a **pulsing colored
  glow** (additive, NO_DEPTH_TEST) with soft edges. Per-race color + shimmer.
- **Ore veins (Dwarf Stone-sense):** don't box blocks — draw a **flowing vein**: sample ore blocks
  in radius, render small additive "gem-spark" quads at block faces with a slow twinkle, and faint
  connective lines along contiguous veins (a glowing seam through the stone). Gold/mithril veins
  glow brighter (Ithildin white for mithril).
- **Keen-sight (Elf):** targets get a **star-glow** — a soft white/silver aura + a few drifting
  star particles, brightest on foes; distant things get a faint tracer, echoing Legolas seeing far.
- **Heat/Blood-sight (Orc):** living things pulse **red/orange**, stronger the more wounded (a
  wounded target glows hot) — the orc smells blood.

### 3.2 Ambient aura layer (self VFX)

Passive/owned capstones give the *player* a subtle signature aura (client particle emitter tied to
owned nodes, throttled): Elf — faint silver motes under open sky; Dwarf — ember flecks; Orc — dark
smoke wisps at night; capstone owners — a stronger, unique emitter (e.g. Light of Eärendil = a soft
halo). Data-authored (see 3.5), gated on `VisionManager`/owned-node state, Iris-degraded.

### 3.3 Ability-cast VFX layer (one-shot effects)

Each active fires a scripted VFX: particle burst + sound + optional screen shake/flash. E.g. War-
horn = expanding shockwave ring of particles + horn sound; Light of Eärendil = a blooming light
flash + lingering glow orb; Ride of the Rohirrim = dust plume + galloping motes.

### 3.4 Rendering tech

- **World layers:** `WorldRenderEvents.AFTER_TRANSLUCENT` (as now) + a dedicated additive
  `RenderLayer` (NO_DEPTH_TEST for see-through, plus a normal-depth pass for the glow that respects
  occlusion where wanted). Build outline geometry from entity models via a lightweight outline pass
  (or use vanilla's glowing/outline framebuffer where feasible).
- **Particles:** custom particle types (star-mote, ember, gem-spark, smoke-wisp, light-halo) via
  `ParticleFactoryRegistry`; emitters driven from a client tick, budgeted by distance/count.
- **HUD/screen overlays:** `HudElementRegistry` / a HUD render callback for tints and vignettes
  (Phial glow, blood-sight red edge), like the existing `HudTintOverlay`.
- **Iris/shaders:** keep the detect-and-degrade path (`VisionManager.isLensLive`), fall back to
  HUD-only glow when a shaderpack is active.
- **Mithril-mod precedents:** reuse the gamma-restore-on-disconnect discipline, the
  DataAttachment + CustomPayload sync patterns, and the NO_DEPTH_TEST see-through layer as the base
  to build the richer glow on.
- **Perf:** radius-capped scans on a throttled interval (already the pattern), hard particle caps,
  frustum-cull highlights, only one active vision at a time.

### 3.5 New data schema for VFX (author effects without code)

Add authorable effect descriptors so trees define *how things look*, not just what they do:

- `VisionDef` (registry `kindreds:vision`) — replaces hard-coded lens constants:
  `{ id, style: outline_glow|ore_vein|heat|tracker, color, secondary_color, pulse, radius,
     particle: <particleId?>, target: entities|ores|players|undead, hud_tint? }`.
- `AbilityDef` additions (see §5): `vfx` block on actives/capstones:
  `{ cast: {particle, count, shape: burst|ring|beam, sound, flash?, shake?}, aura?: {particle,
     rate, color} }`.

Result: the "Elf archer sight" becomes a `VisionDef` with `style: outline_glow`, silver color,
star particles, `target: entities` — a shimmering star-lit highlight, not a box; fully data-tweakable.

---

## 4. THE EIGHT TREES (structure + node lists)

Notation per node: **Name** *(tier, discipline, cost)* — effect. `‡` = deed-gated capstone,
`⟂group` = exclusive_group member, `⋈` = convergent (multi-prereq).

### 4.1 ELF — "The Firstborn" (archery / lore / survival / song / woodcraft)  — full design

**Roots (t1):** Eyes of the Eldar *(archery,1)* — Keen-sight vision (star-glow) · Woodland Step
*(survival,1)* — +move in leaves/grass, no leaf-slow · Elven-tongue *(lore,1)* — +lore XP, read
moon-runes hint · Kindler *(song,1)* — unlock Song discipline actives.

**Branch A — Galadhrim Marksman (archery):**
- Sure Aim *(t2,archery,2)* — bow charges faster.
- Elven-fletching *(t2,archery,2)* — arrows cost reduced / occasional no-consume.
- Wind-reader *(t3,archery,3)* — arrow drop reduced (flat-shot).
- Piercing Shaft *(t3,archery,3)* — arrows pierce 1 extra target.
- **⟂A Starlit Aim** *(t5,archery,6)‡* — ACTIVE "Star-shot": a charged arrow that leaves a light
  trail and **blinds + marks** on hit (mark = your hits crit the target briefly).
- **⟂A The Last Arrow** *(t5,archery,6)‡* — passive: at low ammo/health your next shot is guaranteed
  crit + knockback (Bard's black arrow). Exclusive with Starlit Aim.

**Branch B — Warden of the Wood (survival/woodcraft):**
- Tireless *(t2,survival,2)* · Woodland Grace *(t3,survival,3)* — +move, feather-fall in trees ·
  Speaker-to-Beasts *(t3,beast-lore,3)* — animals neutral/friendly, tame faster ·
- **⟂B Song of Rest** *(t5,song,5)‡* — ACTIVE AoE: allies in range gain Regen+Resistance, hostile
  Undead are repelled (a healing hymn).
- **⟂B Mirkwood Hunter** *(t5,survival,5)‡* — passive: in forests, permanent Speed + invisibility
  while still; exclusive with Song of Rest.

**Branch C — Loremaster (lore/song):**
- Star-lore *(t2,lore,2)* — night vision brighter, see moon-runes/Ithildin glow ·
- Elven-song *(t3,song,3)* — ACTIVE "Song of Power": channel to **charm** a mob (temporary ally) or
  break enemy morale (Lúthien vs Sauron). ·
- Ring-lore *(t4,lore,4)⋈* (req Star-lore + Elven-song) — reduces corruption gain; reveals magic.
- **⟂C Light of the Phial** *(t5,lore,6)‡* — see §6 signature.

**Grand Capstone (t6):** **Grace of the Eldar** *(lore,8)‡⋈* (req 2 of the 3 branch capstones + Deed
"Walk under the stars of Elostirion") — permanent star-aura; Starlit Grace boon upgraded (Regen II
under sky); the Phial recharges faster; foes of the Dark are unnerved nearby.

Node count: ~36.

### 4.2 DWARF — "Khazad" (mining / smithing / combat / runecraft)  — full design

**Roots (t1):** Aulë's Inheritance *(mining,1)* — never lose bearing underground (compass to spawn/
death) · Stone-sense *(mining,1)* — ore-vein vision · Forge-born *(smithing,1)* — smelt/repair
bonus · Deep-lungs *(survival,1)* — slower drown, cave sight.

**Branch A — Delver (mining):**
- Vein-seeker *(t2,mining,2)* — Stone-sense radius up, gold/mithril flare · Rockbreaker
  *(t3,mining,3)* — Haste when mining stone · Sure-footed Deeps *(t4,mining,4)⋈* — no fall damage
  underground, ledge-grab ·
- **⟂A Heart of the Mountain** *(t5,mining,6)‡* — ACTIVE "Delver's Sense": pulse that reveals ALL
  ores + caverns in a large radius as glowing veins for 30s (see §6) · **⟂A Dragon-hoard** *(t5,
  mining,6)‡* — passive: mining gems grants brief Strength/Resistance (gold-lust as power, at a
  corruption tick) — exclusive.

**Branch B — Runesmith (smithing/runecraft):**
- Khazâd-craft *(t2,smithing,2)* — better tool/armor durability · Moon-runes *(t3,runecraft,3)* —
  inscribe a block with a warding rune (buff zone) · Ithildin *(t4,runecraft,4)⋈* — craft glowing
  mithril-sign that lights + wards Undead ·
- **⟂B Master Smith of Erebor** *(t5,smithing,6)‡* — forge a signature Dwarf-make item (bonus stats)
  · **⟂B Warden-runes** *(t5,runecraft,6)‡* — place rune-totems granting party auras — exclusive.

**Branch C — Warrior of Khazad-dûm (combat):**
- Axe-master *(t2,combat,2)* · Shield-wall *(t3,combat,3)* — blocking reflects knockback ·
  Slayer of the Deep *(t4,combat,4)* — +dmg vs large/undead ·
- **⟂C Baruk Khazâd!** *(t5,combat,6)‡* — ACTIVE war-cry: nearby allies +Strength, enemies briefly
  frightened; you gain Resistance · **⟂C Unbroken** *(t5,combat,6)‡* — passive: below 25% HP gain
  Resistance II + knockback immunity (last-stand of Azaghâl) — exclusive.

**Grand Capstone (t6):** **Durin's Crown** *(combat,8)‡⋈* — permanent Deep-Delver upgrade (Haste II
underground), a Dwarf-make relic, and Mirrormere scrying (map of nearby caverns).

Node count: ~35.

### 4.3 HUMAN — "The Secondborn" (combat / leadership / archery / smithing / song)

Three **cultural** branches (fits Gondor/Rohan/Dale under one race):

- **Gondor (Leadership/combat):** Steward's Resolve · Tower Guard (shield/armor) · Beacon-lighter
  (signal → summon aid/rally) · **Hands of the King** ‡ (heal allies, cleanse — Aragorn) / **Anduril,
  Flame of the West** ‡ (a signature sword-strike, extra vs Orcs/Undead) ⟂.
- **Rohan (Beast-lore/combat):** Horse-master (tame/ride Mearas, mounted speed) · Spear of the Mark ·
  **Ride of the Rohirrim** ‡ (mounted charge: trample + fear AoE — see §6) / **Shieldmaiden's
  Defiance** ‡ (bonus vs the great and the Nazgûl) ⟂.
- **Dale (Archery/smithing):** Bowman of Dale (Bard) · Wind-lance · **The Black Arrow** ‡ (a crafted
  one-shot heavy bolt) / **Master of the Long Lake** ‡ (trade/renown, market boons) ⟂.

**Grand Capstone:** **Kingship of the West** ‡⋈ — a rallying banner aura (party buff), Last-Stand
upgraded, renown discounts. ~40 nodes (richest, being 3 cultures).

### 4.4 HOBBIT — "The Little Folk" (stealth / survival / archery(sling) / song / woodcraft)

- **Burglar (stealth):** Soft Step · Pocketses (extra loot/steal) · Unseen (near-invis while still)
  · **Thief of Erebor** ‡ (steal-and-vanish: hit + brief invis + speed) / **Barrel-rider** ‡
  (evasion, water/movement tricks) ⟂.
- **Sling & Stone (archery):** Stone-thrower (thrown snowballs/stones deal real damage + stagger —
  see §6) · Sharp Eye · **The Bounder** ‡ (rapid stone volley) / **Conker-champion** ‡ (charged
  heavy throw) ⟂.
- **Homely Heart (survival/song):** Second Breakfast (food heals more) · Gaffer's Wisdom (garden/
  crop bonus) · **Ringbearer's Resolve** ‡ (strong corruption/effect resistance; endure the
  unbearable) / **Songs of the Shire** ‡ (morale aura) ⟂.

**Grand Capstone:** **There and Back Again** ‡⋈ — luck aura, corruption resistance, a "pocket" of
saved gear on death. ~34 nodes.

### 4.5 ORC (combat / shadow / beast-lore / survival)  ·  4.6 GOBLIN (stealth / mining / shadow) ·
### 4.7 SNAGA (stealth / survival / shadow) · 4.8 URUK (combat / leadership / smithing)

Shared dark framework (each still distinct):

- **ORC — Mordor's Soldiery:** Cruelty (lifesteal on kill) · Warg-kinship (tame/ride wargs) ·
  **War-horn of Barad-dûr** ‡ (rout AoE — see §6) / **Eye Is Upon You** ‡ (mark + fear) ⟂ ·
  Grand: **Captain of the Dark Tower** ‡ (summon a lesser orc ally / command aura).
- **GOBLIN — Misty Mountains:** Wall-crawler (climb + wall-cling) · Tinker (traps: place a snare/
  spike that roots/poisons) · Swarm (stronger near other orckin/players) · **Great Goblin's Horde**
  ‡ (loot/greed burst) / **Cave-collapse** ‡ (mining-charge that breaks a burst of blocks) ⟂.
- **SNAGA — the Underlings:** Overlooked (deep stealth, backstab crit) · Scavenger (extra drops) ·
  Tracker (heat/blood-sight of wounded prey) · **Slit-and-Run** ‡ (hit + vanish) / **Whatever It
  Takes** ‡ (desperation power at low HP) ⟂.
- **URUK — the Fighting-Uruk-hai:** Isengard-forged (heavy armor mastery) · Orc-draught (a "potion"
  active: heal + Strength, small self-harm) · Relentless (no slow when hurt) · **Fighting Uruk-hai**
  ‡ (frenzy: attack speed + lifesteal, shedding the −atk-speed) / **Forced March** ‡ (party speed,
  ignore terrain) ⟂ · Grand: **Hand of Saruman** ‡ (rally warband aura + sun-defiant Strength II).

Each ~30–34 nodes.

---

## 5. TECHNICAL PLAN

### 5.1 New AbilityDef subtypes (extend the sealed dispatch — additive, backward-compatible)

- `active_projectile` — fire a themed projectile: `{ projectile, speed, on_hit: [AbilityDef],
  pierce, gravity, trail_vfx }` (Star-shot, Black Arrow, Thrown Stone).
- `active_aura` — timed AoE around caster: `{ radius, target: allies|enemies|undead, effects:
  [AbilityDef], vfx }` (Song of Rest, War-horn, Baruk Khazâd, banners).
- `active_reveal` — vision pulse: `{ vision_ref, radius, duration }` (Delver's Sense).
- `active_summon` — spawn a temporary ally: `{ entity, count, duration, buffs }` (Captain, warg).
- `active_terrain` — place a transient structure/effect: `{ kind: rune_totem|snare|light|collapse,
  duration }` (Warden-runes, traps, Ithildin, Cave-collapse).
- `mounted_charge` — Ride of the Rohirrim: requires mount; trample + fear cone.
- `vision_unlock_v2` → references a `VisionDef` registry entry (the rich, data-authored lens) rather
  than a hard-coded id.
- `vfx` — a shared block usable on any active/passive for cast + aura visuals (§3.5).
- `light_ward` — the Phial/Ithildin family: emit light + repel/blind Undead in radius.

All are new `permits` entries + `codecFor` cases + exhaustive-switch cases (AbilityApplier,
NodeTooltip, SkillTreeScreen.kindOf, CurseContextService). Old data keeps working (dispatch is
open-by-`type`).

### 5.2 New registries

- `kindreds:vision` (VisionDef) — synced; drives the highlight system data-drivenly.
- `kindreds:discipline` already exists — add the new discipline entries + `race_scaling` rows.
- Keep `skill_tree`/`theme`/`birth_trait` as-is; author bigger tree JSON.

### 5.3 Server services

- **ActiveAbilityService v2:** dispatch by ability subtype (projectile/aura/summon/terrain/charge),
  cooldowns (exists), plus **Attunement loadout** (from Chunk-3 plan: N slots, assign which owned
  active fires) — bring forward if desired.
- **ProjectileService / SummonService / TerrainService:** spawn + track transient entities/blocks,
  cleanup on timeout, tag ownership.
- **VisionService (server side):** validates unlocked visions; the heavy lifting is client render.
- **ExclusiveGroupService:** enforce `exclusive_group` at unlock (UnlockService addition) — reject
  if a sibling in the group is owned; allow via respec.

### 5.4 Client rendering (the big build)

- `VisionRenderer` — reads active `VisionDef`, scans targets (throttled, radius-capped), draws the
  themed glow/vein/heat via the additive RenderLayer + particle emitters.
- `AuraEmitter` — per-owned-capstone ambient particles.
- `AbilityVfxPlayer` — on a `PlayAbilityVfxS2C` packet, plays the cast VFX (particles/sound/flash).
- `ParticleFactoryRegistry` — register star-mote, ember, gem-spark, smoke-wisp, light-halo.
- HUD overlays via a render callback for tints/vignettes.

### 5.5 Networking

- `PlayAbilityVfxS2C` (server → owner + nearby) for cast visuals.
- Existing `SyncKindredDataS2C` carries owned nodes/visions; client derives auras/visions from it.

### 5.6 Phased build order (each phase ships + is playtested)

1. **VFX foundation** — VisionDef registry + `VisionRenderer` glow/vein/heat; convert Keen-sight &
   Stone-sense off the box onto the new system (immediate visible win). Custom particles.
2. **Tree data v2 (framework)** — exclusive_group enforcement, multi-prereq convergence, bigger
   authored trees for 2–3 races end-to-end (Elf, Dwarf) to prove the shape + UI at scale.
3. **New AbilityDef actives** — projectile/aura/reveal/summon/terrain + ActiveAbilityService v2 +
   the VFX-cast pipeline; wire the signature abilities for Elf/Dwarf.
4. **Remaining races' trees** + new disciplines (Song, Runecraft, Beast-lore, Leadership) with hooks.
5. **Attunement loadout + Codex/tree UI polish** for the larger trees (search, discipline filter,
   ability-slot assign).
6. Woodcraft/Shadow/Seafaring + Fellowship hooks.

### 5.7 Performance

Throttle scans (20-tick), cap particle counts and highlight targets by distance, frustum-cull,
one vision at a time, pool transient entities, and gate all render on Iris-degrade.

---

## 6. SIGNATURE CREATIVE ABILITIES (exact MC mechanics, per race)

- **Elf — Light of the Phial (Eärendil):** ACTIVE `light_ward` + `vfx`. Raise the star-glass: emit
  bright light (place temporary light), **blind + push Undead/spiders**, and grant nearby allies
  Fear-immunity for 8s; a blooming halo VFX. Dims (reduced radius) in "dark power" biomes/near
  bosses — lore-true the Phial waned near Sauron. Canon: the Phial "kindled to a silver flame…
  as though Eärendil had come down… with the last Silmaril." [Phial of Galadriel]
- **Dwarf — Delver's Sense (Heart of the Mountain):** ACTIVE `active_reveal`. A stone-ringing pulse
  reveals every ore vein + open cavern in a big radius as glowing seams (mithril = Ithildin-white)
  for 30s; briefly Haste. The gem-vein VFX from §3.1. Canon: Khuzdul/Angerthas, Ithildin reflecting
  star/moonlight, Mirrormere. [Khuzdul / Moon-letters]
- **Human/Rohan — Ride of the Rohirrim:** `mounted_charge`. While mounted at speed, blow the horn:
  a forward cone **tramples** (knockback+damage) and inflicts **Fear** (mobs flee), allies in the
  cone gain Speed; dust-plume VFX + horn. Canon: the charge at Pelennor with "blaring horns,
  thunderous hooves." [Rohirrim]
- **Elf/Human — Song of Power:** `active_aura` channel. Sustain a song: choose **Ward** (allies
  Regen/Resistance, Undead repelled) or **Binding** (a mob is charmed to your side, or enemies
  routed). Interrupted if you take heavy damage. Canon: Lúthien's "song of greater power" that
  overthrew Sauron; Finrod's song-duel. [Songs of Power]
- **Orc — War-horn of the Dark Tower:** `active_aura`. A dread blast: enemies (non-orc) get
  Weakness + brief Fear, orc-kin allies get Strength; shockwave-ring VFX + horn. (Mirrors Rohan's
  from the Shadow's side.)
- **Hobbit — Thrown Stone:** `active_projectile`. Hurl a stone (or any throwable) that deals real
  damage + stagger (mining-fatigue/slow) and can knock items from hands; cheap, spammable. Canon:
  Hobbits "could throw stones so well that few could match them."
- **Dwarf — Baruk Khazâd! / Uruk — Fighting Uruk-hai / Gondor — Anduril:** aura war-cry / self
  frenzy / signature strike vs Orcs & Undead respectively.

---

## 7. RACE-SYNERGY / FELLOWSHIP HOOKS

The trees should expose join points for the separate Fellowship system:

- **Cross-training "friend" nodes:** tier-4 optional nodes gated on a reputation/deed with another
  race — **Elf-friend** (a Dwarf or Man learns a minor Elf woodcraft/archery node — Gimli & Legolas),
  **Kheled-friend**, **Shire-friend**. They cost the *other* race's discipline and unlock 1–2 of its
  low-tier passives. This is where `allowCrossTraining` config finally binds.
- **Shared-aura capstones:** several capstone auras (Song of Rest, Kingship banner, Warden-runes,
  Baruk Khazâd) already buff *allies* — the Fellowship layer can amplify these when a mixed-race
  party stands together (the "Nine Walkers" bonus).
- **Complementary pairs to reward:** Elf+Dwarf (Gimli/Legolas kill-count rivalry → a friendly
  competitive buff), Man+Hobbit (guardianship), Uruk+Orc (warband). The trees flag which capstones
  are "party" abilities so Fellowship can scale them.
- Reserve a `fellowship` tag on nodes/abilities so the synergy engine can discover them without
  re-authoring trees.

---

## 8. Open decisions for the human partner

- Loadout: bring Attunement slots forward into this chunk (recommended — the trees add many actives),
  or keep "first active" until Chunk 3.
- Shadow/Corruption discipline: include now (opt-in dark path) or hold for the Phase-3 Corruption
  system it feeds.
- Scale of first delivery: Elf + Dwarf fully (trees + signature abilities + new VFX) as the vertical
  slice, then fan out — vs. all-8 trees data-first then abilities.

### Sources
Phial of Galadriel / Light of Eärendil (Tolkien Gateway, Wikipedia); Khuzdul, Moon-letters, Cirith/
Angerthas, Ithildin (Tolkien Gateway); Songs of Power — Lúthien & Finrod vs Sauron (Silmarillion,
commentary); Rohirrim / Ride of the Rohirrim (Tolkien Gateway, Wikipedia).
