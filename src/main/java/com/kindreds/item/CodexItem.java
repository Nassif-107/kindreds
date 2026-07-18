package com.kindreds.item;

import com.kindreds.client.ClientCodexOpener;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * The "Kindred Codex" book item - right-click (use) to open the {@link
 * com.kindreds.client.screen.KindredCodexScreen} almanac. Reusable indefinitely (never consumed),
 * one handed to every player on first join.
 *
 * <h2>Client/server safety</h2>
 * Opening a screen is client-only. {@link ClientCodexOpener} (annotated {@code @Environment(CLIENT)})
 * is referenced only inside the {@code world.isClient} branch, which never executes on a dedicated
 * server - the JVM resolves (and thus loads) that class lazily, on first execution of the instruction,
 * so the client-only class is never touched server-side.
 */
public class CodexItem extends Item {
    public CodexItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (world.isClient) {
            ClientCodexOpener.open();
        }
        return ActionResult.SUCCESS;
    }
}
