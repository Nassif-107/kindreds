package com.kindreds;

import net.fabricmc.api.ClientModInitializer;

public class KindredsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Kindreds.LOGGER.info("[Kindreds] client initialized");
    }
}
