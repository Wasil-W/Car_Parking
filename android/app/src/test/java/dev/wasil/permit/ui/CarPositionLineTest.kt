package dev.wasil.permit.ui

import dev.wasil.permit.parking.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarPositionLineTest {
    private val car = GeoPoint(52.3702, 4.8952, 8f)

    @Test
    fun `a pin and a time says when the car was parked`() {
        assertEquals("Car parked 5 Aug 14:20.", carPositionLine(car, true, "5 Aug 14:20"))
    }

    @Test
    fun `a pin without a parked time is the last known position`() {
        assertEquals("Last known car position.", carPositionLine(car, false, null))
    }

    @Test
    fun `nothing known at all says nothing has been recorded`() {
        assertEquals("No parked location recorded yet.", carPositionLine(null, false, null))
    }

    @Test
    fun `parked without a position says so rather than denying the park`() {
        // The map claiming nothing had happened while the app was acting on
        // being parked was true about the database and false about the app.
        val line = carPositionLine(null, true, null)
        assertEquals("Parked — but the location is unknown.", line)
        assertFalse("must not contradict the app", line.contains("No parked location"))
    }
}
