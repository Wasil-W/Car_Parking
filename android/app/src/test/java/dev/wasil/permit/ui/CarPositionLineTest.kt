package dev.wasil.permit.ui

import dev.wasil.permit.parking.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarPositionLineTest {
    private val car = GeoPoint(52.3702, 4.8952, 8f)

    @Test
    fun `a pin and a time says when the car was parked`() {
        assertEquals("Parked 5 Aug 14:20", carPositionLine(car, true, "5 Aug 14:20"))
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

    @Test
    fun `while driving it says so, rather than pointing at where you last parked`() {
        // The pin is kept in storage through the drive and hidden by the
        // screens. Reported twice: deleting it meant a park that produced no
        // position left no car location at all.
        assertEquals(
            "You're in the car.",
            carPositionLine(null, parked = false, parkedAtText = null, driving = true),
        )
    }

    @Test
    fun `driving wins over every other case, including a held position`() {
        val held = GeoPoint(52.37, 4.89, 8f)
        assertEquals(
            "You're in the car.",
            carPositionLine(held, parked = true, parkedAtText = "25 Jul 17:20", driving = true),
        )
    }

    @Test
    fun `once the drive ends the held position is the answer again`() {
        val held = GeoPoint(52.37, 4.89, 8f)
        assertEquals(
            "Last known car position.",
            carPositionLine(held, parked = false, parkedAtText = null, driving = false),
        )
    }
}
