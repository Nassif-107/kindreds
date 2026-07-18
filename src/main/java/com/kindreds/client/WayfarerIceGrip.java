package com.kindreds.client;

import com.kindreds.playerdata.ClientKindredData;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * "Wayfarer — sure on any ground": Men do not slide on ice. Implemented as a client tick that bleeds
 * off the excess horizontal momentum ice grants, approximating normal ground friction, rather than a
 * mixin into the obfuscated movement code (which would risk a hard crash if a mapping shifted).
 *
 * <p>Client-only and local-player-only: player horizontal movement is client-authoritative, so
 * damping the local player's velocity here is the clean, low-risk place to remove the slide. Gated on
 * the mirrored race id ({@code middle-earth:human}).
 */
@Environment(EnvType.CLIENT)
public final class WayfarerIceGrip {
    private WayfarerIceGrip() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity player = client.player;
            if (player == null || client.world == null || !player.isOnGround()) {
                return;
            }
            KindredData data = ClientKindredData.INSTANCE;
            Identifier race = data != null ? data.race() : null;
            if (race == null || !race.getPath().equals("human")) {
                return;
            }
            BlockState below = client.world.getBlockState(player.getBlockPos().down());
            if (isIcy(below.getBlock())) {
                Vec3d v = player.getVelocity();
                // ~normal ground friction: shed the ice momentum so a Man keeps sure footing.
                player.setVelocity(v.x * 0.6, v.y, v.z * 0.6);
            }
        });
    }

    private static boolean isIcy(Block block) {
        return block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE
                || block == Blocks.FROSTED_ICE;
    }
}
