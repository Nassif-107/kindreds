package com.kindreds.vision.lens;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.sevenstars.middleearth.entity.beasts.cave_troll.CaveTrollEntity;
import net.sevenstars.middleearth.entity.beasts.trolls.TrollEntity;
import net.sevenstars.middleearth.entity.beasts.warg.WargEntity;
import net.sevenstars.middleearth.entity.npcs.NpcEntity;
import net.sevenstars.middleearth.exceptions.FactionIdentifierException;
import net.sevenstars.middleearth.resources.datas.common.DispositionType;
import net.sevenstars.middleearth.resources.datas.factions.Faction;
import net.sevenstars.middleearth.resources.datas.factions.FactionLookup;

/**
 * The <b>only</b> class in {@code com.kindreds.vision.lens} allowed to reference base Middle-earth
 * mod types - reached only after {@link VisionThreat} has confirmed the base mod is loaded (see that
 * class's javadoc for the full rationale). Mirrors the coarse, player-independent disposition check
 * of {@code com.kindreds.threat.MiddleEarthFoesBridge#isHostileFactionMobAtSpawn}, but kept separate
 * so the client vision code never depends on the scaling package.
 *
 * <p>Uses only client-resolvable base-mod API, confirmed by {@code javap} against the real jar:
 * {@code NpcEntity.getFactionIdentifier()} (public, nullable {@link Identifier}),
 * {@code FactionLookup.getFactionById(World, Identifier)} (static, throws the checked {@link
 * FactionIdentifierException} for an id the datapack doesn't recognise), and
 * {@code Faction.getDisposition()} -&gt; {@code DispositionType}. Factions are a datapack registry
 * synced to the client, so the lookup resolves from a {@code ClientWorld}; if it can't (registry not
 * yet synced), the checked exception is caught and the creature is simply not lit.
 */
final class VisionThreatBridge {
    private VisionThreatBridge() {
    }

    static boolean isHostileFactionMob(LivingEntity entity) {
        if (entity instanceof WargEntity || entity instanceof TrollEntity || entity instanceof CaveTrollEntity) {
            return true; // no friendly warg or troll; SnowTroll/StoneTroll are TrollEntity subclasses
        }
        if (entity instanceof NpcEntity npc) {
            Identifier factionId = npc.getFactionIdentifier();
            if (factionId == null) {
                return false; // not yet initialised / unknown faction
            }
            try {
                Faction faction = FactionLookup.getFactionById(entity.getWorld(), factionId);
                return faction != null && faction.getDisposition() == DispositionType.EVIL;
            } catch (FactionIdentifierException e) {
                return false;
            }
        }
        return false;
    }
}
