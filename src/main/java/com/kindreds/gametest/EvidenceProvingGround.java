package com.kindreds.gametest;

import com.kindreds.Kindreds;
import com.kindreds.config.DeathPenalty;
import com.kindreds.config.KindredsConfig;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.threat.ThreatMath;
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

import static com.kindreds.gametest.ProvingGroundSupport.applyLethalDamage;
import static com.kindreds.gametest.ProvingGroundSupport.armForCombat;
import static com.kindreds.gametest.ProvingGroundSupport.check;
import static com.kindreds.gametest.ProvingGroundSupport.closeOut;
import static com.kindreds.gametest.ProvingGroundSupport.discardIfAlive;
import static com.kindreds.gametest.ProvingGroundSupport.equipNetherite;
import static com.kindreds.gametest.ProvingGroundSupport.finish;
import static com.kindreds.gametest.ProvingGroundSupport.freshPlayer;
import static com.kindreds.gametest.ProvingGroundSupport.manufactureVeteran;
import static com.kindreds.gametest.ProvingGroundSupport.newFailureList;
import static com.kindreds.gametest.ProvingGroundSupport.removeIfPresent;
import static com.kindreds.gametest.ProvingGroundSupport.restoreConfig;
import static com.kindreds.gametest.ProvingGroundSupport.settleEquipment;
import static com.kindreds.gametest.ProvingGroundSupport.snapshotConfig;

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
 * pitfall does not apply here - fresh mock players per scenario is still honoured throughout, and
 * every scenario removes its players (and any still-alive mob) from the shared world in the same
 * {@code finally} that restores config, so the whole-dimension fallback in {@code scaledGroupAt}
 * never reads this file's leftovers from another test.
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
     * respawning through the real {@code PlayerManager#respawnPlayer} handler each time. Every death
     * must REALLY happen (asserted per iteration - a respawned player is invulnerable again until
     * re-armed, see {@link ProvingGroundSupport#armForCombat}), {@code threatOf} must never fall
     * below {@code 0.75 * priorAtStart} after any of them, and after all fifty the anti-farming
     * floor (spec §2.4) must have ENGAGED: competence converged onto {@link
     * ThreatMath#COMPETENCE_MIN} exactly, threat onto {@code 0.75 * priorAtStart}. Deleting the
     * {@code COMPETENCE_MIN} clamp inside {@code ThreatMath.band} fails this scenario - that clamp
     * is the whole point of it.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400, skyAccess = true)
    public void fiftyDeathsNeverBreakTheFloor(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        ServerPlayerEntity player = null;
        ZombieEntity killer = null;
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.deathPenalty = DeathPenalty.KEEP;
            // Pin every dial that could move anything but the death folds this scenario measures:
            // eliteChance/maxHealthBonus at 0 so a foreign-dial promotion can never vary the killer's
            // MobDanger and with it every fold's attackerWeight (the review's I3 residual);
            // adaptiveStrength at 100 so the band's floor is COMPETENCE_MIN itself, not a narrowed
            // one; priorDecayPerHour at 0 so the mark is frozen at priorAtStart and deaths are the
            // ONLY thing moving threat here - which is what makes the convergence asserted after the
            // loop exact rather than epsilon-fudged.
            Kindreds.CONFIG.eliteChance = 0;
            Kindreds.CONFIG.escortChance = 0;
            Kindreds.CONFIG.maxHealthBonus = 0;
            Kindreds.CONFIG.adaptiveStrength = 100;
            Kindreds.CONFIG.priorDecayPerHour = 0f;

            ServerWorld world = context.getWorld();
            player = freshPlayer(context, new BlockPos(0, 2, 0));
            manufactureVeteran(player, Kindreds.CONFIG); // equips + settles + refreshes threat itself
            float priorAtStart = KindredAttachment.get(player).threat().priorMark();
            check(failures, priorAtStart > 0f, "veteran's manufactured priorMark is not positive ("
                    + priorAtStart + ") - nothing meaningful to floor-test");

            killer = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            check(failures, killer != null, "killer zombie failed to spawn");

            float floor = 0.75f * priorAtStart;
            float epsilon = 1e-3f; // decay is pinned off, so nothing legitimate drifts the floor
            for (int death = 1; death <= 50 && killer != null; death++) {
                context.damage(player, world.getDamageSources().mobAttack(killer), 1000f);
                // A death that did not happen must fail the scenario - the first cut of this loop
                // silently no-opped deaths 2-50 (respawned players are invulnerable until re-armed)
                // and its 49 floor checks were checking nothing.
                boolean died = !player.isAlive();
                check(failures, died, "death #" + death
                        + " never happened - the lethal hit was silently a no-op");
                if (!died) {
                    break;
                }
                ServerPlayerEntity respawned = world.getServer().getPlayerManager()
                        .respawnPlayer(player, false, Entity.RemovalReason.KILLED);
                check(failures, respawned != null, "respawnPlayer returned null on death #" + death);
                if (respawned == null) {
                    break;
                }
                // Re-arm EVERY respawned player: respawnPlayer hands back a brand-new entity with
                // loaded=false / remainingLoadTicks=60 again, unconditionally invulnerable until
                // setLoaded(true) + setInvulnerable(false) are re-applied - see armForCombat.
                player = armForCombat(context, respawned, new BlockPos(0, 2, 0));
                ThreatService.invalidate(player.getUuid());
                float threat = ThreatService.threatOf(player);
                check(failures, threat >= floor - epsilon,
                        "after death #" + death + ": threatOf=" + threat + " fell below the floor "
                                + floor + " (0.75 * priorAtStart=" + priorAtStart + ")");
            }

            // The floor must have ENGAGED, not merely never been crossed: with fifty real death
            // folds competence converges onto ThreatMath.band's COMPETENCE_MIN clamp exactly, and
            // threat onto 0.75 * the (decay-frozen) prior. If anyone deletes the COMPETENCE_MIN
            // clamp, competence keeps falling past 0.75 and both of these fail.
            float finalCompetence = KindredAttachment.get(player).threat().competence();
            check(failures, Math.abs(finalCompetence - ThreatMath.COMPETENCE_MIN) <= epsilon,
                    "after 50 real deaths competence=" + finalCompetence
                            + " did not converge onto the COMPETENCE_MIN floor " + ThreatMath.COMPETENCE_MIN
                            + " - the anti-farming clamp did not engage");
            float finalThreat = ThreatService.threatOf(player);
            check(failures, Math.abs(finalThreat - floor) <= 0.01f,
                    "after 50 real deaths threatOf=" + finalThreat
                            + " is not the floored 0.75 * priorAtStart = " + floor);

            System.out.println("[8a-2] priorAtStart=" + priorAtStart + " floor=" + floor
                    + " finalCompetence=" + finalCompetence + " finalThreat=" + finalThreat);
        } finally {
            restoreConfig(snapshot);
            removeIfPresent(player);
            discardIfAlive(killer);
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
        ServerPlayerEntity a = null;
        ServerPlayerEntity b = null;
        ZombieEntity stealTarget = null;
        ZombieEntity soloTarget = null;
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.eliteChance = 0;
            Kindreds.CONFIG.escortChance = 0;
            Kindreds.CONFIG.maxHealthBonus = 0; // keep every zombie's max health at plain vanilla 20

            ServerWorld world = context.getWorld();
            a = freshPlayer(context, new BlockPos(0, 2, 0));
            b = freshPlayer(context, new BlockPos(4, 2, 0));
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
            stealTarget = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            check(failures, stealTarget != null, "steal-target zombie failed to spawn");
            float stealDelta = 0f;
            if (stealTarget != null) {
                float maxHealth = stealTarget.getMaxHealth();
                // A deliberate PROXY for real attack() swings by A, kept because of the i-frame
                // findings (see applyLethalDamage's javadoc: same-instant repeat swings are
                // structurally unreliable, and one real swing cannot stop at exactly 94%) -
                // attribution to A still holds, because playerAttack(a) routes this through the same
                // LivingEntity#damage call stack with A as the source attacker, which is exactly the
                // seam ALLOW_DAMAGE/AFTER_DAMAGE read, so A's engagement banks this whittle just as
                // real swings would.
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
            soloTarget = EntityType.ZOMBIE.spawn(world,
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
            removeIfPresent(a);
            removeIfPresent(b);
            discardIfAlive(stealTarget);
            discardIfAlive(soloTarget);
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
        ServerPlayerEntity plain = null;
        ServerPlayerEntity resistant = null;
        ZombieEntity plainKiller = null;
        ZombieEntity resistantKiller = null;
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.eliteChance = 0;
            Kindreds.CONFIG.escortChance = 0;
            Kindreds.CONFIG.maxHealthBonus = 0;

            ServerWorld world = context.getWorld();
            plain = freshPlayer(context, new BlockPos(0, 2, 0));
            resistant = freshPlayer(context, new BlockPos(4, 2, 0));
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

            plainKiller = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            resistantKiller = EntityType.ZOMBIE.spawn(world,
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
                // either player) was elsewhere - see ProvingGroundSupport#armForCombat's javadoc on
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
            removeIfPresent(plain);
            removeIfPresent(resistant);
            discardIfAlive(plainKiller);
            discardIfAlive(resistantKiller);
        }
        finish("mitigationTankingReadsTrueCost", failures);
        context.complete();
    }

    /**
     * Scenario 9: mid-fight bookkeeping (an open engagement, accumulated damage) exists when the
     * player dies. Across the real death+respawn handlers, {@code ThreatState} (competence, marks,
     * families) must survive onto the new player <b>exactly</b> - asserted directly against the
     * old player's post-death-fold competence, so a KEEP-copy bug resetting it to 1.0 fails - while
     * the fight accumulators must not.
     *
     * <p>The accumulator half is constructed so a regression cannot hide: 15 HP of the player's 20
     * are banked before death (hardship 15/20 = 0.75, far ABOVE {@code hardshipTarget} 0.25), so if
     * the stale accumulator wrongly survived, the respawned player's next clean kill would fold the
     * STRUGGLING branch instead of the coasting rise - the delta's sign flips, and its divergence
     * from an identically-stated control's clean kill (~0.005 by construction) cannot slip under the
     * 0.003 gate. The control's {@code ThreatState} is equalized to the respawned player's exact
     * pre-kill values, so the legitimate baseline gap is zero and the gate can be this tight.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400, skyAccess = true)
    public void evidenceSurvivesDeathButAccumulatorsDoNot(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        ServerPlayerEntity player = null;
        ServerPlayerEntity respawned = null;
        ServerPlayerEntity control = null;
        ZombieEntity midFight = null;
        ZombieEntity respawnedCleanTarget = null;
        ZombieEntity controlTarget = null;
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.deathPenalty = DeathPenalty.KEEP;
            Kindreds.CONFIG.eliteChance = 0;
            Kindreds.CONFIG.escortChance = 0;
            Kindreds.CONFIG.maxHealthBonus = 0;
            // Without this pin, PerkEventHandlers amplifies mob->player damage by
            // 1 + maxDamageBonus/100 * scaledAgainst (found on a real run: the 15.0 setup hit below
            // landed as ~19.75 at this player's hand-set threat and nearly killed it) - the banked
            // figure must be the exact 15.0 the sign-flip construction is computed from.
            Kindreds.CONFIG.maxDamageBonus = 0;
            // The hand-set competence figures below (1.15 global, 1.20 undead) must survive
            // refresh's re-band, which clamps to the adaptiveStrength band - pin it wide open.
            Kindreds.CONFIG.adaptiveStrength = 100;
            // Freeze the mark so "priorMark survived death unchanged" and the equalized-control
            // comparison below are exact, not phantom-decay-fuzzed.
            Kindreds.CONFIG.priorDecayPerHour = 0f;

            ServerWorld world = context.getWorld();
            player = freshPlayer(context, new BlockPos(0, 2, 0));

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

            // --- mid-fight: an open engagement (player has hit it, bare-fisted - the engagement
            // needs a real attributed hit, not damage numbers) + accumulated damage (it has hit the
            // player), neither yet closed by a kill or a death fold. Deliberately NO armor on the
            // player yet, so the raw 15.0 below is exactly the HP the accumulator banks: hardship
            // 15/20 = 0.75 sits far above hardshipTarget 0.25, which is what makes a wrongly
            // surviving accumulator SIGN-FLIP the respawned player's next fold (see class javadoc).
            midFight = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            check(failures, midFight != null, "mid-fight zombie failed to spawn");
            if (midFight != null) {
                player.attack(midFight); // open engagement, unresolved
                context.damage(player, world.getDamageSources().mobAttack(midFight), 15.0f); // banked, unresolved
            }
            check(failures, player.isAlive(), "player died from the mid-fight setup damage - too much for this test");
            check(failures, Math.abs(player.getHealth() - 5.0f) < 1e-3f,
                    "the 15.0 setup hit did not land at full value (health=" + player.getHealth()
                            + ", expected 5.0) - the banked-hardship construction is off");

            // --- death, mid-fight, to a lethal blow from the same mob ---
            context.damage(player, world.getDamageSources().mobAttack(
                    midFight != null ? midFight : player), 1000f);
            check(failures, !player.isAlive(), "the lethal blow did not kill the player - no death to survive");
            // The death fold lands on the OLD player's state before COPY_FROM runs - capture it so
            // "survived exactly" below is byte-for-byte, not a range guess.
            float competenceAfterDeathFold = KindredAttachment.get(player).threat().competence();
            check(failures, competenceAfterDeathFold < 1.15f,
                    "the death was not folded as evidence (competence still " + competenceAfterDeathFold + ")");
            respawned = world.getServer().getPlayerManager()
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
                // Direct, exact: the KEEP copy must carry the post-death-fold competence onto the
                // new player untouched - a copy bug that resets it to the 1.0 default must fail here,
                // not hide behind the delta comparison below. Captured now (like priorSurvived above):
                // the clean kill below folds competence again, so this exact equality only exists at
                // this moment.
                boolean competenceSurvived = afterData.threat().competence() == competenceAfterDeathFold;
                check(failures, competenceSurvived,
                        "competence did not survive death exactly: " + competenceAfterDeathFold + " -> "
                                + afterData.threat().competence());
                boolean familySurvived = afterData.threat().familyCompetence().containsKey("undead");
                check(failures, familySurvived,
                        "per-family competence for 'undead' did not survive death");

                // --- the respawned player's next kill must pay no stale credit ---
                armForCombat(context, respawned, new BlockPos(0, 2, 12)); // fresh entity: re-arm, reposition
                equipNetherite(respawned);
                applyLethalDamage(respawned);
                settleEquipment(respawned);
                ThreatService.invalidate(respawned.getUuid());
                ThreatService.threatOf(respawned);

                // Equalization snapshot for the control, taken at the exact moment the respawned
                // player is about to make its clean kill - the control folds from an identical
                // ThreatState, so any delta divergence below is stale bookkeeping, never baseline.
                float priorSnap = afterData.threat().priorMark();
                float maxHealthMarkSnap = afterData.threat().maxHealthMark();
                Float undeadSnap = afterData.threat().familyCompetence().get("undead");
                float respawnedBefore = afterData.threat().competence();

                respawnedCleanTarget = EntityType.ZOMBIE.spawn(world,
                        context.getAbsolutePos(new BlockPos(2, 2, 12)), SpawnReason.COMMAND);
                if (respawnedCleanTarget != null) {
                    closeOut(respawned, respawnedCleanTarget, 5);
                    check(failures, !respawnedCleanTarget.isAlive(),
                            "respawned player's attacks did not kill the clean target");
                }
                float respawnedAfter = afterData.threat().competence();
                float respawnedDelta = respawnedAfter - respawnedBefore;

                // --- control: a fresh player, ThreatState equalized to the respawned player's exact
                // pre-kill values, performing the identical clean kill ---
                control = freshPlayer(context, new BlockPos(0, 2, 20));
                equipNetherite(control);
                applyLethalDamage(control);
                settleEquipment(control);
                var controlThreat = KindredAttachment.get(control).threat();
                controlThreat.setPriorMark(priorSnap);
                controlThreat.setCompetence(respawnedBefore);
                controlThreat.setMaxHealthMark(maxHealthMarkSnap);
                if (undeadSnap != null) {
                    controlThreat.familyCompetence().put("undead", undeadSnap);
                }
                ThreatService.invalidate(control.getUuid());
                ThreatService.threatOf(control);

                controlTarget = EntityType.ZOMBIE.spawn(world,
                        context.getAbsolutePos(new BlockPos(2, 2, 24)), SpawnReason.COMMAND);
                float controlBefore = controlThreat.competence();
                if (controlTarget != null) {
                    closeOut(control, controlTarget, 5);
                    check(failures, !controlTarget.isAlive(),
                            "control player's attacks did not kill its clean target");
                }
                float controlAfter = controlThreat.competence();
                float controlDelta = controlAfter - controlBefore;

                // A clean kill from a coasting position must RISE - a surviving 15-HP accumulator
                // flips this fold onto the struggling branch and the delta goes negative.
                check(failures, respawnedDelta > 0f,
                        "post-death clean kill delta (" + respawnedDelta + ") is not a rise - stale "
                                + "pre-death hardship appears to have folded into it");
                // And it must match the equalized control's identical kill to well under the
                // ~0.005 divergence a surviving accumulator produces by construction.
                check(failures, Math.abs(respawnedDelta - controlDelta) < 0.003f,
                        "post-death clean kill delta (" + respawnedDelta + ") diverges from the "
                                + "state-equalized control's identical clean kill (" + controlDelta
                                + ") - stale fight bookkeeping may have survived death");

                System.out.println("[8a-9] priorSurvived=" + priorSurvived
                        + " competenceSurvived=" + competenceSurvived
                        + " familySurvived=" + familySurvived
                        + " respawnedDelta=" + respawnedDelta + " controlDelta=" + controlDelta);
            }
        } finally {
            restoreConfig(snapshot);
            // The old player entity was consumed by respawnPlayer (same UUID as the respawned) -
            // remove whichever incarnation is currently tracked, never both.
            removeIfPresent(respawned != null ? respawned : player);
            removeIfPresent(control);
            discardIfAlive(midFight);
            discardIfAlive(respawnedCleanTarget);
            discardIfAlive(controlTarget);
        }
        finish("evidenceSurvivesDeathButAccumulatorsDoNot", failures);
        context.complete();
    }
}
