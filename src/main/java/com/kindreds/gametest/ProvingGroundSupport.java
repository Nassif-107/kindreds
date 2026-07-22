package com.kindreds.gametest;

import com.google.gson.Gson;
import com.kindreds.Kindreds;
import com.kindreds.config.KindredsConfig;
import com.kindreds.data.SkillTree;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import com.kindreds.progression.ProgressionService;
import com.kindreds.progression.UnlockService;
import com.kindreds.threat.ThreatService;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared plumbing every {@code *ProvingGround} scenario needs: config snapshot/restore (§ the
 * brief's global pitfall - {@code Kindreds.CONFIG} is a single mutable static), fresh
 * {@code ServerPlayerEntity} mock players positioned in the live world, gearing them up, and a
 * "manufacture a veteran" recipe reused by more than one scenario.
 *
 * <h2>Why {@code createMockCreativeServerPlayerInWorld}, not {@code createMockPlayer}</h2>
 * javap against the 1.21.8 merged jar: {@code TestContext.createMockPlayer(GameMode)} returns a
 * plain {@code PlayerEntity} (its anonymous impl class, {@code TestContext$1}, extends
 * {@code PlayerEntity}) - too shallow for this mod's threat system, which is typed against
 * {@code ServerPlayerEntity} throughout ({@code ThreatService}, {@code ThreatEvidence},
 * {@code ProgressionService.awardXp(ServerPlayerEntity, ...)}, {@code RenownService}...). Only
 * {@code createMockCreativeServerPlayerInWorld()} returns a real {@code ServerPlayerEntity} - it
 * also actually joins (fires {@code ServerPlayConnectionEvents.JOIN} via a real, if fake-channel,
 * {@code PlayerManager#onPlayerConnect}), which is the "real event hooks" the brief asks for.
 *
 * <p>Its hardcoded {@code getGameMode() == CREATIVE} (verified by disassembling
 * {@code TestContext$2}) would otherwise leave the mock invulnerable to the mob damage several
 * scenarios need - {@link #freshPlayer} clears {@code PlayerAbilities#invulnerable} directly (a
 * public, mutable field) rather than trusting {@code changeGameMode} to undo an override that skips
 * the game-mode field it would normally read back from.
 */
public final class ProvingGroundSupport {
    private ProvingGroundSupport() {
    }

    private static final Gson GSON = new Gson();

    public static final Identifier COMBAT = Identifier.of(Kindreds.MOD_ID, "combat");

    // --- Config snapshot/restore -----------------------------------------------------------------

    /** A deep copy of {@link Kindreds#CONFIG} right now - {@link KindredsConfig} is plain public
     * fields of primitives/enums/String, so a Gson round trip is a complete, independent copy. */
    public static KindredsConfig snapshotConfig() {
        return GSON.fromJson(GSON.toJson(Kindreds.CONFIG), KindredsConfig.class);
    }

    /** Restores a snapshot taken by {@link #snapshotConfig()}. Every config-touching scenario must
     * call this in a {@code finally} block (see the brief's global pitfall list) - {@code
     * Kindreds.CONFIG} is a single static shared by every other scenario and the real server. */
    public static void restoreConfig(KindredsConfig snapshot) {
        Kindreds.CONFIG = snapshot;
    }

    // --- Mock players ------------------------------------------------------------------------------

    /**
     * A fresh, real, connected {@code ServerPlayerEntity} (see the class javadoc for why not {@code
     * createMockPlayer}), positioned at {@code relativePos} within the test structure and able to
     * take damage. A brand-new random UUID each call, so its {@link KindredData} is always the
     * untouched default - "fresh mock players per scenario", per the brief.
     */
    public static ServerPlayerEntity freshPlayer(TestContext context, BlockPos relativePos) {
        return armForCombat(context, context.createMockCreativeServerPlayerInWorld(), relativePos);
    }

    /**
     * The full arm-for-combat sequence a mock player needs before any {@code damage()} call against
     * it can land, positioned at {@code relativePos} and healed to full. Split out of
     * {@link #freshPlayer} because it must be re-applied to EVERY player object {@code
     * PlayerManager#respawnPlayer} hands back, not just freshly-created ones: a respawned {@code
     * ServerPlayerEntity} is a brand-new entity with {@code loaded=false, remainingLoadTicks=60}
     * again (javap-verified), so without re-arming, every hit against it is silently a no-op - the
     * exact bug that made {@code fiftyDeathsNeverBreakTheFloor}'s deaths 2-50 never happen on this
     * task's first review.
     */
    public static ServerPlayerEntity armForCombat(TestContext context, ServerPlayerEntity player,
                                                   BlockPos relativePos) {
        player.changeGameMode(GameMode.SURVIVAL);
        // Belt and braces: TestContext$2 hardcodes getGameMode() to CREATIVE regardless of the
        // changeGameMode call above (see class javadoc) - clearing the ability flag directly is
        // what actually stops isInvulnerableTo from reading this player as untouchable.
        player.getAbilities().invulnerable = false;
        player.getAbilities().allowFlying = false;
        player.getAbilities().flying = false;
        player.getAbilities().creativeMode = false;
        // A second, independent invulnerability flag, found empirically (a diagnostic printing
        // health before/after a real damage() call showed it never moving even with the ability
        // flag above already false). Disassembling Entity#isAlwaysInvulnerableTo (javap -c) shows it
        // gates on the ENTITY-level `invulnerable` field (Entity#isInvulnerable/#setInvulnerable) -
        // a completely separate flag from PlayerAbilities#invulnerable. Harmless to clear regardless
        // of whether it was ever actually set here.
        player.setInvulnerable(false);
        // The REAL cause, found by disassembling ServerPlayerEntity#isInvulnerableTo one level
        // deeper (javap -c): past the two flags above, it also returns true unconditionally while
        // {@code !isLoaded()}. PlayerEntity#isLoaded() is {@code this.loaded || this.remainingLoadTicks
        // <= 0} - `loaded` is set true by the real client's own "chunks are loaded" acknowledgement,
        // which a fake-connection mock player never sends, and `remainingLoadTicks` only counts down
        // via real server ticks (PlayerEntity#tickLoaded, called from the entity's own tick()) - a
        // 60-tick grace period (see PlayerEntity#setLoaded's own initializer) neither of this mod's
        // synchronous test bodies nor a couple of manual player.tick() calls come close to exhausting.
        // Without this, EVERY mob-sourced (and player-sourced) damage call against a mock player in
        // this environment is silently a no-op - the actual bug behind this task's hardest-to-find
        // failure (mitigationTankingReadsTrueCost: a real run's diagnostic showed player health never
        // moving at all, for either player, despite both invulnerability flags above already false).
        // PlayerEntity#setLoaded(boolean) is public - the same real API the client's own
        // acknowledgement packet handler calls server-side, just invoked directly here instead of
        // waiting on a client that does not exist.
        player.setLoaded(true);
        BlockPos abs = context.getAbsolutePos(relativePos);
        player.refreshPositionAndAngles(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 0f, 0f);
        player.setHealth(player.getMaxHealth());
        return player;
    }

    /** Removes {@code player} from the world entirely - used to sequentially isolate two players a
     * single scenario needs "in different worlds" of (spec-speak for {@code scaledGroupAt}): rather
     * than gambling on the 128-block spatial radius across gametest barrier geometry, the veteran is
     * fully discarded before the fresh player (and its own mob) is ever created, so neither can ever
     * appear in the other's {@code world.getPlayers()} scan. */
    public static void remove(ServerPlayerEntity player) {
        player.getServer().getPlayerManager().remove(player);
        player.discard();
    }

    /** Null-safe {@link #remove} for a scenario's {@code finally} block (M4): every scenario must
     * clear its own mock players out of the shared world alongside restoring config, or {@code
     * ThreatService#scaledGroupAt}'s whole-dimension fallback keeps reading this scenario's
     * strongest leftover player from every OTHER concurrently- and later-running test - the
     * cross-test contamination the task-8a review flagged. */
    public static void removeIfPresent(ServerPlayerEntity player) {
        if (player != null) {
            remove(player);
        }
    }

    /** Null-safe end-of-scenario discard for a mob (M4), for the same {@code finally} blocks as
     * {@link #removeIfPresent}. Only a still-alive mob needs it - a killed one is removed by its
     * own death handling - so this mostly matters on failure paths that bailed before the kill. */
    public static void discardIfAlive(Entity mob) {
        if (mob != null && mob.isAlive()) {
            mob.discard();
        }
    }

    // --- Gearing -------------------------------------------------------------------------------

    /** Fixed ids for the attribute-modifier reinforcement {@link #equipNetherite} applies - see
     * that method's javadoc. Remove-then-add (the {@code MobScaler#SCALED_HEALTH_ID} lesson) keeps
     * repeated calls idempotent rather than compounding. */
    private static final Identifier GEAR_ARMOR_ID = Identifier.of(Kindreds.MOD_ID, "test/gear_armor");
    private static final Identifier GEAR_DAMAGE_ID = Identifier.of(Kindreds.MOD_ID, "test/gear_damage");

    /**
     * Full netherite armor + sword - the "full-mithril reference" {@code ThreatService#gearOf}
     * measures against (its comment literally reads "25 is about full mithril" for armor and "12 is
     * about a mithril sword" for damage; netherite clears both) - <b>plus</b> a direct attribute
     * modifier reinforcing the same armor/attack-damage values.
     *
     * <p><b>Why the reinforcement, verified empirically on a real run (not guessed):</b> a
     * diagnostic print placed right after {@code equipStack} showed {@code getEquippedStack} correctly
     * returning the netherite item in every slot, yet {@code getAttributeValue(ARMOR)} read exactly
     * {@code 0.0} and {@code getAttributeValue(ATTACK_DAMAGE)} exactly {@code 1.0} (the bare-handed
     * base) - on this mock {@code ServerPlayerEntity}, equipping an item never installs its {@code
     * AttributeModifiersComponent} onto the attribute container at all, tick or no tick (disassembling
     * {@code LivingEntity.updateAttributes()} confirmed it only clamps health/absorption/scale/waypoint
     * tracking on an attribute CHANGE - it is not what equipment attribute modifiers even go through).
     * Status effects, by contrast, DO apply their modifiers immediately (the same diagnostic run
     * showed a Strength-buffed mock player's attack damage correctly reflecting the buff) - so whatever
     * player-inventory/equipment-sync path real armor attribute modifiers travel through evidently
     * needs more of a real client round trip than this mock player's fake connection provides. Adding
     * the modifier directly is the honest, documented workaround: same public {@code
     * EntityAttributeInstance.addPersistentModifier} API real items use internally, just applied
     * without waiting on a pipeline this environment cannot complete.
     */
    public static void equipNetherite(ServerPlayerEntity player) {
        player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
        player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
        player.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));

        EntityAttributeInstance armor = player.getAttributeInstance(EntityAttributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(GEAR_ARMOR_ID);
            armor.addPersistentModifier(new EntityAttributeModifier(GEAR_ARMOR_ID, 20.0,
                    EntityAttributeModifier.Operation.ADD_VALUE)); // vanilla full netherite armor value
        }
        EntityAttributeInstance damage = player.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.removeModifier(GEAR_DAMAGE_ID);
            damage.addPersistentModifier(new EntityAttributeModifier(GEAR_DAMAGE_ID, 8.0,
                    EntityAttributeModifier.Operation.ADD_VALUE)); // netherite sword's own bonus damage
        }
    }

    /** Strips every equipment slot back to empty, and removes {@link #equipNetherite}'s attribute
     * reinforcement too - the "strip all gear" half of {@code theMarkNeverForgets}. */
    public static void stripGear(ServerPlayerEntity player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            player.equipStack(slot, ItemStack.EMPTY);
        }
        EntityAttributeInstance armor = player.getAttributeInstance(EntityAttributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(GEAR_ARMOR_ID);
        }
        EntityAttributeInstance damage = player.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.removeModifier(GEAR_DAMAGE_ID);
        }
    }

    private static final Identifier LETHAL_DAMAGE_ID = Identifier.of(Kindreds.MOD_ID, "test/lethal_damage");

    /**
     * A one-swing guaranteed kill: adds a huge {@code ATTACK_DAMAGE} modifier so a single real
     * {@code attack()} call obliterates any test-sized target regardless of attack-cooldown scaling.
     *
     * <p><b>Why this exists, found empirically.</b> A diagnostic that printed target health and
     * {@code getAttackCooldownProgress} around every {@code attack()} call in a real run showed two
     * things: (1) a mock player's cooldown progress reads a low, constant {@code 0.1} even on its
     * very first swing (vanilla's damage formula is {@code 0.2 + progress^2 * 0.8}, so this alone
     * scales real damage down to ~21%), and (2) every attack AFTER the first in the same instant
     * dealt exactly zero - vanilla's post-hit invulnerability window only lets a NEW hit through if
     * it exceeds the previously recorded damage, and repeated same-tick swings never do. Multi-swing
     * "close it out" retries within one instant are therefore structurally unreliable here; the fix
     * is to make the first (only) swing overwhelming rather than to retry.
     */
    public static void applyLethalDamage(ServerPlayerEntity player) {
        EntityAttributeInstance damage = player.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.removeModifier(LETHAL_DAMAGE_ID);
            damage.addPersistentModifier(new EntityAttributeModifier(LETHAL_DAMAGE_ID, 2000.0,
                    EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** A few more real {@code attack()} swings in case the first didn't finish {@code target} off.
     * Bounded so a scenario's assertion (not this helper) is what reports a mob that genuinely
     * refuses to die - kept as a defensive margin even though {@link #applyLethalDamage} is what
     * actually makes a single swing reliable (see its javadoc for why retries alone cannot be). */
    public static void closeOut(ServerPlayerEntity attacker, LivingEntity target, int maxAttempts) {
        for (int i = 0; i < maxAttempts && target.isAlive(); i++) {
            attacker.attack(target);
        }
    }

    // --- Renown ----------------------------------------------------------------------------------

    /**
     * Directly records {@code count} Great Deeds on {@code player}'s {@link KindredData#renown()}.
     *
     * <p>Not routed through a real advancement grant: {@link com.kindreds.progression.RenownService
     * #belongsToRace} counts <b>every</b> renown path when {@code data.race()} is {@code null} (its
     * own javadoc: "a null race counts everything, deliberately") - which every mock player's race
     * is, in this environment (see the class javadoc's raceless-contract note) - so a plain string
     * add is exactly as real, from {@link com.kindreds.progression.RenownService#deedsForRace}'s
     * point of view, as an advancement completing under an unresolvable race would be.
     */
    public static void addRenown(ServerPlayerEntity player, int count) {
        KindredData data = KindredAttachment.get(player);
        for (int i = 0; i < count; i++) {
            data.renown().add("test/manufactured_deed_" + i);
        }
    }

    // --- Veteran manufacture -----------------------------------------------------------------------

    /**
     * A couple of direct {@code player.tick()} calls after an {@link #equipNetherite}/{@link
     * #stripGear} change (or any other attribute-affecting change a scenario makes) - cheap
     * insurance for anything {@code LivingEntity#tick()} settles as a side effect of an attribute
     * value changing (health/absorption clamping to a new max, entity dimensions on a SCALE change,
     * waypoint tracking - see {@code LivingEntity#updateAttribute}), without ever yielding control
     * back to the test framework the way {@code context.runAtTick} would.
     *
     * <p><b>Deliberately NOT what makes gear itself register - that turned out to be a dead end,
     * found empirically.</b> This task's first draft assumed equipment attribute modifiers were
     * simply applied a tick late, and called this method expecting it to fix scenarios 1/3/4/5/7
     * (see the report). It didn't: disassembling {@code LivingEntity.equipStack}/{@code
     * onEquipStack} (javap -c) shows neither ever touches an attribute instance at all, and
     * {@code updateAttributes()} - confirmed by disassembly to run once inside {@code tick()} - only
     * reacts to an attribute that already changed; it does not itself install equipment-derived
     * modifiers. A later diagnostic (printing {@code getAttributeValue(ARMOR)} immediately after
     * {@code equipStack}, ticked or not) showed it reading exactly {@code 0.0} regardless - on this
     * mock {@code ServerPlayerEntity} equipment attributes never register through any number of
     * ticks. {@link #equipNetherite}'s direct {@code EntityAttributeInstance.addPersistentModifier}
     * reinforcement is the actual fix; this method is kept only for the smaller, real side effects
     * listed above, and because avoiding {@code context.runAtTick} altogether sidesteps a second,
     * independent problem found on the same run: {@code Kindreds.CONFIG} is a single static shared
     * by every test in the same {@code runGametest} batch (they all run concurrently in the same
     * world), so a scenario that yields across several ticks can have its dials silently overwritten
     * mid-flight by another test's own {@code snapshotConfig}/{@code restoreConfig} pair running in
     * between - see the report's "concerns" section. A same-tick {@code player.tick()} call has no
     * such window.
     */
    public static void settleEquipment(ServerPlayerEntity player) {
        player.tick();
        player.tick();
    }

    /**
     * Manufactures a veteran exactly as the brief's scenario 1 describes: xp granted, nodes unlocked
     * via {@link UnlockService} (only where a tree actually resolves - see below), netherite
     * equipped (settled via {@link #settleEquipment} so it registers immediately), renown added,
     * {@link ThreatService} refreshed.
     *
     * <p><b>The raceless contract.</b> {@link UnlockService#treeFor} resolves a tree only through
     * {@link com.kindreds.playerdata.RaceAccess#getRace}, which requires the base Middle-earth mod
     * to both be loaded AND have a race already recorded for this exact player UUID - neither holds
     * for a mock player created inside this gametest run (no onboarding flow ever ran for it). This
     * is the brief's documented, REAL supported state ("commitment 0, prior = gear+renown"), not a
     * gap to route around: xp is still granted (harmlessly banked with no discipline tree to spend
     * it against), and the unlock step is skipped rather than calling {@link UnlockService#canUnlock}
     * with a null tree (which would NPE) - {@link #treeResolved(ServerPlayerEntity)} tells a caller
     * which branch actually ran, so a scenario can report which contract it exercised.
     */
    public static void manufactureVeteran(ServerPlayerEntity player, KindredsConfig config) {
        ProgressionService.awardXp(player, null, COMBAT, 5000, config.xpRateGlobal);
        Optional<SkillTree> tree = UnlockService.treeFor(player);
        if (tree.isPresent()) {
            KindredData data = KindredAttachment.get(player);
            int unlocked = 0;
            for (var node : tree.get().nodes()) {
                if (unlocked >= 6) {
                    break;
                }
                UnlockService.UnlockResult r = UnlockService.canUnlock(data, tree.get(), node.id(),
                        id -> 99, adv -> true);
                if (r.ok()) {
                    UnlockService.applyUnlock(data, node.id());
                    unlocked++;
                }
            }
        }
        equipNetherite(player);
        addRenown(player, 4);
        settleEquipment(player);
        refreshThreat(player);
    }

    /** Invalidates the cached threat and forces a fresh {@code ThreatService.threatOf} read. */
    public static float refreshThreat(ServerPlayerEntity player) {
        ThreatService.invalidate(player.getUuid());
        return ThreatService.threatOf(player);
    }

    /** The real {@code ThreatService.REFRESH_TICKS} - a private constant there, mirrored here
     * (verified by reading the source) because {@link #simulateHoursOfDecay} needs to know exactly
     * how many synthetic refreshes make up one played hour. */
    private static final int REFRESH_TICKS = 40;
    private static final long TICKS_PER_HOUR = 72000L;

    /**
     * Simulates {@code hours} of played time decaying the mark, the way the real
     * {@code ServerTickEvents.END_SERVER_TICK} timer would - repeatedly, not with one big {@code
     * addPlayedTicks} call.
     *
     * <p><b>Why this exists, found empirically on a real run.</b> {@code ThreatService#refresh}
     * calls {@code ThreatMath.decayed(state.priorMark(), live, priorDecayPerHour, REFRESH_TICKS)} -
     * always the fixed 40-tick constant, never {@code state.playedTicks()} (its own comment explains
     * why: that field accumulates for the player's whole life, and REFRESH_TICKS is deliberately the
     * increment since the last decay step). A single {@code addPlayedTicks(50 hours worth of ticks)}
     * followed by one {@code invalidate}/{@code threatOf} call therefore only ever decays by one
     * 40-tick allowance (a few thousandths of a point) - not the 50 hours a caller might expect from
     * the argument's name. The real periodic timer gets a real hour of decay by calling refresh 1800
     * times (72000 / 40), once per real 40-tick window; this reproduces exactly that, synchronously.
     */
    public static void simulateHoursOfDecay(ServerPlayerEntity player, int hours) {
        var data = KindredAttachment.get(player).threat();
        long steps = (TICKS_PER_HOUR / REFRESH_TICKS) * hours;
        for (long i = 0; i < steps; i++) {
            data.addPlayedTicks(REFRESH_TICKS);
            ThreatService.invalidate(player.getUuid());
            ThreatService.threatOf(player);
        }
    }

    /** Whether {@code player}'s race resolves to a real {@link SkillTree} right now - see
     * {@link #manufactureVeteran}'s javadoc. */
    public static boolean treeResolved(ServerPlayerEntity player) {
        return UnlockService.treeFor(player).isPresent();
    }

    // --- Assertion bookkeeping -----------------------------------------------------------------

    /** Records {@code message} in {@code failures} when {@code condition} is false - the
     * collect-then-throw shape 47d099b established for the client gametest suite, reused here so a
     * broken scenario reports every failing assertion at once instead of stopping at the first. */
    public static void check(List<String> failures, boolean condition, String message) {
        if (!condition) {
            failures.add(message);
        }
    }

    /**
     * Throws (failing the build) if any assertion collected via {@link #check} failed.
     *
     * <p>Throws {@link RuntimeException}, deliberately <b>not</b> {@link AssertionError} (the
     * 47d099b convention for the client gametest suite, whose {@code runTest} is invoked directly by
     * client-side code with no reflective/tick-scheduled hop in between). Verified by disassembling
     * both {@code GameTestState.tickTests()} and {@code GameTestState.start()} (javap -c): each
     * catches only {@code java.lang.Exception}, not {@code Throwable}/{@code Error} - a scenario that
     * throws from inside a {@code context.runAtTick} continuation (a plain {@code Runnable.run()}
     * call, never routed through the {@code Method.invoke} wrapping that would otherwise catch and
     * re-wrap an {@code AssertionError} into a plain {@code RuntimeException}) sends an
     * {@code AssertionError} straight past that catch, out of {@code MinecraftServer.tick()}
     * uncaught, and crashes the entire game-test server - taking every other test down with it
     * rather than just failing this one. Found empirically: this exact crash happened on this task's
     * {@code championsAndCompany} draft, the one scenario that genuinely needs to yield across real
     * ticks (waiting for {@code SpawnHelper.Info} to populate).
     */
    public static void finish(String scenarioName, List<String> failures) {
        if (!failures.isEmpty()) {
            throw new RuntimeException(scenarioName + " failures: " + failures);
        }
    }

    public static List<String> newFailureList() {
        return new ArrayList<>();
    }
}
