package dev.wasil.permit.parking

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
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedSignals(
    /** samples that "arrive" at the given poll round (0-based). */
    private val script: Map<Int, ActivitySample> = emptyMap(),
    private val locations: MutableList<GeoPoint?> = mutableListOf(null),
) : DetectionSignals {
    var round = -1
    private val seen = mutableListOf<ActivitySample>()
    var started = false
    var stopped = false

    override suspend fun start() { started = true }
    override suspend fun stop() { stopped = true }
    override fun activitySamples(): List<ActivitySample> {
        round++
        script[round]?.let { seen += it }
        return seen.toList()
    }
    override suspend fun currentLocation(): GeoPoint? =
        if (locations.size > 1) locations.removeAt(0) else locations.first()
}

private class RecordingNotifier : ParkNotifier {
    val calls = mutableListOf<String>()
    override fun statusPermitOn(label: String, vrn: String) { calls += "status:$label:$vrn" }
    override fun statusFreeZone() { calls += "freezone" }
    override fun askManualDecision() { calls += "manual" }
    override fun switchFailed(reason: String?) { calls += "failed" }
    override fun mismatchWarning(serverVrn: String?) { calls += "mismatch:$serverVrn" }
}

private class SwitchApi(var active: String? = "XX123Y", var fail: Boolean = false) : PermitApi {
    override suspend fun login(body: LoginRequest) = LoginResponse("tok")
    override suspend fun getClientProduct(productId: Long): ClientProductResponse =
        ClientProductResponse(listOf(
            VrnEntry("RH950F", active == "RH950F"), VrnEntry("XX123Y", active == "XX123Y")))
    override suspend fun activate(body: ActivateRequest): ActivateResponse {
        if (fail) throw IOException("offline")
        active = body.vrn
        return ActivateResponse(1)
    }
}

class ParkDetectionUseCaseTest {
    private val config = PermitConfig("u", "p", "RH950F", "XX123Y")
    private val stillAt6s = ActivitySample(ActivityType.STILL, 85, 6_000)
    private val driving = ActivitySample(ActivityType.IN_VEHICLE, 85, 6_000)

    private fun useCase(
        signals: DetectionSignals,
        state: FakeParkStateStore = FakeParkStateStore(),
        zones: FakeFreeZoneStore = FakeFreeZoneStore(),
        api: SwitchApi = SwitchApi(),
        notifier: RecordingNotifier = RecordingNotifier(),
    ): ParkDetectionUseCase {
        val claim = ClaimPermit(PermitRepository(api), FakeCredentialStore(config), state, notifier)
        return ParkDetectionUseCase(signals, state, zones, claim, notifier)
    }

    @Test
    fun `confirmed park auto-claims my plate and updates status`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore()
        val notifier = RecordingNotifier()
        val outcome = useCase(signals, state, notifier = notifier).run()
        assertEquals(ParkOutcome.Claimed("RH950F"), outcome)
        assertTrue(state.parked)
        assertTrue(signals.stopped)
        assertTrue(notifier.calls.contains("status:Wasil:RH950F"))
    }

    @Test
    fun `bluetooth blip while driving does nothing`() = runTest {
        val signals = ScriptedSignals(script = mapOf(0 to driving))
        val state = FakeParkStateStore()
        val api = SwitchApi()
        val outcome = useCase(signals, state, api = api).run()
        assertEquals(ParkOutcome.FalseAlarm, outcome)
        assertFalse(state.parked)
        assertEquals("XX123Y", api.active) // permit untouched
    }

    @Test
    fun `timeout with no evidence asks for a manual decision`() = runTest {
        val signals = ScriptedSignals()
        val notifier = RecordingNotifier()
        val api = SwitchApi()
        val outcome = useCase(signals, api = api, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(notifier.calls.contains("manual"))
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `park inside stored free zone never touches the permit`() = runTest {
        val home = GeoPoint(52.3702, 4.8952, 10f)
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s), locations = mutableListOf(home))
        val zones = FakeFreeZoneStore(mutableListOf(FreeZone(52.3702, 4.8952, 60.0, "Home")))
        val api = SwitchApi()
        val outcome = useCase(signals, zones = zones, api = api).run()
        assertEquals(ParkOutcome.FreeZoneParked, outcome)
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `auto-claim off falls back to manual notification`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore().apply { autoClaim = false }
        val api = SwitchApi()
        val outcome = useCase(signals, state, api = api).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `network failure during switch is loud and reported`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val notifier = RecordingNotifier()
        val outcome = useCase(signals, api = SwitchApi(fail = true), notifier = notifier).run()
        assertEquals(ParkOutcome.SwitchFailed, outcome)
        assertTrue(notifier.calls.contains("failed"))
    }

    @Test
    fun `server mismatch after activate warns loudly`() = runTest {
        // activate "succeeds" but the server still reports the other plate
        val api = object : PermitApi {
            override suspend fun login(body: LoginRequest) = LoginResponse("tok")
            override suspend fun getClientProduct(productId: Long) =
                ClientProductResponse(listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true)))
            override suspend fun activate(body: ActivateRequest) = ActivateResponse(1)
        }
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val notifier = RecordingNotifier()
        val state = FakeParkStateStore()
        val claim = ClaimPermit(PermitRepository(api), FakeCredentialStore(config), state, notifier)
        val outcome = ParkDetectionUseCase(signals, state, FakeFreeZoneStore(), claim, notifier).run()
        assertEquals(ParkOutcome.MismatchDetected("XX123Y"), outcome)
        assertTrue(notifier.calls.contains("mismatch:XX123Y"))
    }

    @Test
    fun `walid phone claims walid plate`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore().apply { myCar = MyCar.WALID }
        val api = SwitchApi(active = "RH950F")
        val outcome = useCase(signals, state, api = api).run()
        assertEquals(ParkOutcome.Claimed("XX123Y"), outcome)
    }

    @Test
    fun `unconfigured phone does nothing`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore().apply { myCar = null }
        val outcome = useCase(signals, state).run()
        assertEquals(ParkOutcome.NotConfigured, outcome)
    }
}
