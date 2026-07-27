package com.kindreds;

import com.kindreds.ability.BirthTraitService;
import com.kindreds.ability.CurseContextService;
import com.kindreds.ability.PerkEventHandlers;
import com.kindreds.ability.PerkService;
import com.kindreds.ability.RacialNatureService;
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
        // Same lazy-static-final hazard, same fix, for the per-mob mark (Task 7 found this while
        // wiring the doctor's "MobMark.KEY registered" check: nothing before this line forced
        // MobMark to load, so a server restarting with already-scaled/elite mobs on disk would have
        // silently dropped every kindreds:mob_mark tag on the very first chunk load of a fresh
        // session - see MobMark#init()'s javadoc).
        com.kindreds.threat.MobMark.init();

        KindredsRegistries.register();

        PayloadTypeRegistry.playS2C().register(SyncKindredDataS2C.ID, SyncKindredDataS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(UnlockResultS2C.ID, UnlockResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(com.kindreds.network.SyncConfigS2C.ID,
                com.kindreds.network.SyncConfigS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(com.kindreds.network.SyncDeedsS2C.ID,
                com.kindreds.network.SyncDeedsS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(com.kindreds.network.SyncInscriptionsS2C.ID,
                com.kindreds.network.SyncInscriptionsS2C.CODEC);
        com.kindreds.network.SetDifficultyC2S.registerServerHandler();
        com.kindreds.network.SetConfigFlagC2S.registerServerHandler();
        com.kindreds.network.SetMenaceC2S.registerServerHandler();
        com.kindreds.network.SetConfigValueC2S.registerServerHandler();
        com.kindreds.network.RequestInscriptionsC2S.registerServerHandler();
        RequestUnlockC2S.registerServerHandler();
        ActivateAbilityC2S.registerServerHandler();
        SetVisionLensC2S.registerServerHandler();
        OpenTreeC2S.registerServerHandler();
        RespecC2S.registerServerHandler();
        com.kindreds.network.TakeBargainC2S.registerServerHandler();

        KindredsCommand.register();

        // ThreatMath is deliberately Minecraft-free, so it cannot read the config itself. Installing a
        // supplier - rather than pushing a value on every config change - means there is exactly one
        // wiring point and no cached copy anywhere that could serve a stale ceiling after an operator
        // moves it from the rules screen mid-session.
        com.kindreds.threat.ThreatMath.competenceMaxSource(
                () -> CONFIG == null ? com.kindreds.threat.ThreatMath.COMPETENCE_MAX_DEFAULT
                        : CONFIG.maxCompetence);

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
        BirthTraitService.register();
        RacialNatureService.register();
        PerkService.register();
        com.kindreds.progression.RenownService.register();
        com.kindreds.ability.CorruptionService.register();
        PerkEventHandlers.register();
        DeathHandler.register();
        com.kindreds.threat.ThreatService.register();
        com.kindreds.threat.ThreatEvidence.register();
        com.kindreds.threat.MobScaler.register();
        com.kindreds.threat.EliteMobs.register();

        // Push each player's server-authoritative skill data to their own client as soon as their
        // play session is ready, so client-side UI/HUD has real data from the very first tick
        // rather than the empty default.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Before the first sync, so the client is told the rescued totals rather than the
            // stranded ones. Naturally idempotent - see StrandedXpMigration.
            com.kindreds.progression.StrandedXpMigration.run(handler.player);
            SyncKindredDataS2C.sendTo(handler.player);
            // The client needs the server's rules to display them (settings screen, soft cap).
            com.kindreds.network.SyncConfigS2C.sendTo(handler.player);
            // What each Great Deed asks for. Static datapack data, so once is enough.
            com.kindreds.network.SyncDeedsS2C.sendTo(handler.player);
        });

        LOGGER.info("[Kindreds] initialized");
    }

    /** Hands every player a Kindred Codex the first time they join (tracked by a persistent command
     * tag, so it isn't re-given after they use up, drop, or store it). */
}
