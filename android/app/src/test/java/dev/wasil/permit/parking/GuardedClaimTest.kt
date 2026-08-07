package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.FakeCredentialStore
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedClaimTest {
    private val config = testConfig()  // Wasil RH950F, Walid XX123Y
    private val now = 1_000_000_000_000L
    private val walidParkedFresh = PhoneState(
        parkedOutside = true, parkedAtMs = now - 120_000, heartbeatAtMs = now - 60_000,
    )

    private fun guarded(
        api: SwitchApi = SwitchApi(),
        shared: FakeSharedStateStore = FakeSharedStateStore(),
        state: FakeParkStateStore = FakeParkStateStore(),
        notifier: RecordingParkNotifier = RecordingParkNotifier(),
    ): GuardedClaim {
        val repo = PermitRepository(api)
        val credentials = FakeCredentialStore(config)
        return GuardedClaim(repo, credentials, state, shared,
            ClaimPermit(repo, credentials, state, notifier), nowMs = { now })
    }

    @Test
    fun `claiming my car while other parked fresh and holding blocks`() = runTest {
        val api = SwitchApi(active = "XX123Y")   // permit on Walid
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val result = guarded(api, shared).claim()
        assertTrue(result is GuardedResult.Blocked)
        assertEquals("Walid", (result as GuardedResult.Blocked).otherLabel)
        assertEquals("XX123Y", api.active)   // untouched
    }

    @Test
    fun `force claims anyway and records the takeover`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val result = guarded(api, shared).claim(force = true)
        assertEquals(ParkOutcome.Claimed("RH950F"), (result as GuardedResult.Done).outcome)
        assertEquals("RH950F", api.active)
        assertTrue(shared.permitWrites.single().forced)
        assertEquals("wasil", shared.permitWrites.single().holder)
    }

    @Test
    fun `other parked but not holding proceeds and records claim`() = runTest {
        val api = SwitchApi(active = "RH950F")   // permit already mine
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val result = guarded(api, shared).claim()
        assertEquals(ParkOutcome.Claimed("RH950F"), (result as GuardedResult.Done).outcome)
        assertFalse(shared.permitWrites.single().forced)
    }

    @Test
    fun `stale other proceeds`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val stale = walidParkedFresh.copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS - 1)
        val result = guarded(api, FakeSharedStateStore(other = stale)).claim()
        assertTrue((result as GuardedResult.Done).outcome is ParkOutcome.Claimed)
    }

    @Test
    fun `rtdb down on an automatic claim degrades to manual`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(throwOnRead = true)
        val result = guarded(api, shared).claim(userInitiated = false)
        assertEquals(ParkOutcome.ManualNeeded, (result as GuardedResult.Done).outcome)
        assertEquals("XX123Y", api.active)   // did NOT gamble
    }

    @Test
    fun `rtdb down on a user claim proceeds with a note`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(throwOnRead = true)
        val result = guarded(api, shared).claim(userInitiated = true)
        result as GuardedResult.Done
        assertEquals(ParkOutcome.Claimed("RH950F"), result.outcome)
        assertEquals("couldn't check Walid's status", result.guardSkippedNote)
    }

    @Test
    fun `switching to the other car warns when MY car is parked outside holding`() = runTest {
        val api = SwitchApi(active = "RH950F")   // permit on me (Wasil)
        val state = FakeParkStateStore().apply { parkedOutside = true; parkedAtMs = now - 60_000 }
        val result = guarded(api, state = state).claim(target = WALID)
        assertTrue(result is GuardedResult.Blocked)
        assertEquals("Wasil", (result as GuardedResult.Blocked).otherLabel)
    }

    @Test
    fun `successful claim of my car while parked marks parkedOutside`() = runTest {
        val state = FakeParkStateStore().apply { parked = true; parkedOutside = false }
        val result = guarded(state = state).claim(userInitiated = true)
        assertTrue((result as GuardedResult.Done).outcome is ParkOutcome.Claimed)
        assertTrue(state.parkedOutside)
    }

    @Test
    fun `give-back hands permit to the parked other`() = runTest {
        val api = SwitchApi(active = "RH950F")   // mine
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val result = guarded(api, shared).giveBack()
        assertEquals(GiveBackResult.Given("XX123Y"), result)
        assertEquals("XX123Y", api.active)
        assertEquals("walid", shared.permitWrites.single().holder)
    }

    @Test
    fun `give-back does nothing when other is not parked`() = runTest {
        val api = SwitchApi(active = "RH950F")
        val result = guarded(api, FakeSharedStateStore(other = null)).giveBack()
        assertEquals(GiveBackResult.NothingToDo, result)
        assertEquals("RH950F", api.active)
    }

    @Test
    fun `give-back does nothing when permit is not mine`() = runTest {
        val api = SwitchApi(active = "XX123Y")   // already Walid's
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        assertEquals(GiveBackResult.NothingToDo, guarded(api, shared).giveBack())
    }

    @Test
    fun `give-back reports failure when rtdb is down`() = runTest {
        val result = guarded(shared = FakeSharedStateStore(throwOnRead = true)).giveBack()
        assertEquals(GiveBackResult.Failed, result)
    }

    @Test
    fun `give-back reports failure when the switch fails`() = runTest {
        val api = SwitchApi(active = "RH950F", fail = true)
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        assertEquals(GiveBackResult.Failed, guarded(api, shared).giveBack())
    }

    // --- stillStands: read the live facts a pending decision depends on ------

    @Test
    fun `blocked still stands while walid is genuinely parked outside and holding`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        assertTrue(guarded(api, shared).stillStands(decision))
    }

    @Test
    fun `blocked lapses once walid has driven off`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(other = walidParkedFresh.copy(parkedOutside = false))
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        assertFalse(guarded(api, shared).stillStands(decision))
    }

    @Test
    fun `blocked keeps the decision when the network read fails`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(throwOnRead = true)
        val decision = PendingDecision.Blocked("Walid", now, now, raisedAtMs = now)
        assertTrue(guarded(api, shared).stillStands(decision))
    }

    @Test
    fun `give back still stands while walid still needs it and the permit is still mine`() = runTest {
        val api = SwitchApi(active = "RH950F")
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        assertTrue(guarded(api, shared).stillStands(decision))
    }

    @Test
    fun `give back lapses once the permit has already moved off my plate`() = runTest {
        val api = SwitchApi(active = "XX123Y")   // already Walid's - nothing left to give back
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        assertFalse(guarded(api, shared).stillStands(decision))
    }

    @Test
    fun `give back keeps the decision when the network read fails`() = runTest {
        val api = SwitchApi(active = "RH950F")
        val shared = FakeSharedStateStore(throwOnRead = true)
        val decision = PendingDecision.GiveBack(otherLabel = "Walid", raisedAtMs = now)
        assertTrue(guarded(api, shared).stillStands(decision))
    }

    @Test
    fun `manual and takeover always stand without touching the network`() = runTest {
        val shared = FakeSharedStateStore(throwOnRead = true)
        val guardedClaim = guarded(shared = shared)
        assertTrue(guardedClaim.stillStands(PendingDecision.Manual(raisedAtMs = now)))
        assertTrue(guardedClaim.stillStands(PendingDecision.Takeover(byLabel = "Walid", raisedAtMs = now)))
        assertEquals(0, shared.readOtherCalls)
    }
}
