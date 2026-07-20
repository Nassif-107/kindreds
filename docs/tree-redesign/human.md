# Human — Skill-Tree Redesign (discipline by discipline, no filler)

**Identity:** the Race of Men — versatile, ambitious, and mortal, but graced with the Gift of Men:
freedom to shape their own fate, and defiance in the face of doom. Kings and captains (Aragorn,
Gondor), horse-lords (Rohan), and Rangers of the North (the Dúnedain). Neither the Elf's archery nor
the Dwarf's stone — the Human is the **duelist and the leader**, strong in valour, rally, and cavalry.

**Consolidated to 5 deep disciplines** (was 7 thin ones):
1. **Combat** — Valour & the Blade: swordsmanship, Gift-of-Men defiance (low-health second wind),
   Andúril the Flame of the West, bane of orcs/trolls/undead. *(preserves `desperate_valour`)*
2. **Leadership** — Kingship: rally auras (self + allies), Hands of the King (healing), war-horn
   (rally + fear), banners. *(preserves `hands_of_the_king`)*
3. **Archery** — Bowmen of Gondor & the Ranger's bow. *(preserves `captain_of_the_bowmen`)*
4. **Beast-lore** — Horse-lords of Rohan: war-steeds, the Ride of the Rohirrim (mounted charge).
5. **Survival** — Rangers of the North: Dúnedain endurance, tracking, dawn/dusk vigor, wilderness.

(Song & Smithing dropped from Human — Elf owns song, Dwarf owns smithing; their good bits fold into
Leadership morale and Combat soldiery.)

**New engine to build:**
- `anduril` handler — the Flame of the West: frightens nearby foes (Weakness/Slowness), burns undead.
- `hands_of_the_king` handler — kingsfoil healing: heal + cure self & allies.
- `war_horn` handler — rally self+allies (Strength/Resistance) + fear foes.
- `ride_of_the_rohirrim` handler — mounted charge: speed + knock back & hurt nearby foes.
- Reuse: attack_damage/speed, ally_aura (self+allies now), bane, heal_on_kill, low_health boons.

## Discipline order & status
1. **Combat** — ✅ built — Valour & the Blade. ← building
2. **Leadership** — ✅ built — Kingship.
3. **Archery** — ✅ built — Bowmen.
4. **Beast-lore** — ✅ built — Horse-lords.
5. **Survival** — ✅ built — Rangers of the North.
