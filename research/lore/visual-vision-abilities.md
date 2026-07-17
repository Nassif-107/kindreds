# Vision & Visual-Effect Abilities + Fabric 1.21.8 Rendering Tech

> Authored from the three lore docs (`elves-and-men`, `dwarves-and-hobbits`, `evil-races`) plus direct rendering experience: a sibling mod in this project already shipped a custom **NO_DEPTH_TEST "see-through" line RenderLayer** to draw ore markers through terrain — so through-wall outlines are *proven feasible* here. Vision is a signature pillar of the mod: skills change **how you see the world**.

---

## 1. The lore-grounded vision abilities

### Dwarf — **Stone-sense / Deep-sight**
- **Lore:** Dwarves are "of the stuff of the earth," tunnel-dwellers who read stone; night is no barrier underground. ("delved too greedily and too deep.")
- **What you see:** ore veins & cavities **glow through nearby rock** (radius grows with skill/depth); full dark-vision underground; a subtle sense of open space vs. solid.
- **When:** toggle (a "listen to the stone" active) or passive-while-underground; strengthens the deeper you are.
- **Tech: CHEAP** — reuse the proven see-through NO_DEPTH_TEST line/box layer to outline target blocks within a radius (exactly the mithril-locator pattern) + a brightness/night-vision effect underground.

### Elf — **Keen Sight / Starlight-eyes**
- **Lore:** Elves see "far and keen," Legolas counts riders leagues away; they need no torch under stars.
- **What you see:** extended clarity/brightening under moon & stars; **living creatures outlined at distance**, tinted **friend (soft blue) vs. foe (red)**; hidden/invisible things faintly revealed.
- **When:** passive (ambient), with a short "far-sight" active that briefly zooms/extends the outline range.
- **Tech: MODERATE** — entity scan (like the ore scanner but for mobs) → see-through outline layer with per-faction tint (read the base mod's diplomacy for friend/foe); brightening via a gentle gamma/night-vision-lite. Distance-capped to stay balanced.

### Orc / Goblin — **Dawnless Sight** (+ the daylight penalty made *visible*)
- **Lore:** bred in the Great Darkness; keen in the dark, but "cannot abide the Sun" (Treebeard). Sauron's Dawnless Day freed them to fight by day.
- **What you see:** strong night/underground vision with a **dark red monochrome tint**; in **direct daylight**, a harsh **bloom + desaturation wash** — the screen visually "hurts," expressing the Dread-of-the-Sun debuff (not damage, morale). Uruk "Sun-Defiance" node removes the wash.
- **When:** passive, contextual to light level.
- **Tech: MODERATE** — a post-process color-grade (tint/desaturate) *or*, for Iris/Sodium safety, a full-screen HUD tint quad; light-level check drives which state. HUD approach avoids shader conflicts.

### Hobbit — **Unseen**
- **Lore:** Hobbits "disappear swiftly and silently"; to big folk it "seems like magic."
- **What you see:** while sneaking, your own body **fades toward transparent**, a soft **vignette + muffled edges** frames the view (you feel small and hidden); at high tiers, safe footing / quiet paths hinted.
- **When:** active-while-sneaking (ties to the +45% sneak identity and the "Vanish" active).
- **Tech: CHEAP** — player-model alpha/translucency in the client renderer + a HUD vignette overlay. No shaders needed.

### Ranger / Dúnedain (Gondor tracking branch) — **Tracking Sight**
- **Lore:** the Dúnedain Rangers are master trackers reading trails others can't.
- **What you see:** recent creature **trails / footprints** and passage markers highlighted on the ground toward nearby/recently-passed mobs.
- **When:** toggle active.
- **Tech: MODERATE** — approximate by sampling recent entity positions (breadcrumb markers) rendered as faded ground decals/particles; or outline nearby mobs + a directional trail. (Same entity-outline tech as Elf keen-sight.)

### Wraith-world / Nazgûl dread (high-tier / Shadow-corruption)
- **Lore:** wearing the Ring throws Frodo into a desaturated shadow-world where the Nazgûl blaze as terrible shining forms; the Black Breath spreads dread.
- **What you see:** **Wraith-sight** — the world desaturates to a grey shadow-realm while *living/powerful beings glow*; **Fear/Black-Breath** — near a Nazgûl the screen gets a creeping **vignette, desaturation, subtle distortion + heartbeat**, vision narrows.
- **When:** Wraith-sight = risky toggle tied to the Shadow/corruption system (power at a cost); Fear = enemy-imposed status near Nazgûl.
- **Tech: MODERATE** — post-process desaturate + entity glow for wraith-sight; HUD vignette/overlay + gentle screen-shake/distortion for fear. Curable (athelas) removes the effect.

---

## 2. Fabric 1.21.8 rendering toolbox (by cost)

| Technique | Use for | Cost | Notes |
|---|---|---|---|
| **NO_DEPTH_TEST see-through RenderLayer** (`DepthTestFunction.NO_DEPTH_TEST`, `WorldRenderEvents.AFTER_TRANSLUCENT`) | Stone-sense ore/vein outlines through rock; entity silhouettes through walls | **Cheap** | **Proven** (mithril-locator). Draw boxes/lines at cached positions, camera-relative translate, distance-fade. |
| **Entity outline / glow** (vanilla glowing-effect outline framebuffer, or custom outline layer with team/faction color) | Elf keen-sight friend/foe outlines, Ranger tracking, wraith beings | Cheap–Moderate | Faction tint by reading base-mod diplomacy. Distance-cap for balance. |
| **HUD overlay quads** (`HudElementRegistry` + `DrawContext`, full-screen translucent quad, vignette, tint) | Orc daylight wash, Hobbit vignette, Fear vignette, color tints | **Cheap** | **Iris/Sodium-safe** — no shader pipeline needed. Preferred for screen tints. |
| **Post-process / screen shader** (1.21.x `post_effect/*.json` + `PostEffectProcessor`, like vanilla creeper/spider/invert vision) | Desaturation, color grade, blur, wraith-world | Moderate | Iris/Sodium may conflict — **test**; fall back to HUD-quad tint if broken. |
| **Brightness / night-vision** (`StatusEffects.NIGHT_VISION` or custom gamma bump) | Dwarf dark-sight, Elf starlight brightening | Cheap | Cleanest for "see in the dark." |
| **Fog color/distance** (fog events / mixin on fog renderer) | Wraith-world haze, atmosphere per vision | Moderate | Optional polish. |

**Iris/Sodium note:** the pack ships Iris + Sodium. Full post-process shaders can conflict with shaderpacks; **default to HUD-overlay tints + status-effect brightness + the see-through outline layer** (all shader-independent), and treat true post-process color-grading as an optional "enhanced" path that gracefully degrades.

---

## 3. Limits, exclusivity & combinations (the balance)

- **One active "vision mode" at a time.** Treat vision as a **lens slot**: the player toggles ONE primary vision (Stone-sense OR Keen-sight OR Dawnless OR Wraith-sight). This prevents screen-effect chaos, keeps performance sane, and makes vision a *real build choice*. A bind cycles/toggles the equipped lens.
- **Passives can layer** (e.g., baseline dark-vision, Hobbit fade-while-sneaking) but **full-screen visions are mutually exclusive** while active.
- **Emergent combos (across the tree, not simultaneous visions):**
  - Dwarf **Stone-sense** + Snaga **+block-reach** = the ultimate miner.
  - Elf **Keen-sight** + Ranger **Tracking** + archery = the ultimate hunter.
  - Orc **Dawnless-sight** + night-power + stealth = the perfect nightstalker (helpless-feeling by day → drives the harder evil playstyle).
  - **Wraith-sight** is powerful but tied to the **Shadow/corruption** system — using it advances corruption, a risk/reward that can warp you permanently.
- **Cost:** high-tier visions cost points AND (for wraith-sight) corruption; the daylight-wash is a *downside* baked into orc vision, not removable except via the Uruk sun-tolerance branch.

---

## 4. Summary
Vision is **cheap-to-moderate** to build and hugely distinctive. Start with the shader-independent trio — **see-through outlines** (Dwarf stone-sense, already proven), **HUD-overlay tints/vignettes** (Orc daylight, Hobbit/Fear), and **status-effect brightness** (dark/starlight-sight) — which cover 80% of the wow-factor with zero shaderpack conflict. Layer the optional **post-process color-grade** (wraith-world, desaturation) later as an enhanced path. Gate it all behind a **one-active-lens limiter** so vision is a meaningful, combinable build choice.
