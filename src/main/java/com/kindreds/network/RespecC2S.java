package com.kindreds.network;

import com.kindreds.Kindreds;
import com.kindreds.config.KindredsConfig;
import com.kindreds.progression.RespecService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.InvalidIdentifierException;

/**
 * C2S: "unlearn the old ways" - the player-facing respec button in {@code SkillTreeScreen}. Unlike
 * the admin {@code /kindreds respec} command, this one costs the server-configured item
 * ({@link KindredsConfig#respecItem} x {@link KindredsConfig#respecCost}), consumed from the
 * player's main inventory before anything is reversed. Carries no fields - the UI is expected to
 * have already shown its own confirm dialog before sending this.
 *
 * <p>Reuses {@link RespecService#reverseAll} for the actual reversal - the exact same logic the
 * admin command uses - then re-syncs and reports the outcome via {@link UnlockResultS2C} (reasons:
 * {@code "respec_ok"}, {@code "respec_item_invalid"} (server misconfigured), or
 * {@code "respec_insufficient_item"}).
 */
public record RespecC2S() implements CustomPayload {
    public static final CustomPayload.Id<RespecC2S> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "respec"));

    public static final PacketCodec<RegistryByteBuf, RespecC2S> CODEC = PacketCodec.unit(new RespecC2S());

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** Registers the payload type and its server-side handler. Call once from
     * {@link Kindreds#onInitialize()} (a common entrypoint, so this also registers the payload type
     * client-side - required for the client to be able to send it at all - matching
     * {@code RequestUnlockC2S}'s pattern). */
    public static void registerServerHandler() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);

        // Fabric API guarantees global C2S receivers run on the server thread, so it's safe to
        // mutate player/inventory state directly here (no extra context.server().execute(...) needed).
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) -> handle(context.player()));
    }

    private static void handle(ServerPlayerEntity player) {
        KindredsConfig config = Kindreds.CONFIG;
        Item costItem = resolveItem(config.respecItem);
        if (costItem == null) {
            Kindreds.LOGGER.warn("[Kindreds] respecItem '{}' in config does not resolve to a real item; "
                    + "rejecting respec for {}", config.respecItem, player.getGameProfile().getName());
            UnlockResultS2C.sendTo(player, false, "respec_item_invalid");
            return;
        }

        int cost = Math.max(0, config.respecCost);
        PlayerInventory inventory = player.getInventory();
        if (countInMain(inventory, costItem) < cost) {
            UnlockResultS2C.sendTo(player, false, "respec_insufficient_item");
            return;
        }

        removeFromMain(inventory, costItem, cost);
        RespecService.reverseAll(player);
        SyncKindredDataS2C.sendTo(player);
        UnlockResultS2C.sendTo(player, true, "respec_ok");
    }

    /** Total count of {@code item} across the player's main inventory only (not armor/offhand) -
     * deliberately not {@code Inventory.count(Item)} (which scans the whole inventory including
     * armor/offhand slots), since {@link #removeFromMain} only removes from the same main-stack
     * list - keeping both scoped identically avoids a check that passes but a removal that can't
     * actually take the full cost. */
    private static int countInMain(PlayerInventory inventory, Item item) {
        int total = 0;
        for (ItemStack stack : inventory.getMainStacks()) {
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Removes up to {@code amount} of {@code item} from the player's main inventory stacks.
     * Assumes the caller already verified (via {@link #countInMain}) that enough is present. */
    private static void removeFromMain(PlayerInventory inventory, Item item, int amount) {
        int remaining = amount;
        for (ItemStack stack : inventory.getMainStacks()) {
            if (remaining <= 0) {
                break;
            }
            if (stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.decrement(take);
                remaining -= take;
            }
        }
    }

    /** Parses {@code idString} (e.g. {@code "minecraft:amethyst_shard"}) and resolves it against the
     * item registry; returns {@code null} (rather than throwing) on a malformed id or one that
     * doesn't resolve to a registered item, so a typo'd config value degrades to "respec disabled"
     * instead of crashing the handler. */
    private static Item resolveItem(String idString) {
        try {
            Identifier id = Identifier.of(idString);
            if (!Registries.ITEM.containsId(id)) {
                return null;
            }
            return Registries.ITEM.get(id);
        } catch (InvalidIdentifierException e) {
            return null;
        }
    }
}
