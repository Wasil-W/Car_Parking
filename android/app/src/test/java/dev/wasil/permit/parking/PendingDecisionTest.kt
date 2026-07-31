package dev.wasil.permit.parking

import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDecisionTest {

    private val now = 1_000_000_000_000L

    // --- Pure encode/decode round-trip -------------------------------------

    @Test fun `blocked round-trips through the primitive record`() {
        val decision = PendingDecision.Blocked(
            otherLabel = "Walid", parkedAtMs = now - 5_000, heartbeatAtMs = now - 1_000, raisedAtMs = now,
        )
        assertEquals(decision, decision.toRecord().toDecision())
    }

    @Test fun `manual round-trips through the primitive record`() {
        val decision = PendingDecision.Manual(raisedAtMs = now)
        assertEquals(decision, decision.toRecord().toDecision())
    }

    @Test fun `give back round-trips through the primitive record`() {
        val decision = PendingDecision.GiveBack(otherLabel = "Wasil", raisedAtMs = now)
        assertEquals(decision, decision.toRecord().toDecision())
    }

    @Test fun `takeover round-trips through the primitive record`() {
        val decision = PendingDecision.Takeover(byLabel = "Walid", raisedAtMs = now)
        assertEquals(decision, decision.toRecord().toDecision())
    }

    @Test fun `record stores only primitives - kind label and three longs`() {
        val record = PendingDecision.Blocked("Walid", 111L, 222L, 333L).toRecord()
        assertEquals("BLOCKED", record.kind)
        assertEquals("Walid", record.label)
        assertEquals(111L, record.parkedAtMs)
        assertEquals(222L, record.heartbeatAtMs)
        assertEquals(333L, record.raisedAtMs)
    }

    @Test fun `an empty record - the never-raised default - decodes to no decision`() {
        assertNull(PendingDecisionRecord(kind = null).toDecision())
    }

    @Test fun `an unrecognised kind decodes to no decision rather than throwing`() {
        assertNull(PendingDecisionRecord(kind = "SOMETHING_FUTURE").toDecision())
    }

    @Test fun `a blocked or give-back or takeover record missing its label decodes to no decision`() {
        assertNull(PendingDecisionRecord(kind = "BLOCKED", label = null).toDecision())
        assertNull(PendingDecisionRecord(kind = "GIVE_BACK", label = null).toDecision())
        assertNull(PendingDecisionRecord(kind = "TAKEOVER", label = null).toDecision())
    }

    // --- Lifecycle: raise / clear / expiry, via ParkStateStore --------------

    @Test fun `raising a decision makes it the current one`() {
        val store = FakeParkStateStore()
        store.pendingDecision = PendingDecision.Manual(raisedAtMs = now)
        assertEquals(PendingDecision.Manual(now), store.currentPendingDecision(nowMs = now))
    }

    @Test fun `no decision ever raised is absent`() {
        val store = FakeParkStateStore()
        assertNull(store.currentPendingDecision(nowMs = now))
    }

    @Test fun `acting on a decision clears it`() {
        val store = FakeParkStateStore()
        store.pendingDecision = PendingDecision.Takeover(byLabel = "Wasil", raisedAtMs = now)

        store.pendingDecision = null // what ParkActionReceiver.perform does on every action

        assertNull(store.currentPendingDecision(nowMs = now))
    }

    @Test fun `a decision just under 12 hours old is still current`() {
        val store = FakeParkStateStore()
        store.pendingDecision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)

        val justUnder = now + PENDING_DECISION_MAX_AGE_MS - 1
        assertEquals(PendingDecision.GiveBack("Walid", now), store.currentPendingDecision(nowMs = justUnder))
    }

    @Test fun `a decision over 12 hours old is treated as absent`() {
        val store = FakeParkStateStore()
        store.pendingDecision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)

        val justOver = now + PENDING_DECISION_MAX_AGE_MS + 1
        assertNull(store.currentPendingDecision(nowMs = justOver))
    }

    @Test fun `a stale decision never resurfaces, no matter how much later it is read`() {
        val store = FakeParkStateStore()
        store.pendingDecision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)

        val wayLater = now + PENDING_DECISION_MAX_AGE_MS * 100
        assertNull(store.currentPendingDecision(nowMs = wayLater))
        // Reading again even later still finds nothing - it was never un-cleared.
        assertNull(store.currentPendingDecision(nowMs = wayLater * 2))
    }

    // --- stillStands: does the situation a decision was raised about still hold? ---

    private val walidParkedFresh = PhoneState(
        parkedOutside = true, parkedAtMs = now - 120_000, heartbeatAtMs = now - 60_000,
    )

    @Test fun `manual always stands - it's about my own park, facts irrelevant`() {
        val decision = PendingDecision.Manual(raisedAtMs = now)
        assertTrue(decision.stillStands(facts = null, nowMs = now))
        assertTrue(decision.stillStands(
            facts = DecisionFacts(otherState = null, otherPlate = null, myPlate = null, activeVrn = null),
            nowMs = now,
        ))
    }

    @Test fun `takeover always stands - it reports something that already happened`() {
        val decision = PendingDecision.Takeover(byLabel = "Walid", raisedAtMs = now)
        assertTrue(decision.stillStands(facts = null, nowMs = now))
        assertTrue(decision.stillStands(
            facts = DecisionFacts(otherState = null, otherPlate = null, myPlate = null, activeVrn = null),
            nowMs = now,
        ))
    }

    @Test fun `blocked stands when unreadable - a failed read is not evidence it lapsed`() {
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        assertTrue(decision.stillStands(facts = null, nowMs = now))
    }

    @Test fun `blocked stands when the other plate can't be determined either`() {
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        val facts = DecisionFacts(otherState = walidParkedFresh, otherPlate = null, myPlate = "RH950F", activeVrn = "XX123Y")
        assertTrue(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `blocked stands while the other car is still parked outside and holding`() {
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        val facts = DecisionFacts(otherState = walidParkedFresh, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "XX123Y")
        assertTrue(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `blocked lapses once the other car is no longer parked outside`() {
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        val driftedOff = walidParkedFresh.copy(parkedOutside = false)
        val facts = DecisionFacts(otherState = driftedOff, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "XX123Y")
        assertFalse(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `blocked lapses once the other's heartbeat has gone stale`() {
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        val stale = walidParkedFresh.copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS - 1)
        val facts = DecisionFacts(otherState = stale, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "XX123Y")
        assertFalse(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `blocked lapses once the permit is no longer on the other's plate`() {
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        // e.g. it was already given back or reclaimed by the time we check.
        val facts = DecisionFacts(otherState = walidParkedFresh, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "RH950F")
        assertFalse(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `blocked lapses when the other phone has no reported state at all`() {
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        val facts = DecisionFacts(otherState = null, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "XX123Y")
        assertFalse(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `give back stands when unreadable - a failed read is not evidence it lapsed`() {
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        assertTrue(decision.stillStands(facts = null, nowMs = now))
    }

    @Test fun `give back stands when my own plate can't be determined either`() {
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        val facts = DecisionFacts(otherState = walidParkedFresh, otherPlate = "XX123Y", myPlate = null, activeVrn = "RH950F")
        assertTrue(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `give back stands while the other still needs it and the permit is still mine`() {
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        val facts = DecisionFacts(otherState = walidParkedFresh, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "RH950F")
        assertTrue(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `give back lapses once the other car has driven off`() {
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        val driftedOff = walidParkedFresh.copy(parkedOutside = false)
        val facts = DecisionFacts(otherState = driftedOff, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "RH950F")
        assertFalse(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `give back lapses once the other's heartbeat has gone stale`() {
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        val stale = walidParkedFresh.copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS - 1)
        val facts = DecisionFacts(otherState = stale, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "RH950F")
        assertFalse(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `give back lapses once the permit has already moved off my plate`() {
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        // Someone else already claimed it (or it was given back some other way).
        val facts = DecisionFacts(otherState = walidParkedFresh, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "XX123Y")
        assertFalse(decision.stillStands(facts, nowMs = now))
    }

    @Test fun `give back lapses when the other phone has no reported state at all`() {
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        val facts = DecisionFacts(otherState = null, otherPlate = "XX123Y", myPlate = "RH950F", activeVrn = "RH950F")
        assertFalse(decision.stillStands(facts, nowMs = now))
    }
}
