package com.kindreds.ability;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.kindreds.ability.CurseContextService.Transition.APPLY;
import static com.kindreds.ability.CurseContextService.Transition.NONE;
import static com.kindreds.ability.CurseContextService.Transition.REMOVE;
import static com.kindreds.ability.CurseContextService.decideTransition;
import static com.kindreds.ability.CurseContextService.isActiveForTest;
import static com.kindreds.ability.CurseContextService.markActiveForTest;
import static com.kindreds.ability.CurseContextService.resetActive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic test for {@link CurseContextService#decideTransition}, the apply/remove decision
 * rule behind {@code tickPlayer}'s per-tick bookkeeping. No Minecraft server is needed - this is
 * exactly the "small unit test covering the apply/remove bookkeeping" the Task 12 curse-lifecycle
 * fix brief asked for, isolating the invariant under test (effect present iff {@code owned &&
 * contextMatches}) from the real {@link AbilityApplier}/world-state side effects {@code
 * tickPlayer} itself drives.
 */
class CurseContextServiceTest {

    @Test
    void notActiveAndShouldNotBeActive_staysNone() {
        assertEquals(NONE, decideTransition(false, false, false));
        assertEquals(NONE, decideTransition(true, false, false));
        assertEquals(NONE, decideTransition(false, true, false));
    }

    @Test
    void ownedAndInContext_andNotYetActive_applies() {
        assertEquals(APPLY, decideTransition(true, true, false));
    }

    @Test
    void alreadyActiveAndStillOwnedAndInContext_isIdempotent() {
        assertEquals(NONE, decideTransition(true, true, true));
    }

    @Test
    void contextEndsWhileStillOwned_removes() {
        // FIX 2's scenario: player was in daylight (active), then leaves it - node is still owned.
        assertEquals(REMOVE, decideTransition(true, false, true));
    }

    @Test
    void nodeNoLongerOwnedWhileActive_removesEvenIfContextStillMatches() {
        // FIX 1's scenario: a respec drops ownership mid-context. The effect must still be
        // stripped - "still in context" must never keep an unowned curse's effect applied.
        assertEquals(REMOVE, decideTransition(false, true, true));
    }

    @Test
    void nodeNoLongerOwnedAndContextEnded_removes() {
        assertEquals(REMOVE, decideTransition(false, false, true));
    }

    @Test
    void neverActiveAndNodeUnowned_staysNone() {
        // No spurious remove call for a node that was never active in the first place.
        assertEquals(NONE, decideTransition(false, false, false));
        assertEquals(NONE, decideTransition(false, true, false));
    }

    // --- Fix 1: resetActive (post-death re-apply) ------------------------------------------------

    @Test
    void resetActiveClearsBookkeepingSoNextTickReapplies() {
        // Regression for the death-branch staleness bug: DeathHandler's !alive branch preserves
        // unlockedNodes() across death, but vanilla still drops the real persistent modifier a
        // contextual curse installed. Before Fix 1, ACTIVE kept claiming the curse was still
        // applied, so the next tick saw wasActive=true and decideTransition returned NONE instead
        // of re-applying the (actually absent) effect.
        UUID uuid = UUID.randomUUID();
        markActiveForTest(uuid, "deep_dark_unease");
        assertTrue(isActiveForTest(uuid, "deep_dark_unease"));

        resetActive(uuid);

        assertFalse(isActiveForTest(uuid, "deep_dark_unease"));
        // With ACTIVE cleared, wasActive is correctly re-derived as false on the next tick, so a
        // still-owned, still-in-context curse takes the APPLY transition rather than NONE.
        assertEquals(APPLY, decideTransition(true, true, false));
    }

    @Test
    void resetActiveOnUnknownUuid_isANoOp() {
        // No entry for this uuid yet - resetActive must not throw, matching the death branch's
        // unconditional call even for a player with no contextual curses tracked.
        resetActive(UUID.randomUUID());
    }
}
