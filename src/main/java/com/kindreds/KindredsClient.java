package com.kindreds;

import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.playerdata.ClientKindredData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class KindredsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Store the latest server-authoritative skill data for client-side UI/HUD to read; hop
        // onto the main client thread since network receivers otherwise run on the networking
        // thread (matches the pattern used by the Mithril Locator mod's PolicyPayload receiver).
        ClientPlayNetworking.registerGlobalReceiver(SyncKindredDataS2C.ID, (payload, context) ->
                context.client().execute(() -> ClientKindredData.INSTANCE = payload.data()));

        Kindreds.LOGGER.info("[Kindreds] client initialized");
    }
}
