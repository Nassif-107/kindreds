package com.kindreds.gametest;

import com.kindreds.Kindreds;
import com.kindreds.config.KindredsConfig;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.RespecService;
import com.kindreds.threat.ThreatService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static com.kindreds.gametest.ProvingGroundSupport.addRenown;
import static com.kindreds.gametest.ProvingGroundSupport.armForCombat;
import static com.kindreds.gametest.ProvingGroundSupport.check;
import static com.kindreds.gametest.ProvingGroundSupport.equipNetherite;
import static com.kindreds.gametest.ProvingGroundSupport.finish;
import static com.kindreds.gametest.ProvingGroundSupport.freshPlayer;
import static com.kindreds.gametest.ProvingGroundSupport.manufactureVeteran;
import static com.kindreds.gametest.ProvingGroundSupport.newFailureList;
import static com.kindreds.gametest.ProvingGroundSupport.removeIfPresent;
import static com.kindreds.gametest.ProvingGroundSupport.restoreConfig;
import static com.kindreds.gametest.ProvingGroundSupport.settleEquipment;
import static com.kindreds.gametest.ProvingGroundSupport.simulateHoursOfDecay;
import static com.kindreds.gametest.ProvingGroundSupport.snapshotConfig;
import static com.kindreds.gametest.ProvingGroundSupport.stripGear;

/**
 * Scenarios that prove the mark's two headline promises: it never snaps down on the spot (only
 * played time erodes it), and it never lies about a rank crossing (announced exactly once, exactly
 * when it actually happens).
 *
 * <p>Both scenarios are single synchronous bodies: every gear change is followed by {@link
 * ProvingGroundSupport#settleEquipment} (a direct {@code player.tick()} call) rather than a {@code
 * context.runAtTick} wait - see that method's javadoc for why yielding across real ticks was
 * abandoned (a same-batch config race with other concurrently-running scenarios).
 */
public class MarkAndRankProvingGround {

    /**
     * Scenario 5: exercises {@code ThreatService}'s real {@code LAST_ANNOUNCED} bookkeeping through
     * a fully real, connected player whose {@code sendMessage} we can observe - see {@link
     * RecordingServerPlayerEntity}'s javadoc for why subclassing {@code createMockPlayer}/{@code
     * createMockCreativeServerPlayerInWorld}'s own anonymous classes is infeasible, and what public-API
     * recipe stands in for it instead. Threat is driven up (real gear + renown - the raceless
     * environment's own supported path, see {@link ProvingGroundSupport#manufactureVeteran}'s
     * javadoc) and back down (real decay, {@code addPlayedTicks} + a refresh) rather than via a
     * literal skill-tree unlock, which does not resolve for a mock player with no race in this
     * environment.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true)
    public void rankCrossingAnnounces(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        RecordingServerPlayerEntity player = null;
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;

            ServerWorld world = context.getWorld();
            player = RecordingServerPlayerEntity.create(world);
            armForCombat(context, player, new BlockPos(0, 2, 0)); // same arm sequence freshPlayer applies

            // --- first population after "join": silent, no matter what rank it lands on ---
            ThreatService.invalidate(player.getUuid());
            ThreatService.threatOf(player);
            check(failures, player.sentMessages.isEmpty(),
                    "a message arrived on the very first population after join: " + player.sentMessages);

            // --- drive threat up across a rank boundary (real gear + renown) ---
            equipNetherite(player);
            addRenown(player, 4);
            settleEquipment(player);
            ThreatService.invalidate(player.getUuid());
            float threatAfterRise = ThreatService.threatOf(player);
            check(failures, player.sentMessages.size() == 1,
                    "expected exactly one message after the rise, got " + player.sentMessages.size()
                            + ": " + player.sentMessages);
            if (player.sentMessages.size() == 1) {
                String key = keyOf(player.sentMessages.get(0));
                check(failures, "kindreds.threat.risen".equals(key),
                        "expected a 'risen' message, got key '" + key + "'");
            }

            // --- drive threat back down across the same boundary (real decay) ---
            stripGear(player);
            settleEquipment(player);
            // 5 hours: enough to cross back down out of MARKED (>= 40) into WATCHED (20-39), but not
            // so much it also blows through WATCHED into UNNOTICED - this scenario wants exactly one
            // more crossing, not two. See simulateHoursOfDecay's javadoc for why this is a loop of
            // real refreshes, not one big addPlayedTicks call.
            simulateHoursOfDecay(player, 5);
            float threatAfterFall = ThreatService.threatOf(player);
            check(failures, threatAfterFall < threatAfterRise,
                    "threat did not actually fall (" + threatAfterRise + " -> " + threatAfterFall + ")");
            check(failures, player.sentMessages.size() == 2,
                    "expected exactly one additional message after the fall, got "
                            + player.sentMessages.size() + " total: " + player.sentMessages);
            if (player.sentMessages.size() == 2) {
                String key = keyOf(player.sentMessages.get(1));
                check(failures, "kindreds.threat.fallen".equals(key),
                        "expected a 'fallen' message, got key '" + key + "'");
            }

            System.out.println("[8a-5] messages=" + player.sentMessages.size()
                    + " threatAfterRise=" + threatAfterRise + " threatAfterFall=" + threatAfterFall);
        } finally {
            restoreConfig(snapshot);
            removeIfPresent(player); // M4: fully-connected recording player must not linger either
        }
        finish("rankCrossingAnnounces", failures);
        context.complete();
    }

    private static String keyOf(Text text) {
        return text.getContent() instanceof TranslatableTextContent t ? t.getKey() : "<not translatable: " + text + ">";
    }

    /**
     * Scenario 7: a high mark must not snap down the instant gear is stripped and the tree
     * respecced (a real {@link RespecService#reverseAll} call) - only played time may erode it, at
     * most {@code priorDecayPerHour} per hour, repeatably; re-equipping gear raises the live prior
     * back up, and the mark rises to meet it immediately (never gated behind more played time).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true)
    public void theMarkNeverForgets(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        ServerPlayerEntity player = null;
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;

            player = freshPlayer(context, new BlockPos(0, 2, 0));
            manufactureVeteran(player, Kindreds.CONFIG); // equips + settles + refreshes threat itself
            KindredData data = KindredAttachment.get(player);
            float priorMark = data.threat().priorMark();
            float threatBeforeStrip = ThreatService.threatOf(player);
            check(failures, priorMark > 0f, "manufactured veteran's priorMark is not positive");

            // --- strip gear + respec: threat must not snap down this instant ---
            stripGear(player);
            int reversed = RespecService.reverseAll(player); // real handler; safe with 0 unlocked nodes too
            settleEquipment(player);
            ThreatService.invalidate(player.getUuid());
            float threatImmediatelyAfter = ThreatService.threatOf(player);
            float epsilon = 0.05f; // absorbs the documented sub-tick phantom decay allowance every
                                    // refresh charges (see ThreatService#refresh's own comment) -
                                    // negligible next to any real per-hour decay figure.
            check(failures, Math.abs(threatImmediatelyAfter - threatBeforeStrip) <= epsilon,
                    "threat snapped down instantly after stripping gear/respec: "
                            + threatBeforeStrip + " -> " + threatImmediatelyAfter + " (reversed " + reversed + " nodes)");

            // --- one hour decays, bounded --- (simulateHoursOfDecay: see its javadoc for why one
            // big addPlayedTicks call does not work - ThreatService#refresh always decays by the
            // fixed 40-tick REFRESH_TICKS allowance per call, never by state.playedTicks() directly)
            float markAfterStrip = data.threat().priorMark();
            simulateHoursOfDecay(player, 1);
            float markAfterHour1 = data.threat().priorMark();
            float fellHour1 = markAfterStrip - markAfterHour1;
            check(failures, fellHour1 <= Kindreds.CONFIG.priorDecayPerHour + epsilon,
                    "mark fell by " + fellHour1 + " in one hour, more than priorDecayPerHour="
                            + Kindreds.CONFIG.priorDecayPerHour);

            // --- a second hour decays again ---
            simulateHoursOfDecay(player, 1);
            float markAfterHour2 = data.threat().priorMark();
            float fellHour2 = markAfterHour1 - markAfterHour2;
            check(failures, fellHour2 <= Kindreds.CONFIG.priorDecayPerHour + epsilon,
                    "mark fell by " + fellHour2 + " in the second hour, more than priorDecayPerHour="
                            + Kindreds.CONFIG.priorDecayPerHour);
            check(failures, markAfterHour2 < markAfterHour1,
                    "mark did not continue decaying on the second hour (" + markAfterHour1 + " -> "
                            + markAfterHour2 + ")");

            // --- gear re-equipped: prior rises immediately, no waiting ---
            equipNetherite(player);
            settleEquipment(player);
            ThreatService.invalidate(player.getUuid());
            ThreatService.threatOf(player);
            float markAfterReequip = data.threat().priorMark();
            check(failures, markAfterReequip > markAfterHour2,
                    "priorMark did not rise immediately after re-equipping gear (" + markAfterHour2
                            + " -> " + markAfterReequip + ")");

            System.out.println("[8a-7] priorMark=" + priorMark + " afterStrip=" + markAfterStrip
                    + " afterHour1=" + markAfterHour1 + " afterHour2=" + markAfterHour2
                    + " afterReequip=" + markAfterReequip);
        } finally {
            restoreConfig(snapshot);
            // M4: this player ends re-equipped with a high mark - the single worst contaminator for
            // every other scenario's scaledGroupAt whole-dimension fallback if left in the world.
            removeIfPresent(player);
        }
        finish("theMarkNeverForgets", failures);
        context.complete();
    }
}
