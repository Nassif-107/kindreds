# Elf — Skill-Tree Redesign (discipline by discipline, to perfection)

**Process:** one discipline at a time. Research → design (this doc) → your review → implement → playtest → next.
We can keep good ideas from the current tree, invent brand-new mechanics, and swap freely.

**Elf identity (overall):** the Firstborn — deathless, keen of eye and ear, swift and graceful, deep in
lore and song, at home under the stars and in the deep woods, resilient to the Shadow but ill-at-ease
in lightless places. Disciplines they train: **archery, beast_lore, lore, song, survival.**

---

## DISCIPLINE 1 — ARCHERY  *(in design)*

### Lore foundation (researched)
- **Legolas**, greatest archer of the Third Age: unearthly **eyesight** (counts riders leagues off,
  sees by starlight), looses **in the dark**, and shot a **fell-beast out of the night sky** at
  extreme range — the shot that arguably saved the Ring.
- **Bow of the Galadhrim** (Galadriel's gift): mallorn wood, **elf-hair string**, longer & stouter than
  Mirkwood bows → **more power, greater range**.
- **Arrows of Lórien**: ash shafts, gold **mallorn-leaf** tips, **spiral fletching** → accurate to a
  quarter-mile; war-arrows sometimes **poisoned or barbed**.
- **Silvan Shadow**: Wood-elves move unseen and loose from **concealment**; peerless woodcraft.
- Elven archery is a **forest-realm** art (Lórien, Mirkwood) — not the Noldor's sword-work.

### Design pillars (what Elf archery should *feel* like)
1. **Preternatural aim** — you hit what a mortal couldn't: curving true-flight, no drop at range, crits.
2. **Swift and endless** — draw faster, loose in a blur, rain arrows (volley).
3. **The great bow** — farther, harder, arrows that punch *through* ranks and slay great foes.
4. **The fletcher's craft** — special arrows: venom, starlight, barbed bleed.
5. **The Silvan shadow** — deadlier when you strike unseen; a hunter of the wood.

### Branch structure
A shared **trunk** (2–3 core nodes), then **three specialization branches** that fan out and deepen to
tier 7, plus **exclusive spec tips** and a **grand capstone**. (~18–22 nodes for this discipline alone.)

```
                 [Wood-elf's Aim]  (T1 trunk)
                        |
                 [Eye of the Eldar]  (T2 trunk: keen sight)
                 /        |         \
     KEEN-EYED       SWIFT BOW       THE LONG BOW
    (precision)      (speed)       (power + arrow-craft)
        |               |                |
       ...             ...              ...
        \               |               /
              [Bow of the Galadhrim]  (T7 grand capstone)
```

### Node-by-node design (proposed)

**Trunk**
- **T1 Wood-elf's Aim** — `+15% bow damage` (attribute path via arrow-damage perk, see mechanics). The
  price of entry; every Elf archer starts here.
- **T2 Eye of the Eldar** — unlock **keen_sight** vision (spot foes) **+ `+8 blocks bow range`** (no
  arrow-drop / higher velocity). The famous Elven eyesight.

**Branch A — KEEN-EYED (precision / true aim)**
- **T3 Unerring Aim** — `true_flight` (arrows curve toward a foe in your sights). *[have this]*
- **T3 Marksman's Poise** — `+full-draw crit`: a fully-drawn shot always crits (`arrow_crit` perk).
- **T4 Star-sighted** — keen_sight radius up + **see foes' weak points**: `+25% bow damage vs the
  target you're looking at` (or simpler: crit damage up).
- **T5 spec tip → Deadeye of Mirkwood** — `arrow_crit` + big range; every shot that hits at >20 blocks
  deals bonus damage (`long_shot`).  *(exclusive with Swift Bow's tip)*
- **T6 Shot in the Dark** — Legolas' fell-beast shot: **massive bonus damage vs flying/large foes**
  (`arrow_slaying` foe=`dragon`+`flying`) and true-flight range up.

**Branch B — SWIFT BOW (speed / volley)**
- **T3 Swift Nock** — `swift_draw 1` (draw ~1.5× faster) — *generalize the current Elf-innate bow speed
  into a perk so it's earned & stackable.*
- **T4 Blur of Arrows** — `swift_draw 2` (draw ~2×) + attack_speed for melee fallback.
- **T5 Galadhrim Volley (active)** — fan of 5 arrows. *[have this handler]*
- **T5 spec tip → Wind-runner** — chance to **not consume the arrow** (`arrow_thrift`) + move speed
  while drawing. *(exclusive with Keen-Eyed's tip)*
- **T6 Storm of the Golden Wood** — **twin-shot**: your normal bow shots fire **2 arrows**
  (`multishot` perk). The signature "rain of arrows."

**Branch C — THE LONG BOW (power + arrow-craft)**
- **T3 Great Bow** — `+velocity` + `+bow damage`, arrows **pierce** 1 target (`arrow_pierce`).
- **T3 Fletcher's Craft** — choose an arrow-craft:
  - **Venom-tipped** — bow hits apply **Poison** (`arrow_effect` = poison).
  - **Barbed** — bow hits apply a short **bleed/Weakness** + heal-block.
  *(a mini exclusive pair — pick your arrow)*
- **T4 Bane of the Yrch** — `arrow_slaying` foe=orc (Elf hatred of orcs) — big bonus vs orc-kin.
- **T5 Piercing Shaft** — pierce 3 targets + more damage; arrows skewer a whole file of orcs.
- **T6 Arrow of the Eldar (active)** — one heavy, piercing, critical slaying shot. *[have this handler]*

**Grand capstone**
- **T7 Bow of the Galadhrim** — the ultimate: `+bow damage`, `+range`, `multishot`, `true_flight`, and a
  starlight-arrow effect (glowing/Marks the target). Requires reaching a tip in **two** of the three
  branches — a true master-archer only.

### Mechanics to BUILD (new engine work for this discipline)
Current perks we already have and can reuse: `arrow_slaying` (bane on bow hits), `true_flight`
(aim-assist), the `galadhrim_volley` / `arrow_of_the_eldar` active handlers, `swift_draw` (Elf-innate
now — generalize to a perk with a magnitude).

New, small, mixin-in-the-projectile mechanics (all hang off the existing `PersistentProjectileEntity`
hooks / a new arrow-hit hook — low risk, one place):
1. **`arrow_damage`** perk — flat/percent bonus to bow-arrow damage (so "+% bow damage" nodes are real,
   not the useless melee attack_damage). Applied when the arrow is spawned or on hit.
2. **`swift_draw`** perk (generalize `BowDrawSpeed`) — param `ticks` extra draw speed; earned, stackable.
3. **`arrow_crit`** perk — a full-draw arrow always crits (+ optional crit-damage bump).
4. **`long_shot`** perk — bonus damage scaling with flight distance (rewards the quarter-mile shot).
5. **`arrow_pierce`** perk — arrow passes through N targets (set pierce level on spawn).
6. **`arrow_effect`** perk — apply a status effect to a struck foe on **bow** hit (poison/weakness/glow).
7. **`multishot`** perk — normal bow release fires +N extra arrows in a small spread.
8. **`arrow_velocity` / range** — nudge arrow speed / disable gravity briefly for flat, fast shots.

All of these are read from `PerkService` at arrow-spawn / arrow-hit — one mixin touchpoint, gated so
non-Elves pay nothing.

### Open questions for you
- **Scope of new mechanics:** happy to build all 8, or want to trim to the highest-impact (I'd say
  `arrow_damage`, `swift_draw`, `arrow_effect`, `multishot`, `arrow_pierce` are the "feel" ones)?
- **Specialization exclusivity:** should the three branches be freely maxable (long journey, pick all),
  or force a real *choose-your-archer* identity (Deadeye vs Volley vs Longbow)?
- **Silvan Shadow** (shoot-from-stealth bonus): put it here in archery, or save it for **survival**?
- **Numbers:** how punchy? e.g. is "+15% bow damage" per node right, or bigger?

Once you're happy with this, I build the mechanics + author the ~20 nodes for Archery only, we test it,
then move to the next Elf discipline.
