# Vision Rendering — Verified Fabric 1.21.8 Tech Notes

> Companion to `visual-vision-abilities.md`. Verified against yarn-1.21.8+build.1 + FabricMC 1.21.8 sources where noted. **Every exact enum/class spelling below must be re-checked against decompiled 1.21.8 before coding** (two research passes disagreed on a few names). This is reference material for the Vision system (Phase 2).

## ⚠️ Version-pinning (critical)
- MC's client renderer was **rewritten across 1.21.2→1.21.6** (Blaze3D / RenderPipeline rework). Pre-1.21 rendering guidance is obsolete.
- **`WorldRenderEvents` (fabric-rendering-v1) works in 1.21.8 but is REMOVED in 1.21.9** (replaced by `LevelRenderEvents`). → **Pin this mod to 1.21.8**; moving past it needs a rendering-layer rewrite. Do NOT follow the live Fabric docs site (it documents the post-1.21.9 API).
- The mithril-locator sibling mod already uses the **correct 1.21.8 pattern** (custom `RenderPipeline` with `withDepthTestFunction(NO_DEPTH_TEST)` + a `RenderLayer` + `WorldRenderEvents.AFTER_TRANSLUCENT`) — reuse it verbatim for see-through outlines.

## See-through outlines (Stone-sense, Keen-sight, friend/foe) — PROVEN, cheap
- Depth test now lives on `com.mojang.blaze3d…DepthTestFunction.NO_DEPTH_TEST` (verify package: `blaze3d.pipeline` vs `blaze3d.platform`), set via `RenderPipeline.Builder.withDepthTestFunction(...)`. Register the pipeline + `RenderLayer.of(name, size, pipeline, MultiPhaseParameters)` **once at init** (of() is private → access-widener/wrapper).
- For flat colored line/box outlines, **reuse a vanilla `ShaderProgramKeys` line/debug program** — no custom GLSL needed.
- Draw per-entity boxes into **one shared `VertexConsumerProvider.Immediate` per layer** (one draw per layer, not per entity). Friend/foe tint = per-faction color (read base-mod diplomacy).
- **Do NOT use vanilla Glowing** (`OutlineVertexConsumerProvider` + `entity_outline` post-pass) — heavyweight and **Iris-fragile** (see below).
- **Manual frustum + distance culling required** — no-depth geometry bypasses occlusion, so cost doesn't drop behind terrain.

## Screen tints / vignette / desaturation (Orc daylight, Hobbit/Fear) — prefer CHEAP
- **Recommended:** full-screen quad in a HUD element — `DrawContext.fill(...)` (flat tint) or `fillGradient(...)` (vignette), `RenderSystem.enableBlend()`. No mixin, no extra pass, trivially toggled, **Iris-safe**.
- Real **post-process** (`assets/<ns>/post_effect/*.json`, new 1.21.5+ format) is the "enhanced" path: `GameRenderer.setPostProcessor(Identifier)` is **private → needs an accessor/invoker mixin**; `clearPostProcessor()` is public. Vanilla post effects are only: blur, creeper, spider, invert, entity_outline, transparency, end_of_frame (no nausea/darkness/night_vision shaders — those are in-code/overlays). **Iris likely supersedes custom post passes — treat as unverified/degrade-gracefully.**

## Dark-vision / brightening (Dwarf dark-sight, Elf starlight) — cheap, not a shader
- **Night vision = a lightmap brightness override** (class `LightmapTextureManager` / maybe `LightTexture` — verify), not a shader. Hook the lightmap path (mixin) or use `StatusEffects.NIGHT_VISION`.
- **Gamma** is `GameOptions.getGamma()` (`SimpleOption<Double>`); a "starlit brightening" skill can raise it (mixin past the cap like Gamma Utils).

## HUD overlays — use `HudElementRegistry` (NOT deprecated `HudRenderCallback`)
- `addLast(Identifier, HudElement)` draws on top of everything (tints/vignettes). `attachElementBefore/After(anchor,…)`. Anchors in `VanillaHudElements` (only `CHAT` confirmed — read the class). `HudElement` = `(DrawContext, DeltaTracker)`. 1.21.8 HUD transforms are 2D-only (`pose()` dropped z). `DrawContext.drawTexture/drawGuiTexture` now take a `RenderPipeline` first arg.

## Fog & view distance
- Fog reworked into a `FogModifier` hierarchy (`StandardFogModifier`, etc.); **no Fabric fog hook** → mixin (ref: IMB11/Fog). "Extended clarity" = push fog start/end farther (client-only illusion over already-loaded chunks).
- **View/sim distance is server-limited** — a vision skill **cannot** reveal terrain the server never sent. Only the fog-pushback illusion. Flag in design: no true "see further" without server cooperation.

## Iris / Sodium compatibility (the pack ships both)
- **Do NOT bundle Indium** (obsolete since Sodium 0.6 — Sodium absorbed the Rendering API).
- **Entity outline/glow breaks under Iris** (silently degraded on 1.21.8; visibly broken by 1.21.10). Custom no-depth-test layers also have Iris friction (draw-order/depth issues). → **Detect Iris and degrade gracefully** (disable/reroute outline & post-process visions when a shaderpack is active).
- **HUD overlays are Iris-safe** (GUI pass runs after the shader pipeline). → another reason to prefer HUD-quad tints.
- Always **restore GL blend/depth/cull state after each custom pass** (state-leak is a common failure).

## Design implication
Ship the Vision system on the **shader-independent trio**: see-through outlines (proven), HUD-overlay tints/vignettes (Iris-safe), and lightmap/gamma brightness. This covers ~80% of the wow-factor with the least risk. Treat post-process color-grading (wraith-world desaturation) as an optional enhanced path that detects Iris and degrades. Pin to MC 1.21.8.

## Must-verify-against-decompiled-1.21.8 list
`DepthTestFunction` package + constants · night-vision lightmap class name · `FogModifier` hierarchy (sourced from 1.21.7) · team glow-color accessor · full `VanillaHudElements` constants · `GameRenderer` post-processor method signatures.
