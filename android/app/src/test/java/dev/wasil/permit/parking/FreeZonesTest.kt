package dev.wasil.permit.parking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeZonesTest {
    private val home = FreeZone(52.3702, 4.8952, radiusM = 60.0, label = "Home")

    @Test
    fun `point inside zone radius is free`() {
        assertTrue(isInFreeZone(GeoPoint(52.3703, 4.8952, 10f), listOf(home))) // ~11 m away
    }

    @Test
    fun `point outside radius is not free`() {
        assertFalse(isInFreeZone(GeoPoint(52.3712, 4.8952, 10f), listOf(home))) // ~111 m away
    }

    @Test
    fun `no zones means never free`() {
        assertFalse(isInFreeZone(GeoPoint(52.3702, 4.8952, 10f), emptyList()))
    }
}
