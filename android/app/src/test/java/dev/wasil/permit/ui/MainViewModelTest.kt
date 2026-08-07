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
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
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
    /** The site answering 401 — a refusal, as distinct from never reaching it. */
    var rejectNextGet = false
    /** An account that signs in fine but covers no vehicles. */
    var noCars = false
    /** What the product endpoint calls this permit, or null when it says nothing. */
    var productName: String? = null
    override suspend fun login(body: LoginRequest) = LoginResponse("tok")
    override suspend fun getClientProduct(productId: Long): ClientProductResponse {
        if (failNextGet) { failNextGet = false; throw IOException("offline") }
        if (rejectNextGet) {
            rejectNextGet = false
            throw HttpException(Response.error<Any>(401, "".toResponseBody(null)))
        }
        return ClientProductResponse(
            if (noCars) emptyList() else listOf(
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
    /** How many times the view model threw the cached session away. */
    private var sessionsForgotten = 0

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = ScriptedApi()
        sessionsForgotten = 0
    }
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
        return MainViewModel(
            repo, store, parkState, { guarded }, { shared },
            forgetSession = { sessionsForgotten++ },
            nowMs = { now },
        )
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

    // --- "i entered incorrect credentials and it still showed my cars" (v0.6.8) ---

    /**
     * The defect itself, at the level it was caused.
     *
     * The request went out on a token from the previous sign-in, so the site
     * answered about the previous *account* and the wrong password was never
     * tested. Clearing the session before asking is what makes the call a
     * sign-in; this pins that it happens, because everything else about the fix
     * is downstream of it.
     */
    @Test
    fun `finding cars throws the old session away first, so the credentials are actually used`() =
        runTest(dispatcher) {
            vm().findPlates("u", "p")
            assertTrue("the cached token must not survive a change of credentials",
                sessionsForgotten > 0)
        }

    @Test
    fun `refused credentials are reported as refused, not as an unreachable site`() =
        runTest(dispatcher) {
            api.rejectNextGet = true
            assertEquals(SignIn.Rejected, vm().findPlates("u", "wrong"))
        }

    @Test
    fun `a site that cannot be reached is not blamed on the password`() = runTest(dispatcher) {
        api.failNextGet = true
        assertEquals(SignIn.Unreachable, vm().findPlates("u", "p"))
    }

    @Test
    fun `signing in successfully returns the cars the account lists`() = runTest(dispatcher) {
        assertEquals(SignIn.Cars(listOf("RH950F", "XX123Y")), vm().findPlates("u", "p"))
    }

    /**
     * A working permit must not be broken by a typo.
     *
     * The old note in `findPlates` called storing an unproven pair "harmless and
     * recoverable" — true only while nothing could tell it was wrong. Now that a
     * refusal is visible, leaving it stored would mean every background claim
     * from that moment signs in with a password the site has just rejected.
     */
    @Test
    fun `a refused sign-in puts the working credentials back`() = runTest(dispatcher) {
        val store = FakeCredentialStore(config)
        api.rejectNextGet = true
        vm(store).findPlates("wrong-user", "wrong-pass")
        assertEquals(config.username, store.config!!.username)
        assertEquals(config.password, store.config!!.password)
    }

    @Test
    fun `a refused sign-in on an install with no permit leaves it with no permit`() =
        runTest(dispatcher) {
            // The other half of "put it back": there was nothing to put back, so
            // the refused pair must not be what remains.
            val store = FakeCredentialStore(null)
            api.rejectNextGet = true
            vm(store).findPlates("wrong-user", "wrong-pass")
            assertNull(store.config)
        }

    @Test
    fun `an account that signs in but lists no cars is not called a failure`() =
        runTest(dispatcher) {
            val store = FakeCredentialStore(null)
            api.noCars = true
            assertEquals(SignIn.NoCars, vm(store).findPlates("u", "p"))
            // The credentials worked, so they stay — the plates are typed in.
            assertEquals("u", store.config!!.username)
        }

    // --- the permit site had a hiccup and the app showed nothing (v0.6.8) ---

    /**
     * Reported 2026-08-08: the site had a moment, the app fell back to its blank
     * defaults and showed "No plate active" — while the site was fine in a
     * browser. A failed read says nothing about where the permit is.
     */
    @Test
    fun `a failed refresh keeps the holder it already had and admits it is stale`() =
        runTest(dispatcher) {
            val vm = vm()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("RH950F", vm.state.value.activeVrn)

            api.failNextGet = true
            vm.refresh()
            dispatcher.scheduler.advanceUntilIdle()

            val s = vm.state.value
            assertEquals("the holder must not be erased by our own inability to look",
                "RH950F", s.activeVrn)
            assertTrue(s.permitReadFailed)
            assertEquals(now, s.activeVrnReadAtMs)
        }

    /**
     * The cold start, which is the case actually reported: the app was opened
     * with the site down, so there was never a successful read in this session
     * to fall back *from*. The answer has to come off disk.
     */
    @Test
    fun `a cold start with the site down shows the last holder it ever read`() =
        runTest(dispatcher) {
            val parkState = FakeParkStateStore().apply {
                lastKnownHolderVrn = "XX123Y"
                lastKnownHolderAtMs = now - 3_600_000
            }
            api.failNextGet = true
            val vm = vm(parkState = parkState)
            dispatcher.scheduler.advanceUntilIdle()

            val s = vm.state.value
            assertEquals("XX123Y", s.activeVrn)
            assertTrue(s.permitReadFailed)
            assertEquals(now - 3_600_000, s.activeVrnReadAtMs)
        }

    @Test
    fun `a successful read is what gets remembered, and only a successful one`() =
        runTest(dispatcher) {
            val parkState = FakeParkStateStore()
            val vm = vm(parkState = parkState)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("RH950F", parkState.lastKnownHolderVrn)
            assertEquals(now, parkState.lastKnownHolderAtMs)

            api.failNextGet = true
            vm.refresh()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("a failure must not overwrite the fallback",
                "RH950F", parkState.lastKnownHolderVrn)
        }

    @Test
    fun `a successful refresh stops calling the permit state stale`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        api.failNextGet = true
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.permitReadFailed)

        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(!vm.state.value.permitReadFailed)
    }
}
