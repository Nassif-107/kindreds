package com.kindreds;

import com.kindreds.ability.CurseContextService;
import com.kindreds.command.KindredsCommand;
import com.kindreds.config.KindredsConfig;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.network.ActivateAbilityC2S;
import com.kindreds.network.OpenTreeC2S;
import com.kindreds.network.RequestUnlockC2S;
import com.kindreds.network.RespecC2S;
import com.kindreds.network.SetVisionLensC2S;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.network.UnlockResultS2C;
import com.kindreds.playerdata.DeathHandler;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.progression.ActivityHooks;
import com.kindreds.progression.RaceScaling;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Kindreds implements ModInitializer {
    public static final String MOD_ID = "kindreds";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Server-authoritative config, (re)loaded from {@code <configDir>/kindreds-server.json} on
     * {@code ServerLifecycleEvents.SERVER_STARTING} (see {@link #onInitialize}) and again by
     * {@code /kindreds reload} (Task 9). Defaults to a fresh {@link KindredsConfig} until then so
     * this is never null. */
    public static KindredsConfig CONFIG = new KindredsConfig();

    @Override
    public void onInitialize() {
        // Register the persistent player-data attachment FIRST, at mod-init time. Its AttachmentType
        // is a lazy static final; if left to register on first use (join-time sync), the server has
        // already loaded player NBT and dropped the unknown "kindreds:player" attachment, wiping
        // saved progress. Forcing the class to load here registers it before any world loads.
        KindredAttachment.init();

        KindredsRegistries.register();

        PayloadTypeRegistry.playS2C().register(SyncKindredDataS2C.ID, SyncKindredDataS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(UnlockResultS2C.ID, UnlockResultS2C.CODEC);
        RequestUnlockC2S.registerServerHandler();
        ActivateAbilityC2S.registerServerHandler();
        SetVisionLensC2S.registerServerHandler();
        OpenTreeC2S.registerServerHandler();
        RespecC2S.registerServerHandler();

        KindredsCommand.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            CONFIG = KindredsConfig.load(FabricLoader.getInstance().getConfigDir().resolve("kindreds-server.json"));
            LOGGER.info("[Kindreds] loaded server config (xpRateGlobal={}, deathPenalty={})",
                    CONFIG.xpRateGlobal, CONFIG.deathPenalty);
        });

        // Materialize data/kindreds/kindreds/race_scaling/*.json into RaceScaling's plain lookup table -
        // both on server start (registries are fully loaded by SERVER_STARTED) and after every
        // datapack /reload, so an authored change is picked up without a server restart.
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                RaceScaling.loadFrom(server.getRegistryManager().getOrThrow(KindredsRegistries.RACE_SCALING)));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, registries, success) -> {
            if (success) {
                RaceScaling.loadFrom(server.getRegistryManager().getOrThrow(KindredsRegistries.RACE_SCALING));
            }
        });

        ActivityHooks.register();
        CurseContextService.register();
        DeathHandler.register();

        // Push each player's server-authoritative skill data to their own client as soon as their
        // play session is ready, so client-side UI/HUD has real data from the very first tick
        // rather than the empty default.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SyncKindredDataS2C.sendTo(handler.player));

        LOGGER.info("[Kindreds] initialized");
    }
}
