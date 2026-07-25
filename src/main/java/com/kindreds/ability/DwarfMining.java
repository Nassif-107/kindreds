package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.data.ability.PerkDef;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.recipe.BlastingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The <b>Dwarf mining</b> block-break arts, hung off {@code PlayerBlockBreakEvents.AFTER} in
 * {@link PerkEventHandlers}. All are cheap no-ops for non-Dwarves / non-ores.
 * <ul>
 *   <li><b>vein_miner</b> ({@code max}) - one strike takes the whole connected ore seam.</li>
 *   <li><b>miners_rhythm</b> ({@code amplifier}) - a short Haste for striking ore.</li>
 *   <li><b>auto_smelt</b> - ores you mine come up already smelted (recipe-driven, so it covers modded
 *       metals too - tried as both a furnace and a blast-furnace recipe, since the base mod's tin,
 *       lead, silver and mithril only register a blasting recipe for their raw ore). Gems and the
 *       like, which have no smelting recipe of either kind, are left untouched.</li>
 *   <li><b>ore_magnet</b> - mined drops fly straight into your pack, none left on the ground.</li>
 *   <li><b>prospector_xp</b> ({@code xp}) - the Dwarf knows the worth of stone: bonus experience.</li>
 * </ul>
 * The primary block's vanilla drops (already spawned when this fires) are handled by a tight local
 * scan of just-dropped items; vein blocks are fully controlled inline. A re-entrancy guard makes sure
 * the flood-fill never recurses.
 */
public final class DwarfMining {
    private DwarfMining() {
    }

    private static final ThreadLocal<Boolean> VEINING = ThreadLocal.withInitial(() -> false);

    /**
     * Every ore this kit answers to: the Fabric convention tag ({@code c:ores}, which covers vanilla
     * ore blocks) plus the base mod's own tin/lead/silver/mithril ore tags, referenced optionally so
     * a world without the base mod loads this tag as just {@code c:ores}. The base mod ships those
     * four metals under its own {@code middle-earth:*_ores} tags only - none of them are in
     * {@code c:ores} - so checking the convention tag alone silently excluded all four (the entire
     * vein-miner/auto-smelt/ore-magnet/miner's-rhythm/prospector kit did nothing for them).
     *
     * <p>Also carries the base mod's adamant, emerald, ruby and sapphire ores by explicit block id:
     * those seven gem ores are in <b>no</b> ore tag at all, neither the convention one nor any of the
     * base mod's own, so a tag union alone would still have missed them.
     *
     * <p>Public, and the single gate for every mining perk including {@code mining_fortune} over in
     * {@link PerkEventHandlers}. Fortune used to check {@code c:ores} instead, which silently exempted
     * 25 of the base mod's 47 ores - every metal and gem a Dwarf actually digs for.
     */
    public static final TagKey<Block> DWARF_ORES = TagKey.of(RegistryKeys.BLOCK, Identifier.of(Kindreds.MOD_ID, "dwarf_ores"));

    public static void onBlockBroken(ServerWorld world, ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (VEINING.get() || !state.isIn(DWARF_ORES)) {
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
        // Prospector's bounty: bonus experience from the ore.
        List<PerkDef> prospect = PerkService.perksOfType(player, "prospector_xp");
        if (!prospect.isEmpty()) {
            int xp = 0;
            for (PerkDef p : prospect) {
                xp = Math.max(xp, Math.round(p.param("xp", 3f)));
            }
            if (xp > 0) {
                ExperienceOrbEntity.spawn(world, Vec3d.ofCenter(pos), xp);
            }
        }
        boolean autoSmelt = !PerkService.perksOfType(player, "auto_smelt").isEmpty();
        boolean oreMagnet = !PerkService.perksOfType(player, "ore_magnet").isEmpty();
        // The primary block's drops already exist in the world; transform them in place.
        if (autoSmelt || oreMagnet) {
            processPrimaryDrops(world, player, pos, autoSmelt, oreMagnet);
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
            breakVein(world, player, pos, state.getBlock(), cap, autoSmelt, oreMagnet);
        } finally {
            VEINING.set(false);
        }
    }

    /** Transform the freshly-dropped items from the primary block (auto-smelt / magnet). */
    private static void processPrimaryDrops(ServerWorld world, ServerPlayerEntity player, BlockPos pos,
                                            boolean autoSmelt, boolean oreMagnet) {
        Box box = new Box(pos).expand(1.5);
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, box, e -> e.getItemAge() == 0)) {
            ItemStack stack = item.getStack();
            if (autoSmelt) {
                stack = smelt(world, stack);
            }
            if (oreMagnet) {
                player.getInventory().offerOrDrop(stack);
                item.discard();
            } else {
                item.setStack(stack);
            }
        }
    }

    private static void breakVein(ServerWorld world, ServerPlayerEntity player, BlockPos origin, Block target,
                                  int cap, boolean autoSmelt, boolean oreMagnet) {
        ItemStack tool = player.getMainHandStack();
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
            // Drop with the player's tool context so Fortune / Silk Touch are honoured, then apply the
            // Dwarf's forge-craft to each drop.
            for (ItemStack drop : Block.getDroppedStacks(st, world, p, be, player, tool)) {
                ItemStack out = autoSmelt ? smelt(world, drop) : drop;
                if (oreMagnet) {
                    player.getInventory().offerOrDrop(out);
                } else {
                    Block.dropStack(world, p, out);
                }
            }
            world.spawnParticles(ParticleTypes.CRIT, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 3,
                    0.25, 0.25, 0.25, 0.0);
            broken++;
            enqueueNeighbours(p, queue, seen);
        }
    }

    /** The smelted form of {@code in} if a furnace OR a blast-furnace recipe exists (scaled to the
     * stack), else {@code in} unchanged. Recipe-driven, so modded ores smelt to their own results -
     * tried as a furnace recipe first, then a blast-furnace one, because the base mod's tin, lead,
     * silver and mithril register only the latter for their raw ore (a furnace-only lookup silently
     * left those four the one exception to "auto-smelt just works"). */
    private static ItemStack smelt(ServerWorld world, ItemStack in) {
        if (in.isEmpty()) {
            return in;
        }
        SingleStackRecipeInput input = new SingleStackRecipeInput(in);
        Optional<RecipeEntry<SmeltingRecipe>> smelting =
                world.getRecipeManager().getFirstMatch(RecipeType.SMELTING, input, world);
        ItemStack result;
        if (smelting.isPresent()) {
            result = smelting.get().value().craft(input, world.getRegistryManager());
        } else {
            Optional<RecipeEntry<BlastingRecipe>> blasting =
                    world.getRecipeManager().getFirstMatch(RecipeType.BLASTING, input, world);
            if (blasting.isEmpty()) {
                return in;
            }
            result = blasting.get().value().craft(input, world.getRegistryManager());
        }
        if (result.isEmpty()) {
            return in;
        }
        ItemStack out = result.copy();
        out.setCount(result.getCount() * in.getCount());
        return out;
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
