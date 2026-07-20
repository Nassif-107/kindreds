# Dwarf — Skill-Tree Redesign (discipline by discipline, no filler)

**Identity:** Children of Aulë — deep-delving miners, peerless smiths, stone-hard and stout,
resistant to fire and the Shadow, doughty axe-and-hammer warriors, makers of runes, and greedy for
mithril and gold. They thrive in the deep places and hold long grudges (bane of orcs & trolls).

**Disciplines (4):** mining, smithing, combat, runecraft.

**Reused engine (already built):** mining_fortune, mining_efficiency, bane, heal_on_kill, lifesteal,
thorns, war_pack, ally_aura, evasion, strike_effect, contextual boons (`underground`, `low_health`,
`darkness`…), armor/toughness/knockback/burning_time attributes, durins_wrath shockwave.

**New engine to build:**
- `vein_miner` (mining) — breaking an ore breaks the connected vein (capped). ✅ signature
- `miners_rhythm` (mining) — brief Haste after breaking stone/ore.
- `mend_gear` (smithing) — equipped gear slowly self-repairs (Aulë's master-craft).
- repair active (smithing) — a smith's touch that mends held/worn gear on cast.
- rune-ward active handlers (runecraft) — give durins_ward/aule_legacy real world-effects.

## Discipline order & status
1. **Mining** — ✅ built — delver's fortune, tunneler (vein-mine), deep-dweller (thrive underground). ← building
2. **Smithing** — ✅ built — master-craft, self-repairing gear, temper, mithril.
3. **Combat** — ✅ built — axe/hammer, shield-wall, Dwarf-fury (low-health rage), bane of orcs & trolls.
4. **Runecraft** — ✅ built — rune-magic: wards, Durin's power, Khazad-dûm.
