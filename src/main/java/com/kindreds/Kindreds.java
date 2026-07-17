package com.kindreds;

import com.kindreds.data.KindredsRegistries;
import com.kindreds.network.RequestUnlockC2S;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.network.UnlockResultS2C;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Kindreds implements ModInitializer {
    public static final String MOD_ID = "kindreds";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        KindredsRegistries.register();

        PayloadTypeRegistry.playS2C().register(SyncKindredDataS2C.ID, SyncKindredDataS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(UnlockResultS2C.ID, UnlockResultS2C.CODEC);
        RequestUnlockC2S.registerServerHandler();

        // Push each player's server-authoritative skill data to their own client as soon as their
        // play session is ready, so client-side UI/HUD has real data from the very first tick
        // rather than the empty default.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SyncKindredDataS2C.sendTo(handler.player));

        LOGGER.info("[Kindreds] initialized");
    }
}
