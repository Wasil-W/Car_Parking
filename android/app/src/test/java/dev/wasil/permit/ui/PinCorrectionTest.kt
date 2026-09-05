package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZonePolygon
import dev.wasil.permit.parking.zones.ZoneResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCorrectionTest {

    /** A square from 52.3705 to 52.3715 N, 4.8895 to 4.8905 E. */
    private val paidArea = TariffArea(
        code = "T13B",
        name = "Basistarief TC3 ma-za 09-24",
        tariffText = "€5,37/h",
        polygons = listOf(
            ZonePolygon(
                listOf(
                    LatLng(52.3705, 4.8895),
                    LatLng(52.3705, 4.8905),
                    LatLng(52.3715, 4.8905),
                    LatLng(52.3715, 4.8895),
                ),
            ),
        ),
    )

    /** 60 m circle centred ~111 m south of the paid square, so they never overlap. */
    private val freeZone = FreeZone(52.3700, 4.8900, radiusM = 60.0, label = "Free spot")

    private val resolver = ZoneResolver(null, listOf(freeZone), listOf(paidArea))

    private val inFree = GeoPoint(52.3700, 4.8900, 0f)
    private val inPaid = GeoPoint(52.3710, 4.8900, 0f)

    /** One degree of latitude is ~111,195 m under the app's haversine radius. */
    private fun northOf(p: GeoPoint, metres: Double) =
        GeoPoint(p.lat + metres / 111_194.9, p.lng, 0f)

    @Test
    fun `a nudge within the cap is accepted`() {
        assertTrue(correctionFor(inFree, northOf(inFree, 200.0), false, resolver) is CorrectionResult.Ok)
    }

    @Test
    fun `a tap beyond the cap is refused and reports how far it was`() {
        val result = correctionFor(inFree, northOf(inFree, 500.0), false, resolver)
        assertTrue(result is CorrectionResult.TooFar)
        assertEquals(500.0, (result as CorrectionResult.TooFar).distanceM, 2.0)
    }

    @Test
    fun `successive corrections are measured from detection, never from the last one`() {
        // Reported 2026-08-02: "it is literally possible to keep clicking and
        // then bring the car all the way to somewhere else". Confirming a move
        // and starting another used to re-anchor the cap to the new pin, so the
        // car could walk the city 300 m at a time. A cap that resets is not a
        // cap — callers must always pass the detected position as `from`.
        val detected = inFree
        val first = northOf(detected, 250.0)
        assertTrue(correctionFor(detected, first, false, resolver) is CorrectionResult.Ok)

        // Only 250 m beyond the first correction, but 500 m from detection.
        val second = northOf(detected, 500.0)
        assertTrue(correctionFor(detected, second, false, resolver) is CorrectionResult.TooFar)
    }

    @Test
    fun `correcting from a free zone onto a paid street flips to paid`() {
        val result = correctionFor(inFree, inPaid, false, resolver) as CorrectionResult.Ok
        assertEquals(inPaid, result.point)
        assertEquals("T13B", result.zoneCode)
        assertTrue(result.parkedOutside)
        assertEquals(Flip.NOW_PAID, result.flip)
    }

    @Test
    fun `correcting from a paid street into a free zone flips to free and clears the code`() {
        val result = correctionFor(inPaid, inFree, true, resolver) as CorrectionResult.Ok
        assertNull(result.zoneCode)
        assertEquals(false, result.parkedOutside)
        assertEquals(Flip.NOW_FREE, result.flip)
    }

    @Test
    fun `a correction within the same paid area is not a flip`() {
        val result = correctionFor(inPaid, northOf(inPaid, 20.0), true, resolver) as CorrectionResult.Ok
        assertEquals("T13B", result.zoneCode)
        assertTrue(result.parkedOutside)
        assertEquals(Flip.NONE, result.flip)
    }

    @Test
    fun `a correction onto an unmetered street is free but not a flip when it already was`() {
        // 150 m south: clear of the 60 m free circle and south of the paid
        // square, but still well inside the correction cap.
        val nowhere = northOf(inFree, -150.0)
        val result = correctionFor(inFree, nowhere, false, resolver) as CorrectionResult.Ok
        assertNull(result.zoneCode)
        assertEquals(false, result.parkedOutside)
        assertEquals(Flip.NONE, result.flip)
    }

    // ---- a park that never had a position at all -------------------------
    //
    // Reproduced on shipped v0.7.5: with no position there is no marker, and
    // the marker was the only way into this flow. These pin the way out.

    @Test
    fun `the first placement of a never-placed park is not capped`() {
        // Ten kilometres away. There is no detected point to have wandered
        // from, so there is nothing for the 300 m cap to measure — and
        // refusing here would leave the park unfixable, which is the bug.
        val faraway = northOf(inPaid, 10_000.0)
        assertTrue(correctionFor(null, faraway, null, resolver) is CorrectionResult.Ok)
    }

    @Test
    fun `once placed, the cap applies from that first placement`() {
        // The caller writes the first placement into detectedParkLocation, so
        // the second move is measured against it. The guard is postponed by
        // one placement, never removed.
        val first = correctionFor(null, inPaid, null, resolver) as CorrectionResult.Ok
        assertTrue(
            correctionFor(first.point, northOf(first.point, 500.0), true, resolver)
                is CorrectionResult.TooFar,
        )
    }

    /**
     * The case that silently stranded the other car.
     *
     * `CarBluetoothReceiver` writes `parkedOutside = false` at the start of
     * every drive. Passing that leftover as "what it was before" makes a first
     * placement in a free zone compute [Flip.NONE] — which USE-CASES C9 says
     * means *ask nothing* — so the spot would stay unknown forever and the
     * other phone would stay blocked by a car whose owner had just placed it.
     */
    @Test
    fun `a first placement in a free zone still asks, rather than reading as unchanged`() {
        val leftover = correctionFor(null, inFree, false, resolver) as CorrectionResult.Ok
        assertEquals("the old signature's answer, and it is wrong here", Flip.NONE, leftover.flip)

        val correct = correctionFor(null, inFree, null, resolver) as CorrectionResult.Ok
        assertEquals(Flip.UNKNOWN_BEFORE, correct.flip)
        assertEquals(false, correct.parkedOutside)
    }

    @Test
    fun `a first placement in a paid zone resolves the area and asks`() {
        val result = correctionFor(null, inPaid, null, resolver) as CorrectionResult.Ok
        assertEquals("T13B", result.zoneCode)
        assertEquals(true, result.parkedOutside)
        // Not NOW_PAID: nothing changed to paid, because nothing was known.
        assertEquals(Flip.UNKNOWN_BEFORE, result.flip)
    }
}
