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
    fun `the header chip says what tapping it will do, in the same voice`() {
        // Every control on this screen announces its outcome rather than its
        // state — the focus button, the layers button, and now the chip.
        assertEquals("Show the whole week", weekToggleLabel(expanded = false))
        assertEquals("Hide the whole week", weekToggleLabel(expanded = true))
    }

    @Test
    fun `the home zone item is set the first time and move after that`() {
        assertEquals("Set home zone", homeZoneMenuLabel(homeZoneSet = false))
        assertEquals("Move home zone", homeZoneMenuLabel(homeZoneSet = true))
    }

    /**
     * The pill no longer announces a hand-off to Google Maps before it has
     * tried, and that is the whole change. It used to relabel itself on a stale
     * null — a position read that failed once at app start and was never
     * retried — so an app that had drawn the route in-app since v0.5.3 appeared
     * to have dropped the feature. Wasil, 2026-08-08: *"why does it now say walk
     * with google maps even though it always was within the app."*
     *
     * There is deliberately no label for "we have no position". The app cannot
     * know that until it asks, the tap is when it asks, and a maps app is what
     * happens when the answer really is no.
     */
    @Test
    fun `the pill says what it is for rather than predicting whether it can`() {
        assertEquals("Walk to car", walkPillText(routing = false, routeSummary = null))
    }

    @Test
    fun `a drawn route turns the pill into its own dismissal`() {
        assertEquals(
            "Hide route · 12 min · 984 m",
            walkPillText(routing = false, routeSummary = "12 min · 984 m"),
        )
    }

    @Test
    fun `fetching outranks every other state`() {
        assertEquals(
            "Finding the way…",
            walkPillText(routing = true, routeSummary = "12 min · 984 m"),
        )
    }
}
