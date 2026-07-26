package com.kindreds.progression;

import com.kindreds.Kindreds;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.playerdata.RaceAccess;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rescues experience already banked into disciplines the player's kindred can never spend.
 *
 * <h2>Why this exists</h2>
 * Not every race has every discipline - a Dwarf's tree holds combat, mining, smithing and runecraft
 * and nothing else - but until {@code ActivityHooks} learned to redirect, xp was banked wherever the
 * activity happened to pay. Advancements pay Lore, so a Dwarf who explored and achieved accumulated
 * Lore levels with nothing whatsoever to buy: one reached fifteen of them, and could only see the
 * tree insisting he had points he was not allowed to spend.
 *
 * <p>The redirect stopped the bleeding but could not heal it - xp banked before the fix stayed
 * stranded. This moves it, once, into the nearest discipline the race actually has, by exactly the
 * same kinship rule {@code ActivityHooks} now uses for new xp, so what a player earned is what a
 * player keeps.
 *
 * <h2>Why no "already migrated" flag</h2>
 * It needs none: the move empties the stranded discipline, so a second run finds nothing stranded
 * and does nothing. Being naturally idempotent is worth more than a flag here - a flag would have to
 * be persisted, synced and reasoned about, and could itself desync.
 */
public final class StrandedXpMigration {
    private StrandedXpMigration() {
    }

    /**
     * Moves every scrap of unspendable xp on {@code player} into disciplines their kindred can use.
     * Safe to call on every login. Does nothing when the race is unknown (the player has not picked
     * one yet), since "what this race can spend" is unanswerable until then.
     */
    public static void run(ServerPlayerEntity player) {
        Optional<Identifier> race = RaceAccess.getRace(player);
        if (race.isEmpty()) {
            return;
        }
        KindredData data = KindredAttachment.get(player);
        Object2LongMap<Identifier> xp = data.disciplineXp();

        // Collected first, then applied: mutating the map while iterating it would be a concurrent
        // modification, and the destination may itself be a key already present in the map.
        List<Identifier> stranded = new ArrayList<>();
        for (Object2LongMap.Entry<Identifier> entry : xp.object2LongEntrySet()) {
            if (entry.getLongValue() > 0 && !ActivityHooks.canSpendIn(player, race.get(), entry.getKey())) {
                stranded.add(entry.getKey());
            }
        }
        if (stranded.isEmpty()) {
            return;
        }

        long movedTotal = 0;
        for (Identifier from : stranded) {
            long amount = xp.getLong(from);
            Identifier to = ActivityHooks.nearestSpendableFor(player, race.get(), from);
            if (to == null || to.equals(from)) {
                continue;   // nowhere to put it; leave it be rather than destroy it
            }
            xp.put(from, 0L);
            data.addXp(to, amount);
            movedTotal += amount;
            Kindreds.LOGGER.info("[Kindreds] {}: moved {} stranded xp from {} to {}",
                    player.getGameProfile().getName(), amount, from, to);
        }
        if (movedTotal > 0) {
            Kindreds.LOGGER.info("[Kindreds] {}: {} stranded xp rescued in total",
                    player.getGameProfile().getName(), movedTotal);
        }
    }
}
