package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapFocusTest {

    @Test
    fun `with both a car and a position it cycles through all three`() {
        assertEquals(MapFocus.BOTH, nextFocus(MapFocus.ME, hasCar = true, hasMe = true))
        assertEquals(MapFocus.CAR, nextFocus(MapFocus.BOTH, hasCar = true, hasMe = true))
        assertEquals(MapFocus.ME, nextFocus(MapFocus.CAR, hasCar = true, hasMe = true))
    }

    @Test
    fun `with no car parked the button only ever means me`() {
        // Still worth having: panning away from yourself is otherwise a
        // one-way trip, which was the whole reason locate-me was added.
        assertEquals(MapFocus.ME, nextFocus(MapFocus.ME, hasCar = false, hasMe = true))
        assertEquals(MapFocus.ME, nextFocus(MapFocus.CAR, hasCar = false, hasMe = true))
    }

    @Test
    fun `with no position of our own it only ever means the car`() {
        assertEquals(MapFocus.CAR, nextFocus(MapFocus.CAR, hasCar = true, hasMe = false))
        assertEquals(MapFocus.CAR, nextFocus(MapFocus.ME, hasCar = true, hasMe = false))
    }

    @Test
    fun `framing is offered only when there are two things to frame`() {
        assertEquals(MapFocus.CAR, nextFocus(MapFocus.ME, hasCar = true, hasMe = false))
        assertEquals(MapFocus.ME, nextFocus(MapFocus.CAR, hasCar = false, hasMe = true))
    }

    @Test
    fun `with nothing at all the button does not pretend to move`() {
        assertEquals(MapFocus.ME, nextFocus(MapFocus.ME, hasCar = false, hasMe = false))
        assertEquals(MapFocus.CAR, nextFocus(MapFocus.CAR, hasCar = false, hasMe = false))
    }

    @Test
    fun `an unreachable focus falls back rather than advertising an impossible move`() {
        // Caught on screen: with nothing parked, the button offered "frame the
        // car and me" over an empty map.
        assertEquals(MapFocus.ME, focusOrFallback(MapFocus.BOTH, hasCar = false, hasMe = true))
        assertEquals(MapFocus.ME, focusOrFallback(MapFocus.CAR, hasCar = false, hasMe = true))
        assertEquals(MapFocus.CAR, focusOrFallback(MapFocus.ME, hasCar = true, hasMe = false))
    }

    @Test
    fun `a reachable focus is left alone`() {
        assertEquals(MapFocus.BOTH, focusOrFallback(MapFocus.BOTH, hasCar = true, hasMe = true))
        assertEquals(MapFocus.CAR, focusOrFallback(MapFocus.CAR, hasCar = true, hasMe = false))
    }

    @Test
    fun `with nothing at all it keeps what it had rather than inventing one`() {
        assertEquals(MapFocus.BOTH, focusOrFallback(MapFocus.BOTH, hasCar = false, hasMe = false))
    }
}
