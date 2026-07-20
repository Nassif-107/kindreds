# Uruk-hai — Skill-Tree Redesign (discipline by discipline, no filler)

**Identity:** Saruman's fighting Uruk-hai (and Sauron's black Uruks) — big, brutally strong, tireless,
savage, bred for war under the White Hand. Unlike common orcs they **bear the sun** (birth trait: +45%
attack in daylight, night-vision, no sun-weakness) but are **slow** (−15% attack speed). Heavy shock
troops: raw melee power, blood-frenzy, cannibal healing, and strength in the horde. The bruiser of the
roster — Elf = ranged/mobile, Dwarf = tank/craft, Human = duelist/leader, **Uruk = relentless brute**.

**4 disciplines (like Dwarf):**
1. **Combat** — The Fighting Uruk-hai: scimitar power, savage cleave, blood-frenzy, cannibal heal, the
   Death March (thrive in the sun). *(preserves `blood_frenzy`, `death_march`)*
2. **Leadership** — Warlord of Isengard: the horde (war_pack strength-in-numbers), driving march,
   White Hand command auras. *(preserves `warlord_of_isengard`, `whispers_of_saruman`)*
3. **Smithing** — Forges of Isengard: crude heavy war-gear, armor of the White Hand.
4. **Shadow** — Servants of the Shadow: terror & dread on the foe, cruel strikes, fearlessness, the
   sorcery-bred savagery of Isengard.

**Engine:** all reuse — war_pack, heal_on_kill, strike_effect, bane, lifesteal, ally_aura, blood_frenzy
(dread nova), savage_swing (new HANDLERS entry → shockwave cleave), contextual daylight/low_health boons.
The orc-kin template: strength-in-numbers, savagery, sun-behaviour. Orc/Snaga/Goblin reuse it after.

## Order & status
1. **Combat** — ✅ built — The Fighting Uruk-hai. ← building
2. **Leadership** — Warlord of Isengard.
3. **Smithing** — Forges of Isengard.
4. **Shadow** — Servants of the Shadow.
