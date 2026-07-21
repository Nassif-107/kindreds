# The World Answers — Enemy Scaling Design

**Goal.** Middle-earth grows more dangerous as a player grows stronger, so that power never makes the
world safe. Configurable per server, on by default.

**Status.** Design approved. Not yet implemented.

---

## 1. Principles

These decide every argument that follows.

1. **New danger before bigger numbers.** Threat should mostly buy *different* enemies and *new*
   behaviour, and only modestly buy health and damage. Pure stat inflation is the Oblivion trap:
   bandits in daedric armour, every fight the same length, progress invisible. Bullet sponges are
   harder without being more interesting.
2. **Health, not damage resistance.** Mathematically similar, completely different to play. A health
   bar that falls slowly reads as a worthy enemy; a sword that does less reads as a broken sword.
3. **Observe, don't guess.** What a player *declares* (points, gear) estimates their power. What they
   *do* (what they kill, how fast, how hurt they get, how often they die) measures it. Prefer the
   measurement, floored by the estimate.
4. **The world must pay for the danger.** If danger rises and rewards don't, the rational play is to
   stay weak and hide. Scaled danger pays scaled discipline XP and better loot.
5. **Visible, like every other build-defining rule in this mod.** The point cap is visible, the deeds
   are visible, an unlock never fails silently. A player can see what the land thinks of them.
6. **Never touch the spawner.** Transform what arrives; do not change how much arrives.

---

## 2. Threat

`Threat` is a per-player number, `0..100`. It is a **prior** corrected by **evidence**.

### 2.1 The prior — declared power

```
prior = 100 * (Wc*commitment + Wg*gear + Wr*renown) / (Wc + Wg + Wr)
```

| Term | Source | Range |
|---|---|---|
| `commitment` | `UnlockService.totalPointsSpent(data, tree) / UnlockService.effectiveCap(tree, data)` | 0..1 |
| `gear` | armour points + weapon damage, normalised against a full-mithril reference | 0..1 |
| `renown` | `RenownService.deedsForRace(data) / 4` | 0..1 |

Defaults `Wc = 3`, `Wg = 2`, `Wr = 1`. A server that wants pure skill-based scaling sets `Wg = 0`.

`commitment` is the spine because it is race-normalised for free — a Dwarf's four lanes and an Elf's
five do not distort it — and because it weights a 4-point capstone above a 1-point filler, which a
raw node count does not.

**Rejected:** discipline XP *earned* as the spine. It is unstrippable and simpler, but it measures
time served rather than power held, so a player who banks XP without spending it would face a world
scaled past what they can actually do.

### 2.2 Gear is gameable — the high-water mark

Read instantly, `gear` invites stripping your armour before going hunting and re-equipping to fight.
So `gear` is a **slow high-water mark**: it rises to the current reading immediately and decays
toward it at no more than **2 points per in-game day**. Taking your armour off lowers nothing but
your own effectiveness for the next while.

### 2.3 The evidence — revealed power

A `competence` multiplier, `0.75..1.25`, maintained per player as an exponentially weighted moving
average over *qualifying fights* (see §5 for what counts as a scaled mob).

Per qualifying fight, the signal is **hardship**:

```
hardship = damageTakenInFight / maxHealth
```

with a target of `0.25` — a meaningful fight should cost about a quarter of your health.

| Observation | Effect on `competence` |
|---|---|
| `hardship < target` (coasting) | rises, EWMA α = 0.10 |
| `hardship > target` (struggling) | falls, EWMA α = 0.04 |
| Death to a scaled mob | −0.05, applied once |
| Dropped below 25% health and survived | −0.01 |
| Time-to-kill far below expected | rises, folded into the same EWMA |

Kills are weighted by the target's **base danger** (its unscaled max health × attack damage), so
deleting a cave troll counts and deleting a chicken does not.

**Asymmetric by design.** Threat rises promptly when a player is coasting and falls slowly when they
are struggling: a death must never instantly soften the world, but a genuinely stuck player does get
relief.

**Deaths that are not combat evidence are ignored** — fall damage, lava, drowning, suffocation, the
void, `/kill`. Only deaths caused by a mob in scope.

### 2.4 The floor — why this cannot be farmed

```
threat = clamp(prior * competence, 0, 100)
```

Because `competence` is bounded to `0.75..1.25`, evidence can only move threat **±25% around the
prior**. No amount of deliberate dying grinds the world down: commitment and gear set a floor. This
is the property that makes the adaptive loop safe, and it must not be removed for tuning.

### 2.5 Per-family competence

Alongside the global figure, a `competence` per **mob family** (§5.2), same maths, same bounds. The
effective competence against a given mob is:

```
0.5 * global + 0.5 * family(that mob)
```

So a player who is death on wargs and helpless against trolls meets trolls that press them and wargs
that have stopped bothering. Families a player has barely fought default to the global figure.

### 2.6 The curve

The curve decides how much of a player's threat becomes world difficulty. One setting, three values:

| Setting | Exponent | Feel |
|---|---|---|
| `FEEL_STRONGER` (default) | `0.8` | The world grows slower than you. A wolf stops mattering; new things start to. |
| `EXACT_PACE` | `1.0` | Every fight stays about as dangerous as your first. |
| `LONG_DEFEAT` | `1.2` | The world outgrows you. Grim, Tolkien-true, hardest on a solo player. |

Applied as `scaled = (threat / 100) ^ exponent`, giving `0..1` — the single input every effect reads.

---

## 3. Effects

All caps below are settings; the values given are defaults.

| Effect | Formula | Cap | Shared or per-player |
|---|---|---|---|
| Damage dealt to you | `× (1 + 0.6 * scaled)` | +60% | **Per-player**, at the hit |
| Mob max health | `× (1 + 1.0 * scaledGroup)` | +100% | **Shared**, at spawn |
| Detection of you | `detection_range` raised toward its 1.0 clamp | see below | **Per-player**, attribute |
| Elite promotion | `chance = 0.25 * scaledGroup` | 25% | **Shared**, at spawn |
| Species replacement | `chance = 0.35 * scaledGroup` | 35% | **Shared**, at spawn |
| Discipline XP | `× (1 + 0.5 * scaled)` | +50% | **Per-player**, at the kill |

**Damage** extends the existing implementation in `PerkEventHandlers` (currently `+0.5%` per unlocked
node, capped `+50%`), which is replaced by the threat model.

**Detection** reuses the base mod's `middle-earth:detection_range` player attribute through
`AbilityApplier.setDynamicModifier`. Note the real constraint: the attribute is clamped to
`[0.1, 1.0]` and is tagged `is_buff_reversed` (lower = stealthier), so raising it can **cancel a
stealth build's advantage but never push detection past the baseline**. This axis is a counter to
stealth, not an unbounded dial, and must not be planned as one.

**Elites** are the point of the whole feature. A promoted mob gets:
- a name shown above it, drawn from a per-family list, so it reads as an event;
- one extra ability from a small pool (a damaging aura, a rallying shout that pulls nearby mobs, a
  burst of speed on being hurt, or a resistance while above half health);
- a loot bonus — a re-roll of its table plus a chance at a race-craft-tier material.

**Species replacement** is what "harder enemies more often" actually means, and needs no spawner
changes: a mob arriving in the world may be swapped for a tougher member of its own family — a
zombie for a warg, a warg for a cave troll. Replacement respects the family table (§5.2) so it stays
lore-true, and never replaces a mob whose replacement would not fit the space.

**Rewards** close the loop: a mob that was scaled up pays proportionally more discipline XP, and
elites drop better. Growing dangerous must be something a player wants.

---

## 4. Multiplayer

The rule that makes this work:

> **Per-player effects use that player's threat. Shared effects use the group's.**

- **Damage, detection and XP** resolve at the moment they touch a specific player, so two players
  fighting one troll each get their own difficulty from it with no shared state and nothing to
  reconcile.
- **Health, elite promotion and replacement** are properties of the mob, decided once when it enters
  the world, from the players near enough to matter.

```
scaledGroup = scaled(strongest nearby player) * (1 + 0.15 * (nearbyPlayers - 1))
```

capped at `+45%` for group size. "Nearby" is a 128-block radius at spawn time; a mob spawning with no
player in range uses the strongest player in that dimension, decayed by `0.5`.

**Why strongest rather than average.** Average lets a veteran hide behind newcomers to farm soft
mobs. Strongest means a veteran's presence makes the fight beefier — which is fair, because a group
kills faster — while the newcomer beside them is protected by the per-player damage correction: the
troll has more health for everyone, but only hits each player as hard as *that* player deserves.

---

## 5. What is in scope

### 5.1 Which mobs

Scope is a tag, not a hardcoded list, so a datapack can extend it:

- included: any `Monster`, plus everything in `kindreds:scaled_extra`
- excluded: everything in `kindreds:never_scaled` (bosses, the Ender Dragon, the Wither, tamed
  creatures, NPCs of a faction friendly to the player)
- per-dimension multiplier, defaulting to `1.0` in the Middle-earth dimension and `0.75` in the
  overworld, so the old world stays gentler than the new one.

**Faction NPCs need care.** The base mod's `middle-earth:npc` covers both hostile and friendly
factions. Only NPCs currently hostile to the player are in scope; this is resolved through
`Allegiance`, which is already the single friend-or-foe authority in this mod.

### 5.2 Families

Tags we ship, seeded from the base mod's own where they exist:

| Family | Seeded from | Replacement ladder |
|---|---|---|
| `trolls` | `middle-earth:giants` | stone → cave → snow |
| `spiders` | `middle-earth:ungolieni` | larva → scuttler → spawn of Shelob |
| `wargs` | base mod warg | wolf → warg |
| `orc_kin` | faction NPCs | goblin → orc → uruk |
| `undead` | vanilla | zombie → husk → drowned |
| `other` | everything else in scope | no replacement |

---

## 6. Settings

A new **"The world answers"** section in the rules screen, operator-only to change like the rest,
readable by everyone.

| Setting | Default | Notes |
|---|---|---|
| `enableEnemyScaling` | **true** | Exists already; default flips from false |
| `scalingCurve` | `FEEL_STRONGER` | §2.6 |
| `weightCommitment` / `weightGear` / `weightRenown` | 3 / 2 / 1 | §2.1 |
| `adaptiveStrength` | 100% | 0% = prior only, no evidence loop |
| `maxDamageBonus` | 60% | |
| `maxHealthBonus` | 100% | |
| `eliteChance` | 25% | 0 disables elites |
| `replacementChance` | 35% | 0 disables species replacement |
| `xpBonus` | 50% | |
| `groupScaling` | 15% per extra player | |
| `dimensionMultiplier` | 1.0 Middle-earth, 0.75 overworld | |

The difficulty presets set these together: `FIRESIDE` off, `ROAD` on with `FEEL_STRONGER`,
`LONG_DEFEAT` on with `EXACT_PACE`, `DOOM` on with `LONG_DEFEAT`.

**The rules screen has bitten us before** — a fixed-height layout printed the numbers over the
difficulty rows. This section must be laid out in the flowing, measured style the screen now uses,
and verified by screenshot at several GUI scales.

---

## 7. Persistence and sync

Threat state is per player: `gearHighWater`, `competenceGlobal`, `competencePerFamily`, and a small
ring buffer of recent fights. It belongs in `KindredData` alongside the rest of a player's state,
with the same rules the existing fields follow:

- persisted in `CODEC` with `optionalFieldOf` so worlds written before this feature load cleanly;
- carried in `PACKET_CODEC` only as the **resolved threat number and its three components**, which is
  all the client needs to display it. The ring buffer and the per-family table stay server-side.

**A caution from this codebase's own history:** the sync packet must send a snapshot, not the live
collections, or netty encodes them on its own thread while the server thread is still editing.

---

## 8. Architecture

| File | Responsibility |
|---|---|
| `threat/ThreatService` | Owns the number. Prior, evidence, curve, per-family. The only thing that answers "how strong is this player". |
| `threat/ThreatEvidence` | Listens to damage, kills and deaths; folds them into competence. Knows nothing about effects. |
| `threat/MobScaler` | `ENTITY_LOAD` handler: health, elite promotion, species replacement. Knows nothing about how threat was derived. |
| `threat/EliteMobs` | The elite pool — names, abilities, loot. |
| `threat/ScaledFamilies` | Tag lookups and the replacement ladder. |
| `PerkEventHandlers` | Existing per-hit hook; its node-count scaling is replaced by a `ThreatService` call. |
| `KindredsSettingsScreen` | The new settings section. |

Each has one job and a narrow interface; `MobScaler` and `ThreatEvidence` both depend on
`ThreatService` and not on each other.

---

## 9. Phases

Each phase is playable and verified before the next begins.

**Phase 1 — the number and the per-player effects.**
`ThreatService`, `ThreatEvidence`, persistence and sync, damage and detection, XP reward scaling, the
settings section and the curve, the threat readout in the UI. Delivers "the world answers as you
grow" end to end.

**Phase 2 — weight and reward.**
`MobScaler` health scaling with group resolution, `EliteMobs`, elite loot. Delivers the part that
makes higher threat exciting rather than grindy.

**Phase 3 — composition.**
`ScaledFamilies` replacement ladder. Smallest phase, and last because it changes what a player meets
rather than how it behaves.

---

## 10. Non-goals

- **Spawn density.** Explicitly cut. It needs spawner surgery, it is the classic cause of lag, it
  fights the mob cap and other mods, and it is the least interesting axis — "a cave troll instead of
  a zombie" beats "six zombies instead of three".
- **Scaling bosses.** Out of scope; they are hand-tuned encounters.
- **Player-versus-player scaling.** Threat never affects what players do to each other.
- **Difficulty for its own sake.** Every point of added danger must come with added reward.

---

## 11. Testing

- **Unit** — the threat maths: prior weighting, the high-water decay, the competence bounds, the
  floor (assert that a long run of deaths cannot push threat below `0.75 × prior`), curve exponents,
  and group resolution with 1, 2 and 5 players.
- **Gametest** — a scaled mob hits a fresh player and a veteran differently in the same world; an
  elite spawns, is named, and drops its bonus; a replacement respects its family ladder; the settings
  section renders without collision at GUI scales 1–4.
- **Doctor** — a new check: every family tag resolves, every replacement target exists, and the
  configured caps are within their attribute clamps (the same clamp check that already covers traits).
- **By hand** — this is a tuning loop and cannot be settled by reasoning alone. Phase 1 needs real
  play before phase 2 begins.
