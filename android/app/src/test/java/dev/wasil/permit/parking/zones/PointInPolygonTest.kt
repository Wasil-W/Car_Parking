package dev.wasil.permit.parking.zones

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointInPolygonTest {
    // Unit square around Amsterdam-ish coordinates.
    private val square = ZonePolygon(outer = listOf(
        LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 5.0), LatLng(53.0, 4.0),
    ))

    @Test
    fun `point inside square`() {
        assertTrue(pointInPolygon(LatLng(52.5, 4.5), square))
    }

    @Test
    fun `point outside square`() {
        assertFalse(pointInPolygon(LatLng(51.5, 4.5), square))
        assertFalse(pointInPolygon(LatLng(52.5, 5.5), square))
    }

    @Test
    fun `point in hole is outside`() {
        val withHole = square.copy(holes = listOf(listOf(
            LatLng(52.4, 4.4), LatLng(52.4, 4.6), LatLng(52.6, 4.6), LatLng(52.6, 4.4),
        )))
        assertFalse(pointInPolygon(LatLng(52.5, 4.5), withHole))
        assertTrue(pointInPolygon(LatLng(52.1, 4.1), withHole))
    }

    @Test
    fun `concave polygon (U shape)`() {
        // U opening upward: notch cut from the top between lng 4.3 and 4.7.
        val u = ZonePolygon(outer = listOf(
            LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 5.0), LatLng(53.0, 4.7),
            LatLng(52.3, 4.7), LatLng(52.3, 4.3), LatLng(53.0, 4.3), LatLng(53.0, 4.0),
        ))
        assertTrue(pointInPolygon(LatLng(52.1, 4.5), u))    // bottom of the U
        assertFalse(pointInPolygon(LatLng(52.8, 4.5), u))   // inside the notch
        assertTrue(pointInPolygon(LatLng(52.8, 4.1), u))    // left arm
    }
}
