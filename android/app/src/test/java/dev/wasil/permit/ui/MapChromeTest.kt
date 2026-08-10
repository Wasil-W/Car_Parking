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
        //
        // "What happens next", not "the whole week": v0.7.0 moved the week into
        // its own sheet and left this label describing the chevron's old job,
        // so a screen reader was promised a week the chevron does not open — and
        // on a single-row area the panel had no route to one at all. The voice
        // is unchanged; only the claim is.
        assertEquals("Show what happens next", weekToggleLabel(expanded = false))
        assertEquals("Hide what happens next", weekToggleLabel(expanded = true))
    }

    @Test
    fun `a chip opens only when it has something behind it`() {
        val areas = dev.wasil.permit.parking.zones.TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        // Wednesday 13:00 — a moment with nothing special about it.
        val silent = areas.filterNot { tariffChipOpens(it, 2, 13 * 60) }.map { it.code }.toSet()
        // Exactly the three that charge every minute of the week. T18P's week is
        // one row too, and it must NOT be in here: it has a next span.
        assertEquals(setOf("T11V", "T12V", "T13V"), silent)
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
