package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapChromeTest {

    @Test
    fun `weekdayRange survives a day outside 1 to 7 instead of throwing`() {
        // It indexes a fixed seven-element list. Before v0.7.6 this threw
        // IndexOutOfBoundsException from inside the facility sheet — a crash on
        // tap, on data the parser had already accepted.
        assertEquals("", weekdayRange(setOf(0)))
        assertEquals("", weekdayRange(setOf(8)))
        assertEquals("", weekdayRange(emptySet()))
        assertEquals("ma, di", weekdayRange(setOf(0, 1, 2, 99)))
    }

    @Test
    fun `weekdayRange abbreviates a run of three or more and lists the rest`() {
        assertEquals("ma–vr", weekdayRange(setOf(1, 2, 3, 4, 5)))
        assertEquals("ma–wo", weekdayRange(setOf(3, 1, 2)))
        // Two adjacent days stay a list: "ma–di" is longer to read than
        // "ma, di" and says nothing extra.
        assertEquals("ma, di", weekdayRange(setOf(1, 2)))
        assertEquals("za, zo", weekdayRange(setOf(6, 7)))
        assertEquals("ma, vr", weekdayRange(setOf(1, 5)))
        assertEquals("zo", weekdayRange(setOf(7)))
    }

    // The tariff button's own label test is gone with the function. v0.7.5 turned
    // that button into a menu, so it now reads "Layers" and the show/hide
    // phrasing moved onto two rows that carry their state as a tick instead.
    // The test outlived the string by one release and kept passing, which is the
    // failure this project already has a name for: a green assertion about a
    // sentence no screen can produce.

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
