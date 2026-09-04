package dev.wasil.permit.ui

import dev.wasil.permit.parking.PendingDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionPresentationTest {

    @Test fun `blocked offers claim anyway, mark free, and leave it - in that order`() {
        val content = contentFor(PendingDecision.Blocked("Walid", 1L, 2L, raisedAtMs = 3L))
        assertEquals(
            listOf(DecisionActionKind.CLAIM_FORCE, DecisionActionKind.FREE_HERE, DecisionActionKind.IGNORE),
            content.choices.map { it.kind },
        )
    }

    @Test fun `blocked names the other car and explains the fine risk`() {
        val content = contentFor(PendingDecision.Blocked("Walid", 1L, 2L, raisedAtMs = 3L))
        assertTrue(content.title.contains("Walid"))
        assertTrue(content.body.contains("Walid"))
        assertTrue(content.body.contains("fine"))
    }

    @Test fun `manual offers claim, mark free, and ignore`() {
        val content = contentFor(PendingDecision.Manual(raisedAtMs = 1L))
        assertEquals(
            listOf(DecisionActionKind.CLAIM, DecisionActionKind.FREE_HERE, DecisionActionKind.IGNORE),
            content.choices.map { it.kind },
        )
    }

    /**
     * The same prompt covers two different situations, and until v0.7.7 it said
     * one sentence for both. With a position it is a real question; without one
     * the app is asking about a spot it cannot see, and every park produced the
     * same unanswerable prompt — Wasil: "the message pop up far more often than
     * before... i have to do it manually now every time."
     */
    @Test fun `manual says why it cannot decide when the park has no position`() {
        val known = contentFor(PendingDecision.Manual(raisedAtMs = 1L), positionKnown = true)
        assertTrue(known.body.contains("possible park was detected"))
        assertFalse("must not speculate when it does know", known.body.contains("could not work out"))

        val unknown = contentFor(PendingDecision.Manual(raisedAtMs = 1L), positionKnown = false)
        assertTrue(unknown.title.contains("where"))
        assertTrue(unknown.body.contains("could not work out where"))
        // States the consequence, not just the failure: why it has to ask at all.
        assertTrue(unknown.body.contains("cannot tell whether this spot is paid"))
        // The choices never change — only the explanation does.
        assertEquals(known.choices.map { it.kind }, unknown.choices.map { it.kind })
    }

    @Test fun `it only blames the permission when that permission is actually missing`() {
        val fixable = contentFor(
            PendingDecision.Manual(raisedAtMs = 1L),
            positionKnown = false,
            fixablePermission = true,
        )
        assertTrue(fixable.body.contains("Allow all the time"))

        // Granted, or below API 29 where it does not exist: pointing at a
        // permission that is already on would send someone to fix a working
        // setting, which is worse than saying nothing.
        val notFixable = contentFor(
            PendingDecision.Manual(raisedAtMs = 1L),
            positionKnown = false,
            fixablePermission = false,
        )
        assertFalse(notFixable.body.contains("Allow all the time"))
        assertTrue(notFixable.body.contains("set the car's position on the Map"))
    }

    @Test fun `give back offers give back and keep it, and names the other car`() {
        val content = contentFor(PendingDecision.GiveBack("Wasil", raisedAtMs = 1L))
        assertEquals(
            listOf(DecisionActionKind.GIVE_BACK, DecisionActionKind.IGNORE),
            content.choices.map { it.kind },
        )
        assertTrue(content.title.contains("Wasil"))
    }

    @Test fun `takeover offers reclaim and ok, and names who took it`() {
        val content = contentFor(PendingDecision.Takeover("Walid", raisedAtMs = 1L))
        assertEquals(
            listOf(DecisionActionKind.CLAIM, DecisionActionKind.IGNORE),
            content.choices.map { it.kind },
        )
        assertTrue(content.title.contains("Walid"))
    }
}
