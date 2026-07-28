package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkSignalsTest {
    @Test
    fun `distance between identical points is zero`() {
        val p = GeoPoint(52.3702, 4.8952, 10f)
        assertEquals(0.0, distanceMeters(p, p), 0.001)
    }

    @Test
    fun `distance of roughly 111m per thousandth of latitude`() {
        val a = GeoPoint(52.3702, 4.8952, 10f)
        val b = GeoPoint(52.3712, 4.8952, 10f)
        assertEquals(111.2, distanceMeters(a, b), 2.0)
    }

    @Test
    fun `ten meter walk is measurable`() {
        val a = GeoPoint(52.370200, 4.895200, 5f)
        val b = GeoPoint(52.370290, 4.895200, 5f) // ~10 m north
        assertTrue(distanceMeters(a, b) in 8.0..12.0)
    }
}
