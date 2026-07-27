package com.kindreds.network;

import com.google.gson.Gson;
import com.kindreds.Kindreds;
import com.kindreds.config.KindredsConfig;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * S2C: the server's difficulty/rule settings, so the client can <b>display</b> them (the settings
 * screen, and any UI that needs to know the soft cap). The client previously had no idea what the
 * server was configured to, which made an in-game settings screen impossible.
 *
 * <p>Carried as a small JSON blob rather than a ten-field codec: these are display values that change
 * rarely, and one string keeps the payload trivial to extend without a codec rewrite every time a
 * setting is added.
 *
 * <p>This is <b>display data only</b>. Nothing the client does with it grants any authority - every
 * change still goes through {@link SetDifficultyC2S}, which re-checks operator permission server-side.
 */
public record SyncConfigS2C(String json) implements CustomPayload {
    public static final CustomPayload.Id<SyncConfigS2C> ID =
            new CustomPayload.Id<>(Identifier.of(Kindreds.MOD_ID, "sync_config"));

    public static final PacketCodec<RegistryByteBuf, SyncConfigS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SyncConfigS2C::json,
            SyncConfigS2C::new);

    private static final Gson GSON = new Gson();

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** The subset of the server config the client is allowed to see and show. {@code scalingCurve}
     * is carried as its name, matching {@code difficulty}/{@code death} - the client only ever
     * displays it via a lang key, never re-derives behaviour from it. */
    public record View(String difficulty, double xpRate, String death, double deathPercent, int softCap,
                       int capPercent, int respecCost, boolean enemyScaling, boolean birthTraits,
                       boolean curses, boolean crossTraining, boolean vision, boolean grantXp,
                       String scalingCurve, int maxDamageBonus, int xpBonus,
                       int maxHealthBonus, int eliteChance, int escortChance,
                       String menace, int groupScalingPercent, int adaptiveStrength,
                       double maxCompetence, double dimensionMultiplierMiddleEarth,
                       double dimensionMultiplierOverworld,
                       int armorBonus, int knockbackResistBonus, int mobSpeedBonus,
                       int followRangeBonus) {
    }

    public static View snapshot() {
        KindredsConfig c = Kindreds.CONFIG;
        return new View(String.valueOf(c.difficulty), c.xpRateGlobal, String.valueOf(c.deathPenalty),
                c.deathPercent, c.pointSoftCap, c.pointCapPercent, c.respecCost, c.enableEnemyScaling,
                c.enableBirthTraits, c.enableCurses, c.allowCrossTraining, c.enableVision,
                c.allowGrantXp, String.valueOf(c.scalingCurve), c.maxDamageBonus, c.xpBonus,
                c.maxHealthBonus, c.eliteChance, c.escortChance,
                String.valueOf(c.menace), c.groupScalingPercent, c.adaptiveStrength,
                c.maxCompetence, c.dimensionMultiplierMiddleEarth, c.dimensionMultiplierOverworld,
                c.armorBonus, c.knockbackResistBonus, c.mobSpeedBonus, c.followRangeBonus);
    }

    /**
     * The value of one {@link com.kindreds.config.RuleDial} as this view carries it.
     *
     * <p>Here rather than in the screen so the wire field and the row are matched in exactly one
     * place: a dial added to the enum without a case here fails to compile, instead of silently
     * rendering as a zero.
     */
    public static double dialValue(View v, com.kindreds.config.RuleDial dial) {
        return switch (dial) {
            case MAX_HEALTH_BONUS -> v.maxHealthBonus();
            case MAX_DAMAGE_BONUS -> v.maxDamageBonus();
            case ARMOR_BONUS -> v.armorBonus();
            case KNOCKBACK_RESIST_BONUS -> v.knockbackResistBonus();
            case MOB_SPEED_BONUS -> v.mobSpeedBonus();
            case FOLLOW_RANGE_BONUS -> v.followRangeBonus();
            case ELITE_CHANCE -> v.eliteChance();
            case ESCORT_CHANCE -> v.escortChance();
            case GROUP_SCALING_PERCENT -> v.groupScalingPercent();
            case XP_BONUS -> v.xpBonus();
            case ADAPTIVE_STRENGTH -> v.adaptiveStrength();
            case MAX_COMPETENCE -> v.maxCompetence();
            case DIMENSION_MULTIPLIER_MIDDLE_EARTH -> v.dimensionMultiplierMiddleEarth();
            case DIMENSION_MULTIPLIER_OVERWORLD -> v.dimensionMultiplierOverworld();
        };
    }

    public static void sendTo(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new SyncConfigS2C(GSON.toJson(snapshot())));
    }

    /** Settings are server-wide, so a change is pushed to everyone online. */
    public static void sendToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            sendTo(p);
        }
    }

    public static View parse(String json) {
        try {
            return GSON.fromJson(json, View.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
