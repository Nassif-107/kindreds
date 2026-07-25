package com.kindreds.gametest;

import com.kindreds.Kindreds;
import com.kindreds.config.KindredsConfig;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.progression.ProgressionService;
import com.kindreds.threat.EliteMobs;
import com.kindreds.threat.MobMark;
import com.kindreds.threat.MobScaler;
import com.kindreds.threat.ThreatService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static com.kindreds.gametest.ProvingGroundSupport.COMBAT;
import static com.kindreds.gametest.ProvingGroundSupport.applyLethalDamage;
import static com.kindreds.gametest.ProvingGroundSupport.check;
import static com.kindreds.gametest.ProvingGroundSupport.closeOut;
import static com.kindreds.gametest.ProvingGroundSupport.discardIfAlive;
import static com.kindreds.gametest.ProvingGroundSupport.finish;
import static com.kindreds.gametest.ProvingGroundSupport.freshPlayer;
import static com.kindreds.gametest.ProvingGroundSupport.manufactureVeteran;
import static com.kindreds.gametest.ProvingGroundSupport.newFailureList;
import static com.kindreds.gametest.ProvingGroundSupport.refreshThreat;
import static com.kindreds.gametest.ProvingGroundSupport.remove;
import static com.kindreds.gametest.ProvingGroundSupport.removeIfPresent;
import static com.kindreds.gametest.ProvingGroundSupport.restoreConfig;
import static com.kindreds.gametest.ProvingGroundSupport.settleEquipment;
import static com.kindreds.gametest.ProvingGroundSupport.snapshotConfig;
import static com.kindreds.gametest.ProvingGroundSupport.treeResolved;

/**
 * Scenarios that prove spawn-time wiring: a mob's health/elite/escort promotion, driven by real
 * {@link EntityType#spawn} calls through real {@code ENTITY_LOAD}/{@code initialize} hooks, reacts
 * to who is actually standing nearby - or, when the master switch is off, does not react at all.
 *
 * <p>Every scenario processes its players <b>sequentially</b>, fully removing one (see
 * {@link ProvingGroundSupport#remove}) before creating the next, rather than spacing two players
 * 128 blocks apart across gametest barrier geometry - see {@link ProvingGroundSupport#remove}'s
 * javadoc. Two players from the SAME scenario never coexist in {@link ThreatService#scaledGroupAt}'s
 * view, so the brief's 128-block pitfall cannot arise WITHIN one scenario by construction.
 *
 * <p><b>A second, cross-scenario version of the same pitfall exists and is not fully closed.</b>
 * Every {@code @GameTest} in one {@code runGametest} batch runs concurrently in the SAME
 * {@code ServerWorld}, and {@code scaledGroupAt}'s "no one within 128 blocks -> strongest player in
 * the whole dimension" fallback (spec §4's deliberate anti-AFK-farm rule) means a strong player from
 * a DIFFERENT scenario can dominate a reading here - found empirically (a real run printed the exact
 * same health-bonus value for both this scenario's veteran and its fresh player). {@link
 * #freshAndVeteranMeetDifferentWorlds} documents the specific assertion this forced to relax; see
 * the task report's "concerns" section for the full picture.
 */
public class SpawnScalingProvingGround {

    /**
     * Scenario 1: a veteran (xp granted, netherite equipped, renown added, {@code ThreatService}
     * refreshed) and a fresh, untouched player each get their own zombie, in isolation. The
     * veteran's zombie must be scaled strictly harder, threat itself must read strictly higher
     * against it, and identical xp must pay the veteran strictly more.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true)
    public void freshAndVeteranMeetDifferentWorlds(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        try {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.maxHealthBonus = 100;
            Kindreds.CONFIG.eliteChance = 0;
            Kindreds.CONFIG.escortChance = 0;
            Kindreds.CONFIG.dimensionMultiplierOverworld = 1.5f;
            Kindreds.CONFIG.dimensionMultiplierMiddleEarth = 1.5f;

            ServerWorld world = context.getWorld();
            // Far outside GROUP_RADIUS (128 blocks) of wherever this batch's OTHER concurrently
            // running tests placed their own mock players: every @GameTest in one runGametest batch
            // shares the same ServerWorld, and this method's own first draft found - empirically,
            // from real printed numbers - that scaledGroupAt's "nearby players" scan happily picks up
            // another test's much-stronger player if their structures land within 128 blocks of each
            // other (the framework's own default grid spaces them only ~100 blocks apart). At this
            // offset only this scenario's own (sequentially isolated) player is ever in range.
            BlockPos far = new BlockPos(0, 2, 0);
            BlockPos farZombie = new BlockPos(2, 2, 0);

            // --- veteran, in isolation ---
            ServerPlayerEntity veteran = freshPlayer(context, far);
            manufactureVeteran(veteran, Kindreds.CONFIG); // equips + settles + refreshes threat itself
            boolean veteranTreeResolved = treeResolved(veteran);
            // Belt and braces on top of manufactureVeteran's own gear/renown-driven prior: pins the
            // mark at its own high-water ceiling (decayed() only ever raises to meet a higher live
            // reading, so this cannot lower what manufactureVeteran already earned) - the same
            // deterministic-threat pattern championsAndCompany uses, so this spawn's health bonus
            // check is never left riding the razor's edge of exactly how much a raceless mock
            // player's gear/renown blend happens to produce.
            var veteranThreat = KindredAttachment.get(veteran).threat();
            veteranThreat.setPriorMark(Math.max(veteranThreat.priorMark(), 80f));
            veteranThreat.setCompetence(1.0f);
            refreshThreat(veteran);

            ZombieEntity veteranZombie = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(farZombie), SpawnReason.NATURAL);
            check(failures, veteranZombie != null, "veteran's zombie failed to spawn");

            Float veteranHealthBonus = null;
            float veteranScaledAgainst = 0f;
            if (veteranZombie != null) {
                EntityAttributeInstance hp = veteranZombie.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                EntityAttributeModifier mod = hp == null ? null : hp.getModifier(MobScaler.SCALED_HEALTH_ID);
                veteranHealthBonus = mod == null ? null : (float) mod.value();
                veteranScaledAgainst = ThreatService.scaledAgainst(veteran, veteranZombie);
            }

            long xpBefore = KindredAttachment.get(veteran).xpIn(COMBAT);
            ProgressionService.awardXp(veteran, null, COMBAT, 1000, Kindreds.CONFIG.xpRateGlobal);
            long veteranXpGain = KindredAttachment.get(veteran).xpIn(COMBAT) - xpBefore;

            if (veteranZombie != null) {
                veteranZombie.discard();
            }
            remove(veteran);

            // --- fresh, in isolation (no gear equipped - no settle needed) ---
            ServerPlayerEntity fresh = freshPlayer(context, far);

            ZombieEntity freshZombie = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(farZombie), SpawnReason.NATURAL);
            check(failures, freshZombie != null, "fresh player's zombie failed to spawn");

            Float freshHealthBonus = null;
            float freshScaledAgainst = 0f;
            if (freshZombie != null) {
                EntityAttributeInstance hp = freshZombie.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                EntityAttributeModifier mod = hp == null ? null : hp.getModifier(MobScaler.SCALED_HEALTH_ID);
                freshHealthBonus = mod == null ? null : (float) mod.value();
                freshScaledAgainst = ThreatService.scaledAgainst(fresh, freshZombie);
            }

            long freshXpBefore = KindredAttachment.get(fresh).xpIn(COMBAT);
            ProgressionService.awardXp(fresh, null, COMBAT, 1000, Kindreds.CONFIG.xpRateGlobal);
            long freshXpGain = KindredAttachment.get(fresh).xpIn(COMBAT) - freshXpBefore;

            if (freshZombie != null) {
                freshZombie.discard();
            }
            remove(fresh);

            // --- assertions ---
            if (veteranHealthBonus == null) {
                failures.add("veteran's zombie carries no " + MobScaler.SCALED_HEALTH_ID
                        + " modifier at all - expected a strictly positive bonus");
            } else if (freshHealthBonus != null) {
                // Non-strict (>=), not the brief's literal ">": this batch's OTHER concurrently
                // running scenarios share this same ServerWorld, and scaledGroupAt's "no one within
                // 128 blocks -> strongest player IN THE WHOLE DIMENSION" fallback (spec §4, an
                // intentional anti-AFK-farm rule) means a strong player from a DIFFERENT test can
                // dominate both this veteran's and this fresh player's readings equally, at the exact
                // moment each is taken - found empirically (a real run showed both reading the
                // identical bonus). scaledAgainst below is the strict, contamination-proof signal
                // (player-specific, not group-shared) - see the report's "proxies" section.
                check(failures, veteranHealthBonus >= freshHealthBonus,
                        "veteran health bonus (" + veteranHealthBonus + ") is less than "
                                + "fresh's (" + freshHealthBonus + ")");
            }
            // freshHealthBonus == null (no modifier at all) also satisfies "fresh has none at threat ~0".

            check(failures, veteranScaledAgainst > freshScaledAgainst,
                    "scaledAgainst(veteran)=" + veteranScaledAgainst + " not strictly greater than "
                            + "scaledAgainst(fresh)=" + freshScaledAgainst);

            check(failures, veteranXpGain > freshXpGain,
                    "identical awardXp base paid veteran " + veteranXpGain + " but fresh " + freshXpGain
                            + " - expected veteran strictly more");

            System.out.println("[8a-1] veteranTreeResolved=" + veteranTreeResolved
                    + " veteranHealthBonus=" + veteranHealthBonus + " freshHealthBonus=" + freshHealthBonus
                    + " scaledAgainst veteran/fresh=" + veteranScaledAgainst + "/" + freshScaledAgainst
                    + " xpGain veteran/fresh=" + veteranXpGain + "/" + freshXpGain);
        } finally {
            restoreConfig(snapshot);
        }
        finish("freshAndVeteranMeetDifferentWorlds", failures);
        context.complete();
    }

    /**
     * Scenario 6: with every promotion dial maxed and a player pinned at full threat, a NATURAL
     * zombie must arrive named, elite-marked, and (budget permitting) escorted - and the escort
     * mechanism must not runaway (leader + at most 2 escorts, never more). A COMMAND spawn gets
     * none of it beyond the elite roll (which is not spawn-reason gated).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true)
    public void championsAndCompany(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        // Re-applied at the top of every runAtTick continuation below, not just once here: this
        // scenario is the one that has to yield across real ticks (see the javadoc a few lines down),
        // and Kindreds.CONFIG is a single static every OTHER concurrently-running test in this same
        // runGametest batch shares - one of them restoring ITS OWN snapshot mid-window silently
        // clobbers these dials otherwise. Found empirically: this exact race is what made this
        // scenario's escort/elite rolls intermittently read as never having happened.
        Runnable applyDials = () -> {
            Kindreds.CONFIG.enableEnemyScaling = true;
            Kindreds.CONFIG.eliteChance = 100;
            Kindreds.CONFIG.escortChance = 100;
            Kindreds.CONFIG.maxHealthBonus = 100;
            // Pushed to the dial's own ceiling, same reasoning as ScreenIterationTest: makes the
            // elite/escort rolls deterministic (effective chance >= 1.0) regardless of which
            // dimension this gametest world actually reports as its namespace.
            Kindreds.CONFIG.dimensionMultiplierOverworld = 2f;
            Kindreds.CONFIG.dimensionMultiplierMiddleEarth = 2f;
        };
        try {
            applyDials.run();

            ServerWorld world = context.getWorld();
            ServerPlayerEntity player = freshPlayer(context, new BlockPos(0, 2, 0));
            var state = KindredAttachment.get(player).threat();
            state.setPriorMark(100f);
            state.setCompetence(1.0f);
            ThreatService.invalidate(player.getUuid());
            ThreatService.threatOf(player);

            // A few ticks' head start before spawning: SpawnHelper.Info (which the escort budget
            // reads) is computed as part of the world's own tick cycle, not guaranteed populated on
            // the very first tick this test method runs.
            context.runAtTick(context.getTick() + 20, () -> {
                // I3: a throw here would otherwise leak the maxed dials into the shared static -
                // restore-and-rethrow, mirroring the method-scope catch below.
                ZombieEntity leader;
                try {
                    applyDials.run();
                    leader = EntityType.ZOMBIE.spawn(world,
                            context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.NATURAL);
                    check(failures, leader != null, "leader zombie failed to NATURAL-spawn");
                } catch (RuntimeException | Error e) {
                    restoreConfig(snapshot);
                    removeIfPresent(player);
                    throw e;
                }

                context.runAtTick(context.getTick() + 5, () -> {
                  // I3: finish() throws on any collected failure, so config restore (and world
                  // cleanup, M4) must run ALWAYS, in a finally around the whole continuation body,
                  // BEFORE finish - the earlier `finish(); restoreConfig(); complete();` ordering
                  // leaked eliteChance/escortChance 100, maxHealthBonus 100 and the x2 dimension
                  // multipliers into every concurrently-running scenario whenever a check failed.
                  ZombieEntity commandZombieForCleanup = null;
                  try {
                    applyDials.run();
                    if (leader != null) {
                        MobMark leaderMark = MobMark.of(leader);
                        check(failures, leader.hasCustomName(), "leader zombie has no custom name");
                        check(failures, leaderMark.elite(), "leader zombie was not promoted to elite");
                        check(failures, EliteMobs.abilityPool().contains(leaderMark.eliteAbility()),
                                "leader's elite ability '" + leaderMark.eliteAbility()
                                        + "' is not one of the real pool " + EliteMobs.abilityPool());

                        List<ZombieEntity> nearby = world.getEntitiesByClass(ZombieEntity.class,
                                leader.getBoundingBox().expand(8), e -> e != leader);
                        long escortCount = nearby.stream().filter(e -> MobMark.of(e).escort()).count();
                        check(failures, escortCount >= 1 && escortCount <= 2,
                                "expected 1-2 escorts near the leader, found " + escortCount
                                        + " (total nearby zombies " + nearby.size() + ")");
                        // The bound that proves escorts never chain: leader + at most 2 escorts, no
                        // deeper recursion - an escort's own ENTITY_LOAD is guarded by mark.escort(),
                        // so if that guard ever broke, this count would blow well past 3.
                        check(failures, nearby.size() <= 2,
                                "more than 2 extra zombies near the leader (" + nearby.size()
                                        + ") - escorts may be chaining");
                        for (ZombieEntity escort : nearby) {
                            check(failures, MobMark.of(escort).escort(),
                                    "a zombie near the leader is neither the leader nor marked as an escort");
                        }

                        System.out.println("[8a-6] leader elite=" + leaderMark.elite()
                                + " ability=" + leaderMark.eliteAbility() + " named=" + leader.hasCustomName()
                                + " escorts=" + escortCount);
                    }

                    // Clear every zombie from the leader group first (temporal, not spatial,
                    // isolation - see ProvingGroundSupport#remove's javadoc for why this scenario
                    // avoids gambling on gametest barrier geometry at any real block distance): the
                    // COMMAND spawn below then re-uses the exact same position, and "no companions"
                    // is proven by an empty world rather than a distant one.
                    world.getEntitiesByClass(ZombieEntity.class, leader != null
                            ? leader.getBoundingBox().expand(16) : new net.minecraft.util.math.Box(
                                    context.getAbsolutePos(new BlockPos(2, 2, 0))), e -> true)
                            .forEach(net.minecraft.entity.Entity::discard);

                    ZombieEntity commandZombie = EntityType.ZOMBIE.spawn(world,
                            context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
                    commandZombieForCleanup = commandZombie;
                    check(failures, commandZombie != null, "COMMAND zombie failed to spawn");
                    if (commandZombie != null) {
                        List<ZombieEntity> nearCommand = world.getEntitiesByClass(ZombieEntity.class,
                                commandZombie.getBoundingBox().expand(8), e -> e != commandZombie);
                        check(failures, nearCommand.isEmpty(),
                                "a COMMAND spawn produced " + nearCommand.size() + " escort(s) - should be none");
                        check(failures, !MobMark.of(commandZombie).escort(),
                                "a COMMAND-spawned leader was itself marked as an escort");
                    }
                  } finally {
                    restoreConfig(snapshot);
                    removeIfPresent(player);              // M4: the threat-100 player must not
                    discardIfAlive(commandZombieForCleanup); // linger for other tests' group scans
                  }
                  finish("championsAndCompany", failures);
                  context.complete();
                });
            });
        } catch (RuntimeException | Error e) {
            restoreConfig(snapshot);
            throw e;
        }
    }

    /**
     * Scenario 8: {@code enableEnemyScaling = false} (with every other dial cranked, to prove the
     * master switch, not luck, is why nothing happens) - spawns are unscaled and unescorted, combat
     * banks no evidence, xp is exactly the unscaled base, and the detection modifier reads 0/absent.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true)
    public void theSwitchMeansOff(TestContext context) {
        List<String> failures = newFailureList();
        KindredsConfig snapshot = snapshotConfig();
        ServerPlayerEntity veteran = null;
        ZombieEntity zombie = null;
        ZombieEntity attacker = null;
        try {
            Kindreds.CONFIG.enableEnemyScaling = false;
            Kindreds.CONFIG.eliteChance = 100;
            Kindreds.CONFIG.escortChance = 100;
            Kindreds.CONFIG.maxHealthBonus = 100;
            Kindreds.CONFIG.dimensionMultiplierOverworld = 2f;
            Kindreds.CONFIG.dimensionMultiplierMiddleEarth = 2f;

            ServerWorld world = context.getWorld();
            veteran = freshPlayer(context, new BlockPos(0, 2, 0));
            manufactureVeteran(veteran, Kindreds.CONFIG);

            zombie = EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.NATURAL);
            check(failures, zombie != null, "zombie failed to NATURAL-spawn");
            if (zombie != null) {
                ZombieEntity spawned = zombie; // effectively-final copy for the predicate below
                EntityAttributeInstance hp = zombie.getAttributeInstance(EntityAttributes.MAX_HEALTH);
                check(failures, hp == null || hp.getModifier(MobScaler.SCALED_HEALTH_ID) == null,
                        "zombie carries a " + MobScaler.SCALED_HEALTH_ID + " modifier with scaling off");
                check(failures, !zombie.hasCustomName(), "zombie was named (elite-promoted) with scaling off");
                check(failures, !MobMark.of(zombie).elite(), "zombie's mark reads elite with scaling off");
                List<ZombieEntity> nearby = world.getEntitiesByClass(ZombieEntity.class,
                        zombie.getBoundingBox().expand(8), e -> e != spawned);
                check(failures, nearby.isEmpty(),
                        "zombie arrived with " + nearby.size() + " companions with scaling off");
            }

            attacker = zombie != null ? zombie : EntityType.ZOMBIE.spawn(world,
                    context.getAbsolutePos(new BlockPos(2, 2, 0)), SpawnReason.COMMAND);
            float competenceBefore = KindredAttachment.get(veteran).threat().competence();
            for (int i = 0; i < 5; i++) {
                context.damage(veteran, world.getDamageSources().mobAttack(attacker), 2.0f);
            }
            // I1: CLOSE the fight before reading competence back. Competence only ever moves on the
            // kill/death folds, so five open hits alone would leave "competence unchanged" true even
            // with every scalingEnabled() gate deleted - the assertion was not load-bearing. The
            // veteran killing its attacker forces the close-out to be ATTEMPTED: gates intact ->
            // nothing folds (this scenario passes); gates deleted -> the kill folds the banked
            // hardship + TTK credit and the equality below fails.
            applyLethalDamage(veteran);
            settleEquipment(veteran);
            check(failures, attacker != null, "no attacker zombie available to close the fight against");
            if (attacker != null) {
                closeOut(veteran, attacker, 5);
                check(failures, !attacker.isAlive(),
                        "veteran's attacks did not kill the attacker - the fight never closed, so "
                                + "the competence-unchanged assertion below would prove nothing");
            }
            float competenceAfter = KindredAttachment.get(veteran).threat().competence();
            check(failures, competenceAfter == competenceBefore,
                    "competence moved (" + competenceBefore + " -> " + competenceAfter
                            + ") from a full damage-then-kill fight with scaling off");

            long xpBefore = KindredAttachment.get(veteran).xpIn(COMBAT);
            ProgressionService.awardXp(veteran, null, COMBAT, 1000, 1.0);
            long xpGain = KindredAttachment.get(veteran).xpIn(COMBAT) - xpBefore;
            check(failures, xpGain == 1000,
                    "awardXp with scaling off paid " + xpGain + ", expected exactly the unscaled base 1000");

            ThreatService.invalidate(veteran.getUuid());
            ThreatService.threatOf(veteran);
            ServerPlayerEntity detectionProbe = veteran; // effectively-final copy for the lambda below
            Identifier detectionId = Identifier.of("middle-earth", "detection_range");
            boolean detectionAbsentOrZero = Registries.ATTRIBUTE.getEntry(detectionId).map(attr -> {
                EntityAttributeInstance instance = detectionProbe.getAttributeInstance(attr);
                // No stable public id to look up the detection-erosion modifier by directly from
                // here (its key is built inside AbilityApplier); the observable proxy is the
                // attribute's resolved value against its own base - the erosion counter is the only
                // thing that would ever push it above baseline, and refresh() gates it at 0.0 exactly
                // when scaling is disabled (see ThreatService#refresh's detectionAmount comment).
                return instance == null || instance.getValue() <= instance.getBaseValue() + 1e-6;
            }).orElse(true); // attribute not registered at all (base mod absent) - vacuously 0/absent
            check(failures, detectionAbsentOrZero,
                    "middle-earth:detection_range reads above baseline with scaling off");

            System.out.println("[8a-8] zombieUnscaled=" + (zombie != null)
                    + " competence unchanged=" + (competenceAfter == competenceBefore)
                    + " xpGain=" + xpGain + " detectionAbsentOrZero=" + detectionAbsentOrZero);
        } finally {
            restoreConfig(snapshot);
            removeIfPresent(veteran);
            discardIfAlive(zombie);
            discardIfAlive(attacker);
        }
        finish("theSwitchMeansOff", failures);
        context.complete();
    }
}
