# The Fellowship & the Host — Race Synergy (Design)

Two mirror systems, straight from the theme of the books: the **Free Peoples grow strong through
fellowship** (unity of *diverse* kinds), while **the servants of the Shadow grow strong through
numbers** but are undone by their own discord. Both must be *noticeable, interesting, and lore-true*.

Config: `enableFellowship` (+ radius, strengths). Server-authoritative; effects show as normal
status icons so players always see what's active. A small HUD readout names the current tier and
active gifts.

---

## A. The Free Peoples — "The Fellowship"  (Elf, Dwarf, Man, Hobbit)

A fellowship forms among Free-People players within ~24 blocks of each other. **Both layers stack.**

### Layer 1 — Unity of the Company (scales with DIVERSITY, not headcount)
The more *distinct* Free races stand together, the stronger the shared courage against the Dark:
- **2 distinct races — "A Growing Company":** small Resistance; immune to *Dread of the Sun*/fear-type dread.
- **3 — "A True Fellowship":** the above + a courage boost (Absorption or +hearts).
- **4 (all Free kinds) — "The Nine Walkers":** full — strong Resistance, corruption immunity, notable
  Absorption. The company that cannot be broken.

Two Elves standing together = still just "one kind." It's *variety* that matters — exactly the point
of the Fellowship.

### Layer 2 — Each race lends its gift (an aura per race PRESENT)
Every Free race in the band radiates a share of its nature to all allies nearby:
- **Elf present → "Grace of the Eldar":** allies heal faster (mild Regeneration); Elven light keeps the
  worst of the dark at bay.
- **Dwarf present → "Steadfastness of Khazâd":** allies gain Shadow-resistance (ills fade faster) and
  a little toughness (Resistance).
- **Hobbit present → "Heart of the Shire":** allies gain Luck and resistance to corruption (the humble
  heart the Ring cannot grip).
- **Man present → "Valour of Men":** allies gain a combat lift (Strength) — Men rally the company.

So a mixed band literally *combines* strengths: everyone heals like an Elf, endures like a Dwarf, is
lucky like a Hobbit, and strikes like a Man. That is why you want a diverse party.

### Named bonds (one-time discovery + a keepsake bonus/title)
- **Elf + Dwarf together → "Legolas & Gimli":** a bonus for burying the ancient hatred — the standout
  easter egg, with a toast the first time it forms.
- **Man + Hobbit → "Strider's Charges."**
- **All four at once → "The Nine Walkers"** (ties to Layer-1 top tier).

---

## B. The Shadow — "The War-host"  (Orc, Goblin, Snaga, Uruk)

Orcs are not noble; they take courage from the pack, not from friendship.

### Layer 1 — Strength in numbers (scales with HEADCOUNT, any orc-kin)
More orc-kin nearby → bolder and stronger: a swarm buff (Strength/Speed) growing with the count
(e.g. 2 / 4 / 6+ tiers: "A Pack" → "A War-band" → "A Host of Mordor").

### Layer 2 — Discord (the price of the horde)
Orcs squabble and turn on each other (Cirith Ungol). A large host risks **infighting**: a periodic
small penalty (unease/weakness flickers) that grows with size — powerful but unstable.

### Layer 3 — The Uruk captain (discipline)
An **Uruk** present *stabilizes* the host — Uruk-hai were the disciplined captains who kept the lesser
orcs in line. An Uruk in the band **reduces the infighting penalty and strengthens the swarm.** A
war-host led by Uruks is the real threat; a leaderless mob of Snaga tears itself apart.

---

## Mixing
Free + Shadow players near each other → **no synergy** (they are enemies). Optionally a faint mutual
"unease," to be decided.

## Noticeability (make it FELT)
- **Toast on joining/leaving** a fellowship/host, naming the tier and the gifts gained ("You feel the
  Grace of the Eldar…").
- **Status icons** for every lent gift + the unity/swarm buff — always visible in the HUD.
- **HUD readout** (small, corner): current band name/tier + active gift list.
- **Optional aura VFX** (Chunk-2 visual system): a faint thread of light between fellowship members;
  a low red glow among a war-host. Fold into the Chunk-2 effects overhaul.

## Technical sketch
- New `FellowshipService` (server tick ~1–2s): for each player, gather nearby players in radius,
  read their races (`RaceAccess`), classify Free vs Shadow, compute composition, apply the matching
  status effects (visible) and any attribute tweaks. Reuse the existing effect application.
- Client HUD element (like the Mithril locator HUD) for the band readout; aura VFX deferred to the
  Chunk-2 render system.
- Config: `enableFellowship`, `fellowshipRadius`, per-gift strengths.
- Ties into Chunk-2 trees: "Elf-friend" / cross-training nodes deepen what you draw from allies.

## Build order
Ship after Chunk 1 is playtested; can land alongside or just before Chunk 2. Layer 2 (lent gifts) is
the heart — build the Free "Fellowship" first, then the "War-host," then bonds + HUD + VFX.
