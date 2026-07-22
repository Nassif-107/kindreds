package com.kindreds.threat;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.sevenstars.middleearth.entity.beasts.cave_troll.CaveTrollEntity;
import net.sevenstars.middleearth.entity.beasts.trolls.TrollEntity;
import net.sevenstars.middleearth.entity.beasts.warg.WargEntity;
import net.sevenstars.middleearth.entity.npcs.NpcEntity;
import net.sevenstars.middleearth.exceptions.FactionIdentifierException;
import net.sevenstars.middleearth.resources.StateSaverAndLoader;
import net.sevenstars.middleearth.resources.datas.factions.Faction;
import net.sevenstars.middleearth.resources.datas.factions.FactionLookup;
import net.sevenstars.middleearth.resources.persistent_datas.PlayerData;

/**
 * Sibling to {@link com.kindreds.playerdata.MiddleEarthRaceBridge}: the <b>only</b> class in the
 * {@code com.kindreds.threat} package allowed to reference base Middle-earth mod types
 * ({@code net.sevenstars.middleearth.*}). See that class's javadoc for the full rationale (the base
 * mod's jar is {@code modCompileOnly} - present at compile time but not guaranteed at runtime - so
 * every reference to its types must be isolated to one class per call site, never touched unless
 * {@link MiddleEarthFoes} has already confirmed the base mod is loaded).
 *
 * <h2>Why the scope gate needs this at all</h2>
 * The base mod ships its entire army - every orc, uruk, goblin, Snaga and brigand, hostile and
 * friendly alike - as one {@code NpcEntity} class extending {@code PassiveEntity}, and its wargs and
 * trolls as a custom {@code AbstractBeastEntity} extending {@code AbstractHorseEntity} (a mount
 * lineage). None of that is a vanilla {@link net.minecraft.entity.mob.Monster}, so {@link
 * MobDanger#isInScope} would otherwise never see them.
 *
 * <h2>What's called, exactly - confirmed by {@code javap} against the real jar</h2>
 * <ul>
 *   <li>{@code NpcEntity.getFactionIdentifier()} - public, returns a nullable {@link Identifier}.
 *       ({@code getFaction()} is protected and deliberately not used here.)</li>
 *   <li>{@code FactionLookup.getFactionById(World, Identifier)} - static, throws the checked
 *       {@link FactionIdentifierException} for an id the datapack does not recognise.</li>
 *   <li>{@code Faction.isHostileToward(Identifier)} - the datapack's static per-faction-pair
 *       diplomacy table, directional and not per-player reputation.</li>
 *   <li>{@code StateSaverAndLoader.getPlayerState(PlayerEntity)} -&gt;
 *       {@code PlayerData.getFaction()} - the attacking/defending player's own faction, nullable if
 *       they never onboarded.</li>
 * </ul>
 *
 * <p>{@code NpcEntity.shouldTarget(...)} is deliberately <b>not</b> called even though its name
 * fits: its bytecode references a class from a different, optional companion mod ({@code
 * of_beasts_and_wild_things}'s {@code SnailEntity}), so calling it here would risk {@link
 * NoClassDefFoundError} whenever that companion mod is absent - and the base mod's own goals never
 * call it either, so it may simply be dead code not worth depending on.
 */
final class MiddleEarthFoesBridge {
    private MiddleEarthFoesBridge() {
    }

    /**
     * @return whether {@code entity} is one of the base mod's mobs that should count as hostile to
     * {@code player} for scaling purposes: wargs and trolls unconditionally (there is no friendly
     * warg or troll), and an {@code NpcEntity} only when its faction is hostile toward the player's
     * own faction. A factionless player (never onboarded) is treated as hostile-by-everyone, mirroring
     * the base mod's own default for that case.
     */
    static boolean isHostileBaseMob(Entity entity, ServerPlayerEntity player) {
        if (entity instanceof WargEntity || entity instanceof TrollEntity || entity instanceof CaveTrollEntity) {
            return true; // universally hostile; SnowTroll/StoneTroll are covered via TrollEntity
        }
        if (entity instanceof NpcEntity npc) {
            Identifier npcFactionId = npc.getFactionIdentifier();
            if (npcFactionId == null) {
                return false; // unknown faction: don't scale
            }
            Faction faction;
            try {
                faction = FactionLookup.getFactionById(entity.getWorld(), npcFactionId);
            } catch (FactionIdentifierException e) {
                return false; // unregistered faction id: don't scale
            }
            if (faction == null) {
                return false;
            }
            PlayerData data = StateSaverAndLoader.getPlayerState(player);
            Identifier playerFactionId = data == null ? null : data.getFaction();
            if (playerFactionId == null) {
                return true; // factionless player: hostile to all, per the base mod's own default
            }
            return faction.isHostileToward(playerFactionId);
        }
        return false;
    }
}
