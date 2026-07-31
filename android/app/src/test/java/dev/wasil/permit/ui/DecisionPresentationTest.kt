package dev.wasil.permit.ui

import dev.wasil.permit.parking.PendingDecision
import org.junit.Assert.assertEquals
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
