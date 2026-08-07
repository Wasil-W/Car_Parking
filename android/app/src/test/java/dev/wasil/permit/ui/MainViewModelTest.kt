package dev.wasil.permit.ui

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ActivateResponse
import dev.wasil.permit.data.api.ClientProductResponse
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitApi
import dev.wasil.permit.data.api.VrnEntry
import dev.wasil.permit.data.store.FakeCredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.parking.ClaimPermit
import dev.wasil.permit.parking.FakeParkStateStore
import dev.wasil.permit.parking.FakeSharedStateStore
import dev.wasil.permit.parking.GuardedClaim
import dev.wasil.permit.parking.RecordingParkNotifier
import dev.wasil.permit.parking.shared.PhoneState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import dev.wasil.permit.data.api.PermitKind
import dev.wasil.permit.parking.WALID
import dev.wasil.permit.parking.WASIL
import dev.wasil.permit.parking.legacyRoster
import dev.wasil.permit.parking.testConfig
import org.junit.Test

private class ScriptedApi : PermitApi {
    var active: String? = "RH950F"
    var failNextGet = false
    /** What the product endpoint calls this permit, or null when it says nothing. */
    var productName: String? = null
    override suspend fun login(body: LoginRequest) = LoginResponse("tok")
    override suspend fun getClientProduct(productId: Long): ClientProductResponse {
        if (failNextGet) { failNextGet = false; throw IOException("offline") }
        return ClientProductResponse(
            listOf(
                VrnEntry("RH950F", active == "RH950F"),
                VrnEntry("XX123Y", active == "XX123Y"),
            ),
            name = productName,
        )
    }
    override suspend fun activate(body: ActivateRequest): ActivateResponse {
        active = body.vrn
        return ActivateResponse(1L)
    }
}

class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ScriptedApi
    private val config = testConfig()
    private val now = 1_000_000_000_000L

    @Before fun setUp() { Dispatchers.setMain(dispatcher); api = ScriptedApi() }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(
        store: FakeCredentialStore = FakeCredentialStore(config),
        parkState: FakeParkStateStore = FakeParkStateStore(),
        shared: FakeSharedStateStore = FakeSharedStateStore(configured = false),
    ): MainViewModel {
        val repo = PermitRepository(api)
        val guarded = GuardedClaim(
            repo, store, parkState, shared,
            ClaimPermit(repo, store, parkState, RecordingParkNotifier()),
            nowMs = { now },
        )
        return MainViewModel(repo, store, parkState, { guarded }, { shared })
    }

    @Test
    fun `unconfigured store shows setup screen`() = runTest(dispatcher) {
        val vm = vm(FakeCredentialStore(null))
        assertTrue(vm.state.value.needsSetup)
    }

    @Test
    fun `configured store loads active plate and both options on init`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("RH950F", s.activeVrn)
        assertEquals(listOf("Wasil", "Walid"), s.roster.vehicles.map { it.name })
        assertEquals(listOf("RH950F", "XX123Y"), s.roster.vehicles.map { it.plate })
    }

    @Test
    fun `switchTo updates active plate after confirmed switch`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        vm.switchTo(config.roster.require(WALID))
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("XX123Y", s.activeVrn)
        assertNull(s.switching)
    }

    @Test
    fun `network failure surfaces message instead of crashing`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        api.failNextGet = true
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.message != null)
    }

    @Test
    fun `switching while the brother is parked and holding raises the blocked dialog`() =
        runTest(dispatcher) {
            api.active = "XX123Y"   // permit on Walid's car
            val shared = FakeSharedStateStore(
                other = PhoneState(
                    parkedOutside = true, parkedAtMs = now - 120_000, heartbeatAtMs = now - 60_000,
                ),
            )
            val vm = vm(shared = shared)
            dispatcher.scheduler.advanceUntilIdle()
            vm.switchTo(config.roster.require(WASIL))
            dispatcher.scheduler.advanceUntilIdle()

            val blocked = vm.state.value.blocked
            assertEquals("Walid", blocked?.otherLabel)
            assertEquals("XX123Y", api.active)   // permit untouched

            vm.confirmBlockedSwitch()            // "Claim anyway"
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("RH950F", api.active)
            assertNull(vm.state.value.blocked)
        }

    @Test
    fun `saveSetup normalizes plates and leaves setup mode`() = runTest(dispatcher) {
        val store = FakeCredentialStore(null)
        val vm = vm(store)
        vm.saveSetup("u", "p", "rh-950-f", "xx 123 y")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("RH950F", "XX123Y"), store.config!!.roster.vehicles.map { it.plate })
        assertTrue(!vm.state.value.needsSetup)
    }

    /**
     * The defect the roster removes rather than the one it adds.
     *
     * The editor asks for "your plate" and "the other car's plate"; the store
     * used to put the first in the *Wasil* slot whichever phone was typing. On
     * Walid's phone the two were therefore swapped, so `MyCar.WALID` pointed at
     * Wasil's plate and a claim moved the permit to the wrong car. Storing by
     * plate and recording which one this phone drives makes the slot
     * unguessable-at rather than merely correct today.
     */
    @Test
    fun `whose phone this is follows the plate, not a fixed slot`() = runTest(dispatcher) {
        val store = FakeCredentialStore(null)
        val parkState = FakeParkStateStore()
        val vm = vm(store, parkState)
        // Walid's phone: "my plate" is XX123Y, which sorts into slot 1.
        vm.saveSetup("u", "p", "xx 123 y", "rh-950-f")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("RH950F", "XX123Y"), store.config!!.roster.vehicles.map { it.plate })
        assertEquals(WALID, parkState.thisPhoneDrives)
        assertEquals("XX123Y", store.config!!.roster.require(WALID).plate)
    }

    // --- the permit's own type, read from the same response as the plates ---

    @Test
    fun `an account that names a visitor permit is recorded as one`() = runTest(dispatcher) {
        val store = FakeCredentialStore(null)
        api.productName = "Bezoekersvergunning Centrum"
        vm(store).findPlates("u", "p")
        assertEquals(PermitKind.VISITOR, store.config!!.permitKind)
    }

    /**
     * The direction that cannot cost a fine. A response that says nothing about
     * the permit's type leaves it UNKNOWN, and UNKNOWN is treated as the
     * restricted kind — the one the 66 exception areas bind.
     */
    @Test
    fun `an account that says nothing about its type stays unknown`() = runTest(dispatcher) {
        val store = FakeCredentialStore(null)
        api.productName = null
        vm(store).findPlates("u", "p")
        assertEquals(PermitKind.UNKNOWN, store.config!!.permitKind)
    }

    @Test
    fun `saving the permit afterwards keeps what the account said`() = runTest(dispatcher) {
        val store = FakeCredentialStore(null)
        api.productName = "Bezoekersvergunning Centrum"
        val vm = vm(store)
        vm.findPlates("u", "p")
        vm.saveSetup("u", "p", "RH950F", "XX123Y")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PermitKind.VISITOR, store.config!!.permitKind)
    }
}
