package com.kindreds.progression;

import com.kindreds.Kindreds;
import com.kindreds.network.SyncKindredDataS2C;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * <b>Great Deeds</b>: the renown that widens what a kindred may become.
 *
 * <p>The tree-wide point cap ({@link UnlockService#effectiveCap}) means no one can master their whole
 * tree by simply living long enough - which is the point, but it left the ceiling immovable no matter
 * what a player actually <i>did</i>. Renown is the answer, and it is the Middle-earth answer: standing
 * comes from deeds, not from time served. Each of the four Great Deeds permanently grants
 * {@link #PERCENT_PER_DEED}% more of your own tree.
 *
 * <p>Deeds are ordinary advancements under {@code kindreds:renown/<race>/}, so they are
 * datapack-authorable and vanilla shows them in the advancement screen for free. They are deliberately
 * reachable in normal play - a deed you will never perform is not a ceiling raise, it is decoration.
 *
 * <p><b>Every kindred has its own four.</b> A Dwarf's craft-deed is an anvil raised in the deep; an
 * Elf's is a bow with a song sung over it; a Hobbit's is a full larder. Only deeds belonging to your
 * own race count toward your cap - which also means renown is something you did <i>as</i> that people.
 * Deeds performed under a former race are kept on record (so returning to it restores them) but count
 * for nothing while you wear another face.
 *
 * <p>Once earned, a deed is recorded on {@link KindredData#renown()} and never re-checked against the
 * advancement tracker (see that field's javadoc for why revocation must not claw the cap back).
 */
public final class RenownService {
    private RenownService() {
    }

    /** Advancement id path prefix that marks an advancement as a Great Deed
     * ({@code renown/<race>/<deed>}). */
    public static final String RENOWN_PREFIX = "renown/";

    /** Cap widening, in percentage points of the player's own tree, per deed performed. */
    public static final int PERCENT_PER_DEED = 5;

    public static void register() {
        // Deeds may have been earned before this feature existed (or while the player was offline in a
        // multiplayer world whose datapack changed), so reconcile once per join. This only ever ADDS.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> reconcile(handler.player));
    }

    /**
     * Whether {@code advancement} is one of the Great Deeds.
     *
     * <p>Excludes {@code renown/root}, which is the advancement-tree container: it completes on a
     * plain tick trigger the moment anyone joins, so counting it handed every player a free +5% cap
     * before they had done anything at all. (The doctor already excluded it from its tally, which is
     * how the two disagreed - the tally was fixed and the rule it was tallying was not.)
     */
    public static boolean isRenown(Identifier id) {
        return Kindreds.MOD_ID.equals(id.getNamespace())
                && id.getPath().startsWith(RENOWN_PREFIX)
                && !id.getPath().equals(RENOWN_PREFIX + "root");
    }

    /**
     * Records a newly-completed Great Deed. Called from {@code ActivityHooks.onAdvancementCompleted}
     * (which the {@code PlayerAdvancementTrackerMixin} fires exactly once per advancement).
     */
    public static void onAdvancementCompleted(ServerPlayerEntity player, AdvancementEntry advancement) {
        if (player == null || advancement == null || !isRenown(advancement.id())) {
            return;
        }
        KindredData data = KindredAttachment.get(player);
        if (!data.renown().add(advancement.id().getPath())) {
            return; // already recorded
        }
        // Recorded either way (see the class javadoc on former races), but only announced as a gain
        // when it is actually one of your own kindred's deeds.
        if (belongsToRace(advancement.id().getPath(), data.race())) {
            announce(player, data);
        }
        SyncKindredDataS2C.sendTo(player);
    }

    /** Adds any Great Deed the player has already completed but that isn't recorded yet. */
    public static void reconcile(ServerPlayerEntity player) {
        if (player.getServer() == null) {
            return;
        }
        KindredData data = KindredAttachment.get(player);
        boolean changed = false;
        for (AdvancementEntry entry : player.getServer().getAdvancementLoader().getAdvancements()) {
            if (!isRenown(entry.id())) {
                continue;
            }
            if (player.getAdvancementTracker().getProgress(entry).isDone()
                    && data.renown().add(entry.id().getPath())) {
                changed = true;
            }
        }
        if (changed) {
            SyncKindredDataS2C.sendTo(player);
        }
    }

    /** The cap widening this player has earned, in percentage points: their own race's Great Deeds,
     * plus the Bargain if taken. */
    public static int bonusPercent(KindredData data) {
        if (data == null) {
            return 0;
        }
        return deedsForRace(data) * PERCENT_PER_DEED
                + com.kindreds.ability.CorruptionService.bonusPercent(data);
    }

    /** How many of this player's own kindred's Great Deeds are done. */
    public static int deedsForRace(KindredData data) {
        if (data == null) {
            return 0;
        }
        int count = 0;
        for (String path : data.renown()) {
            if (belongsToRace(path, data.race())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether the deed at {@code path} ({@code renown/<race>/<deed>}) belongs to {@code race}.
     *
     * <p>A {@code null} race counts <b>everything</b>, deliberately. Race is transient state re-read
     * from the base mod, and the failure mode of guessing wrong matters enormously: counting nothing
     * would silently narrow a ceiling the player has already spent points under, leaving them
     * committed above their own cap. Counting too much merely leaves it a little wide for the moment
     * before the race resolves.
     */
    private static boolean belongsToRace(String path, Identifier race) {
        if (race == null) {
            return true;
        }
        return path.startsWith(RENOWN_PREFIX + race.getPath() + "/");
    }

    /** Word of a Great Deed spreads: the doer is told what it bought them, and the hall hears of it. */
    private static void announce(ServerPlayerEntity player, KindredData data) {
        int total = deedsForRace(data) * PERCENT_PER_DEED;
        player.sendMessage(Text.translatable("kindreds.renown.earned", PERCENT_PER_DEED, total)
                .formatted(Formatting.GOLD), false);
        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                SoundCategory.PLAYERS, 0.7f, 1.0f);
    }
}
