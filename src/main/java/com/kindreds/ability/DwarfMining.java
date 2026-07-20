package com.kindreds.ability;

import com.kindreds.data.ability.PerkDef;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The <b>Dwarf mining</b> block-break arts, hung off the existing {@code PlayerBlockBreakEvents.AFTER}
 * hook in {@link PerkEventHandlers}:
 * <ul>
 *   <li><b>vein_miner</b> ({@code max}) - breaking one ore breaks the whole connected vein, dropping
 *       each with your tool's own fortune/silk-touch. The Dwarf tunnels a seam out in one go.</li>
 *   <li><b>miners_rhythm</b> ({@code amplifier}) - each ore struck grants a short Haste; the hammer
 *       finds its rhythm in the deep.</li>
 * </ul>
 * Vein blocks are removed with {@link net.minecraft.world.World#removeBlock} (which does <i>not</i>
 * re-fire the break event), and a re-entrancy guard makes doubly sure the flood-fill never recurses.
 */
public final class DwarfMining {
    private DwarfMining() {
    }

    private static final ThreadLocal<Boolean> VEINING = ThreadLocal.withInitial(() -> false);

    /** Called for every block a player breaks (see {@link PerkEventHandlers}). Cheap no-op for
     * non-Dwarves / non-ores. */
    public static void onBlockBroken(ServerWorld world, ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (VEINING.get() || !state.isIn(ConventionalBlockTags.ORES)) {
            return;
        }
        // Miner's rhythm: a short Haste for striking ore.
        List<PerkDef> rhythm = PerkService.perksOfType(player, "miners_rhythm");
        if (!rhythm.isEmpty()) {
            int amp = 0;
            for (PerkDef p : rhythm) {
                amp = Math.max(amp, Math.round(p.param("amplifier", 0f)));
            }
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 120, amp, false, false, true));
        }
        // Vein-miner: break the connected seam of the same ore.
        List<PerkDef> vein = PerkService.perksOfType(player, "vein_miner");
        if (vein.isEmpty()) {
            return;
        }
        int cap = 0;
        for (PerkDef p : vein) {
            cap = Math.max(cap, Math.round(p.param("max", 16f)));
        }
        if (cap <= 0) {
            return;
        }
        VEINING.set(true);
        try {
            breakVein(world, player, pos, state.getBlock(), cap);
        } finally {
            VEINING.set(false);
        }
    }

    private static void breakVein(ServerWorld world, ServerPlayerEntity player, BlockPos origin, Block target, int cap) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(origin.toImmutable());
        enqueueNeighbours(origin, queue, seen);
        int broken = 0;
        while (!queue.isEmpty() && broken < cap) {
            BlockPos p = queue.poll();
            BlockState st = world.getBlockState(p);
            if (st.getBlock() != target) {
                continue;
            }
            BlockEntity be = world.getBlockEntity(p);
            world.removeBlock(p, false);
            // Drop with the player's tool context so Fortune / Silk Touch are honoured.
            Block.dropStacks(st, world, p, be, player, player.getMainHandStack());
            world.spawnParticles(ParticleTypes.CRIT, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 3,
                    0.25, 0.25, 0.25, 0.0);
            broken++;
            enqueueNeighbours(p, queue, seen);
        }
    }

    /** 26-connected flood: an ore counts as part of the vein if it touches on a face, edge, or corner. */
    private static void enqueueNeighbours(BlockPos centre, Deque<BlockPos> queue, Set<BlockPos> seen) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos n = centre.add(dx, dy, dz);
                    if (seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
    }
}
