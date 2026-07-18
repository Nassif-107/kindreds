# Kindreds — Playtest Guide (step by step)

## 0. Setup (do this first, every time)
1. **Fully close Minecraft**, then relaunch (the jar can't be swapped while the game runs).
2. Make a test world with **cheats ON** (needed for the `/kindreds` admin commands).
   - Existing world: pause → **Open to LAN** → *Allow Cheats: ON* → Start.
3. Pick a race in the base mod (the **Player's Book** on first join). To retest another race, use the Player's Book / base-mod race change again.
4. Useful keys (rebind under Options → Controls → "Kindreds"): **K** = skill tree, **J** = Codex, **V** = cycle vision, **G** = use active ability.

**Core commands**
| Command | What it does |
|---|---|
| `/kindreds inspect` | Prints your race, discipline levels/points, birth boons & banes |
| `/kindreds grantxp <discipline> <amount>` | Bank points fast (e.g. `/kindreds grantxp combat 8000`) |
| `/kindreds respec` | Refund all unlocked nodes |
| `/kindreds codex` | Get a Codex book |
| `/kindreds config` | List all server config values |
| `/kindreds config <key> <value>` | Set one (e.g. `/kindreds config enableBirthTraits false`) |

Disciplines you can grant xp to: `combat, archery, mining, stealth, smithing, survival, lore, song, beast_lore, runecraft, leadership, shadow`.

---

## 1. Codex & UI (any race)
- [ ] You **start with a Kindred Codex** book in your inventory. **Right-click it** → the parchment Codex opens. Turn pages with **◄ Prev / Next ►** through all 8 peoples. *Expected:* a book-styled screen, boons in green ✦, banes in red ✖, guidance at the top.
- [ ] Press **J** anywhere in-world → same Codex opens.
- [ ] Start a NEW character → on the base race-selection screen, a **"⚔ Kindred Traits"** button appears top-right → opens the Codex almanac.
- [ ] Press **K** → skill tree opens in **Map view** (all disciplines as coloured regions). **Drag** to pan, **scroll** to zoom out/in. Click **◇ Whole map / ▤ One branch** (top-right) to toggle. Left rail shows **only your race's disciplines**. *Expected:* readable, no purple/missing-texture, no clipping.

---

## 2. Birth traits per race
For each race: `/kindreds inspect` first (it lists the boons/banes), then verify a few in the world. Effects that are potion-type show as **icons** in your inventory/HUD.

### 🌟 Elf
- [ ] +1 heart (11 hearts total), **Night Vision** always on.
- [ ] Attacks are visibly **faster** (+15% atk speed); mobs notice you **later** (stealth).
- [ ] At **night under open sky** → **Regeneration + Speed** icons appear (Starlit Grace). Go under a roof/underground or wait for day → they vanish.
- [ ] Never starves (no hunger drain); eat nothing for a while — hunger bar stays. **Nausea/Hunger** effects don't stick; standing in powder snow doesn't freeze you.
- [ ] Stand in an **unlit cave with no sky above** → **Weakness** (Unease in the deep dark). Light it up or leave → gone.
- [ ] Get a harmful effect (e.g. splash a Poison potion) → it **wears off ~3× faster** than normal.

### 🔥 Dwarf
- [ ] +3 hearts (13 total). Stand in **fire/lava** → you take damage but **stop burning much faster** (not immune).
- [ ] Go **underground (no sky above)** → **Haste + Resistance** icons. Surface → gone.
- [ ] Mine stone — noticeably faster. Movement is **~10% slower** than a Man.
- [ ] Poison/curse effects wear off ~3× faster; hunger drains slower than normal.

### 🍃 Hobbit
- [ ] Mobs notice you **very late** (sneak up on a mob and it reacts slowly). **+3 luck** (better fishing/loot).
- [ ] While **food is 18+** you slowly **regenerate** health.
- [ ] −1 heart (9 total), weak in melee (−20% dmg). Harmful effects wear off fast (Ring-resistance).

### ⚔️ Men (Human)
- [ ] +2 hearts, a bit stronger hit, slightly longer **reach** (hit/place from a touch farther).
- [ ] **Run onto ice** → you do **NOT slide** (Wayfarer). As any other race you DO slide — compare.
- [ ] Drop to **≤35% health** in a fight → **Strength + Resistance** icons flare (Last Stand). Heal up → gone.
- [ ] No innate resistances — fire/dark/effects all hit in full.

### 🌑 Orc / 🪓 Goblin / 🗡️ Snaga
- [ ] **Night Vision** always on.
- [ ] At **night or underground** → **Strength** icon (Children of the Dark).
- [ ] Eat **rotten flesh** → **no Hunger** effect (Iron guts).
- [ ] Step into **open daylight** → **Weakness (+Slowness for Orc/Snaga)** (Dread of the Sun). Shade/night → gone.
- [ ] Goblin: high climbing (jump up walls better); Snaga: very fast + very stealthy; all frail.

### 🛡️ Uruk-hai
- [ ] +3 hearts, hardest-hitting (+20% dmg), but **slower attack speed** (−15%).
- [ ] Step into **open daylight** → **Strength** icon (Sun-defiant — the opposite of lesser orcs).
- [ ] Iron guts (rotten flesh safe); endures hunger; no Dread of the Sun.

---

## 3. XP, trees & choices
- [ ] Do race activities and watch xp rise: mine (mining), hit mobs (combat), shoot (archery), sneak (stealth), use an **anvil/enchanting table/lectern** (runecraft), **note block/jukebox** (song), handle/ride **animals** (beast-lore), fell a **champion** / stand with allies (leadership), slay a **villager** (shadow — orc-kin), craft (smithing), eat/new biome (survival). Check with `/kindreds inspect`.
- [ ] Bank points: `/kindreds grantxp <discipline> 8000`. Open **K**, click a node → detail panel shows effect/cost/requires → **Learn**. *Expected:* a chime + gold flash on the node.
- [ ] Unlock down a branch to a **specialization tip**, then look at its rival tip → it shows **LOCKED / closed** (you committed to one path). Clicking it says "a different path is closed."
- [ ] Push to a **Grand capstone** (needs nodes from more than one discipline — shown as "Requires …").
- [ ] `/kindreds respec` → all nodes refunded, effects removed.

---

## 4. Config (admin/server)
- [ ] `/kindreds config` lists everything.
- [ ] `/kindreds config enableBirthTraits false` → birth traits strip off within a couple seconds. Set back to `true` → they return.
- [ ] `/kindreds config xpRateGlobal 3.0` → xp gains triple. `/kindreds config deathPenalty HARDCORE` etc.

---

## What "good" looks like
Every effect should be **noticeable** and **fit the race** (a Dwarf feels tanky/slow, an Orc dominates at night and wilts by day, an Elf is fast/keen/fragile-in-the-dark). The UI should be **clear, readable, and never crash or show purple/missing textures**. Report anything that feels flat, wrong, or buggy.
