package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkDecisionEngineTest {
    private val here = GeoPoint(52.3702, 4.8952, 10f)
    private val tenMetersAway = GeoPoint(52.370290, 4.8952, 10f)

    private fun decide(
        samples: List<ActivitySample> = emptyList(),
        from: GeoPoint? = null,
        to: GeoPoint? = null,
        elapsed: Long = 10_000,
        linkBackUp: Boolean = false,
    ) = ParkDecisionEngine.decide(samples, from, to, elapsed, linkBackUp)

    @Test
    fun `no evidence keeps sampling`() = assertNull(decide())

    @Test
    fun `in-vehicle activity means bluetooth blip - false alarm`() {
        assertEquals(Decision.FalseAlarm,
            decide(listOf(ActivitySample(ActivityType.IN_VEHICLE, 80, 6_000))))
    }

    @Test
    fun `low confidence in-vehicle is ignored`() {
        assertNull(decide(listOf(ActivitySample(ActivityType.IN_VEHICLE, 40, 6_000))))
    }

    @Test
    fun `still after five seconds means parked in car`() {
        assertEquals(Decision.ParkedInCar,
            decide(listOf(ActivitySample(ActivityType.STILL, 85, 6_000))))
    }

    @Test
    fun `still too early is ignored - could be a red light blip`() {
        assertNull(decide(listOf(ActivitySample(ActivityType.STILL, 85, 3_000))))
    }

    // --- the red light that got through anyway (v0.6.8) ---

    /**
     * The defect this rule exists for. Reported 2026-08-08: stopped at a light
     * with the engine running, and the app recorded a park there.
     *
     * Five seconds of STILL is exactly what a car at a red light produces —
     * activity recognition reports IN_VEHICLE for *motion*, and a stationary car
     * has none — so the table above reads it as ParkedInCar and always did. The
     * threshold is not the answer: no wait short enough to keep detection
     * responsive is longer than a red light.
     */
    @Test
    fun `the car's bluetooth coming back beats five seconds of sitting still`() {
        assertEquals(
            Decision.FalseAlarm,
            decide(listOf(ActivitySample(ActivityType.STILL, 95, 30_000)), linkBackUp = true),
        )
    }

    @Test
    fun `a reconnected link beats a walk away and beats the timeout`() {
        // Both of the other ways this run could have ended. Neither may survive
        // the one fact that says the car has power: whatever the phone thinks it
        // is doing, the stereo is answering.
        assertEquals(
            Decision.FalseAlarm,
            decide(listOf(ActivitySample(ActivityType.ON_FOOT, 90, 8_000)), linkBackUp = true),
        )
        assertEquals(
            Decision.FalseAlarm,
            decide(from = here, to = tenMetersAway, elapsed = 90_000, linkBackUp = true),
        )
    }

    @Test
    fun `walking means parked and walked away`() {
        assertEquals(Decision.ParkedWalkedAway,
            decide(listOf(ActivitySample(ActivityType.ON_FOOT, 75, 8_000))))
    }

    @Test
    fun `gps displacement over ten meters means walked away`() {
        assertEquals(Decision.ParkedWalkedAway, decide(from = here, to = tenMetersAway))
    }

    @Test
    fun `gps displacement with in-vehicle activity is not a walk`() {
        assertEquals(Decision.FalseAlarm,
            decide(listOf(ActivitySample(ActivityType.IN_VEHICLE, 90, 6_000)), here, tenMetersAway))
    }

    @Test
    fun `inaccurate gps fix is not trusted`() {
        assertNull(decide(from = here, to = tenMetersAway.copy(accuracyM = 50f)))
    }

    @Test
    fun `timeout with nothing conclusive is unclear`() {
        assertEquals(Decision.Unclear, decide(elapsed = 90_000))
    }
}
