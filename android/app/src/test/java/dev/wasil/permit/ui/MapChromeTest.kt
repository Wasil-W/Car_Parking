package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapChromeTest {

    @Test
    fun `the tariff button says what it will do, not what is on screen`() {
        assertEquals("Show tariff areas", tariffToggleLabel(showing = false))
        assertEquals("Hide tariff areas", tariffToggleLabel(showing = true))
    }

    @Test
    fun `the home zone item is set the first time and move after that`() {
        assertEquals("Set home zone", homeZoneMenuLabel(homeZoneSet = false))
        assertEquals("Move home zone", homeZoneMenuLabel(homeZoneSet = true))
    }

    @Test
    fun `the pill offers a walk when we know where we are`() {
        assertEquals(
            "Walk to car",
            walkPillText(routing = false, routeSummary = null, haveMyPosition = true),
        )
    }

    @Test
    fun `without our own position the pill hands off to a maps app`() {
        // There is no line to draw from nowhere. A button that looks like it
        // routes and then does nothing is worse than one that says it leaves.
        assertEquals(
            "Open walk in Maps",
            walkPillText(routing = false, routeSummary = null, haveMyPosition = false),
        )
    }

    @Test
    fun `a drawn route turns the pill into its own dismissal`() {
        assertEquals(
            "Hide route · 12 min · 984 m",
            walkPillText(routing = false, routeSummary = "12 min · 984 m", haveMyPosition = true),
        )
    }

    @Test
    fun `fetching outranks every other state`() {
        assertEquals(
            "Finding the way…",
            walkPillText(routing = true, routeSummary = "12 min · 984 m", haveMyPosition = true),
        )
    }
}
