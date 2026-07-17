package com.kindreds.ability;

import org.junit.jupiter.api.Test;

import static com.kindreds.ability.CurseContextService.Transition.APPLY;
import static com.kindreds.ability.CurseContextService.Transition.NONE;
import static com.kindreds.ability.CurseContextService.Transition.REMOVE;
import static com.kindreds.ability.CurseContextService.decideTransition;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
