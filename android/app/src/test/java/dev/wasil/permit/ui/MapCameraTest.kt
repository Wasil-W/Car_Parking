package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapCameraTest {

    private fun command(
        overview: Boolean = false,
        locate: Boolean = false,
        haveMe: Boolean = true,
        frame: Boolean = false,
        car: Boolean = false,
        haveCar: Boolean = true,
        auto: Boolean = false,
    ) = mapCameraCommand(overview, locate, haveMe, frame, car, haveCar, auto)

    @Test
    fun `nothing pending leaves the camera where the user panned it`() {
        assertEquals(MapCameraCommand.NONE, command())
    }

    @Test
    fun `each request on its own does its own move`() {
        assertEquals(MapCameraCommand.OVERVIEW, command(overview = true))
        assertEquals(MapCameraCommand.LOCATE, command(locate = true))
        assertEquals(MapCameraCommand.FRAME, command(frame = true))
        assertEquals(MapCameraCommand.CAR, command(car = true))
    }

    @Test
    fun `centring on the car needs a car, and says nothing rather than guessing`() {
        assertEquals(MapCameraCommand.NONE, command(car = true, haveCar = false, auto = false))
    }

    @Test
    fun `a tap on the car beats the automatic fit, like the other taps do`() {
        assertEquals(MapCameraCommand.CAR, command(car = true, auto = true))
    }

    @Test
    fun `the automatic first fit is the same move as the frame button`() {
        assertEquals(MapCameraCommand.FRAME, command(auto = true))
    }

    @Test
    fun `locate beats the automatic framing its own new fix triggers`() {
        // The whole reason this function exists. Re-reading the position both
        // satisfies the locate request and changes the car-and-me focus set, so
        // both arrive in the same pass — and the button the user pressed has to
        // be the one that wins.
        assertEquals(MapCameraCommand.LOCATE, command(locate = true, auto = true))
    }

    @Test
    fun `locate without a position does not move the map at all`() {
        // "We do not know" must never render as "you are here". With no fix
        // there is nothing honest to centre on, so nothing moves.
        assertEquals(MapCameraCommand.NONE, command(locate = true, haveMe = false))
    }

    @Test
    fun `a failed locate still lets the automatic framing through`() {
        assertEquals(MapCameraCommand.FRAME, command(locate = true, haveMe = false, auto = true))
    }

    @Test
    fun `switching the tariff overlay on outranks everything`() {
        // Its zoom-out is the point: at parking zoom the whole viewport sits
        // inside one polygon and the overlay looks like it did nothing.
        assertEquals(
            MapCameraCommand.OVERVIEW,
            command(overview = true, locate = true, frame = true, auto = true),
        )
    }
}
