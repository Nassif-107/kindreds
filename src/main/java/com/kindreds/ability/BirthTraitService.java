package com.kindreds.ability;

import com.kindreds.Kindreds;
import com.kindreds.data.BirthTrait;
import com.kindreds.data.KindredsRegistries;
import com.kindreds.data.ability.AbilityDef;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Optional;

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

    /** Wire up: apply on join, and re-check every ~2s (only acts on an actual race/config change). */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> refreshIfChanged(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % 40 != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                refreshIfChanged(player);
            }
        });
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
