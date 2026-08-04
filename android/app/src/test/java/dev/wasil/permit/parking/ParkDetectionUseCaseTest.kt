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

    // --- D2: a failed GPS read is not a finding about where the car is ---

    @Test
    fun `no fix anywhere leaves the published parked-outside value alone`() = runTest {
        // This used to write false, which reads on the other phone as "my car
        // is not on a paid street, the permit is yours" — on the strength of a
        // failed GPS read, with their car possibly on a paid street.
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore().apply {
            parkedOutside = true
            lastZoneCode = "T11V"
        }
        useCase(signals, state, SwitchApi()).run()
        assertTrue("the previous value must stand", state.parkedOutside)
        assertEquals("T11V", state.lastZoneCode)
    }

    @Test
    fun `no fix anywhere marks the parked-outside state unknown`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore().apply { parkedOutside = true }
        val scheduler = RecordingScheduler()
        useCase(signals, state, SwitchApi(), scheduler = scheduler).run()
        assertFalse(state.parkedOutsideKnown)
        // Still syncs: the other phone has to learn the difference between a
        // finding and a gap, and it can only learn it from a write.
        assertTrue(scheduler.calls.contains("sync"))
    }

    @Test
    fun `a resolved zone is a finding and says so`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(homePoint))
        val state = FakeParkStateStore().apply { parkedOutsideKnown = false }
        useCase(signals, state, SwitchApi()).run()
        assertTrue(state.parkedOutsideKnown)
        assertFalse(state.parkedOutside)
    }

    @Test
    fun `parking in a paid zone is a finding too`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore().apply { parkedOutsideKnown = false }
        useCase(signals, state).run()
        assertTrue(state.parkedOutsideKnown)
        assertTrue(state.parkedOutside)
    }

    // --- the live trail: the drive supplies what the final fix could not ---

    @Test
    fun `with no fix the drive's last position resolves the zone`() = runTest {
        // The reported bug, from the inside: park in a garage or after the
        // phone has sat still and there is no fix to be had — but the drive to
        // get there produced positions all the way.
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore().apply {
            carLinkConnected = false
            liveLocation = LiveLocation.captured(homePoint, now - 20_000)
        }
        val api = SwitchApi()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, api, notifier = notifier).run()

        assertEquals(ParkOutcome.FreeZoneParked, outcome)
        assertFalse("no permit is needed at home", state.parkedOutside)
        assertTrue(notifier.calls.contains("noclaim:at home"))
        assertFalse("this is the prompt Wasil kept getting at home",
            notifier.calls.contains("manual"))
    }

    @Test
    fun `with no fix the drive's last position pins the car on the map`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore().apply {
            liveLocation = LiveLocation.captured(paidPoint, now - 20_000)
        }
        useCase(signals, state).run()
        // The other half of the same bug: "no parked car location even though
        // it detected the parked car".
        assertEquals(paidPoint, state.lastParkLocation)
        assertEquals(paidPoint, state.detectedParkLocation)
    }

    @Test
    fun `with no fix the drive's last position can still claim a paid spot`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore().apply {
            liveLocation = LiveLocation.captured(paidPoint, now - 20_000)
        }
        val outcome = useCase(signals, state, SwitchApi()).run()
        assertEquals(ParkOutcome.Claimed("RH950F"), outcome)
        assertTrue(state.parkedOutside)
        assertEquals("T11V", state.lastZoneCode)
    }

    @Test
    fun `a trail from a link that came back up cannot claim anything`() = runTest {
        // A Bluetooth blip that reconnects while detection is still running.
        // The position is fresh and would resolve to a paid zone, and it must
        // still be refused: the car is being driven, not parked.
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore().apply {
            carLinkConnected = true
            liveLocation = LiveLocation.captured(paidPoint, now - 20_000)
        }
        val api = SwitchApi()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, api, notifier = notifier).run()

        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertEquals("the permit must not move on a driving position", "XX123Y", api.active)
        assertFalse(state.parkedOutside)
        assertTrue(notifier.calls.contains("manual"))
    }

    @Test
    fun `nothing the sampler writes during a whole drive can move the permit`() = runTest {
        // The requirement stated as a test: the live position must not be able
        // to fire a zone lookup, a permit switch or a purchase, not even via a
        // stale timer or a race. So run the claim path against the trail at
        // every single sample of a drive through a paid zone, with the link
        // still up, and check the permit never moves.
        val api = SwitchApi()
        val notifier = RecordingParkNotifier()
        val state = FakeParkStateStore().apply { carLinkConnected = true }

        repeat(20) { poll ->
            state.liveLocation = LiveLocation.captured(paidPoint, now - poll * 1_000L)
            val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
                locations = mutableListOf(null))
            useCase(signals, state, api, notifier = notifier).run()

            assertEquals("permit moved on poll $poll", "XX123Y", api.active)
            assertFalse("poll $poll blocked the other phone", state.parkedOutside)
            assertEquals("poll $poll resolved a zone", null, state.lastZoneCode)
        }
    }

    @Test
    fun `a trail older than the cap is refused rather than guessed from`() = runTest {
        // Sampling died early in a long drive, so the freshest thing left is
        // the driveway. Sealing it would say "at home, no permit needed" with
        // the car parked in town.
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore().apply {
            liveLocation = LiveLocation.captured(homePoint, now - LIVE_FIX_MAX_AGE_MS - 1)
        }
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, SwitchApi(), notifier = notifier).run()

        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(notifier.calls.contains("manual"))
        assertFalse(state.parkedOutsideKnown)
    }

    @Test
    fun `a real fix beats the trail`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore().apply {
            liveLocation = LiveLocation.captured(homePoint, now - 20_000)
        }
        useCase(signals, state).run()
        assertEquals(paidPoint, state.lastParkLocation)
        assertTrue(state.parkedOutside)
    }

    // --- A3: ninety seconds of silence is not a reason to ask at home ---

    @Test
    fun `unclear signals at a known home spot stay quiet`() = runTest {
        // Weak activity signals mean indoors, and indoors correlates with home
        // — so the prompt fired hardest where there is nothing to decide.
        val signals = ScriptedSignals(locations = mutableListOf(null))
        val state = FakeParkStateStore().apply {
            liveLocation = LiveLocation.captured(homePoint, now - 20_000)
        }
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, SwitchApi(), notifier = notifier).run()

        assertEquals(ParkOutcome.FreeZoneParked, outcome)
        assertFalse(notifier.calls.contains("manual"))
        assertTrue(notifier.calls.contains("noclaim:at home"))
    }

    @Test
    fun `unclear signals at a known paid spot still ask and never claim`() = runTest {
        // "Unclear" is not evidence that a claim is wanted. It may go quiet; it
        // may never go ahead.
        val signals = ScriptedSignals(locations = mutableListOf(null))
        val state = FakeParkStateStore().apply {
            liveLocation = LiveLocation.captured(paidPoint, now - 20_000)
        }
        val api = SwitchApi()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, api, notifier = notifier).run()

        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("manual"))
    }

    @Test
    fun `unclear signals with nothing known still ask`() = runTest {
        val signals = ScriptedSignals(locations = mutableListOf(null))
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
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
