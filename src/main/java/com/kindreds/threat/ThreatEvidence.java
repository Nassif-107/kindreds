package com.kindreds.threat;

import com.kindreds.Kindreds;
import com.kindreds.playerdata.KindredAttachment;
import com.kindreds.playerdata.KindredData;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The evidence loop (design spec §2.3): listens to damage, kills and deaths, and folds them into a
 * player's {@link ThreatState#competence()} and per-family record. Knows nothing about the effects
 * that read competence back out (that is {@link ThreatService}'s job) - only how it is earned.
 *
 * <p>Damage is accumulated per player between kills/deaths rather than folded on every hit, because
 * hardship is a property of the <em>whole fight</em>, not any one blow (spec §2.3: {@code hardship =
 * damageTakenFromScopedMobs / highWaterMaxHealth}).
 *
 * <h2>What counts as a qualifying fight</h2>
 * A fight only produces evidence if the mob on the other end of it is {@link MobDanger#isInScope}.
 * This is deliberately checked twice, at two different moments, for two different reasons:
 * <ul>
 *   <li>{@code AFTER_DAMAGE} filters by the <b>attacker</b> - never another player, never fall, lava
 *       or drowning damage counts, or the exploit is a friend beating you up, or a leap off a cliff
 *       mid-fight (spec §2.3).</li>
 *   <li>{@code AFTER_KILLED_OTHER_ENTITY} filters by the <b>kill target</b> too, even though the
 *       brief for this event does not restate it. {@link ThreatMath#foldHardship} now weights
 *       <em>both</em> branches by attacker danger (the coasting rise included - the final review
 *       closed the provoked-trivial-kill farm by making the rise earn its weight the same way the
 *       fall does), so a chicken kill folds to almost nothing even when it slips in scope. The gate
 *       remains as defence-in-depth: it keeps out-of-scope kills from resetting the damage
 *       accumulator or touching the per-family table at all, so a chicken punch produces no
 *       evidence rather than merely negligible evidence - the "a hundred slow kills of a trivial
 *       mob" exploit spec §12 requires a regression test for.</li>
 * </ul>
 *
 * <h2>Time-to-kill</h2>
 * {@code ALLOW_DAMAGE} captures, per (player, mob) pair, when that player first struck that mob (see
 * {@link #ENGAGEMENTS}) - the fight's start. {@code AFTER_KILLED_OTHER_ENTITY} closes the loop: a
 * kill of that same mob, fast enough relative to its danger (see {@link #isFastKill}), folds
 * through {@link ThreatMath#foldFastKill} the same way a coasting hardship fold does - raise-only,
 * and weighted by the same {@code dangerRatio} (now also by {@code killShare}, see below), so
 * killing a trivial mob quickly proves as little as killing it slowly (spec §2.3).
 *
 * <h2>Kill credit is earned, not just witnessed</h2>
 * Both the coasting rise in {@link ThreatMath#foldHardship} and the fast-kill raise in
 * {@link ThreatMath#foldFastKill} used to be weighted only by the mob's danger, never by how much
 * of the actual fight the killer fought. That left a kill-steal channel open even after the
 * danger-weighting fix: a friend whittles a dangerous mob to 1 HP over several minutes: an
 * opportunist tags it and lands the last hit within a couple of ticks, and walks away with the
 * <em>same</em> full, danger-weighted rise as someone who fought the whole thing. {@code
 * killShare} - the killer's own damage dealt to the mob, as a fraction of its max health - closes
 * this structurally rather than merely bounding it: see {@link #ENGAGEMENTS} and the {@code
 * ALLOW_DAMAGE}/{@code AFTER_DAMAGE} pair below for how it is tracked, and
 * {@code ThreatExploitTest#lastHittingAFriendsWhittledMobPaysAlmostNothing} for the regression.
 *
 * <p>{@code damageDealt} banks actual HP removed, not the raw hit amount: a re-review of the first
 * cut of this system found {@code AFTER_DAMAGE}'s {@code damageTaken} parameter is
 * <b>pre-mitigation</b> (Fabric's own javadoc on that event: it "does NOT include damage reduction
 * from armor and enchantments" - armor, resistance and absorption are all applied later, inside
 * {@code applyDamage}). Banking that raw figure let a target with heavy resistance (Turtle Master,
 * a shielded mob, anything with a damage-reduction enchant) bank multiples of its own max health of
 * "work" from a fraction of the real damage it actually took, handing a friend-whittled kill a
 * {@code killShare} of 1.0 it did not earn - the same channel the share was meant to close, reopened
 * one level down. Measuring the HP delta each hit's own {@code ALLOW_DAMAGE} snapshot minus that
 * mob's health afterward is mitigation-proof by construction: whatever armor, resistance or
 * absorption consumed never shows up as HP removed, because it never reduced the mob's actual
 * health. See {@code #ENGAGEMENTS} and the {@code AFTER_DAMAGE} attacker branch below.
 */
public final class ThreatEvidence {
    private ThreatEvidence() {
    }

    /** Damage taken from in-scope mobs since this player's last qualifying kill or death - the
     * running numerator of {@link #hardshipOf}. Cleared whenever a fight closes: a qualifying kill,
     * or any player death (see the {@code AFTER_DEATH} handler below for why death always clears
     * it, even a death this class does not treat as evidence). */
    private static final Map<UUID, Float> ACCUMULATED_DAMAGE = new ConcurrentHashMap<>();

    /** Every mob a player has an open engagement against, keyed by that mob's own {@code UUID}, is
     * capped at this many entries. Not a config value - a structural bound so a player who tags many
     * mobs in a single running fight (an army skirmish, a mob farm) cannot grow this table without
     * limit; see {@link #newEngagementTable()} for how the cap is enforced. */
    private static final int MAX_ENGAGEMENTS_PER_PLAYER = 8;

    /** One entry per player, each holding that player's open engagements against every in-scope mob
     * they are currently mid-fight with, keyed by the mob's {@code UUID} - not a single slot, so a
     * sword sweep that tags a second orc mid-fight no longer destroys the first orc's banked credit
     * (see {@link Engagement} for what each fight's record holds and why).
     *
     * <p>Each player's inner table is bounded to {@link #MAX_ENGAGEMENTS_PER_PLAYER} entries (see
     * {@link #newEngagementTable()}): the 9th distinct mob a player engages evicts the oldest
     * (by first-engaged order) automatically. This is deliberately a size bound, not a correctness
     * requirement - a mob that is killed by <em>someone else</em> while this player has an open
     * engagement against it leaves a stale entry nobody ever explicitly removes; the cap keeps that
     * bounded rather than unbounded, and the entry is fully reclaimed the moment this player next
     * dies or disconnects (both clear the player's whole inner table below), or the moment the cap
     * evicts it to make room for a ninth mob.
     *
     * <p><b>Thread-safety invariant:</b> the outer map is a {@link ConcurrentHashMap} because it is
     * read and written by whichever server thread happens to be running each event, but all three
     * events that ever touch an inner {@link LinkedHashMap} - {@code ALLOW_DAMAGE}, {@code
     * AFTER_DAMAGE} and {@code AFTER_KILLED_OTHER_ENTITY} - are Fabric server-thread callbacks, and
     * only ever the server thread. The inner table is therefore never mutated concurrently and needs
     * no lock of its own; the outer map's own concurrency handles a different player's table being
     * created or removed while this one is in use.
     */
    private static final Map<UUID, LinkedHashMap<UUID, Engagement>> ENGAGEMENTS = new ConcurrentHashMap<>();

    /**
     * A fresh, bounded per-player engagement table. Insertion-order eviction ({@code accessOrder =
     * false}, the JDK default): overwriting an existing mob's entry - what every re-hit on a mob
     * already being fought does - is not a structural change and does not move that mob's position,
     * so only genuinely engaging a <em>new</em> mob can ever trigger an eviction, never re-hitting an
     * old one. {@link #removeEldestEntry} makes the cap correct by construction (the same
     * well-tested {@code LinkedHashMap} machinery every other bounded-LRU-style cache in the JDK
     * ecosystem leans on) rather than a hand-rolled rule sitting next to it, so there is nothing
     * further to extract into a separate pure function - this factory is itself the seam
     * {@code ThreatEvidenceTest} exercises directly to prove the cap-at-8-oldest-evicted behaviour
     * without a running game.
     */
    static LinkedHashMap<UUID, Engagement> newEngagementTable() {
        return new LinkedHashMap<>(MAX_ENGAGEMENTS_PER_PLAYER + 1, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Engagement> eldest) {
                return size() > MAX_ENGAGEMENTS_PER_PLAYER;
            }
        };
    }

    /**
     * A player's in-progress fight against one specific mob (the mob itself is the key it is stored
     * under in {@link #ENGAGEMENTS}, not a field here), first struck at {@code firstHitTick}.
     *
     * <p>{@code damageDealt} is the running total of HP this player has actually removed from the
     * mob across every hit <em>before</em> the current one in flight: each {@code AFTER_DAMAGE} call
     * banks {@code max(0, healthBeforeHit - mob.getHealth())} for its own hit - the mob's health
     * immediately before that hit, from this same hit's {@code ALLOW_DAMAGE} snapshot, minus its
     * health immediately after. That is an endpoint measurement of real HP loss, not the event's raw
     * {@code damageTaken} parameter, which Fabric documents as pre-mitigation (armor, resistance and
     * absorption are all applied later, inside {@code applyDamage}) - banking the raw figure would
     * let a heavily-mitigated hit count for far more "work" than HP it actually removed. {@code
     * healthBeforeHit} is the mob's health the instant before whichever hit is <em>currently in
     * flight</em>, refreshed on every {@code ALLOW_DAMAGE} call for this mob - it exists because
     * {@code AFTER_DAMAGE} never fires for the hit that actually kills the mob (see the class
     * javadoc), so there is no HP-delta figure available for that one hit by the time the kill
     * closes. Using {@code healthBeforeHit} instead of the raw (potentially wildly overkill) hit
     * amount is what clamps a 50-damage killing blow against a 1-HP mob down to the 1 HP of real work
     * it actually did - see {@code ALLOW_DAMAGE}'s registration below for why this, not {@code
     * ALLOW_DEATH}, is where that clamp has to be taken.
     */
    record Engagement(long firstHitTick, float damageDealt, float healthBeforeHit) {
    }

    /** 8 seconds - the internal yardstick an at-level, full-weight kill is judged against. Not a
     * config value: exposing this would let a server tune the fast-kill bar independently of
     * {@link MobDanger#expectedAt}, which is the one place "at-level" is defined. */
    private static final long TTK_BASE_TICKS = 160;

    /** Registers the five listeners. Call once from {@link Kindreds#onInitialize()}. */
    public static void register() {
        // Mirrors ThreatService#register's own disconnect handler: without this, a player who logs
        // out mid-fight carries that accumulation into a fight days later, and the map itself grows
        // for the server's lifetime, since nothing else ever removes an entry from it. Removing the
        // player's whole ENGAGEMENTS entry clears every open fight they had, not just one mob's.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ACCUMULATED_DAMAGE.remove(handler.player.getUuid());
            ENGAGEMENTS.remove(handler.player.getUuid());
        });

        // ALLOW_DAMAGE, not ALLOW_DEATH, is where each (player, mob) kill-credit engagement is
        // created and kept current. This was originally meant to be ALLOW_DEATH (capture the lethal
        // blow right as it lands), but ALLOW_DEATH fires from a @Redirect around
        // LivingEntity#damage's *second* isDead() check - by which point LivingEntity#applyDamage has
        // already run and setHealth has already clamped health to 0, so entity.getHealth() there is
        // always 0, never the mob's pre-blow remaining health (verified by disassembling
        // fabric-entity-events-v1 2.1.1's LivingEntityMixin against yarn 1.21.8's named
        // LivingEntity#damage bytecode - see the task-6 report). ALLOW_DAMAGE fires earlier in the
        // same method, before applyDamage touches health at all, and - unlike AFTER_DAMAGE - fires
        // for every hit including the one that kills the mob, which is exactly the pre-application
        // read the clamp needs. We only ever observe here; the return value must stay `true` always,
        // or this would start cancelling damage.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (scalingEnabled() && entity instanceof LivingEntity mob
                    && source.getAttacker() instanceof ServerPlayerEntity player
                    && MobDanger.isInScope(mob, player)) {
                LinkedHashMap<UUID, Engagement> table =
                        ENGAGEMENTS.computeIfAbsent(player.getUuid(), id -> newEngagementTable());
                float healthBeforeHit = mob.getHealth();
                Engagement current = table.get(mob.getUuid());
                if (current != null) {
                    // Same fight, next hit: keep firstHitTick and damageDealt so far (see the
                    // Engagement javadoc for why re-hitting the same mob must not push the TTK
                    // clock forward), refresh only the pre-hit health snapshot. Other mobs' entries
                    // in this player's table are untouched.
                    table.put(mob.getUuid(),
                            new Engagement(current.firstHitTick(), current.damageDealt(), healthBeforeHit));
                } else {
                    // A new mob for this player - the clock starts now, no damage banked yet. If
                    // this very hit turns out lethal (a genuine one-shot of a full-health mob),
                    // healthBeforeHit alone already gives it share ~= 1.0. May evict this player's
                    // oldest other engagement if this is their 9th - see newEngagementTable().
                    table.put(mob.getUuid(),
                            new Engagement(player.getWorld().getTime(), 0f, healthBeforeHit));
                }
            }
            return true; // observe only - never cancel a hit
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!scalingEnabled()) {
                return;
            }
            if (entity instanceof ServerPlayerEntity player) {
                if (!(source.getAttacker() instanceof LivingEntity attacker) || !MobDanger.isInScope(attacker, player)) {
                    return; // never another player, never fall/lava/drowning - see the class javadoc
                }
                ACCUMULATED_DAMAGE.merge(player.getUuid(), damageTaken, Float::sum);
                return;
            }
            // Attacker side: bank this (non-lethal - see the class javadoc for why AFTER_DAMAGE never
            // fires for the killing hit) hit's actual HP removed - healthBeforeHit (this same hit's
            // ALLOW_DAMAGE snapshot) minus the mob's health now - into damageDealt, NOT the event's
            // own damageTaken parameter (that is pre-mitigation - see the Engagement javadoc for why
            // that distinction matters). The engagement itself - which mob, when the fight started -
            // is ALLOW_DAMAGE's job, above, which always runs first for this same hit; if it didn't
            // create/refresh one for this mob (scaling was off, or the mob fell out of scope between
            // the two events), there is nothing to bank onto and this hit contributes nothing to
            // kill-share, same as before this fix existed.
            if (entity instanceof LivingEntity mob && source.getAttacker() instanceof ServerPlayerEntity player
                    && MobDanger.isInScope(mob, player)) {
                LinkedHashMap<UUID, Engagement> table = ENGAGEMENTS.get(player.getUuid());
                Engagement current = table != null ? table.get(mob.getUuid()) : null;
                if (current != null) {
                    float hpRemoved = Math.max(0f, current.healthBeforeHit() - mob.getHealth());
                    table.put(mob.getUuid(),
                            new Engagement(current.firstHitTick(), current.damageDealt() + hpRemoved,
                                    current.healthBeforeHit()));
                }
            }
        });

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killed) -> {
            if (!scalingEnabled() || !(entity instanceof ServerPlayerEntity player)
                    || !MobDanger.isInScope(killed, player)) {
                return; // only a kill of a mob in scope is a "qualifying fight" - see class javadoc
            }
            float accumulated = ACCUMULATED_DAMAGE.getOrDefault(player.getUuid(), 0f);
            ACCUMULATED_DAMAGE.remove(player.getUuid());

            KindredData data = KindredAttachment.get(player);
            ThreatState state = data.threat();
            // The mark rises at once, same as ThreatService#refresh: a fresh player's mark defaults
            // to 0, and this is the floor that keeps hardshipOf's denominator from ever being that
            // zero (see hardshipOf's javadoc).
            state.setMaxHealthMark(Math.max(state.maxHealthMark(),
                    (float) player.getAttributeValue(EntityAttributes.MAX_HEALTH)));

            float hardship = hardshipOf(accumulated, state.maxHealthMark());
            // dangerRatio doubles as both foldHardship's attackerWeight and the TTK signal's
            // weighting below - both are "how dangerous was this kill relative to what this
            // player's threat expects", the same quantity spec §2.3 defines once.
            float dangerRatio = ThreatMath.attackerWeight(MobDanger.of(killed),
                    MobDanger.expectedAt(ThreatService.threatOf(player)));
            ThreatTuning tuning = tuningFor();

            // Kill credit is earned, not just witnessed: killShare is this player's own HP removed
            // from `killed`, as a fraction of its max health - see the class javadoc and the
            // Engagement javadoc. Consuming exactly the killed mob's own entry (not the whole table)
            // leaves every other open engagement this player has against a different mob untouched -
            // a sword sweep that also tagged a second orc mid-fight must not erase this orc's banked
            // credit. Checked, and the entry removed, before the hardship fold below so a kill that
            // qualifies folds both signals (TTK and hardship) from the same evidence.
            LinkedHashMap<UUID, Engagement> table = ENGAGEMENTS.get(player.getUuid());
            Engagement engagement = table != null ? table.remove(killed.getUuid()) : null;
            float killShare = 1f;
            if (engagement != null) {
                // No max(0f, ...) guard on healthBeforeHit here: LivingEntity#setHealth clamps to
                // >= 0 (verified by bytecode disassembly - see the task-6 report), so the snapshot
                // ALLOW_DAMAGE ever stores can never itself be negative; a guard against a value that
                // cannot occur would just be dead code pretending to be a safety net.
                float contribution = engagement.damageDealt() + engagement.healthBeforeHit();
                killShare = clamp01(contribution / Math.max(1f, killed.getMaxHealth()));

                long ttk = world.getTime() - engagement.firstHitTick();
                if (isFastKill(ttk, TTK_BASE_TICKS, dangerRatio)) {
                    state.setCompetence(
                            ThreatMath.foldFastKill(state.competence(), clamp01(dangerRatio * killShare), tuning));
                }
            }
            // No engagement for this specific mob at this point should only happen if scope itself
            // changed between the lethal hit and this event firing (MobDanger's own documented
            // caveat: a provoked-friendly's target can already be cleared by the time death is
            // processed) - ALLOW_DAMAGE above always runs for the killer's own lethal hit first,
            // INSIDE the same damage() call stack that eventually fires this kill event, not at some
            // later tick: the window for the two to disagree is intra-call, not "moments later".
            // Falling back to killShare = 1 keeps that rare edge case behaving exactly as it did
            // before this fix, rather than silently crediting nothing for a kill that did qualify as
            // evidence.

            String family = MobDanger.family(killed);
            // A family never seen before starts from the player's overall record, not a neutral
            // 1.0 - the same default ThreatService#scaledAgainst reads when a family entry is
            // absent, so a family's very first fold does not look like a fresh start.
            float familyBefore = state.familyCompetence().getOrDefault(family, state.competence());

            state.setCompetence(ThreatMath.foldHardship(state.competence(), hardship, dangerRatio, killShare, tuning));
            state.familyCompetence().put(family,
                    ThreatMath.foldHardship(familyBefore, hardship, dangerRatio, killShare, tuning));

            ThreatService.invalidate(player.getUuid());
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity player)) {
                return;
            }
            // The fight is over the moment the player dies, whatever killed them - clear the
            // accumulator unconditionally so a death to something out of scope (fall, lava, /kill)
            // does not let stale damage bleed into the next fight's hardship. The engagement table
            // clears the same way, for the same reason - a death mid-fight must not let any of that
            // player's open fights' TTK clocks survive into whatever comes next. This removes the
            // player's whole table, every mob they had an open engagement against, not just one.
            ACCUMULATED_DAMAGE.remove(player.getUuid());
            ENGAGEMENTS.remove(player.getUuid());
            if (!scalingEnabled() || !(source.getAttacker() instanceof LivingEntity killer)
                    || !MobDanger.isInScope(killer, player)) {
                return; // only a death to a mob in scope is evidence - see class javadoc
            }
            KindredData data = KindredAttachment.get(player);
            ThreatState state = data.threat();
            float weight = ThreatMath.attackerWeight(MobDanger.of(killer),
                    MobDanger.expectedAt(ThreatService.threatOf(player)));
            ThreatTuning tuning = tuningFor();

            // Mirrors the per-family fold in AFTER_KILLED_OTHER_ENTITY above, so a death is as much
            // evidence against the killer's family as a kill is for it - without this, per-family
            // competence could only ever rise, and dying to family X would never record "X is hard
            // for me" (spec §2.3 symmetry).
            String family = MobDanger.family(killer);
            float familyBefore = state.familyCompetence().getOrDefault(family, state.competence());

            state.setCompetence(ThreatMath.foldDeath(state.competence(), weight, tuning));
            state.familyCompetence().put(family, ThreatMath.foldDeath(familyBefore, weight, tuning));

            ThreatService.invalidate(player.getUuid());
        });
    }

    /** Whether scaling (and therefore this whole evidence loop) is on at all. Checked first in
     * every handler so a world with scaling off, or a config not yet loaded, accrues no evidence
     * and folds nothing - the same guard {@link ThreatService#scaledFor} applies to effects. */
    private static boolean scalingEnabled() {
        return Kindreds.CONFIG != null && Kindreds.CONFIG.enableEnemyScaling;
    }

    /** Delegates to {@link ThreatService#tuningFor()} - the one place {@link ThreatTuning} is built
     * from config, so this class's folds and {@code ThreatService#refresh}'s re-band can never
     * quietly drift apart on what "the current tuning" means. Only called after {@link
     * #scalingEnabled()} has confirmed {@link Kindreds#CONFIG} is non-null. */
    private static ThreatTuning tuningFor() {
        return ThreatService.tuningFor();
    }

    /**
     * The hardship signal for a closed fight: {@code accumulatedDamage} as a fraction of {@code
     * effectiveMaxHealth}. Pure and Minecraft-free so it is provable without a running game (see
     * {@code ThreatEvidenceTest}), even though the class around it is not.
     *
     * <p>{@code effectiveMaxHealth} is floored at {@code 1f} rather than trusted as-is. In practice
     * the caller above always passes {@code state.maxHealthMark()} <em>after</em> raising it to at
     * least the player's live max health, so it is never actually {@code 0} by the time it reaches
     * here - but this method has no way to enforce that from its own signature, and a {@code 0} (or
     * negative, from corrupted save data) denominator would otherwise produce {@code NaN} or {@code
     * Infinity}, which {@link ThreatMath#foldHardship} is not required to defend against and which
     * would poison competence on write. The floor is the second, independent guard - belt and
     * braces, not a substitute for the caller raising the mark.
     */
    static float hardshipOf(float accumulatedDamage, float effectiveMaxHealth) {
        return Math.max(0f, accumulatedDamage) / Math.max(1f, effectiveMaxHealth);
    }

    /**
     * The pure decision core of the time-to-kill signal (spec §2.3): was {@code ttkTicks} fast
     * enough, against a mob of this danger, to count as evidence of strength? {@code
     * dangerRatio} - {@link ThreatMath#attackerWeight}'s output - scales the {@code baseTicks}
     * yardstick down for a below-expected mob, so besting a trivial mob quickly proves as little
     * as besting it slowly: {@code expected} collapses toward {@code 0} as danger falls, so
     * {@code ttkTicks} (never negative) can only clear the bar for a mob genuinely near or above
     * what this player's threat expects. Provable without a running game (see {@code TtkTest}),
     * even though the engagement bookkeeping around it is not.
     */
    static boolean isFastKill(long ttkTicks, long baseTicks, float dangerRatio) {
        long expected = (long) (baseTicks * clamp01(dangerRatio));
        return ttkTicks < expected / 2;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
