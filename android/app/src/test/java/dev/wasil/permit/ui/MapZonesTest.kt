package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapZonesTest {
    private val home = FreeZone(52.3702, 4.8952, radiusM = 60.0, label = "Home")
    private val zoneA = FreeZone(52.3800, 4.9000, radiusM = 50.0)
    private val zoneB = FreeZone(52.3900, 4.9100, radiusM = 50.0)

    @Test
    fun `tap inside the home zone hits Home`() {
        assertEquals(ZoneRef.Home, zoneHitAt(GeoPoint(52.3702, 4.8952, 0f), home, emptyList()))
    }

    @Test
    fun `tap inside a free zone hits its index`() {
        assertEquals(
            ZoneRef.Free(1),
            zoneHitAt(GeoPoint(52.3900, 4.9100, 0f), home, listOf(zoneA, zoneB)),
        )
    }

    @Test
    fun `tap outside every zone hits nothing`() {
        assertNull(zoneHitAt(GeoPoint(10.0, 10.0, 0f), home, listOf(zoneA, zoneB)))
    }

    @Test
    fun `no home zone means a tap there hits nothing`() {
        assertNull(zoneHitAt(GeoPoint(52.3702, 4.8952, 0f), null, emptyList()))
    }

    @Test
    fun `overlapping zones — the nearest centre wins`() {
        val big = FreeZone(52.0, 4.0, radiusM = 200.0)
        val small = FreeZone(52.0009, 4.0, radiusM = 30.0) // ~100 m north of big, inside both
        val tap = GeoPoint(52.0009, 4.0, 0f) // dead centre of the small zone
        assertEquals(ZoneRef.Free(1), zoneHitAt(tap, null, listOf(big, small)))
    }

    @Test
    fun `radius clamps to the 30 to 200 metre range`() {
        assertEquals(30.0, clampZoneRadius(10.0), 0.0)
        assertEquals(200.0, clampZoneRadius(500.0), 0.0)
        assertEquals(75.0, clampZoneRadius(75.0), 0.0)
    }
}
