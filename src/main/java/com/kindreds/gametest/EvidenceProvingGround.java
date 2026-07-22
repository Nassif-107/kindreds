package com.kindreds.gametest;

import com.kindreds.Kindreds;
import com.kindreds.config.DeathPenalty;
import com.kindreds.config.KindredsConfig;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.threat.ThreatService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static com.kindreds.gametest.ProvingGroundSupport.*;

/**
 * Scenarios that prove the evidence loop's wiring: real damage exchanged through real
 * {@code LivingEntity#damage} calls, folded into competence through the real
 * {@code ServerLivingEntityEvents}/{@code ServerEntityCombatEvents} hooks {@link
 * com.kindreds.threat.ThreatEvidence} actually listens to - not the pure math {@code
 * ThreatMathTest}/{@code ThreatEvidenceTest} already prove, but the seam connecting that math to a
 * real fight.
 *
 * <p>None of these scenarios depend on {@code ThreatService#scaledGroupAt}'s nearby-player scan (the
 * evidence loop is keyed per player UUID, not by proximity), so the brief's 128-block spatial
 * pitfall does not apply here - fresh mock players per scenario is still honoured throughout.
 *
 * <p>Every scenario is a single synchronous body (no {@code context.runAtTick}): where gear needs to
 * matter for an {@code attack()} or an attribute read, {@link ProvingGroundSupport#settleEquipment}
 * forces it to register immediately via a direct {@code player.tick()} call - see that method's
 * javadoc for why this was chosen over yielding across real ticks (a same-{@code runGametest}-batch
 * config race with the other concurrently-running scenarios, found on this task's second real run).
 */
public class EvidenceProvingGround {

    /**
     * Scenario 2: a veteran with an established prior dies fifty times to mob-sourced lethal damage,
     * respawning through the real {@code PlayerManager#respawnPlayer} handler each time. After every
     * single death, {@code threatOf} must never fall below {@code 0.75 * priorAtStart} (minus a small
     * float epsilon) - the anti-farming floor (spec §2.4): the mark barely moves across zero played
     * time, and competence itself can never band below {@code COMPETENCE_MIN = 0.75}.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400, skyAccess = true)
    public void fiftyDeathsNeverBreakTheFloor(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.deathPenalty = DeathPenalty.KEEP;

            ServerWorld world = context.getWorld();
            ServerPlayerEntity player = freshPlayer(context, new BlockPos(0, 2, 0));
            manufactureVeteran(player, Kindreds.CONFIG); // equips + settles + refreshes threat itself
            float priorAtStart = KindredAttachment.get(player).threat().priorMark();
            check(failures, priorAtStart > 0f, "veteran's manufactured priorMark is not positive ("
                    + priorAtStart + ") - nothing meaningful to floor-test");

            ZombieEntity killer = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            check(failures, killer != null, "killer zombie failed to spawn");

            float floor = 0.75f * priorAtStart;
            float epsilon = 0.05f;
            for (int death = 1; death <= 50 && killer != null; death++) {
                context.damage(player, world.getDamageSources().mobAttack(killer), 1000f);
                ServerPlayerEntity respawned = world.getServer().getPlayerManager()
                        .respawnPlayer(player, false, Entity.RemovalReason.KILLED);
                check(failures, respawned != null, "respawnPlayer returned null on death #" + death);
                if (respawned == null) {
                    break;
                }
                player = respawned;
                ThreatService.invalidate(player.getUuid());
                float threat = ThreatService.threatOf(player);
                check(failures, threat >= floor - epsilon,
                        "after death #" + death + ": threatOf=" + threat + " fell below the floor "
                                + floor + " (0.75 * priorAtStart=" + priorAtStart + ")");
            }
            System.out.println("[8a-2] priorAtStart=" + priorAtStart + " floor=" + floor
                    + " finalThreat=" + ThreatService.threatOf(player));
        } finally {
            restoreConfig(snapshot);
        }
        finish("fiftyDeathsNeverBreakTheFloor", failures);
        context.complete();
    }

    /**
     * Scenario 3: verifies {@code player.attack(Entity)} really does route through
     * {@code LivingEntity#damage} (the brief's API-verification duty), then proves the kill-steal
     * channel closes: B landing only the killing blow on a mob A already whittled to under 10%
     * health must earn B far less competence rise than B soloing an identical mob from full health.
     *
     * <p>Both the steal and the solo-kill are real {@code attack()} calls (B is buffed with Strength
     * so a netherite sword fells either target in one or two swings - see {@link
     * ProvingGroundSupport#closeOut}) landed within the same instant (no ticks elapse between them),
     * deliberately symmetric in time-to-kill so the only variable between the two is {@code
     * killShare}, not an incidental TTK difference.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true)
    public void killStealPaysAlmostNothing(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.eliteChance = 0;
            Kindreds.CONFIG.escortChance = 0;
            Kindreds.CONFIG.maxHealthBonus = 0; // keep every zombie's max health at plain vanilla 20

            ServerWorld world = context.getWorld();
            ServerPlayerEntity a = freshPlayer(context, new BlockPos(0, 2, 0));
            ServerPlayerEntity b = freshPlayer(context, new BlockPos(4, 2, 0));
            equipNetherite(a);
            equipNetherite(b);
            applyLethalDamage(b); // one guaranteed-lethal swing - see its javadoc for why retries
                                   // within one instant cannot be relied on instead
            settleEquipment(a);
            settleEquipment(b);

            // --- API-verification duty: player.attack(Entity) really reduces health via real damage ---
            ZombieEntity proof = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 4)), SpawnReason.COMMAND);
            if (proof != null) {
                float before = proof.getHealth();
                a.attack(proof);
                check(failures, proof.getHealth() < before || !proof.isAlive(),
                        "player.attack(Entity) did not reduce the mob's health - real attack routing broken");
                proof.discard();
            }

            // --- the steal: A whittles, B lands only the killing blow ---
            ZombieEntity stealTarget = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            check(failures, stealTarget != null, "steal-target zombie failed to spawn");
            float stealDelta = 0f;
            if (stealTarget != null) {
                float maxHealth = stealTarget.getMaxHealth();
                // Real, event-firing damage attributed to A - whittles to just under 10%.
                context.damage(stealTarget, world.getDamageSources().playerAttack(a), maxHealth * 0.94f);
                check(failures, stealTarget.isAlive() && stealTarget.getHealth() > 0
                                && stealTarget.getHealth() <= maxHealth * 0.10f,
                        "whittle left the steal-target at " + stealTarget.getHealth() + "/" + maxHealth
                                + " - not under 10% and alive as required");

                float bBefore = KindredAttachment.get(b).threat().competence();
                closeOut(b, stealTarget, 5); // B never touched this mob before now
                check(failures, !stealTarget.isAlive(), "B's blow(s) did not kill the whittled zombie");
                float bAfter = KindredAttachment.get(b).threat().competence();
                stealDelta = bAfter - bBefore;
            }

            // --- the control: B solos an identical mob from full health ---
            ZombieEntity soloTarget = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 8)), SpawnReason.COMMAND);
            check(failures, soloTarget != null, "solo-target zombie failed to spawn");
            float soloDelta = 0f;
            if (soloTarget != null) {
                float bBefore = KindredAttachment.get(b).threat().competence();
                closeOut(b, soloTarget, 5);
                check(failures, !soloTarget.isAlive(), "B's solo attack(s) did not kill the fresh zombie");
                float bAfter = KindredAttachment.get(b).threat().competence();
                soloDelta = bAfter - bBefore;
            }

            check(failures, soloDelta > 0f,
                    "solo kill produced no competence rise at all (" + soloDelta + ") - nothing to compare against");
            if (soloDelta > 0f) {
                check(failures, Math.abs(stealDelta) < 0.10f * soloDelta,
                        "B's steal delta (" + stealDelta + ") is not under 10% of B's solo delta (" + soloDelta + ")");
            }

            System.out.println("[8a-3] stealDelta=" + stealDelta + " soloDelta=" + soloDelta
                    + " ratio=" + (soloDelta == 0f ? Float.NaN : stealDelta / soloDelta));
        } finally {
            restoreConfig(snapshot);
        }
        finish("killStealPaysAlmostNothing", failures);
        context.complete();
    }

    /**
     * Scenario 4: two identically-geared players, one with Resistance IV, each take the same N
     * mob-sourced hits and then close the fight by killing their attacker. The resistant player's
     * competence must fall strictly less (an endpoint-measured hardship signal, per {@code
     * ThreatEvidence}'s HP-delta banking - not the event's raw pre-mitigation damage figure).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true)
    public void mitigationTankingReadsTrueCost(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.eliteChance = 0;
            Kindreds.CONFIG.escortChance = 0;
            Kindreds.CONFIG.maxHealthBonus = 0;

            ServerWorld world = context.getWorld();
            ServerPlayerEntity plain = freshPlayer(context, new BlockPos(0, 2, 0));
            ServerPlayerEntity resistant = freshPlayer(context, new BlockPos(4, 2, 0));
            // Deliberately NOT equipNetherite here: "identically geared" for this comparison means
            // identical (zero) armor for both, so armor's own mitigation cannot dilute the ONE
            // variable this scenario measures (Resistance IV). Netherite armor on both sides was
            // this scenario's first draft, and on a real run its ~80% mitigation alone brought BOTH
            // players' hardship under hardshipTarget, making the Resistance-driven difference between
            // them vanish into float noise (both read exactly the same competence delta - the
            // Resistance case's combined armor+resistance mitigation formula, {@code 1 - (1-a)(1-r)},
            // pushed it so low both sides landed in the same clamped coasting band).
            applyLethalDamage(plain);
            applyLethalDamage(resistant);
            resistant.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 600, 3)); // level IV

            ZombieEntity plainKiller = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            ZombieEntity resistantKiller = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(6, 2, 0)), SpawnReason.COMMAND);
            check(failures, plainKiller != null && resistantKiller != null,
                    "one of the two zombies failed to spawn");

            float plainFall = 0f;
            float resistantFall = 0f;
            if (plainKiller != null && resistantKiller != null) {
                // Escalating, not four identical, raw amounts - defensive margin against vanilla's
                // ordinary post-hit invulnerability window (a new hit within it only lands if it
                // exceeds the last recorded damage). The real fix for this scenario's actual failure
                // (found empirically: a diagnostic showed player health never moving at ALL, for
                // either player) was elsewhere - see ProvingGroundSupport#freshPlayer's javadoc on
                // PlayerEntity#isLoaded's grace period; this array is cheap extra safety on top.
                float[] rawHits = {2.0f, 3.0f, 4.0f, 5.0f};
                for (float raw : rawHits) {
                    context.damage(plain, world.getDamageSources().mobAttack(plainKiller), raw);
                    context.damage(resistant, world.getDamageSources().mobAttack(resistantKiller), raw);
                }
                check(failures, plain.isAlive() && resistant.isAlive(),
                        "one of the two players died from the identical hit series - not what this scenario measures");

                float plainBefore = KindredAttachment.get(plain).threat().competence();
                closeOut(plain, plainKiller, 6);
                check(failures, !plainKiller.isAlive(), "plain player's attacks did not kill their attacker");
                float plainAfter = KindredAttachment.get(plain).threat().competence();
                plainFall = plainBefore - plainAfter;

                float resistantBefore = KindredAttachment.get(resistant).threat().competence();
                closeOut(resistant, resistantKiller, 6);
                check(failures, !resistantKiller.isAlive(), "resistant player's attacks did not kill their attacker");
                float resistantAfter = KindredAttachment.get(resistant).threat().competence();
                resistantFall = resistantBefore - resistantAfter;

                check(failures, resistantFall < plainFall,
                        "resistant player's competence fall (" + resistantFall
                                + ") is not strictly less than the plain player's (" + plainFall + ")");
            }
            System.out.println("[8a-4] plainFall=" + plainFall + " resistantFall=" + resistantFall);
        } finally {
            restoreConfig(snapshot);
        }
        finish("mitigationTankingReadsTrueCost", failures);
        context.complete();
    }

    /**
     * Scenario 9: mid-fight bookkeeping (an open engagement, accumulated damage) exists when the
     * player dies. Across the real death+respawn handlers, {@code ThreatState} (competence, marks,
     * families) must survive onto the new player, while the fight accumulators must not - proven by
     * comparing the respawned player's next (clean) kill against a totally fresh control player's
     * identical clean kill: if stale accumulated damage had survived, the respawned player's kill
     * would fold a nonzero hardship it never actually earned after respawning, diverging from the
     * control.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400, skyAccess = true)
    public void evidenceSurvivesDeathButAccumulatorsDoNot(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.deathPenalty = DeathPenalty.KEEP;
            Kindreds.CONFIG.eliteChance = 0;
            Kindreds.CONFIG.escortChance = 0;
            Kindreds.CONFIG.maxHealthBonus = 0;

            ServerWorld world = context.getWorld();
            ServerPlayerEntity player = freshPlayer(context, new BlockPos(0, 2, 0));

            // A distinctive, hand-set ThreatState so "survived" is unambiguous (not indistinguishable
            // from a fresh default) - the same style ScreenIterationTest (Task 7) used to make an
            // elite roll deterministic.
            KindredData data = KindredAttachment.get(player);
            data.threat().setPriorMark(50f);
            data.threat().setCompetence(1.15f);
            data.threat().familyCompetence().put("undead", 1.20f);
            ThreatService.invalidate(player.getUuid());
            ThreatService.threatOf(player);
            float priorBeforeDeath = data.threat().priorMark();

            equipNetherite(player); // for the mid-fight engagement below
            settleEquipment(player);

            // --- mid-fight: an open engagement (player has hit it) + accumulated damage (it has hit
            // the player), neither yet closed by a kill or a death fold. ---
            ZombieEntity midFight = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            check(failures, midFight != null, "mid-fight zombie failed to spawn");
            if (midFight != null) {
                player.attack(midFight); // open engagement, unresolved
                context.damage(player, world.getDamageSources().mobAttack(midFight), 4.0f); // accumulated damage, unresolved
            }
            check(failures, player.isAlive(), "player died from the mid-fight setup damage - too much for this test");

            // --- death, mid-fight, to a lethal blow from the same mob ---
            context.damage(player, world.getDamageSources().mobAttack(
                    midFight != null ? midFight : player), 1000f);
            ServerPlayerEntity respawned = world.getServer().getPlayerManager()
                    .respawnPlayer(player, false, Entity.RemovalReason.KILLED);
            check(failures, respawned != null, "respawnPlayer returned null");

            if (respawned != null) {
                KindredData afterData = KindredAttachment.get(respawned);
                // Captured now, not re-read at print time below: equipping the respawned player for
                // its next kill (a few lines down) immediately raises priorMark again (spec: gear
                // raises the mark on the spot) - re-evaluating this expression after that would read
                // a since-risen value and misreport a passing check as "not survived".
                boolean priorSurvived = afterData.threat().priorMark() == priorBeforeDeath;
                check(failures, priorSurvived,
                        "priorMark did not survive death unchanged: " + priorBeforeDeath + " -> "
                                + afterData.threat().priorMark());
                boolean familySurvived = afterData.threat().familyCompetence().containsKey("undead");
                check(failures, familySurvived,
                        "per-family competence for 'undead' did not survive death");

                // --- the respawned player's next kill must pay no stale credit ---
                equipNetherite(respawned);
                applyLethalDamage(respawned);
                settleEquipment(respawned);

                ZombieEntity respawnedCleanTarget = EntityType.ZOMBIE.spawn(world,
                        context.getAbsolutePos(new BlockPos(2, 2, 12)), SpawnReason.COMMAND);
                float respawnedBefore = afterData.threat().competence();
                if (respawnedCleanTarget != null) {
                    closeOut(respawned, respawnedCleanTarget, 5);
                    check(failures, !respawnedCleanTarget.isAlive(),
                            "respawned player's attacks did not kill the clean target");
                }
                float respawnedAfter = afterData.threat().competence();
                float respawnedDelta = respawnedAfter - respawnedBefore;

                // --- control: a totally fresh, untouched player performing the identical clean kill ---
                ServerPlayerEntity control = freshPlayer(context, new BlockPos(0, 2, 20));
                equipNetherite(control);
                applyLethalDamage(control);
                settleEquipment(control);

                ZombieEntity controlTarget = EntityType.ZOMBIE.spawn(world,
                        context.getAbsolutePos(new BlockPos(2, 2, 24)), SpawnReason.COMMAND);
                float controlBefore = KindredAttachment.get(control).threat().competence();
                if (controlTarget != null) {
                    closeOut(control, controlTarget, 5);
                    check(failures, !controlTarget.isAlive(),
                            "control player's attacks did not kill its clean target");
                }
                float controlAfter = KindredAttachment.get(control).threat().competence();
                float controlDelta = controlAfter - controlBefore;

                check(failures, Math.abs(respawnedDelta - controlDelta) < 0.02f,
                        "post-death clean kill delta (" + respawnedDelta + ") diverges from the fresh "
                                + "control's identical clean kill (" + controlDelta
                                + ") - stale fight bookkeeping may have survived death");

                System.out.println("[8a-9] priorSurvived=" + priorSurvived
                        + " familySurvived=" + familySurvived
                        + " respawnedDelta=" + respawnedDelta + " controlDelta=" + controlDelta);
            }
        } finally {
            restoreConfig(snapshot);
        }
        finish("evidenceSurvivesDeathButAccumulatorsDoNot", failures);
        context.complete();
    }
}
