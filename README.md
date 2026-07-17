# Kindreds of Middle-earth

A Fabric mod (Minecraft 1.21.8, Java 21) that gives each race of the **Middle-earth** modpack its
own lore-flavored skill tree: Men, Elves, Dwarves, Hobbits, Orcs, Uruks, Snaga and Goblins each
unlock different passive/active abilities as they earn points by actually playing - fighting,
mining, sneaking, shooting, smithing, surviving, and exploring - rather than through a generic
XP-for-everything grind.

It reads your race from the base **Middle-earth** mod, but works standalone too (see
[Standalone / no base mod](#standalone--no-base-mod) below).

## What it is

Every race gets its own **skill tree**: a themed web of nodes spent on with **discipline points**,
each node granting a passive buff (attribute bonus, status effect, a "curse" drawback that comes
with a strong upside), a vision lens, or an activatable ability. Progress is entirely
**server-authoritative** - the server tracks and validates everything; the client only ever
displays what the server has told it.

## Earning points

You don't grind a single "XP bar" - each of the seven **disciplines** below levels up from doing
the matching activity, and each level grants one spendable point in that discipline:

| Discipline | Earned by |
|---|---|
| Combat | Hitting and killing hostile entities in melee |
| Archery | Landing arrow hits on entities/blocks |
| Mining | Breaking blocks (scaled by block hardness) |
| Stealth | Sustained sneaking, and sneak-kills |
| Smithing | Taking crafted items out of the crafting result slot |
| Survival | Eating food, and discovering a biome for the first time |
| Lore | Completing advancements |

Race matters: each race is scaled differently per discipline (an Elf earns Archery xp faster than
a Dwarf, a Dwarf earns Mining/Smithing xp faster than an Elf, and so on) via a data-driven
`race_scaling` table, so playing "in character" is naturally the fastest way to grow that race's
tree. Xp is only ever awarded once your race is known (the base mod is installed and you've picked
a race) and never in Creative/Spectator mode.

Discovered-biome xp is tracked **per player, persistently** - once you've been credited for a
biome you won't be credited again for it, even after logging out, reconnecting, or a server
restart (this closes a relog-macro farming exploit from an earlier build).

## Opening the tree

Press **K** (rebindable in Controls -> Key Binds -> Kindreds) to open your skill tree. It shows
your race's themed layout, each discipline's current level/points, and every node - locked,
unlockable, or already owned - with a tooltip explaining its cost, prerequisites, and effect.
Click an unlockable node to spend points on it.

Two other (default-unbound, opt-in) keybinds:
- **Use ability** - activates your first unlocked active ability (only fires if you've bound it).
- **Cycle vision** - swaps your active vision lens among the ones you've unlocked (currently
  Keen Sight and Stone Sense - see each node's tooltip for what it reveals).

Spent points can be refunded via a **respec**, either from the tree screen (costs an
admin-configurable item, default one Amethyst Shard) or, for admins/testing, `/kindreds respec`.

## Vision lenses

Certain nodes unlock **vision lenses** - toggleable rendering modes (via the Cycle Vision keybind)
that outline or highlight things in the world (e.g. ore/valuable blocks through terrain, or nearby
mobs) once unlocked. Lenses are Iris-shaderpack-aware and automatically disable their outline
rendering (falling back to a HUD-only tint) when a shaderpack that would conflict is detected.

## Config

Server-side config lives at `config/kindreds-server.json` (created with defaults on first server
start, and reloadable in-game via `/kindreds reload`, op level 2):

| Key | Default | What it controls |
|---|---|---|
| `deathPenalty` | `KEEP` | What happens to a player's progress on death - see [Death penalty](#death-penalty) |
| `deathPercent` | `0.25` | Fraction of progress lost under `LOSE_PERCENT` |
| `xpRateGlobal` | `1.0` | Global xp multiplier on top of race scaling |
| `pointSoftCap` | `60` | Soft cap on total discipline points (informational/tuning) |
| `respecItem` | `minecraft:amethyst_shard` | Item consumed by the player-facing respec |
| `respecCost` | `1` | How many of `respecItem` a respec costs |
| `enableVision` | `true` | Master toggle for the vision-lens framework |
| `enableCurses` | `true` | Master toggle for curse-type nodes (drawback-for-upside) |
| `allowCrossTraining` | `true` | Whether non-exclusive-group nodes can be freely mixed |
| `enableEnemyScaling` | `false` | Reserved for future difficulty scaling |

Three built-in **presets** (`/kindreds` admin tooling or hand-editing the config) bundle sensible
combinations of the above: `casual` (KEEP, generous xp, no curses), `normal` (LOSE_UNSPENT,
balanced), and `legendary` (LOSE_PERCENT at 50%, slower xp, enemy scaling on, no cross-training).

### Death penalty

`deathPenalty` controls what happens to a player's Kindred progress **only on an actual death**
(dying and respawning) - changing dimension never touches your progress regardless of this
setting:

- **`KEEP`** - nothing is lost.
- **`LOSE_UNSPENT`** - every discipline's xp is trimmed down to exactly what's needed to justify
  the points you've already spent on unlocked nodes; any *banked, not-yet-spent* xp is lost.
  Already-unlocked nodes are never taken away.
- **`LOSE_PERCENT`** - every discipline's xp is reduced by `deathPercent` (rounded); unlocked
  nodes are, again, never taken away.
- **`HARDCORE`** - a full wipe: xp, unlocked nodes, titles, everything resets to a blank slate.

Unlocked nodes' actual in-game effects (attribute bonuses, status effects) are also correctly
restored after a real death - Minecraft's own respawn logic drops those on death (unlike a
dimension change, where it preserves them), so the mod explicitly re-applies every node you still
own once you respawn.

## Multiplayer

Everything is **server-authoritative**: the server is the source of truth for xp, unlocked nodes,
and death-penalty math, and only pushes read-only snapshots to each player's own client. Every
player's `KindredData` is tracked independently and persists in their player data across sessions
and server restarts - there's no shared or global state between players.

## Standalone / no base mod

This mod loads standalone; the base **Middle-earth** mod is a soft dependency, not a hard one. If
it isn't installed, or a player hasn't picked a race yet, Kindreds simply doesn't award xp or
resolve a skill tree for that player until a race becomes available - nothing crashes or errors.

## Building

```
./gradlew clean test build --no-daemon
```

Produces the mod jar (and a matching sources jar) under `build/libs/`.
