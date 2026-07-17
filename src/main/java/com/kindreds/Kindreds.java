package com.kindreds;

import com.kindreds.data.KindredsRegistries;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Kindreds implements ModInitializer {
    public static final String MOD_ID = "kindreds";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        KindredsRegistries.register();
        LOGGER.info("[Kindreds] initialized");
    }
}
