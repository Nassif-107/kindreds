package com.kindreds.client;

import com.kindreds.client.screen.KindredCodexScreen;
import com.kindreds.playerdata.ClientKindredData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

/**
 * Client-only entry point for opening the Kindred Codex from the {@link com.kindreds.item.CodexItem}.
 * Isolated behind {@code @Environment(CLIENT)} so it is stripped on dedicated servers; the item only
 * ever calls it inside a {@code world.isClient} branch (see that class's javadoc).
 */
@Environment(EnvType.CLIENT)
public final class ClientCodexOpener {
    private ClientCodexOpener() {
    }

    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new KindredCodexScreen(ClientKindredData.INSTANCE, null));
    }
}
