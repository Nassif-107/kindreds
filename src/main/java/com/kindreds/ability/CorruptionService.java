package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * <b>The Bargain</b>: power now, paid for forever.
 *
 * <p>Great Deeds ({@link com.kindreds.progression.RenownService}) are the honourable way to widen the
 * tree-wide point cap. This is the other way, and it is the oldest story in Middle-earth: the Nine were
 * kings, and they accepted gifts. Taking the Bargain grants {@link #BARGAIN_PERCENT}% more of your tree
 * <b>immediately</b>, and costs {@link #HEALTH_PRICE} points of maximum health - permanently. It cannot
 * be undone, refused later, or respec'd away; that irreversibility is the whole mechanic. A price you
 * can return is not a price.
 *
 * <p>Deliberately gated on already being at your ceiling: the Bargain must be a decision made by
 * someone who has felt the wall, not a level-one power-up.
 *
 * <p>{@code corruption} on {@link KindredData} - reserved since the first version and unused until now -
 * is the store: {@code 0} = untouched, {@code 1} = bargained.
 */
public final class CorruptionService {
    private CorruptionService() {
    }

    /** Cap widening, in percentage points of the player's own tree. */
    public static final int BARGAIN_PERCENT = 10;

    /** Maximum health surrendered - two hearts, felt in every fight for the rest of the save. */
    public static final double HEALTH_PRICE = 4.0;

    private static final Identifier PRICE_MODIFIER_ID = Identifier.of(Kindreds.MOD_ID, "corruption_price");

    private static int tickCounter;

    /**
     * The base Middle-earth mod calls {@code clearModifiers()} + {@code setBaseValue()} on max_health
     * whenever it (re)applies a race - on join, on respawn, and on a dimension change - which strips
     * our price modifier. Rather than mirror all three hooks, the sweep below simply re-asserts it
     * whenever it is missing. Idempotent, and it can never double-apply: an attribute holds at most one
     * modifier per id.
     */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(CorruptionService::onEndTick);
    }

    private static void onEndTick(MinecraftServer server) {
        if (++tickCounter % 40 != 0) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (KindredAttachment.get(player).corruption() > 0) {
                applyPrice(player);
            }
        }
    }

    /** Whether this player has taken the Bargain. */
    public static boolean hasBargained(KindredData data) {
        return data != null && data.corruption() > 0;
    }

    /** The cap widening the Bargain has bought, in percentage points. */
    public static int bonusPercent(KindredData data) {
        return hasBargained(data) ? BARGAIN_PERCENT : 0;
    }

    /**
     * Takes the Bargain on {@code player}'s behalf. Returns {@code false} (changing nothing) if they
     * have already taken it - every other precondition is the caller's to check, since only the packet
     * handler can see the player's tree.
     */
    public static boolean takeBargain(ServerPlayerEntity player) {
        KindredData data = KindredAttachment.get(player);
        if (hasBargained(data)) {
            return false;
        }
        data.setCorruption(1);
        applyPrice(player);
        // Pay the price immediately rather than leaving the player standing at more health than they
        // now have a maximum for.
        player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));

        player.sendMessage(Text.translatable("kindreds.corruption.taken", BARGAIN_PERCENT)
                .formatted(Formatting.DARK_RED), false);
        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN,
                SoundCategory.PLAYERS, 0.35f, 0.6f);
        // Word spreads. A bargain struck in secret is only half the story.
        if (player.getServer() != null) {
            player.getServer().getPlayerManager().broadcast(
                    Text.translatable("kindreds.corruption.broadcast", player.getDisplayName())
                            .formatted(Formatting.DARK_RED), false);
        }
        Kindreds.LOGGER.info("[Kindreds] {} took the Bargain (+{}% cap, -{} max health)",
                player.getGameProfile().getName(), BARGAIN_PERCENT, HEALTH_PRICE);
        return true;
    }

    /** Adds the max-health price modifier if it isn't already on the player. */
    private static void applyPrice(ServerPlayerEntity player) {
        EntityAttributeInstance attribute = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (attribute == null || attribute.getModifier(PRICE_MODIFIER_ID) != null) {
            return;
        }
        attribute.addPersistentModifier(new EntityAttributeModifier(
                PRICE_MODIFIER_ID, -HEALTH_PRICE, EntityAttributeModifier.Operation.ADD_VALUE));
        player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
    }
}
