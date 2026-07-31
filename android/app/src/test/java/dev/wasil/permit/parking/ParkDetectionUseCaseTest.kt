package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.FakeCredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZonePolygon
import dev.wasil.permit.parking.zones.ZoneResolver
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

private class RecordingScheduler : ParkScheduler {
    val calls = mutableListOf<String>()
    override fun requestSync() { calls += "sync" }
    override fun requestGiveBack() { calls += "giveback" }
}

class ParkDetectionUseCaseTest {
    private val config = PermitConfig("u", "p", "RH950F", "XX123Y")
    private val stillAt6s = ActivitySample(ActivityType.STILL, 85, 6_000)
    private val driving = ActivitySample(ActivityType.IN_VEHICLE, 85, 6_000)
    private val now = 1_000_000_000_000L

    // Paid polygon covering lat 52..53, lng 4..5; home circle at 52.3702,4.8952.
    private val paidArea = TariffArea(
        "T11V", "Centrum", "€8,05/h",
        listOf(ZonePolygon(outer = listOf(
            LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 5.0), LatLng(53.0, 4.0),
        ))),
    )
    private val home = FreeZone(52.3702, 4.8952, 60.0, "Home")
    private val paidPoint = GeoPoint(52.5, 4.5, 5f)
    private val homePoint = GeoPoint(52.3702, 4.8952, 5f)
    private val outsidePoint = GeoPoint(51.0, 4.5, 5f)

    private fun useCase(
        signals: DetectionSignals,
        state: FakeParkStateStore = FakeParkStateStore(),
        api: SwitchApi = SwitchApi(),
        shared: FakeSharedStateStore = FakeSharedStateStore(),
        notifier: RecordingParkNotifier = RecordingParkNotifier(),
        scheduler: RecordingScheduler = RecordingScheduler(),
        homeZone: FreeZone? = home,
    ): ParkDetectionUseCase {
        val repo = PermitRepository(api)
        val credentials = FakeCredentialStore(config)
        val guarded = GuardedClaim(repo, credentials, state, shared,
            ClaimPermit(repo, credentials, state, notifier), nowMs = { now })
        val resolver = ZoneResolver(homeZone, emptyList(), listOf(paidArea))
        return ParkDetectionUseCase(signals, state, resolver, guarded, notifier,
            scheduler, nowMs = { now })
    }

    @Test
    fun `park in paid zone auto-claims with zone text and marks parked outside`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore()
        val notifier = RecordingParkNotifier()
        val scheduler = RecordingScheduler()
        val outcome = useCase(signals, state, notifier = notifier, scheduler = scheduler).run()
        assertEquals(ParkOutcome.Claimed("RH950F"), outcome)
        assertTrue(state.parked)
        assertTrue(state.parkedOutside)
        assertEquals("T11V", state.lastZoneCode)
        assertTrue(notifier.calls.contains("status:Wasil:RH950F:€8,05/h zone T11V"))
        assertTrue(scheduler.calls.contains("sync"))
    }

    @Test
    fun `park at home never claims and asks give-back check`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(homePoint))
        val state = FakeParkStateStore()
        val api = SwitchApi()
        val scheduler = RecordingScheduler()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, api, notifier = notifier, scheduler = scheduler).run()
        assertEquals(ParkOutcome.FreeZoneParked, outcome)
        assertFalse(state.parkedOutside)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("noclaim:at home"))
        assertTrue(scheduler.calls.contains("giveback"))
    }

    @Test
    fun `park outside all polygons is free street no claim`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(outsidePoint))
        val api = SwitchApi()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, api = api, notifier = notifier).run()
        assertEquals(ParkOutcome.FreeZoneParked, outcome)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("noclaim:free street parking (outside paid zones)"))
    }

    @Test
    fun `no gps fix asks manual and does not block the other phone`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore()
        val api = SwitchApi()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, api, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(state.parked)
        assertFalse(state.parkedOutside)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("manual"))
    }

    @Test
    fun `blocked by parked brother posts the blocked notification`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(other = PhoneState(
            parkedOutside = true, parkedAtMs = now - 120_000, heartbeatAtMs = now - 60_000))
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, api = api, shared = shared, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("blocked:Walid"))
    }

    @Test
    fun `rtdb down on auto claim degrades to manual`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(throwOnRead = true)
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, api = api, shared = shared, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("manual"))
    }

    @Test
    fun `auto-claim off in paid zone asks manual`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore().apply { autoClaim = false }
        val api = SwitchApi()
        val outcome = useCase(signals, state, api).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(state.parkedOutside)   // still blocks the other phone
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `bluetooth blip while driving does nothing`() = runTest {
        val signals = ScriptedSignals(script = mapOf(0 to driving))
        val state = FakeParkStateStore()
        val api = SwitchApi()
        val outcome = useCase(signals, state, api).run()
        assertEquals(ParkOutcome.FalseAlarm, outcome)
        assertFalse(state.parked)
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `timeout with no evidence asks for a manual decision`() = runTest {
        val signals = ScriptedSignals()
        val notifier = RecordingParkNotifier()
        val api = SwitchApi()
        val outcome = useCase(signals, api = api, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(notifier.calls.contains("manual"))
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `network failure during switch is loud and reported`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, api = SwitchApi(fail = true), notifier = notifier).run()
        assertEquals(ParkOutcome.SwitchFailed, outcome)
        assertTrue(notifier.calls.contains("failed"))
    }

    @Test
    fun `walid phone claims walid plate`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore().apply { myCar = MyCar.WALID }
        val api = SwitchApi(active = "RH950F")
        val outcome = useCase(signals, state, api).run()
        assertEquals(ParkOutcome.Claimed("XX123Y"), outcome)
    }

    @Test
    fun `a failed gps fix keeps the car location already on record`() = runTest {
        val known = GeoPoint(52.4000, 4.9000, 8f)
        val state = FakeParkStateStore().apply { lastParkLocation = known }
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        useCase(signals, state, SwitchApi()).run()
        // Writing null here used to erase the pin from the map on every park
        // without a fix, which is why the car "disappeared".
        assertEquals(known, state.lastParkLocation)
    }

    @Test
    fun `does not ask when the permit is already on my own car`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore().apply { autoClaim = false }
        val api = SwitchApi(active = "RH950F")   // already Wasil's
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, api, notifier = notifier).run()

        assertEquals(ParkOutcome.Claimed("RH950F"), outcome)
        assertFalse("nothing to decide — must not ask", notifier.calls.contains("manual"))
        assertEquals("RH950F", api.active)
    }

    @Test
    fun `still asks when the permit is on the other car`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore().apply { autoClaim = false }
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, SwitchApi(active = "XX123Y"),
            notifier = notifier).run()

        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(notifier.calls.contains("manual"))
    }

    @Test
    fun `unconfigured phone does nothing`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore().apply { myCar = null }
        val outcome = useCase(signals, state).run()
        assertEquals(ParkOutcome.NotConfigured, outcome)
    }
}
