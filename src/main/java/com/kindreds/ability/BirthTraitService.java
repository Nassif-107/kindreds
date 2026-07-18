package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.data.BirthTrait;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies each player's innate {@link BirthTrait} the moment their race is known, and reconciles it
 * when the race changes or the {@code enableBirthTraits} config flips. Birth traits reuse the node
 * effect engine ({@link AbilityApplier}) with a synthetic node id {@code birth/<race>}, so their
 * attribute modifiers are tagged and reversible exactly like a skill node's.
 *
 * <h2>Idempotency / no attribute "blip"</h2>
 * {@link #refreshIfChanged} does nothing when the current race already matches
 * {@link KindredData#appliedBirthRace()} - so the periodic sweep never strips-and-reapplies a live
 * player's traits every tick (which would flicker max-health/hearts). It acts only on a genuine
 * change: first resolve after join, a race change, or a config toggle.
 *
 * <h2>Why reconcile once per session</h2>
 * Birth-trait attribute modifiers are <i>persistent</i> (saved on the player), so they survive a
 * relog. {@link KindredData#appliedBirthRace()} is deliberately NOT persisted, so it resets to
 * {@code null} each session; the first sweep therefore reverses this race's traits (clearing the
 * persisted-from-last-session modifiers) before re-applying, which prevents duplicate-id stacking
 * without needing on-disk bookkeeping.
 */
public final class BirthTraitService {
    private BirthTraitService() {
    }

    private static int tickCounter;

    /**
     * Ticks to wait after a join/respawn before (re)applying birth traits. The base Middle-earth mod
     * applies each race's stats by calling {@code clearModifiers()} + {@code setBaseValue()} on the
     * shared attributes (max_health, movement_speed, attack_damage, mining/climbing/detection, ...) -
     * synchronously, inside its own JOIN handler, on the same tick we join. Anything we apply inline
     * can therefore be wiped by it, load-order-dependently. Applying a couple ticks LATER guarantees
     * our persistent modifiers are the last writer regardless of which mod's JOIN handler ran first.
     */
    private static final int APPLY_DELAY_TICKS = 2;

    /** Players awaiting a deferred (post-base-mod) birth-trait apply, with ticks remaining. */
    private static final Map<UUID, Integer> PENDING = new HashMap<>();

    /** Wire up: schedule a deferred apply on join and on respawn-after-death, and re-check every ~2s
     * (which only acts on an actual race/config change). */
    public static void register() {
        // Deferred (NOT inline) so we re-assert after the base mod's same-tick clearModifiers(). See
        // APPLY_DELAY_TICKS.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PENDING.put(handler.player.getUuid(), APPLY_DELAY_TICKS));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PENDING.remove(handler.player.getUuid()));

        // On a REAL death (alive == false), vanilla drops every persistent attribute modifier and
        // clears status effects - including this mod's birth-trait ones - and the base mod re-asserts
        // race base values. The periodic sweep won't notice (appliedBirthRace still equals the current
        // race), so reset it and schedule a clean, deferred re-apply. A dimension-change respawn
        // (alive == true) keeps the modifiers, so it needs nothing.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) {
                KindredAttachment.get(newPlayer).setAppliedBirthRace(null);
                PENDING.put(newPlayer.getUuid(), APPLY_DELAY_TICKS);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(BirthTraitService::onEndTick);
    }

    /** Ticks down each pending deferred apply and fires it at zero, then runs the periodic race/config
     * sweep every ~2s. */
    private static void onEndTick(MinecraftServer server) {
        if (!PENDING.isEmpty()) {
            Iterator<Map.Entry<UUID, Integer>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Integer> entry = it.next();
                int remaining = entry.getValue() - 1;
                if (remaining <= 0) {
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                    if (player != null) {
                        refreshIfChanged(player);
                    }
                    it.remove();
                } else {
                    entry.setValue(remaining);
                }
            }
        }
        if (++tickCounter % 40 == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                refreshIfChanged(player);
            }
        }
    }

    /**
     * Reconciles {@code player}'s applied birth traits with their current race + the config flag.
     * @return true if anything actually changed (traits applied, reversed, or swapped).
     */
    public static boolean refreshIfChanged(ServerPlayerEntity player) {
        KindredData data = KindredAttachment.get(player);
        Identifier applied = data.appliedBirthRace();

        // Config disabled: strip any applied traits and stop.
        if (!Kindreds.CONFIG.enableBirthTraits) {
            if (applied != null) {
                lookup(player, applied).ifPresent(bt -> AbilityApplier.removeNode(player, bt.traits(), birthId(applied)));
                data.setAppliedBirthRace(null);
                return true;
            }
            return false;
        }

        Identifier current = RaceAccess.getRace(player).orElse(null);
        if (current == null) {
            return false; // race not readable yet - leave whatever is applied untouched
        }
        if (current.equals(applied)) {
            return false; // no change - avoid a strip/reapply attribute blip
        }

        // Reverse the previously-applied race's traits (a race change).
        if (applied != null) {
            lookup(player, applied).ifPresent(bt -> AbilityApplier.removeNode(player, bt.traits(), birthId(applied)));
            // Clear contextual-curse bookkeeping (e.g. a Dread-of-the-Sun that was active for the old
            // race) so the context engine re-derives from scratch for the new race next tick.
            CurseContextService.resetActive(player.getUuid());
        }
        // Apply the current race's traits, first clearing any leftover (persisted-from-last-session)
        // modifiers for this same race so identical modifier ids never double-add.
        lookup(player, current).ifPresent(bt -> {
            AbilityApplier.removeNode(player, bt.traits(), birthId(current));
            for (AbilityDef ability : bt.traits()) {
                AbilityApplier.apply(player, ability, birthId(current));
            }
        });
        data.setAppliedBirthRace(current);
        return true;
    }

    private static Optional<BirthTrait> lookup(ServerPlayerEntity player, Identifier race) {
        Registry<BirthTrait> registry = player.getServer().getRegistryManager().getOrThrow(KindredsRegistries.BIRTH_TRAIT);
        for (BirthTrait trait : registry) {
            if (trait.race().equals(race)) {
                return Optional.of(trait);
            }
        }
        return Optional.empty();
    }

    /** Synthetic node id birth traits are tagged with, so their attribute modifiers are reversible
     * exactly like a real node's (see {@link AbilityApplier#attributeModifierId}). */
    private static String birthId(Identifier race) {
        return "birth/" + race.getPath();
    }
}
