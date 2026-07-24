package com.kindreds.threat;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.sevenstars.middleearth.entity.npcs.NpcEntity;
import net.sevenstars.middleearth.entity.npcs.initializer.NpcEntityInitializer;

/**
 * The <b>only</b> class in the escort path allowed to reference base Middle-earth mod types - reached
 * only after {@link EscortNpcSupport} has confirmed the base mod is loaded (same soft-dependency
 * isolation as {@link MiddleEarthFoesBridge}; see its javadoc for the full rationale).
 *
 * <h2>Why this exists</h2>
 * An escort is spawned with {@code EntityType.spawn(leader.getType(), ...)}, but the base mod's
 * {@code NpcEntity} does not gain its faction, rank and gear from a raw vanilla spawn - those come
 * from its {@code NpcData}, assigned by the mod's own initializer (or, on its first tick, re-rolled
 * <i>by location</i> via {@code tryToInitializeData}). A raw-spawned escort would therefore be a
 * different faction than its leader, or blank. Stamping the leader's NPC type onto the escort and
 * initialising it from that data makes the escort a true copy of its leader instead.
 *
 * <h2>API (Middle-earth 1.0.1, confirmed by {@code javap} against the real jar)</h2>
 * 1.0.1 renamed the NPC-data API from 1.0.0's {@code getNpcDataIdentifier}/{@code setNpcData}/
 * {@code initializeForCurrentNpcData}. The current shape:
 * <ul>
 *   <li>{@code NpcEntity.getNpcTypeIdentifier()} - public, nullable {@link Identifier} (the leader's
 *       NPC type, e.g. {@code middle-earth:brigand.thug}).</li>
 *   <li>{@code NpcEntity.prepareNpcIdentifier(Identifier)} - public void; sets the type the NPC will
 *       initialise as (writes it into the entity's {@code NpcInitializationData}).</li>
 *   <li>{@code NpcEntityInitializer.initializeNpcForCurrentData(NpcEntity, ServerWorld)} - public
 *       static void; builds the NPC (faction/rank/gear/attributes) from its prepared data - the 1.0.1
 *       analog of 1.0.0's {@code initializeForCurrentNpcData()}.</li>
 * </ul>
 */
final class EscortNpcBridge {
    private EscortNpcBridge() {
    }

    /** @return true if this was a base-mod NPC pair (handled); false for anything else, so the caller
     * knows a vanilla escort needs no special treatment. */
    static boolean copyNpcIdentity(Entity escort, Entity leader) {
        if (escort instanceof NpcEntity escortNpc && leader instanceof NpcEntity leaderNpc
                && escort.getWorld() instanceof ServerWorld serverWorld) {
            Identifier typeId = leaderNpc.getNpcTypeIdentifier();
            if (typeId != null) {
                escortNpc.prepareNpcIdentifier(typeId);
                NpcEntityInitializer.initializeNpcForCurrentData(escortNpc, serverWorld);
            }
            return true;
        }
        return false;
    }
}
