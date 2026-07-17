# Evil Races of Middle-earth — Lore Deep-Dive & Ability/Enemy Design Doc

**Scope:** Playable evil races/factions in the `middle-earth` Fabric mod — **Orc** (Mordor), **Uruk** (Isengard/Mordor "fighting Uruk-hai"), **Snaga** (slave-orcs), **Goblin** (Goblin Town / Misty Mountains / Moria/Gundabad) — plus enemy-side dark powers (Trolls, Nazgûl, Balrog, Shelob, Watcher-in-the-Water) to make both sides of the war harder and scarier. Written against the existing attribute system documented in `research/middle-earth-factions-races-study.md` §3.4 (`climbing_strength`, `detection_range`, `burning_time`, `fall_damage_multiplier`, `powdered_snow_immunity`, `delvers_fear_strength` [wired, unused], `mining_efficiency`, `scale`, plus vanilla `speed/health/attack_damage/reach`).

Every lore claim below is cited. Where the books are genuinely ambiguous or contradicted by film canon, I say so explicitly and recommend the game-design-friendly reading — this is a game, not a thesis, but it should know exactly which liberties it's taking and why.

---

## 1. Canonical Lore Digest

### 1.1 Orcs — general (Mordor, and orc-kind broadly)

- **Origin:** In the primary published account (*The Silmarillion*), Orcs began as Elves of Cuiviénen captured, tortured, and corrupted by Melkor before the First Age — "corrupted and enslaved" into "a race of jealous malice." Tolkien's late unpublished essays (*Morgoth's Ring*, "Myths Transformed") complicate this with Orcs-from-Men and beast-hybrid theories, but the Elf-corruption account is the one Tolkien kept closest to canon in his lifetime. [Tolkien Forum — origin of the Orcs](https://thetolkien.forum/threads/the-origin-of-the-orcs-and-matters-touching-thereon.17675/)
- **Bred and multiplied "like flies":** Morgoth (and later Sauron) deliberately gave Orcs "power of recuperation and multiplication" beyond the natural rate of the Free Peoples — a designed, weaponized fecundity, not a biological accident. [Tolkien quote via Tolkien Forum](https://thetolkien.forum/threads/how-do-orcs-breed.7469/)
- **Rule through hate and fear, not loyalty:** *"In the kingdom of hate and fear, the strongest thing is hate. All his Orcs hated one another, and must be kept ever at war with some 'enemy' to prevent them from slaying one another."* From *The Silmarillion*: *"deep in their dark hearts the Orcs loathed the Master whom they served in fear, the maker only of their misery."* This is the mechanical seed for **infighting as a designed feature of orc society**, not a writer's inconsistency. [Wikiquote — Orcs](https://en.wikiquote.org/wiki/Orcs)
- **Cannibalism & infighting on-page:** In *The Two Towers*, "The Uruk-hai" chapter, Grishnakh (Mordor-orc) accuses Uglúk (Isengard-Uruk) of cannibalism, sparking violence between the two orc-companies escorting Merry & Pippin — proof that inter-orc factional hatred and cannibal accusation are textual, not just film flourish (the famous "Meat's back on the menu, boys" line *is* a Jackson-film invention, but the underlying cannibalism-accusation fight is Tolkien's). [CBR — What did Orcs eat](https://www.cbr.com/lotr-what-orcs-eat-explained/) / [Planet Tolkien](https://www.planet-tolkien.com/board/5/1385/0/cannibal-orcs)
- **Sunlight: hatred vs. weakness (the actual textual nuance).** This is the single most important and most misunderstood point, and the mod should be deliberate about which reading it uses:
  - **Strict book canon:** ordinary Orcs are shown fighting in broad daylight throughout the First Age wars, and nothing in the text physically debilitates them in the sun. Their aversion is closer to inherited cultural/spiritual revulsion than a mechanical weakness. [Xenite — Does Light Really Hurt the Orcs?](https://middle-earth.xenite.org/does-light-really-hurt-the-orcs/)
  - **But Tolkien himself writes the contrast as a hard in-world rule via Treebeard:** *"It is a mark of evil things that came in the Great Darkness that they cannot abide the Sun; but Saruman's Orcs can endure it, even if they hate it."* (*The Two Towers*, Book III, "Treebeard"). This sentence is the mod's single best design anchor: it explicitly frames "cannot abide the Sun" as the *default* orc condition and Uruk-hai sun-tolerance as the notable exception worth naming as an ability. [Xenite — Why does the sun turn some trolls to stone](https://middle-earth.xenite.org/why-does-the-sun-turn-some-trolls-to-stone/)
  - **Sauron literally blots out the sun for his own armies' comfort:** before the assault on Minas Tirith, Sauron sends "a great cloud of smoke and vapour... to hide the light" from Orodruin so his armies can march and fight without the Sun's discomfort (*Return of the King*, "Minas Tirith" / "The Land of Shadow"). This is a canonical, textual admission that Sauron's forces fight better, or at least happier, under an artificial night — which is a perfect systemic justification for a "Darkening" world-event mechanic (§5).
  - **Design verdict:** treat "weakened/penalized in direct sunlight" as **true for Orc and Goblin/Snaga, false for Uruk** — faithful to the Treebeard quote, mechanically the cleanest, and it's exactly the axis the user asked to make central.
- **Night vision / cave-dwellers:** Orcs and goblins are consistently written as creatures of tunnels, pits, and darkness, wielding torches or fighting by feel in black tunnels where Men and Elves would be blind — the basis of a light-independent detection/attack kit. [Cubicle 7 forum — Can Orcs See in the Dark](https://stuartrjohnson.co.uk/tor/archive/Can%20Orcs%20See%20In%20The%20Dark.htm)
- **Tireless when driven, but only under a master's will:** Orcs can force-march further than Men or Hobbits, but Tolkien is explicit that this endurance is *conditional on fear of a dominating will* (Sauron's, Saruman's, or a captain's) rather than innate orc stamina — remove the master, and the war-band's discipline and drive collapse. This is the textual basis for a "Pack Tactics"/"needs a leader" mechanic rather than orcs simply being naturally tireless like Uruk-hai are.
- **Fractious, treacherous, contemptuous hierarchy:** orc-companies from different realms (Moria, Mordor, Isengard, "Northerners") distrust and insult each other on sight (again "The Uruk-hai" chapter — Uglúk vs. Grishnakh vs. the "tark-hunting" Northerners), and small "snaga" are bullied by bigger Uruks. This hierarchy of contempt (Uruk > regular Orc > Snaga/Goblin) is baked into the source material and maps directly onto the mod's four playable strata.

### 1.2 Uruk-hai (Isengard, and Sauron's own "black Uruks")

- **Two lineages, one name:** "Uruk-hai" (Black Speech, roughly "orc-folk/orc-race") first denotes Sauron's own larger, stronger black-Uruk breed that sortied from Mordor as early as T.A. 2475 (sacking Osgiliath); Saruman later independently bred (or re-discovered/improved) his own strain at Isengard, cross-breeding Orcs with Men to get both "Men-orcs" (large, cunning, can pass for Men in daylight) and "Orc-men." Both strains share the name and the sun-tolerance. [Stason.org TULARC FAQ — Origin of Saruman's Uruk-hai](https://stason.org/TULARC/education-books/tolkien-newsgroups/41-What-was-the-origin-of-Saruman-s-Uruk-hai-Tolkien.html) / [CBR — Saruman fixed the orcs' biggest weakness](https://www.cbr.com/saruman-improved-orcs-with-uruk-hai-preventing-sunlight-weakness-lord-of-rings/)
- **The Treebeard line again** — Saruman's Orcs "can endure [the Sun], even if they hate it" — is the textual license for a Uruk **capstone** ability that literally removes the sunlight penalty while keeping a smaller "hate it" flavor cost (see §6.2).
- **The self-titled "fighting Uruk-hai":** Uglúk's boast to his captives is Tolkien's own naming gift for a capstone node: *"We are the fighting Uruk-hai! We slew the great warrior. We took the prisoners. We are the servants of Saruman the Wise..."* (*The Two Towers*, "The Uruk-hai"). Note the self-identification as **servants** — even the proudest Uruk still names his master, reinforcing that "who commands you" matters mechanically for all orc-kind.
- **The relentless pursuit across Rohan:** the entire "The Uruk-hai" / "The Riders of Rohan" chapters are built around Saruman's Isengarders + Mordor-orcs covering roughly 45 leagues (135+ miles) in under four days without real rest or food, carrying captives, then still being combat-ready — Gimli's line "Three days and nights' pursuit, no food, no rest, and no sign of our quarry" is spoken of *them keeping pace*, establishing forced-march endurance as their headline trait, distinct from and stronger than ordinary orc stamina. [tk421.net — Two Towers text](https://www.tk421.net/lotr/film/ttt/03.html)
- **Discipline & size:** consistently written as taller, broader, tougher, better-armed and better-organized than common Orcs or Goblins, with the "lesser" orcs (Snaga) serving as their expendable auxiliaries and camp-slaves.

### 1.3 Goblins / Snaga (Goblin Town, Moria, Gundabad, and the "small folk" of orc-kind)

- **"Goblin" = Tolkien's own translation convention.** In *The Hobbit* Tolkien calls the Misty Mountain Orcs "goblins" throughout, explicitly the same race as the "orcs" of *LotR*, just written for a lighter register. Goblin-town (High Pass), Moria's orc-holds, and Gundabad ("Hobgoblin" clans) are all the same kind of creature in-world.
- **Cruel, wicked, clever-but-not-beautiful, and excellent tunnel-tinkerers:** *"Now goblins are cruel, wicked, and bad-hearted. They make no beautiful things, but they make many clever ones... They can tunnel and mine as well as any but the most skilled dwarves, when they take the trouble... Hammers, axes, swords, daggers, pickaxes, tongs, and also instruments of torture, they make very well."* (*The Hobbit*, "Over Hill and Under Hill"). This is a direct textual basis for a mining/engineering-flavored goblin skill branch alongside the swarm-combat one. [Goodreads — Hobbit quote](https://www.goodreads.com/quotes/919874-now-goblins-are-cruel-wicked-and-bad-hearted-they-make-no)
- **The Great Goblin** rules Goblin-town as a singular tyrant-chieftain ("a tremendous goblin with a huge head") — good capstone/boss-title material ("Great Goblin's Horde"). [LOTR Fandom — Great Goblin](https://lotr.fandom.com/wiki/Great_Goblin)
- **Swarm over individual strength:** goblins/small-orcs are individually weak but overwhelming in numbers and ambush — consistent across *The Hobbit* (goblin ambush of the dwarves, goblin army at the Battle of Five Armies) and *LotR* (Moria's horde swarming the Fellowship in the Chamber of Mazarbul, "an endless army" implication rather than a few elite fighters).
- **"Snaga" is literally Black Speech for "slave":** used contemptuously by bigger orcs/Uruks for the small, weak, put-upon orcs who do the dirty/menial work — two named Snagas appear on-page: a scout under Uglúk's Isengard company (*The Two Towers*), and Shagrat's last surviving underling in the Tower of Cirith Ungol after the Mordor-orc civil war over Frodo's mithril coat (*The Return of the King*, "The Tower of Cirith Ungol"). Both named Snagas are subordinate, expendable, and used/abused by their superiors — perfect thematic basis for a "punches-up-when-cornered / bonus-when-outnumbered" underdog kit. [Tolkien Gateway via search — Snaga](https://tolkiengateway.net/wiki/Snaga_(orc_of_Mordor)) / [Valarguild Encyclopedia — Snaga](https://valarguild.org/tolkien/encyc/articles/s/Snaga.htm)
- **Climbers:** goblins are repeatedly shown clambering rock faces, cave walls, and mountain paths with ease (their whole domain is vertical: mountain-holds, tunnel networks, cliff ambushes at the High Pass) — the existing race table's `climbing_strength` wall-cling perk for Goblin already encodes this; it should be leaned into further as a movement-based skill branch (wall-running, ceiling ambush drops).

### 1.4 Other Dark Powers (world/enemy-side flavor, not playable)

- **Trolls literally turn to stone in sunlight** — Tom, Bert, and William are caught arguing till dawn by Gandalf's ventriloquism trick and turn to stone the instant direct sun hits them (*The Hobbit*, "Roast Mutton"). Unlike the orc sunlight question, this one is unambiguous, total, and instant — trolls are a hard "night hunter, sunlight = death/petrify" enemy archetype, not a soft debuff. [Wikipedia — Trolls in Middle-earth](https://en.wikipedia.org/wiki/Trolls_in_Middle-earth)
- **Nazgûl / Black Breath:** proximity to a Ringwraith inflicts the "Black Breath" or "Black Shadow" — a creeping spiritual malady of fear, cold, and despair that causes victims to slip into an ever-deepening dark sleep and die unless treated; in milder doses, brief unconsciousness, nightmares, and agitation on waking. Merry's sword-arm is left paralyzed after he stabs the Witch-king at the Pelennor Fields; only *athelas* in a rightful king's hands (Aragorn, in the Houses of Healing) fully cures it. [Tolkien Gateway/search summary — Black Breath](https://tolkiengateway.net/wiki/Black_Breath) — direct basis for a fear/paralysis debuff status effect with a rare, king-locked or high-tier cure item.
- **Durin's Bane (the Balrog of Moria):** an ancient Maia corrupted by Morgoth, slept for millennia under Caradhras until Dwarven mithril-mining woke it; killed Durin VI, then Gandalf in the Bridge of Khazad-dûm, and the two fought for eight days atop Zirak-zigil. The archetypal "wake a sleeping apex predator by digging too deep/too greedily" raid-boss template. [Tolkien Gateway via search — Durin's Bane](https://tolkiengateway.net/wiki/Durin's_Bane)
- **Watcher in the Water:** a many-tentacled unnamed horror in the dammed pool before Moria's West-gate, that dragged at Frodo's ankle and then sealed the doors behind the fleeing Fellowship, uprooting trees in the process — Tolkien deliberately never explains what it is. Great "unknowable lake ambush miniboss" template for any dammed/flooded structure entrance. [Tolkien Gateway via search — Watcher in the Water](https://tolkiengateway.net/wiki/Watcher_in_the_Water)
- **Shelob:** the last, greatest child of Ungoliant, predates even Sauron in Cirith Ungol, mates with and devours her own offspring, and Sauron indulgently calls her his "cat" and lets her guard the pass as a living security system. Her lesser broods spread from the Ephel Dúath to Dol Guldur and Mirkwood — a canonical justification for a Mirkwood/Mordor "spider brood" enemy family feeding into a Shelob raid-boss. [Search summary — Shelob](https://en.wikipedia.org/wiki/Shelob)
- **Sauron/Saruman's will as a genuine game mechanic:** both Dark Lords are written as actively projecting will to dominate their Orcs — Sauron ruling through hate/fear/rivalry management, Saruman through conditioning ("You do not know pain, you do not know fear" — film, but true to the "bred as tools" theme) — supporting a "buff radius around a Captain/idol/banner, buffed further near a Nazgûl or the Eye" mechanic, and conversely a "leaderless orc-band loses its edge" debuff.

---

## 2. Design Thesis: the Sun/Shadow Axis as the core evil-race mechanic

The user's brief already points here, and the lore supports it more precisely than a blanket "evil = nocturnal" trope:

| Race | Sunlight rule | Textual basis |
|---|---|---|
| **Orc** | Real, meaningful penalty in direct, unobstructed sunlight (damage/regen/accuracy) | Treebeard's "cannot abide the Sun" baseline (§1.1); Sauron literally darkening the sky for his armies |
| **Goblin/Snaga** | Same penalty, **worse**, because they're already glass cannons (14/12 HP base) | goblins as tunnel/cave creatures never shown fighting long daylight campaigns in the source texts |
| **Uruk** | **Immune** — this is their entire lore-identity | Treebeard's explicit exception; Saruman's Uruks marching/fighting by day across Rohan |

This turns "play at night, underground, or in shade" from a generic villain gimmick into a race-specific strategic identity: Orc and Goblin/Snaga players are pushed toward night-raiding, tunnel warfare, and shade-engineering (torches out, roofed roads, cloaks, allied Nazgûl-shadow, the world "Darkening" event in §5); Uruk players are the "we go where we want, whenever we want" heavy-infantry answer, matching their lore role as Saruman's rapid-deployment strike force. This also gives the Good factions (who are unaffected either way) an asymmetric read on who they're fighting: an Orc raid at noon is what full Sauron's-will backing looks like (rare and threatening); a Uruk raid at noon is just Tuesday.

---

## 3. Ability Catalogue (buffs → concrete mechanics)

Mapped to the mod's real attribute system (`middle-earth:*` custom attributes + vanilla) and to status effects / keybind actives, per the existing race stat table.

### 3.1 Shared "orc-kind" buffs (available in some form to Orc/Uruk/Snaga/Goblin)

| Ability | Type | Mechanic | Lore basis |
|---|---|---|---|
| **Darksight** | Passive | Full-bright / night-vision-equivalent in caves & at night; no penalty to detection_range in darkness | orcs/goblins as tunnel creatures, torch-and-feel fighters (§1.1, §1.3) |
| **Bred for War** | Passive | Cheap, fast natural regen (health_regen boost) but a **permanently lower max HP cap** than the good-race equivalent (already true in base race table — Orc 8♥, Snaga 6♥, Goblin 7♥) | Orcs/goblins as mass-produced, disposable soldiery, "multiplied like flies" (§1.1) |
| **Cruelty (lifesteal)** | Passive, unlocked mid-tree | On kill (player or hostile mob), heal a small % of missing HP / gain brief absorption | textual cannibalism & blood-hunger (§1.1) |
| **Pack Tactics** | Passive, scales | Attack-damage & move-speed bonus that scales with number of nearby allied orc-kind (players or faction NPCs) within N blocks, capped | orc war-bands as numbers-over-individual-quality (§1.1, §1.3); mirrors vanilla Illager "bad omen"/raid crowding logic |
| **Servant's Dread** | Passive (mostly flavor/soft debuff, see §4) | Bonus buff **only** while within range of a named Captain/Nazgûl/banner/Eye-shrine; alone and unled, bonuses shrink | "ruled through hate and fear," tireless-only-when-driven (§1.1) |
| **War-Horn** | Active (keybind) | Blow a horn: AoE speed+haste buff to nearby allied orc-kind for a duration, on a long cooldown; simultaneously raises `detection_range` for nearby enemies (it's loud — a real tradeoff) | orc-band signal culture, march discipline |
| **Scavenger's Stomach** | Passive | Can eat raw/rotten meat, rotten flesh, and (unlocked later) hostile-mob drops for full hunger with no sickness chance; **cannot** benefit from lembas/Elvish waybread (see §4) | orc diet of "Men, ponies, and their own kind" (§1.1) |

### 3.2 Orc-specific (Mordor) — "Servant of the Eye" flavor

| Ability | Type | Mechanic |
|---|---|---|
| **Cave-Reared** | Passive | Bonus `mining_efficiency` and no penalty underground/at Y<40 (mirrors Dwarf's mining perk but tied to depth, not race-wide) |
| **Blade in the Dark** | Passive | Bonus sneak-attack damage multiplier at night or underground, stacking with the existing high Orc `sneak` (0.435) stat |
| **Orc-draught** | Active (consumable/keybind) | Drink a brewed "black draught": short but strong haste+strength, at the cost of a stacking `hunger`/`nausea` debuff after it wears off — a controllable, addictive power spike | orc-draughts referenced as battlefield stimulants in Tolkien's war-band logistics flavor |
| **The Eye Is Upon You** | Capstone (passive, always-on once unlocked) | While within a large radius of a Mordor faction structure/beacon: full sunlight-penalty mitigation (Sauron's smoke-cover, §1.1) + Pack Tactics radius doubled; outside that radius, full penalties return | Sauron literally darkening the sky over his own armies (RotK "Minas Tirith") |

### 3.3 Uruk-specific (Isengard/Mordor) — "Fighting Uruk-hai" flavor

| Ability | Type | Mechanic |
|---|---|---|
| **Relentless March** | Passive | Sprint without hunger drain; stamina/exhaustion reduction while sprint-sneaking is disabled but plain sprint is free | the 3-day, no-food, no-rest pursuit across Rohan (§1.2) |
| **Man-Orc Discipline** | Passive | Reduced `total_damage` variance from status ailments (poison/wither resist), reflecting bred toughness over goblin fragility | Uruks as deliberately over-engineered soldier-stock |
| **Forced March (Chase)** | Active (keybind, combat-adjacent) | Temporary big speed burst usable only while actively chasing a fleeing target (distance to target must be closing), long cooldown | the specific "hunting fleeing prisoners across open country" scenario of "The Uruk-hai" |
| **Sun-Defiance** | Capstone (passive, always-on) | **Completely removes the sunlight penalty tree-wide** (unlike Orc's conditional Eye radius version) + immune to the world "Darkening"/Nazgûl-fear penalty stacking; flavor cost: a permanent small `burning_time` INCREASE relative to other orc-kind (they "endure the Sun, even if they hate it" — not immune to fire, just to daylight) | Treebeard's exact line: "Saruman's Orcs can endure it, even if they hate it" (§1.1, §1.2) |
| **Fighting Uruk-hai** | Capstone (passive, title-gated) | Final combat capstone: flat attack_damage and knockback-resist bump, requires Sun-Defiance + a kill-count/battle-count gate — the "we are the fighting Uruk-hai" self-declaration as an earned rank, not a starting freebie | Uglúk's boast (§1.2) |

### 3.4 Snaga-specific — "Slave's Cunning" flavor

| Ability | Type | Mechanic |
|---|---|---|
| **Underdog** | Passive, scaling | Damage-dealt and damage-resist bonus that **increases** the fewer allies are nearby and/or the more numerous the enemies are (inverse of Orc Pack Tactics) — rewards solo/outnumbered Snaga play instead of punishing it | named Snagas surviving alone after their whole company dies (Cirith Ungol massacre, §1.3) |
| **Reach of the Overlooked** | Passive (already partially true in base stats: best block reach 5.5) | Further bonus entity-reach/utility with tools & block interaction, further mining efficiency — leans into their existing "best mining/build reach" niche |
| **Kicked Awake** | Passive | Faster stand-up/no-slow after being knocked down, staggered, or crit — a slave who's used to being kicked and getting back up | flavor from bullied-underclass status |
| **Slit and Run** | Active (keybind) | Very short invisibility/scent-masking + speed burst after landing a sneak-attack kill, to flee a fight they can't win outright — snagas are written as opportunists and cowards-when-outmatched, not suicidal brawlers | Snaga's role as scouts/messengers rather than front-line brutes |
| **Whatever It Takes** | Capstone (passive) | When below 30% HP: brief but strong all-stat surge (speed, attack, resist) — the cornered-slave "last stand" — with a hard once-per-life-threshold cooldown so it can't be farmed | thematic capstone: the weakest orc-kind becoming most dangerous exactly when it has nothing left to lose |

### 3.5 Goblin-specific (Goblin Town/Moria/Gundabad) — "Great Goblin's Horde" flavor

| Ability | Type | Mechanic |
|---|---|---|
| **Wall-Cling+** | Passive (extends existing `climbing_strength`) | Longer wall-cling duration, plus **ceiling-crawl** (short duration upside-down clinging for ambush drops) | goblins as vertical cave/cliff dwellers (§1.3) |
| **Tinker's Hands** | Passive | Bonus `mining_efficiency` on stone/ore specifically (not wood/dirt) + cheaper crafting cost for traps/torture-instrument-flavored gadget items | "they make many clever ones... instruments of torture, they make very well" (§1.3) |
| **Screech (alarm call)** | Active (keybind) | Loud alert shriek: buffs nearby goblin allies' aggro-speed and calls reinforcements from linked spawners/camps within range, at the cost of also alerting/aggroing every hostile player-faction NPC in a larger radius | goblin-town's swarming, alarm-raising ambush culture, and the fact that a single scream summoning "more goblins than you can count" is a recurring beat in *The Hobbit* |
| **Cave-Rat Swarm** | Passive, scaling | Same family as Pack Tactics but radius-larger/magnitude-smaller — goblins need *more* nearby allies for a *smaller* per-ally bonus than Orcs (numbers over quality, taken further than the Orc version) | swarm-not-elite combat doctrine (§1.3) |
| **Great Goblin's Horde** | Capstone (passive + unlocks a one-time summon active) | Once per long cooldown, call a temporary goblin war-band (a handful of allied goblin mobs) to fight alongside the player for a short duration | the Great Goblin as a horde-commander archetype rather than a solo powerhouse (§1.3) |

---

## 4. Debuff Catalogue — what makes evil harder & distinct (not just re-skinned good)

The user explicitly wants evil to be **harder**, not merely different-flavored-equal. These are drawbacks with real teeth:

1. **Sunlight Penalty (Orc, Goblin, Snaga — NOT Uruk).** In direct, unobstructed sunlight (sky-exposed, daytime, no rain/thunder override): stacking `mining_fatigue`-style or bespoke `sun_weakness` status — reduced attack_damage, reduced regen, and a slow "wither tick" if standing in full sun for an extended unbroken period (mirrors the vanilla Warden/Enderman-in-rain design pattern: a mechanical penalty tied to an environmental check already native to the engine). Mitigated by: night, roofed structures, rain/overcast weather (canonically Sauron/Saruman *do* use weather and smoke to cover their armies), cloaks/hoods (future gear-slot idea), or unlocking Orc's conditional "Eye Is Upon You" node. This is the single biggest, most game-defining evil-side drawback, exactly matching the brief.
2. **No Master, No Edge.** Pack Tactics / Servant's Dread-style buffs require *either* nearby allied orc-kind *or* proximity to a faction structure/banner/Captain — a lone, unsupported Orc/Uruk/Snaga/Goblin should feel objectively weaker than a lone Human/Dwarf/Elf of equivalent gear, mechanically enforcing "orcs are only fearsome as an army" (§1.1's "ruled through hate and fear" reading).
3. **Fractious Blood — friendly-fire flavor cost.** Because inter-orc hatred is textual (Uglúk vs. Grishnakh, §1.1), any AoE "War-Horn"/"Screech" active could have a small chance to also provoke nearby *rival* evil-faction NPCs (e.g., a Goblin Town screech has a chance to also draw hostile attention from a Moria patrol, or an Isengard Uruk's horn irritates a nearby Mordor-orc squad) — mechanically punishing over-reliance on noise/summon actives and reinforcing that the evil factions are allied in name (per the study's diplomacy table, §6.5 GOOD/EVIL blocs) but not in spirit.
4. **Can't stomach lembas / good-food penalty.** Purely a design extrapolation (not directly textual, flagged as such) but thematically strong: Elvish waybread and other "blessed"/Free-Peoples-crafted foods give evil-race players reduced or zero hunger restored, or a mild `nausea` tick, symbolizing that the orcs' corruption cannot be nourished by anything wholesome — pushes evil players toward their own (meat/scavenger) food loop rather than raiding good-faction supply chains for free sustenance.
5. **Innate fragility (Goblin/Snaga).** Already true in the base race table (Snaga 6♥ lowest in the game, Goblin 7♥, both with −10%/−20% net damage) — the skill tree should **not** fully patch this over; keep the ceiling of Goblin/Snaga combat power lower than Uruk even at full skill investment, funneling them mechanically toward stealth/mobility/utility/swarm rather than becoming a reskinned heavy fighter. Uruk should remain the only evil race that can go toe-to-toe with a Dwarf/Human in raw attrition.
6. **Infighting-flavored downside for "Cruelty"/lifesteal:** because cannibalism-accusations caused actual violence in the text (§1.1), consider a rule where lifesteal healing from **eating an ally's drops/corpse** (as opposed to an enemy's) gives a smaller "guilt"/`weakness` tick — mechanically discourages griefing your own faction's mob camps for easy heals, while keeping "eating enemies/hostiles" fully clean.
7. **Delvers' Fear reuse.** The study notes `delvers_fear_strength` is a wired-but-unused "fear of the dark/deep" timer reserved for a Dwarf mechanic (study §3.4). Evil races should get the *mirror* debuff instead: a parallel light-dread timer (new attribute, e.g. `sunlight_dread_strength`) that ticks up the longer an Orc/Goblin/Snaga stands in open sun and slowly worsens the sun-penalty the longer they stay (rewarding hit-and-run raiding over sustained daylight sieges) — reusing the exact "creature has a phobia timer that compounds" pattern the codebase already has plumbing for, just pointed at the opposite trigger.

---

## 5. Enemy-side & World Difficulty Ideas

Ideas to make Middle-earth harder and scarier for **both** good and evil players, using the same lore:

- **The Darkening (world event).** Periodically (or triggerable by an evil-faction ritual/structure a la the study's `BIOME_EVENT`/`STRUCTURE_EVENT` spawn-tagging system), a region's sky visually darkens/smokes over (mirroring Sauron's canonical smoke-cover before the Pelennor Fields, §1.1). During a Darkening: Orc/Goblin/Snaga sunlight penalties are suppressed faction-wide in that region, spawn rates of orc war-bands spike, and Good-faction NPCs get a small morale/visibility debuff. This turns "is the sky dark" into a real strategic weather-system stake for both sides, not just flavor.
- **Trolls as true nocturnal apex enemies.** Per §1.4, make Trolls **instantly and permanently destroyed (petrify)** by direct sunrise exposure, full stop — no gradual debuff, an absolute rule straight from the source (Tom/Bert/William). This makes Trolls a "leave before dawn or die" enemy archetype: dangerous night-roaming heavy mobs that smart players (good *or* evil, since Trolls serve Sauron but are dumb enough to be baited by anyone, per the book) can defeat for free by stalling them past sunrise, mirroring Bilbo/Gandalf's actual trick. Bonus: gives evil players a genuine reason to build shaded/roofed troll-pens if they want to *keep* a captured troll past dawn.
- **Nazgûl fear aura + Black Breath status (§1.4).** A Nazgûl-type mob (or the Witch-king specifically as a raid boss) projects a large passive fear aura: nearby Good-faction players get slowed/weakened ("terror"), nearby Evil-faction players get a *smaller* version of the same (Sauron's own fear-based control, §1.1) unless they're within a Captain/banner's steadying radius. Direct hits apply the Black Breath status: escalating darkness-vision-tunneling, slowness, eventual "dark sleep" (long stun/near-death) if untreated, curable only by a rare `athelas`-tier item — deliberately gated (per the books, only a rightful-king figure's use of athelas fully works) so it's a genuine emergency item, not a spammable potion.
- **Balrog of Moria as a raid/dungeon boss (§1.4).** Sleeps under the deepest, richest ore layer of a Moria-style structure; waking it should be tied to mining depth/greed (a "dug too deep, too greedily" trigger on a specific rare-ore vein), exactly mirroring the Dwarves' own in-lore mistake. Fight should be a multi-phase chase-then-duel (Gandalf/Balrog fled the Bridge, fought 8 more days atop a mountain) — e.g., phase 1 in the depths, phase 2 a rooftop/peak finale.
- **Watcher-in-the-Water as a gate-guardian miniboss (§1.4).** Any dammed/flooded entrance to a Moria/Gundabad-style structure gets a tentacled ambush mob that grabs and drags toward deep water, and can seal the gate behind fleeing players (Tolkien's own "the doors are barred behind you now" beat) — a good forced-commitment dungeon gimmick for either faction's players delving there.
- **Shelob's Brood (§1.4).** A spider-enemy family (webbing, poison, ambush-from-above) populating Mirkwood, Dol Guldur, and Cirith Ungol-style biomes/structures, escalating toward a unique named Shelob raid-boss guarding the richest Mordor-adjacent loot route — reusing her canonical role as "Sauron's guard-cat," i.e., not aligned to Evil's diplomacy table at all (hostile to everyone, good and evil alike, same as the study's Brigand/Wild-Goblins neutral-hostile-to-all pattern).
- **Reduced regen "under the Shadow of Mordor" for Good players.** As a mirror-image of the Orc sunlight penalty, give Good-faction players (or all players regardless of alignment) a small passive regen/hunger-drain penalty while deep inside Mordor/Gorgoroth-tagged biomes ("the Shadow" as an ambient debuff), separate from and stackable with any Nazgûl aura — makes Mordor itself, not just its monsters, a hostile environment worth fearing, consistent with the books' repeated description of the land itself as poisoned and oppressive.
- **Orc war-parties that grow at night.** Tie orc/goblin NPC spawn density and rank distribution (the study's `NpcRank` ladder, §2.2) to time-of-day — night-time war-bands should skew toward higher ranks (VETERAN/LEADER) and larger group sizes, daytime toward isolated stragglers/sentries (reflecting that the bulk of orc-kind literally prefers to operate after dark) — this alone would give a huge, systemic, lore-accurate difficulty curve to night in evil territory for both attacking Good players and defending Evil players.

---

## 6. Skill-Tree Branch Sketches

Format: **Tier** (rough power/unlock-order) — **Node name** — [Passive/Active] — effect — prereq. Capstones are the final tier, typically gated behind a full prior branch plus some proof-of-play requirement (kill count, faction structure proximity, etc.) rather than just "spend enough points," per the brief's request for named capstones.

### 6.1 Orc — "Servant of the Eye"

- **T1 — Darksight** [Passive] — no detection penalty in darkness/underground; minor night attack-speed bonus. (root)
- **T1 — Scavenger's Stomach** [Passive] — eat raw/rotten/hostile-mob meat cleanly; lembas/good-food gives reduced hunger. (root)
- **T2 — Cave-Reared** [Passive] — + mining_efficiency, no underground movement penalty. (req. Darksight)
- **T2 — Blade in the Dark** [Passive] — bonus sneak-attack damage at night/underground, stacks with base high sneak stat. (req. Darksight)
- **T2 — Pack Tactics** [Passive, scaling] — attack/speed bonus scaling with nearby allied orc-kind. (root, independent)
- **T3 — Orc-draught** [Active] — burst haste+strength, post-buff hunger/nausea debuff. (req. any T2)
- **T3 — Servant's Dread (mitigated)** [Passive] — reduce the "no master, no edge" penalty's severity by half. (req. Pack Tactics)
- **T4 — War-Horn** [Active] — AoE ally buff, raises own detection_range while active. (req. Pack Tactics + Orc-draught)
- **Capstone — The Eye Is Upon You** [Passive] — near a Mordor structure/beacon: full sunlight-penalty suppression + doubled Pack Tactics radius; outside it, normal rules apply. (req. full tree + faction = Mordor)

### 6.2 Uruk — "Fighting Uruk-hai"

- **T1 — Man-Orc Discipline** [Passive] — poison/wither resistance. (root)
- **T1 — Relentless March** [Passive] — sprint costs no hunger. (root)
- **T2 — Forced March (Chase)** [Active] — speed burst only while closing distance on a fleeing target. (req. Relentless March)
- **T2 — Heavy Hand** [Passive] — flat knockback-resist and a small attack_damage bump (the "bigger, stronger" baseline). (root, independent)
- **T3 — Isengard Drilling** [Passive] — reduced random status-ailment duration further, small shield/block-style damage reduction while holding a weapon+shield combo. (req. Man-Orc Discipline)
- **T3 — Breeding Pits' Gift** [Passive] — small permanent max-HP increase (the one evil race allowed to approach good-race HP totals). (req. Heavy Hand)
- **Capstone tier A — Sun-Defiance** [Passive, always-on] — full, unconditional sunlight-penalty removal + immune to Darkening/Nazgûl-fear stacking; small permanent burning_time increase as flavor cost. (req. Relentless March + Forced March)
- **Capstone tier B — Fighting Uruk-hai** [Passive, title-gated] — final flat attack_damage/knockback bump, requires Sun-Defiance already unlocked plus a battle/kill-count threshold ("earn the name"). (req. Sun-Defiance + Breeding Pits' Gift + kill-count gate)

### 6.3 Snaga — "The Slave's Cunning"

- **T1 — Kicked Awake** [Passive] — faster recovery from knockdown/stagger/crit-flinch. (root)
- **T1 — Reach of the Overlooked** [Passive] — extra entity/block reach and mining efficiency (extends the race's existing best-in-game 5.5 block reach). (root)
- **T2 — Underdog** [Passive, scaling] — damage/resist bonus that *increases* when outnumbered or alone. (req. Kicked Awake)
- **T2 — Quiet Feet** [Passive] — bonus to existing sneak stat + reduced fall-noise/step-noise radius. (req. Reach of the Overlooked)
- **T3 — Slit and Run** [Active] — brief invisibility/speed burst after a sneak-kill, for disengaging fights they can't win. (req. Quiet Feet + Underdog)
- **T3 — Waste Not** [Passive] — better returns from scavenging/looting corpses and mining (bonus drop chance), reflecting a slave's habit of taking everything usable. (req. Reach of the Overlooked)
- **Capstone — Whatever It Takes** [Passive, triggered] — once HP drops below 30%, a strong once-per-threshold all-stat surge; hard cooldown so it can't be farmed as a permanent buff. (req. Underdog + Slit and Run)

### 6.4 Goblin — "Great Goblin's Horde"

- **T1 — Wall-Cling+** [Passive] — extends existing climbing_strength duration; unlocks brief ceiling-crawl. (root)
- **T1 — Tinker's Hands** [Passive] — bonus mining_efficiency vs. stone/ore, cheaper trap/gadget crafting costs. (root)
- **T2 — Ambush Drop** [Active] — from a wall/ceiling-cling, a bonus-damage pounce attack onto a target below. (req. Wall-Cling+)
- **T2 — Cave-Rat Swarm** [Passive, scaling] — Pack-Tactics-style bonus, wider radius/smaller per-ally magnitude than the Orc version (numbers-over-quality taken further). (root, independent)
- **T3 — Screech** [Active] — alarm shriek: buffs nearby goblin allies + summons reinforcements from linked spawners/camps in range; also risks alerting hostile factions in a wider radius. (req. Cave-Rat Swarm)
- **T3 — Instruments of Torture** [Passive] — crafted trap/gadget items (nets, deadfalls, spike-pits — flavor nod to §1.3's "instruments of torture, they make very well") deal bonus damage/apply debuffs when goblin-crafted. (req. Tinker's Hands)
- **Capstone — Great Goblin's Horde** [Passive unlock + one-shot Active] — long-cooldown active summons a temporary allied goblin war-band to fight alongside the player. (req. Screech + Ambush Drop)

---

## 7. Cross-Cutting Systems (tie the whole evil side together)

- **A single new attribute, `sunlight_dread_strength`,** mirroring the existing-but-unused `delvers_fear_strength` pattern (study §3.4) — a compounding timer that worsens the sun-penalty the longer Orc/Goblin/Snaga stay in direct daylight, resetting in shade/night/underground. Reuses existing plumbing conventions, just flips the trigger condition from "fear of the deep" to "dread of the light."
- **A single new attribute or derived stat, `pack_affinity_radius`,** driving Pack Tactics / Cave-Rat Swarm / Underdog's "nearby allies" checks uniformly across all four evil races (tuned per race: wide-and-shallow for Goblin, narrow-and-steep for Snaga's inverse version, medium for Orc, largely irrelevant for self-sufficient Uruk).
- **Diplomacy-aware actives:** War-Horn/Screech-style AoE actives should query the same `Faction.isHostileToward` / diplomacy table the study documents (§6, §9) so an Isengard horn correctly has a small chance to needle nearby Mordor NPCs, etc. — no new diplomacy system needed, just consuming the existing HOSTILE-only predicate creatively, and optionally finally giving FRIENDLY/ALLY (currently inert per study §10) a mechanical use: allied-faction War-Horns could stack, rival-but-not-hostile factions' shouldn't.
- **Weather/roof-check for the sunlight penalty:** reuse vanilla's existing sky-exposure + rain/thunder checks (the same primitives that already gate powdered-snow/enderman-burning-style logic) rather than inventing new physics — keeps this feature cheap to implement given the codebase already has `is_buff_reversed`-style tooltip plumbing and custom attribute precedent (study §3.4).

---

## 8. Sources

- [Tolkien Forum — "The origin of the Orcs and matters touching thereon"](https://thetolkien.forum/threads/the-origin-of-the-orcs-and-matters-touching-thereon.17675/)
- [Tolkien Forum — "How do Orcs breed?"](https://thetolkien.forum/threads/how-do-orcs-breed.7469/)
- [Wikiquote — Orcs](https://en.wikiquote.org/wiki/Orcs) (kingdom-of-hate-and-fear quote; Silmarillion "loathed the Master" quote)
- [CBR — What Did Orcs Eat in The Lord of the Rings?](https://www.cbr.com/lotr-what-orcs-eat-explained/)
- [Planet Tolkien forum — Cannibal Orcs?](https://www.planet-tolkien.com/board/5/1385/0/cannibal-orcs)
- [Middle-earth & Tolkien Blog (Xenite) — Does Light Really Hurt the Orcs?](https://middle-earth.xenite.org/does-light-really-hurt-the-orcs/)
- [Middle-earth & Tolkien Blog (Xenite) — Why Does the Sun Turn Some Trolls to Stone?](https://middle-earth.xenite.org/why-does-the-sun-turn-some-trolls-to-stone/)
- [CBR — How Saruman Fixed the Orcs' Biggest Weaknesses](https://www.cbr.com/saruman-improved-orcs-with-uruk-hai-preventing-sunlight-weakness-lord-of-rings/)
- [Stason.org TULARC FAQ — What was the origin of Saruman's Uruk-hai?](https://stason.org/TULARC/education-books/tolkien-newsgroups/41-What-was-the-origin-of-Saruman-s-Uruk-hai-Tolkien.html)
- [tk421.net — The Two Towers film/book text excerpts](https://www.tk421.net/lotr/film/ttt/03.html)
- [Goodreads — The Hobbit goblin-nature quote](https://www.goodreads.com/quotes/919874-now-goblins-are-cruel-wicked-and-bad-hearted-they-make-no)
- [LOTR Fandom — Great Goblin](https://lotr.fandom.com/wiki/Great_Goblin)
- [Valarguild Encyclopedia — Snaga](https://valarguild.org/tolkien/encyc/articles/s/Snaga.htm)
- [Tolkien Gateway (via search summary) — Snaga (orc of Mordor)](https://tolkiengateway.net/wiki/Snaga_(orc_of_Mordor))
- [Wikipedia — Trolls in Middle-earth](https://en.wikipedia.org/wiki/Trolls_in_Middle-earth)
- [Tolkien Gateway (via search summary) — Black Breath](https://tolkiengateway.net/wiki/Black_Breath)
- [Tolkien Gateway (via search summary) — Durin's Bane](https://tolkiengateway.net/wiki/Durin's_Bane)
- [Tolkien Gateway (via search summary) — Watcher in the Water](https://tolkiengateway.net/wiki/Watcher_in_the_Water)
- [Wikipedia — Shelob](https://en.wikipedia.org/wiki/Shelob)
- [Cubicle 7 forum archive — Can Orcs See in the Dark?](https://stuartrjohnson.co.uk/tor/archive/Can%20Orcs%20See%20In%20The%20Dark.htm)
- Internal: `research/middle-earth-factions-races-study.md` (mod's actual attribute system, race stats, faction diplomacy, spawn/NPC-rank architecture)

*Note on sourcing method: Tolkien Gateway's own wiki blocked direct page fetches (HTTP 403) during this research session, and Fandom wikis returned HTTP 402; the citations above for those two sites are therefore via search-engine result summaries rather than direct page fetches, cross-checked against multiple independent secondary sources (Wikipedia, Wikiquote, TULARC FAQ, CBR, Xenite) where possible. Primary-text quotes (Treebeard, Uglúk, Gimli, the Silmarillion "kingdom of hate and fear" passage) are widely and consistently attested across sources and match the well-known published text of* The Hobbit *and* The Lord of the Rings.
