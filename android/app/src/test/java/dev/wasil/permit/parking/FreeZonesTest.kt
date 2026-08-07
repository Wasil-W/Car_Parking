package dev.wasil.permit.parking

import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.ZonePolygon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Free zones are neighbourhoods, so containment is point-in-polygon rather than
 * a distance. The circle these tests used to describe belongs to the *home*
 * zone now, and only to it — see [dev.wasil.permit.parking.zones.ZoneResolver].
 */
class FreeZonesTest {
    /** A square around Waterlooplein, roughly 400 m on a side. */
    private val square = listOf(
        ZonePolygon(
            outer = listOf(
                LatLng(52.3685, 4.8930), LatLng(52.3685, 4.8990),
                LatLng(52.3720, 4.8990), LatLng(52.3720, 4.8930),
            ),
        ),
    )
    private val shapes: (String) -> List<ZonePolygon>? =
        { name -> square.takeIf { name == "Waterloopleinbuurt" } }
    private val zone = FreeZone(52.3702, 4.8952, label = "Waterloopleinbuurt", buurt = "Waterloopleinbuurt")

    @Test
    fun `a point inside the neighbourhood is free`() {
        assertTrue(isInFreeZone(GeoPoint(52.3703, 4.8952, 10f), listOf(zone), shapes))
    }

    @Test
    fun `a point past the boundary is not`() {
        // ~350 m north of the tapped point, and outside the square. Under the
        // old 60 m circle this was equally not free; the difference is that
        // points 200 m *inside* the boundary now are.
        assertFalse(isInFreeZone(GeoPoint(52.3740, 4.8952, 10f), listOf(zone), shapes))
        assertTrue(isInFreeZone(GeoPoint(52.3715, 4.8985, 10f), listOf(zone), shapes))
    }

    @Test
    fun `no zones means never free`() {
        assertFalse(isInFreeZone(GeoPoint(52.3702, 4.8952, 10f), emptyList(), shapes))
    }
}
