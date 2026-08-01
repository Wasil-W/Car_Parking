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
}
