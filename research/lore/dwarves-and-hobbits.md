# Dwarves & Hobbits — Canon-to-Ability Design Doc

**Scope:** Durin's Folk / Longbeards (Erebor + Iron Hills) and the Shire, for the `middle-earth` Fabric 1.21.8 mining/survival RPG.
**Goal:** ground every proposed ability/debuff in a specific piece of Tolkien canon, then translate it into mechanics using the mod's existing attribute system (`movement_speed`, `max_health`, `attack_damage`, `entity_interaction_range`/`block_interaction_range` ["reach"], `sneaking_speed`, `fall_damage_multiplier`, `mining_efficiency`, `scale`, custom `climbing_strength`, `detection_range`, `delvers_fear_strength` [wired, unused — great hook for Dwarf content], `burning_time`, `powdered_snow_immunity`), vanilla status effects, and keybound active abilities.
**Baseline reminder** (from `middle-earth-factions-races-study.md`): Dwarf players already get scale 0.81, 22 HP, −10% move speed, 2.75 entity-reach, +0.2 mining_efficiency. Hobbit players already get scale 0.66 (smallest), 14 HP, −20% net attack damage, +45% sneak speed, −10% fall damage, detection_range 0.8. Everything below is designed as *additive skill-tree layers* on top of that baseline, not a replacement.

Citations are inline; full source list at the bottom.

---

## PART 1 — DWARVES (Durin's Folk)

### 1.1 Canon traits → abilities → tradeoffs

#### Trait: "Made of the stuff of the earth" — stone-hard endurance
> "Aulë made the dwarves strong to endure... they are stone-hard, stubborn, fast in friendship and in enmity, and they suffer toil and hunger and hurt of body more hardily than all other speaking peoples." — *The Silmarillion*, "Of Aulë and Yavanna" [1][2]

**Ability — "Stone-Kin" (passive):** flat `max_health` bonus beyond the racial 22, plus a small always-on `knockback_resistance` — dwarves are quite literally harder to move and harder to kill than their size suggests. Optionally scale a `hunger`-drain-rate reduction into this node ("suffer... hunger... more hardily").
**Tradeoff:** none inherent — this is a pure strength node; balance it by gating it deep in the tree (costs skill points that a Hobbit-equivalent tree spends on stealth).

#### Trait: Forge-culture — fire & heat familiarity
Dwarves live and work at the forge constantly (Erebor's furnaces, Thorin's company reforging weapons, the "Forges of Narvi" at Khazad-dûm) and their mountain-halls (Erebor "Lonely Mountain," Iron Hills, Ered Luin) are cold, high, northern places. Tolkien never states an explicit magical fire/cold immunity, but the *cultural and geographic* case is strong: Erebor is literally built around volcanic-adjacent heat vents used for smithwork, and Iron Hills dwarves are a mountain people used to sub-arctic cold. This is the one node where the doc leans on inference rather than direct quote — flagged honestly.

**Ability — "Forge-Born" (passive, tiered):** partial fire/lava damage reduction and reduced `burning_time` multiplier (mirrors the Uruk racial burning_time 0.7, but framed as a *learned* Dwarf skill rather than a race-baseline). Capstone tier ("Heart of the Mountain," see Part 3) approaches near-immunity + brief safe lava-wading.
**Ability — "Cold Ward" (passive):** immune to powdered-snow slowness/freezing tick damage (parallel to the Elf racial `powdered_snow_immunity`), no hunger-drain penalty while in snowy/mountain biomes — reflecting the Iron Hills homeland.
**Tradeoff:** neither node grants anything outdoors-offensive; they're pure survivability, so cost real skill points and sit mid-tree, not tier 1.

#### Trait: Unrivaled miners and smiths, drawn to the substances of Arda
> The Dwarves "are attracted to the substances of Arda" and were taught craft by Aulë; *The Hobbit* repeatedly frames Thorin's company as expert tunnelers and metalworkers, and Erebor/Moria's wealth (mithril chief among it) *is* Dwarf-made infrastructure. [2][3]

**Ability — "Sure Grip" / "Master of the Seam" (passive, tiered):** stacks onto the racial `mining_efficiency +0.2` (Haste-like faster block breaking on stone/ore/deepslate specifically, not all blocks — keeps it a *miner* buff, not a universal woodcutting buff).
**Ability — "Forgemaster" (passive):** smithing-table repairs cost less material; grants access to exclusive Dwarf-only smithing-template recipes (runic armor, reinforced pickaxes); armor/tools crafted or repaired by a Dwarf gain a small bonus durability or a free extra enchant slot equivalent.
**Tradeoff:** these nodes do nothing for combat or mobility — a Dwarf who dumps points here is a pure economic/crafting specialist and stays squarely a bad swimmer/slow walker (see 1.2) until they also invest in the Warrior sub-path.

#### Trait: Dark-sight underground / at home in the deep
Not a single crisp Tolkien quote states "dwarves see in the dark" as a supernatural sense — this is closer to widely-adopted fantasy shorthand (D&D darkvision, LOTRO) than hard Tolkien canon. What *is* textually supported: dwarves are the one free people whose entire civilization (Khazad-dûm/Moria, Erebor, Nogrod, Belegost) is built *inside* mountains, they navigate those tunnels confidently in near-blackness (Gimli in Moria recalling the halls from memory and lineage-lore), and Gandalf notes the peril of the deep specifically *because* Dwarves, of all peoples, still fear what lives below their own halls — c.f. "the Dwarves tell no tale; but even as mithril was the foundation of their wealth, so also it was their destruction: they delved too greedily and too deep, and disturbed that from which they fled, Durin's Bane" (*Fellowship of the Ring*, "A Journey in the Dark") [4][5]. This passage is the canonical root of the mod's own unused `delvers_fear_strength` attribute — it's begging to be a Dwarf mechanic (see below).

**Ability — "Deep-Delver's Eyes" (passive, tiered by depth):** grants Night Vision and negates Mining Fatigue while below a Y-level threshold (e.g. Y<40, tier up at Y<0); the deeper you go, the stronger/wider the effect — gamifies "at home in the deep" directly.
**Ability — "Old Halls, Old Blood" (passive):** hooks the dormant `delvers_fear_strength` attribute as a *reduction* — Dwarves resist whatever "fear of the dark/deep" debuff the base game eventually uses this for far better than other races, framed as dwarves being uniquely acclimated to what lurks below (while still, per the Moria quote, not totally immune — a Dwarf-flavored nod to "delved too greedily and too deep").
**Tradeoff:** the depth-gated Night Vision is worthless on the surface — a Dwarf who never mines gets nothing from this branch, keeping it an opportunity cost rather than a free buff.

#### Trait: Resistance to the domination of the Rings of Power
> Sauron gave the Dwarf-lords Seven Rings; unlike the Nine (Men, who became Ringwraiths), the Dwarves "proved tough and hard to tame... it is said that the only power over them that the Rings wielded was to inflame their hearts with a greed of gold and precious things" — Sauron could not make them "invisible" or enslave their wills, only stoke their avarice (*The Silmarillion*, "Of the Rings of Power"; *LotR* Appendix A). [6][7]

**Ability — "Unbroken Will" (passive):** strong resistance to mind-control/charm/fear/wither-style debuff effects and any modded "domination" mechanic — mechanically: shortened duration or flat resistance chance vs. Nausea/Blindness/Darkness/Wither-adjacent status effects imposed by hostile casters. This is the Dwarf mirror of the Hobbit's Ring-purity resistance (Part 2) — both races are "hard to enslave," for opposite in-lore reasons (Dwarves by sheer stubborn will; Hobbits by lacking ambition for the Ring to grip).
**Tradeoff (the actual coupled cost, per canon):** the same Rings "inflamed greed" rather than granting immunity outright — so this node is paired with an optional/parallel **"Dragon-sickness" debuff mechanic** (see 1.2) representing that the resistance is not free; Dwarves are *tougher to dominate* but *easier to corrupt through treasure*.

#### Trait: Stubbornness, grudges, secrecy (Khuzdul, hidden doors, true-names)
> Khuzdul was never taught to outsiders and rarely written; even the Doors of Durin bear a Sindarin (not Khuzdul) inscription because, as Gandalf says, "I shall not have to call on Gimli for words of the secret dwarf-tongue that they teach to none" (*Fellowship of the Ring*, "A Journey in the Dark"). Dwarves also conceal their true (Khuzdul) names even from close allies, using outward "Mannish" names like Thorin, Glóin, Gimli publicly. [8][9]

**Ability — "Word of Khuzdul" (passive):** bonus to detecting/opening hidden doors, trapped chests, and vault-style structures; reduced cost or guaranteed success on structure-lock puzzles if the mod has any; flavor: only a Dwarf player can "read" certain Dwarf-only lore/quest text or unlock Dwarf-faction vault rooms.
**Ability — "Long Memory" (passive):** a grudge-mechanic — after being damaged by a specific mob type, gain a small stacking `attack_damage`/`attack_speed` bonus against that mob type for a limited window ("dwarves never forget a wrong"). Caps out to avoid runaway snowballing.
**Tradeoff:** "Long Memory" is reactive only — it does nothing on the first hit taken, so aggressive burst-damage enemies (ambush mobs) still punish a Dwarf who over-relies on it. Secrecy nodes give zero combat value.

#### Trait: Wrath and stamina in pitched battle, holding the line
The War of the Dwarves and Orcs culminates at the Battle of Azanulbizar beneath Moria's East-gate — one of the bloodiest engagements in the Third Age, "nearly half of those" involved on both sides falling, ending in grim Dwarf victory when Iron Hills reinforcements arrived, and Dáin Ironfoot slaying Azog before the Gate (*LotR* Appendix A, "Durin's Folk"). [10] Dwarves at the Battle of the Five Armies and the Battle of Bywater alike are consistently written as slow to anger but terrifying once committed, and famously hold formation rather than break and run.

**Ability — "Shield-Wall of Khazad-dûm" (active or hybrid passive-trigger):** while stationary/not sprinting for a short duration, gain stacking `knockback_resistance` and flat damage reduction ("holding the line"); breaks on moving. Rewards deliberate, planted-feet tanking over kiting.
**Ability — "Battle-Fury" (active, keybind, cooldown):** a berserker cry usable below a health threshold — short Strength + Resistance + immunity to knockback, echoing the grim, committed last stands of Dwarf-lore (Azanulbizar, the defense of the Chamber of Mazarbul).
**Tradeoff:** both nodes are *defensive-reactive*, not mobile-aggressive — a Dwarf using them is rooted in place or already low on health; kiting/hit-and-run playstyles get nothing from this sub-path.

### 1.2 Dwarf tradeoffs / debuffs (lore-driven, meaningful but fair)

| Debuff | Canon basis | Mechanic |
|---|---|---|
| **Poor swimmer** | Dwarves are never depicted swimming or sailing by choice — a stone-hard, dense people are the one Free People with zero seafaring culture (contrast Elves' Grey Havens, Men's Dol Amroth/Gondor navy). | Reduced swim speed, faster stamina/air loss underwater, sinks rather than floats (already thematically supported by low `scale` 0.81 but dense build) — a genuine "avoid deep water" pressure in a mining game full of flooded caves. |
| **Not riders** | "The Dwarves didn't have relationships with animals... wouldn't mount a horse willingly" — Dwarves fight and travel on foot (Gimli famously rides double with Legolas, awkwardly, in *LotR*); Appendix A / general characterization. [11] | No bonus (or a small penalty) to mounted movement speed / horse levels up slower under a Dwarf rider; encourages the race to lean into its own foot-speed and mining-shortcut abilities instead of horses. |
| **Gold-lust / "dragon-sickness"** | The Seven Rings "inflamed greed" [6][7]; Thorin's fall into dragon-sickness in *The Hobbit* is the textbook case — Bilbo himself "did not reckon with the power that gold has upon which a dragon has long brooded, nor with dwarvish hearts" [12]. | Optional risk/reward mechanic: carrying a large quantity of raw gold/gems in inventory past a threshold applies a mild "Dragon-sickness" debuff (Nausea or Slowness, or a hostile-mob-attraction effect) until the hoard is banked/spent/donated — a Dwarf is mechanically punished for pure hoarding, encouraging them to smith/spend rather than sit on wealth. |
| **Gruff / distrustful of outsiders** | Thorin's company's initial distrust of Bilbo and of Elves generally; Appendix A frames Dwarves as insular and slow to trust non-Dwarves. | Flavor-tier: worse initial standing/prices with non-Free-Peoples-aligned or Elf-flavored NPCs (Lothlórien/Woodland Realm) if/when the mod's dormant FRIENDLY/ALLY diplomacy tiers ever get consumed; purely a roleplay/economy hook, not combat-relevant. |
| **Slower, worse on open ground** | Already baseline (−10% move speed); short legs, stone-heavy build. | Skill tree does *not* fix this outright — instead channels Dwarf mobility gains specifically underground/into stone (mining-speed shortcuts, tunneling) rather than open-field sprint buffs, preserving the "surface is not a Dwarf's home turf" identity. |

---

## PART 2 — HOBBITS (the Shire)

### 2.1 Canon traits → abilities → tradeoffs

#### Trait: Extraordinary, near-magical stealth
> "This is (or was) one of their vices, for they possessed from the first the art of disappearing swiftly and silently, when large folk whom they do not wish to meet come blundering by; and this art they have developed until to Men it may seem magical." — *The Fellowship of the Ring*, Prologue, "Concerning Hobbits" [13]

**Ability — "Unseen Folk" (passive, tiered):** stacks additional `detection_range` reduction on top of the racial 0.8 baseline, plus a genuine sneak-noise radius reduction (footsteps, block-place/break sound) — the racial detection_range stat already exists in code but per the study its AI consumer wasn't confirmed wired; this branch is the natural place to *finish* that feature.
**Ability — "Vanish" (active, keybind, short cooldown):** a burst effect on activation — brief near-total invisibility + silenced footsteps, modeling the "disappearing swiftly" line almost literally. Short duration, meaningful cooldown so it's an escape/opening tool, not permanent stealth.
**Tradeoff:** none of this helps once combat is joined openly — a spotted Hobbit is still a 14-HP, −20%-damage combatant (see 2.2); the whole branch is about *not being seen in the first place*.

#### Trait: Unobtrusive, easily overlooked, quick to lose pursuers
The Prologue and *The Hobbit* repeatedly frame hobbits as beneath notice to "big folk" — armies, orcs, and even wary travelers routinely fail to register hobbits as a threat or even as present, which is exactly why the Ring's quest is entrusted to one. [13][14]

**Ability — "Unassuming" (passive):** hostile mobs that lose direct line-of-sight on a Hobbit "forget"/de-aggro faster than they would for other races — a mechanical rendering of "large folk... come blundering by" without noticing.
**Tradeoff:** purely a disengage tool — provides no in-combat damage or defense boost, so a cornered Hobbit with no escape route gets nothing from it.

#### Trait: Deadly accuracy with thrown stones
While there's no single tidy "hobbits are ranged marksmen" quote, the Shire's own martial history is built on exactly this idea: Bandobras "Bullroarer" Took winning the Battle of Greenfields (T.A. 2747) by charging the goblin-king Golfimbul and literally knocking his head off with a club [15], and the Battle of Bywater/"Scouring of the Shire" being won by ordinary Hobbits with bows, pitchforks, and stones against Sharkey's ruffians — the Shire's folk militia culture, not heavy-armored knighthood, is the throughline. Combined with the hobbit motif of childhood and adult "stone-and-clod" mischief-throwing referenced across the Shire chapters, this generalizes cleanly into a signature ranged mechanic.

**Ability — "Stone-cast" (active, keybind):** throw a stone/pebble for a ranged stun/knockback or bonus "sneak-throw" damage if the target is unaware — the Hobbit's answer to not being able to trade blows in melee.
**Ability — "Green Fields Reckoning" (passive, unlocked with Stone-cast mastery):** bonus damage/accuracy specifically vs. goblin/orc-type targets, a direct callback to Bullroarer's feat.
**Tradeoff:** thrown-stone damage is deliberately modest per-hit (stones are not swords) — it's a control/chip tool, not a DPS replacement for the Hobbit's weak melee.

#### Trait: Remarkable resilience, resistance to fear/despair, and uncanny resistance to the Ring's corruption
Frodo, Sam, and Bilbo are singled out repeatedly as uniquely resistant Ring-bearers — Sauron "saw hobbits as lesser beings" and never targeted them for corruption, and among the three, "Sam's purity of heart left no footholds for the Ring to latch onto" [16][17]. This purity-of-heart, low-ambition psychology is Tolkien's explicit mechanism (not a magical hobbit trait so much as a *psychological* one: no lust for domination = nothing for the Ring to grip).

**Ability — "Simple Heart" (passive):** strong resistance to curse/corruption/withering-style debuff effects and to any modded "domination"/possession mechanic — the Hobbit mirror of the Dwarf's "Unbroken Will," but flavored as innate humility rather than stubbornness.
**Ability — "Stouthearted" (passive):** resistance to Fear-type effects and to a "shaken"/accuracy-debuff mechanic from encountering huge/monstrous mobs (see 2.2 tradeoff below) — modeling that ordinary hobbit militia stood and fought at Bywater despite being drastically outsized.
**Tradeoff:** this is resistance, not immunity — deliberately never total, echoing that even Frodo was *not* immune (he succumbed at Sammath Naur) — so it should be tuned as a percentage/duration reduction, never a flat "can't be affected."

#### Trait: Love of food and comfort — six meals a day, hardy recovery
> Hobbits eat six meals daily where they can get them: breakfast, second breakfast, elevenses, luncheon, afternoon tea, dinner (Prologue, "Concerning Hobbits"); Pippin's insistence on second breakfast even while fleeing danger in *Fellowship* is played for both comedy and characterization — food as a grounding, resilience-giving ritual even amid peril. [18]

**Ability — "Second Breakfast" (passive):** eating any food item grants a short bonus buff on top of normal hunger/saturation restoration (e.g. brief Regeneration or Speed tick) — and this can be *retriggered* more often than vanilla's food-buff cooldown allows, modeling six-meals-a-day.
**Ability — "Packed Lunch" (passive):** slower hunger drain overall, and cooked/prepared meals restore extra saturation specifically (rewarding a Hobbit who actually cooks, Shire-style, over one who eats raw rations).
**Tradeoff:** none directly — but this branch is explicitly *not* a combat branch, so points spent here are points not spent on Stone-cast/Vanish; a "comfort-built" Hobbit plays more like a long-haul survival specialist than a burglar.

#### Trait: Tough bare feet, no boots needed
> "They seldom wore shoes, since their feet had tough leathery soles and were clad in a thick curling hair... much like the hair of their heads" (Prologue, "Concerning Hobbits"; near-identical phrasing in *The Hobbit* ch.1). [19]

**Ability — "Leathery Soles" (passive):** immune to minor ground-hazard damage/slowness that other races take barefoot-equivalent penalties from (soul sand slow, certain "stub your toe" hazard blocks a modpack might add), and a small innate fall-damage-adjacent buff stacking with the racial 0.9 `fall_damage_multiplier` — a Hobbit's feet are their built-in boots.
**Tradeoff:** flavor-locks the Hobbit out of getting *bonus* value from enchanted boots the way other races might (diminishing returns on boot-slot enchants), gently reinforcing "doesn't need footwear" without being punishing.

#### Trait: Small, quick, but weak in a stand-up fight
The entire Shire militia framing — pitchforks and stones beating trained ruffians at Bywater through numbers, home terrain, and surprise, never through individual martial superiority — plus the constant narrative emphasis that hobbits are non-warriors who succeed via cunning, luck, and heart rather than strength.

**Ability — "Quiet Killer" (active/passive hybrid):** bonus damage multiplier specifically on a sneak-attack (first hit while undetected) — rewards ambush-and-vanish play instead of toe-to-toe trading, consistent with the "small, weak in open combat" baseline.
**Tradeoff:** explicitly does *not* fix the racial −20% net attack_damage in a standing fight — it only pays off if the Hobbit plays around detection_range/Vanish, keeping straight melee builds unrewarding for this race by design.

### 2.2 Hobbit tradeoffs / debuffs (lore-driven, meaningful but fair)

| Debuff | Canon basis | Mechanic |
|---|---|---|
| **Weak in stand-up melee** | Already baseline (−20% net attack_damage, 7♥). Hobbits never field heavy infantry; even Merry and Pippin only become notably martial after outside influence (Rohan/Gondor service, ent-draught growth). | Skill tree deliberately offers no direct "fix" to base melee damage — all offense routes through Stone-cast/sneak-attack, preserving the identity. |
| **Can't wear heavy armor well** | Hobbits are never depicted in plate; Merry and Pippin's Rohirrim/Gondorian gear is explicitly ill-fitting and cut down for them. Small `scale` 0.66 makes heavy armor thematically and mechanically odd. | Heavy armor materials (diamond/netherite-tier) impose an extra `movement_speed`/`sneaking_speed` penalty on Hobbits, or simply grant reduced armor_toughness scaling from heavy materials — pushes Hobbits toward light/no armor + stealth instead of tanking in plate. |
| **Fear of "big folk" battles** | Frodo and company's visceral dread of Nazgûl, trolls, and Uruk-hai — scenes consistently frame hobbits as awed/frightened by anything huge, offset only by learned courage over the story. | A "Shaken" mechanic: proximity to Giant/Troll/Boss-scale hostile mobs briefly reduces accuracy or applies a mild debuff unless the "Stouthearted" node (2.1) is taken to blunt it — makes that node a real choice, not a freebie. |
| **Small carrying/reach** | Already baseline (2.5 entity-reach, smallest scale). | No compensation buff offered in melee-reach nodes — Hobbit reach gains, if any, come only through the Stone-cast ranged tool, not through melee-range items. |

---

## PART 3 — SKILL TREE BRANCH SKETCHES

Both trees follow a shared shape: **5 tiers**, prerequisite-gated top-to-bottom, ~4–8 nodes per tier, ending in 1–2 mutually-exclusive-or-both capstones. Passive/Active marked per node.

### 3.1 DWARF TREE — "The Path of Durin's Folk"

**Tier 1 — Stone-Kin (Foundation)**
| Node | Type | Effect | Prereq |
|---|---|---|---|
| Stone-Hard Body | Passive | +2 extra max_health, small flat knockback_resistance | — |
| Sure Grip | Passive | +mining_efficiency (stone/ore/deepslate only), stacks on racial bonus | — |
| Cold Ward | Passive | Immune to freezing/powder-snow slow; no hunger drain in snowy/mountain biomes | — |
| Forge-Born I | Passive | Tier-1 fire/lava damage reduction, reduced burning_time | — |

**Tier 2 — Delver**
| Node | Type | Effect | Prereq |
|---|---|---|---|
| Deep-Delver's Eyes | Passive | Night Vision + no Mining Fatigue below Y-threshold, strengthens deeper | Sure Grip |
| Old Halls, Old Blood | Passive | Strong resistance to delvers_fear_strength / "fear of the dark" debuffs | Cold Ward |
| Long Memory | Passive | Stacking damage buff vs. a mob-type that recently hit you | Stone-Hard Body |
| Word of Khuzdul | Passive | Bonus to finding/opening hidden doors, vaults, trapped chests | — |

**Tier 3 — Craftsman ⟷ Warrior (soft split, both reachable)**
| Node | Type | Effect | Prereq |
|---|---|---|---|
| Forgemaster | Passive | Cheaper smithing-table repairs; exclusive Dwarf smithing recipes | Sure Grip |
| Runic Edge | Passive | Faster/cheaper enchanting & grindstone use | Forgemaster |
| Shield-Wall of Khazad-dûm | Passive (trigger) | Stationary = stacking knockback_resistance + flat damage reduction | Stone-Hard Body |
| Treasure-Ward | Passive (risk/reward) | Bonus ore/gem drop rate; optional "Dragon-sickness" debuff if raw treasure carried exceeds threshold | Long Memory |

**Tier 4 — Elder**
| Node | Type | Effect | Prereq |
|---|---|---|---|
| Heart of the Mountain | Passive | Near-immunity to fire/lava, brief safe lava-wading | Forge-Born I |
| Grim Endurance | Passive | Regeneration boost below 30% HP | Shield-Wall |
| Master Smith of Erebor | Passive | Unlocks mithril/runic-tier gear recipes; crafted gear gains bonus durability | Runic Edge |
| Unbroken Will | Passive | Resistance to charm/fear/domination-style debuffs | Old Halls, Old Blood |
| Battle-Fury | Active (cooldown, low-HP gated) | Short Strength + Resistance + knockback immunity | Grim Endurance |

**Tier 5 — Capstones (choose one, or both if the tree allows dual-capstone spend)**
- **Lord of the Silver Fountains** (Passive) — permanent underground Night Vision + full Mining Fatigue immunity + top mining_efficiency tier + small chance to duplicate ore drops. *(Erebor/Moria's legendary wealth, made playable.)*
- **Durin's Bane-Slayer** (Active, long cooldown) — a war-fury ultimate: several seconds of major Strength + Resistance + Fire Immunity + full knockback immunity, framed as the resolve that let Dwarves stand against horrors from the deep. Meant for boss/"Balrog-tier" encounters.

### 3.2 HOBBIT TREE — "The Shire-Folk's Cunning"

**Tier 1 — Homebody (Foundation)**
| Node | Type | Effect | Prereq |
|---|---|---|---|
| Light Feet | Passive | Additional sneaking_speed + footstep-noise reduction, stacks on racial +45% | — |
| Leathery Soles | Passive | Immune to minor barefoot ground hazards; small extra fall-damage reduction | — |
| Packed Lunch | Passive | Slower hunger drain; cooked meals restore more saturation | — |
| Small & Sly | Passive | Further detection_range reduction | — |

**Tier 2 — Wanderer**
| Node | Type | Effect | Prereq |
|---|---|---|---|
| Unassuming | Passive | Mobs de-aggro faster once line-of-sight is broken | Small & Sly |
| Quick Fingers | Passive | Faster chest/barrel looting, bonus "rummage" loot roll | Light Feet |
| Second Breakfast | Passive | Eating grants a short bonus buff (Speed/Regen tick), more frequent retrigger | Packed Lunch |
| Stone-cast | Active (keybind) | Throw a stone: ranged stun/knockback; bonus damage if target unaware | — |

**Tier 3 — Burglar**
| Node | Type | Effect | Prereq |
|---|---|---|---|
| Burglar's Grace | Passive | Major stealth boost while sneaking; near-silent movement | Unassuming |
| Nimble Escape | Active (keybind, cooldown) | Short dash/roll with brief i-frames | Quick Fingers |
| Green Fields Reckoning | Passive | Bonus damage/accuracy vs. goblin/orc-type mobs with Stone-cast | Stone-cast |
| Quiet Killer | Passive | Big damage multiplier on sneak-attacks (first hit while undetected) | Burglar's Grace |

**Tier 4 — Renowned**
| Node | Type | Effect | Prereq |
|---|---|---|---|
| Simple Heart | Passive | Resistance to curse/corruption/domination-style debuffs | Quiet Killer |
| Stouthearted | Passive | Resistance to Fear/"Shaken" debuff near giant/troll/boss-scale mobs | Nimble Escape |
| Green Thumb of the Shire | Passive | Bonus crop yield/growth speed while in Shire-aligned territory | Second Breakfast |
| Vanish | Active (keybind, cooldown) | Brief near-invisibility + silenced footsteps | Burglar's Grace |

**Tier 5 — Capstones**
- **Ringbearer's Resolve** (Passive) — strongest tier of corruption/curse/fear resistance in the game; small chance to fully shrug off a negative status effect outright, echoing "purity of heart left no footholds" — but never absolute immunity (Frodo himself was not immune at the end).
- **Thief of Erebor** (Active, long cooldown) — a "burglary" ultimate combining Vanish + a guaranteed-crit/backstab window + a short speed burst, styled after Bilbo's role as the Company's official burglar breaking into a dragon's lair.

---

## SOURCES

[1] J.R.R. Tolkien, *The Silmarillion*, "Of Aulë and Yavanna" — Aulë makes the Dwarves "stone-hard... suffer toil and hunger and hurt of body more hardily than all other speaking peoples."
[2] Tolkien Gateway, "Dwarves" — https://tolkiengateway.net/wiki/Dwarves
[3] Wikipedia, "Dwarves in Middle-earth" — https://en.wikipedia.org/wiki/Dwarves_in_Middle-earth
[4] J.R.R. Tolkien, *The Fellowship of the Ring*, "A Journey in the Dark" — "The Dwarves tell no tale; but even as mithril was the foundation of their wealth, so also it was their destruction: they delved too greedily and too deep, and disturbed that from which they fled, Durin's Bane."
[5] Tolkien Gateway / discussion of Durin's Bane and Moria — corroborating context via multiple secondary sources (movie-sounds.org, TV Tropes "Dug Too Deep").
[6] middle-earth.xenite.org, "How Would the Seven Rings Have Affected Mortals?" — https://middle-earth.xenite.org/how-would-the-seven-rings-have-affected-mortals/
[7] middle-earth.xenite.org, "Did the Rings of Power Instill the Dwarves with a Lust for Gold and Jewels?" — https://middle-earth.xenite.org/did-the-rings-of-power-instill-the-dwarves-with-a-lust-for-gold-and-jewels/ ; katespace.com, "If the dwarves could resist their Rings of Power..." — https://katespace.com/2022/03/27/if-the-dwarves-could-resist-their-rings-of-power-why-not-give-them-the-one-ring-to-take-into-mordor/
[8] J.R.R. Tolkien, *The Fellowship of the Ring*, "A Journey in the Dark" — Gandalf on the Doors of Durin's inscription being Sindarin not Khuzdul.
[9] Wikipedia, "Khuzdul" — https://en.wikipedia.org/wiki/Khuzdul ; Tolkien Gateway, "Khuzdul" — https://tolkiengateway.net/wiki/Khuzdul
[10] J.R.R. Tolkien, *The Lord of the Rings*, Appendix A, "Durin's Folk" — Battle of Azanulbizar; Tolkien Gateway, "Battle of Azanulbizar" — https://tolkiengateway.net/wiki/Battle_of_Azanulbizar
[11] Secondary characterization of Dwarves and animals/horses, consistent with Gimli's discomfort riding in *The Two Towers* and *The Return of the King*.
[12] J.R.R. Tolkien, *The Hobbit*, ch. "Fire and Water"/"A Thief in the Night" region — Bilbo "did not reckon with the power that gold has upon which a dragon has long brooded, nor with dwarvish hearts."
[13] J.R.R. Tolkien, *The Fellowship of the Ring*, Prologue, "Concerning Hobbits" — "they possessed from the first the art of disappearing swiftly and silently... this art they have developed until to Men it may seem magical."
[14] screenrant.com, "LOTR: Why Hobbits Weren't Fully Corrupted By The One Ring" — https://screenrant.com/lord-rings-hobbits-not-corrupted-sauron/
[15] Tolkien Gateway, "Bandobras Took" — https://tolkiengateway.net/wiki/Bandobras_Took ; lotr.fandom.com, "Bandobras Took" — https://lotr.fandom.com/wiki/Bandobras_Took
[16] screenrant.com, "How Sam Gamgee Resisted The One Ring In Lord Of The Rings" — https://screenrant.com/lotr-how-sam-gamgee-resisted-one-ring/
[17] Wikipedia, "One Ring" — https://en.wikipedia.org/wiki/One_Ring ; Wikipedia, "Addiction to power in The Lord of the Rings" — https://en.wikipedia.org/wiki/Addiction_to_power_in_The_Lord_of_the_Rings
[18] J.R.R. Tolkien, *The Fellowship of the Ring*, Prologue, "Concerning Hobbits" — six daily meals; cbr.com, "Every Meal Hobbits Eat in The Lord of the Rings" — https://www.cbr.com/hobbit-meals-lord-of-rings/
[19] J.R.R. Tolkien, *The Fellowship of the Ring*, Prologue, "Concerning Hobbits" — "they seldom wore shoes, since their feet had tough leathery soles and were clad in a thick curling hair"; near-identical line in *The Hobbit*, ch. 1.

*Mod context source: `research/middle-earth-factions-races-study.md` (attribute system, racial baselines, dormant `detection_range`/`delvers_fear_strength` hooks).*
