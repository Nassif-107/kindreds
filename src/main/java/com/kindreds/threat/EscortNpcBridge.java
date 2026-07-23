package com.kindreds.threat;

import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.sevenstars.middleearth.entity.npcs.NpcEntity;

/**
 * The <b>only</b> class in the escort path allowed to reference base Middle-earth mod types - reached
 * only after {@link EscortNpcSupport} has confirmed the base mod is loaded (same soft-dependency
 * isolation as {@link MiddleEarthFoesBridge}; see its javadoc for the full rationale).
 *
 * <h2>Why this exists</h2>
 * An escort is spawned with {@code EntityType.spawn(leader.getType(), ...)}, but the base mod's
 * {@code NpcEntity} does <b>not</b> override vanilla {@code initialize()} - it fills in its faction,
 * rank and gear from {@code NpcData}, assigned externally by the mod's own spawner (or, on its first
 * tick, re-rolled <i>by location</i> via {@code tryToInitializeData}). A raw-spawned escort would
 * therefore be a different faction than its leader, or blank. Stamping the leader's exact
 * {@code getNpcDataIdentifier()} onto the escort and initialising it makes the escort a true copy of
 * its leader instead.
 *
 * <p>API confirmed by {@code javap} against the real jar: {@code NpcEntity.getNpcDataIdentifier()}
 * (public, nullable {@link Identifier}), {@code NpcEntity.setNpcData(Identifier)} and
 * {@code NpcEntity.initializeForCurrentNpcData()} (both public, void).
 */
final class EscortNpcBridge {
    private EscortNpcBridge() {
    }

    /** @return true if this was a base-mod NPC pair (handled); false for anything else, so the caller
     * knows a vanilla escort needs no special treatment. */
    static boolean copyNpcIdentity(Entity escort, Entity leader) {
        if (escort instanceof NpcEntity escortNpc && leader instanceof NpcEntity leaderNpc) {
            Identifier dataId = leaderNpc.getNpcDataIdentifier();
            if (dataId != null) {
                escortNpc.setNpcData(dataId);
                escortNpc.initializeForCurrentNpcData();
            }
            return true;
        }
        return false;
    }
}
