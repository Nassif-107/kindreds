package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.playerdata.RaceAccess;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import com.kindreds.mixin.HungerManagerAccessor;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side "racial nature" behaviours that can't be expressed as static {@code AbilityDef} data -
 * effect-duration shaping, sickness/cold immunity, and a sleep/weariness rhythm - all grounded in
 * Tolkien canon and gated on the player's race. Runs on a light 1-second interval.
 *
 * <ul>
 *   <li><b>Resistance to the Shadow</b> - Elves, Dwarves and Hobbits shrug off evil: harmful status
 *       effects wear off far faster on them (Dwarves are "the most resistant to the domination of
 *       Sauron"; Hobbits famously resist the Ring; Elves are more resistant than Men).</li>
 *   <li><b>Elves never sicken</b> - immune to Hunger/Nausea, and cold cannot freeze them (Legolas
 *       walked atop the snow of Caradhras).</li>
 *   <li><b>Weariness</b> - Elves need no sleep; Men/Hobbits/Orc-kin tire after long without rest (a
 *       small Fatigue); Dwarves and Uruk-hai endure far longer and their hunger drains slower
 *       ("suffer toil and hunger more hardily than all other speaking peoples").</li>
 * </ul>
 */
public final class RacialNatureService {
    private RacialNatureService() {
    }

    private static final int INTERVAL = 20; // once per second
    /** Extra decay applied per second to a harmful effect on a Shadow-resistant race (on top of the
     * 20 ticks that naturally elapse) - makes such effects fade roughly 3x faster. */
    private static final int SHADOW_EXTRA_DECAY = 40;

    // Ticks awake without sleep, per player (transient - resets on relog, which is fine).
    private static final Map<UUID, Integer> AWAKE = new HashMap<>();
    private static int tickCounter;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % INTERVAL != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                tickPlayer(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> AWAKE.remove(handler.player.getUuid()));
    }

    private static void tickPlayer(ServerPlayerEntity player) {
        Optional<Identifier> raceOpt = RaceAccess.getRace(player);
        if (raceOpt.isEmpty()) {
            return;
        }
        String race = raceOpt.get().getPath();
        boolean elf = race.equals("elf");
        boolean dwarf = race.equals("dwarf");
        boolean hobbit = race.equals("hobbit");
        boolean uruk = race.equals("uruk");
        boolean orcKin = uruk || race.equals("orc") || race.equals("goblin") || race.equals("snaga");

        if (elf || dwarf || hobbit) {
            resistTheShadow(player);
        }
        if (elf) {
            neverSicken(player);
        }
        if (dwarf || uruk) {
            endureHunger(player);
        }
        if (orcKin) {
            foulFeeder(player);
        }
        if (hobbit) {
            wellFed(player);
        }
        weariness(player, race, elf, dwarf, uruk);
    }

    /** Orc-kind have iron guts and devour foul carrion (even each other) - the Hunger effect that
     * rotten flesh and other foul food inflict never takes hold. */
    private static void foulFeeder(ServerPlayerEntity player) {
        player.removeStatusEffect(StatusEffects.HUNGER);
    }

    /** A well-fed Hobbit is a hale and comfortable one - while their belly is full, they mend
     * quietly (the Little Folk thrive on good food and plain plenty). */
    private static void wellFed(ServerPlayerEntity player) {
        if (player.getHungerManager().getFoodLevel() >= 18 && player.getHealth() < player.getMaxHealth()) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 40, 0, true, false, false));
        }
    }

    /** Harmful, finite status effects wear off ~3x faster (an extra second's worth of decay each
     * check) - the Shadow-resistant peoples throw off curses and poisons quickly. Infinite effects
     * (e.g. this mod's own contextual traits) are left alone. */
    private static void resistTheShadow(ServerPlayerEntity player) {
        List<StatusEffectInstance> harmful = new ArrayList<>();
        for (StatusEffectInstance inst : player.getStatusEffects()) {
            if (!inst.isInfinite()
                    && inst.getEffectType().value().getCategory() == StatusEffectCategory.HARMFUL) {
                harmful.add(inst);
            }
        }
        for (StatusEffectInstance inst : harmful) {
            int reduced = inst.getDuration() - SHADOW_EXTRA_DECAY;
            var type = inst.getEffectType();
            player.removeStatusEffect(type);
            if (reduced > 0) {
                player.addStatusEffect(new StatusEffectInstance(type, reduced, inst.getAmplifier(),
                        inst.isAmbient(), inst.shouldShowParticles(), inst.shouldShowIcon()));
            }
        }
    }

    /** Elves never sicken (immune to Hunger/Nausea) and cold cannot freeze them. */
    private static void neverSicken(ServerPlayerEntity player) {
        player.removeStatusEffect(StatusEffects.HUNGER);
        player.removeStatusEffect(StatusEffects.NAUSEA);
        if (player.getFrozenTicks() > 0) {
            player.setFrozenTicks(0);
        }
    }

    /** Dwarves and Uruks "suffer toil and hunger more hardily than all other speaking peoples" - so
     * their food drains far slower, and (critically) that includes the exhaustion spent by sprinting,
     * fighting, and natural regeneration healing. Vanilla charges every point of healing as ~6.0
     * exhaustion, which quickly eats a full hunger bar in a drawn-out fight; a race that "rarely needs
     * food" whose food still evaporates the moment it heals is a hollow buff. Bleeding off ~90% of the
     * accumulated exhaustion each second makes hunger drain roughly an order of magnitude slower across
     * ALL sources, without ever hard-stopping it. */
    private static void endureHunger(ServerPlayerEntity player) {
        HungerManager hunger = player.getHungerManager();
        float exhaustion = ((HungerManagerAccessor) hunger).kindreds$getExhaustion();
        if (exhaustion > 0.0f) {
            hunger.addExhaustion(-exhaustion * 0.9f);
        }
    }

    /** Weariness: Elves never tire; others gain a mild Mining Fatigue after long without sleep.
     * Dwarves and Uruk-hai endure far longer before it sets in. Sleeping resets the clock. */
    private static void weariness(ServerPlayerEntity player, String race, boolean elf, boolean dwarf, boolean uruk) {
        UUID uuid = player.getUuid();
        if (player.isSleeping()) {
            AWAKE.put(uuid, 0);
            return;
        }
        if (elf) {
            AWAKE.remove(uuid); // Elves need no sleep - never accrue weariness.
            return;
        }
        int awake = AWAKE.merge(uuid, INTERVAL, Integer::sum);
        int threshold = (dwarf || uruk) ? 96000 : 48000; // ~4 vs ~2 in-game days
        if (Kindreds.CONFIG.enableBirthTraits && awake > threshold) {
            // Short, continuously-refreshed so it lifts within seconds of sleeping.
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 60, 0, true, false, true));
        }
    }
}
