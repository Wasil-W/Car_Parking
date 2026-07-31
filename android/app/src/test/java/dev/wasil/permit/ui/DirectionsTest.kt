package dev.wasil.permit.ui

import dev.wasil.permit.parking.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionsTest {
    private val point = GeoPoint(52.370216, 4.895168, 5f)

    @Test
    fun `walking directions URI uses dot decimals and walking mode`() {
        assertEquals("google.navigation:q=52.370216,4.895168&mode=w", walkingDirectionsUri(point))
    }

    @Test
    fun `geo fallback URI carries the same point and a label`() {
        assertEquals(
            "geo:52.370216,4.895168?q=52.370216,4.895168(Parked car)",
            geoFallbackUri(point),
        )
    }

    @Test
    fun `geo fallback URI accepts a custom label`() {
        assertEquals(
            "geo:52.370216,4.895168?q=52.370216,4.895168(Your car)",
            geoFallbackUri(point, "Your car"),
        )
    }
}
