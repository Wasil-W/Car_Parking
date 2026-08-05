package dev.wasil.permit.ui

import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkRecord
import dev.wasil.permit.parking.Settlement
import dev.wasil.permit.parking.zones.TariffNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPresentationTest {

    // --- the badge: settlement, never zone ---

    @Test fun `a permit park names the car that held it`() {
        assertEquals("Permit · Wasil", badgeTextFor(Settlement.PERMIT, MyCar.WASIL))
        assertEquals("Permit · Walid", badgeTextFor(Settlement.PERMIT, MyCar.WALID))
    }

    @Test fun `a permit park with no known holder still says permit`() {
        assertEquals("Permit", badgeTextFor(Settlement.PERMIT, null))
    }

    @Test fun `the three free kinds are named apart, because they are different places`() {
        assertEquals("Home", badgeTextFor(Settlement.HOME, null))
        assertEquals("Free zone", badgeTextFor(Settlement.FREE_ZONE, null))
        assertEquals("Free street", badgeTextFor(Settlement.FREE_STREET, null))
    }

    @Test fun `a charging spot nothing covered is unsettled`() {
        assertEquals("Unsettled", badgeTextFor(Settlement.UNSETTLED, null))
    }

    /**
     * The distinction this whole app is built on. "We could not tell" must never
     * render as "you owed nothing" — that is the direction that costs a fine.
     */
    @Test fun `no zone resolved says so, rather than borrowing a free label`() {
        assertEquals("Not known", badgeTextFor(Settlement.UNKNOWN, null))
    }

    // --- badge colour category ---

    @Test fun `identity colour reaches only the permit badges`() {
        assertEquals(BadgeKind.PERMIT_WASIL, badgeKindFor(Settlement.PERMIT, MyCar.WASIL))
        assertEquals(BadgeKind.PERMIT_WALID, badgeKindFor(Settlement.PERMIT, MyCar.WALID))
        // A state, not a person: no brother's hue may land on these.
        assertEquals(BadgeKind.FREE, badgeKindFor(Settlement.HOME, null))
        assertEquals(BadgeKind.FREE, badgeKindFor(Settlement.FREE_ZONE, null))
        assertEquals(BadgeKind.FREE, badgeKindFor(Settlement.FREE_STREET, null))
        assertEquals(BadgeKind.OWED, badgeKindFor(Settlement.UNSETTLED, null))
        assertEquals(BadgeKind.UNKNOWN, badgeKindFor(Settlement.UNKNOWN, null))
    }

    @Test fun `a permit with no holder falls to Wasil's slot rather than nothing`() {
        // Arbitrary but deliberate: the badge must still draw. It cannot be
        // OWED or FREE, either of which would misreport what settled the spot.
        assertEquals(BadgeKind.PERMIT_WASIL, badgeKindFor(Settlement.PERMIT, null))
    }

    // --- the cost slot ---

    @Test fun `a permit park costs nothing and says covered`() {
        assertEquals("covered", costTextFor(Settlement.PERMIT, "€3,01/h · until 19:00"))
        assertTrue(costIsQuiet(Settlement.PERMIT))
    }

    @Test fun `free parks owe nothing`() {
        assertEquals("nothing owed", costTextFor(Settlement.HOME, null))
        assertEquals("nothing owed", costTextFor(Settlement.FREE_ZONE, null))
        assertEquals("nothing owed", costTextFor(Settlement.FREE_STREET, null))
    }

    /**
     * The one deliberate departure from the mockup, pinned so it cannot drift
     * back: an unsettled park shows the **rate**, not a money total. A total
     * needs a duration the app only sometimes observes, and a confident wrong
     * number is the failure this project has a rule about.
     */
    @Test fun `an unsettled park shows the rate that applied, not an invented total`() {
        assertEquals("€3,01/h", costTextFor(Settlement.UNSETTLED, "€3,01/h · until 19:00"))
        assertFalse(costIsQuiet(Settlement.UNSETTLED))
    }

    @Test fun `an unsettled park with no readable rate says so`() {
        assertEquals("rate unknown", costTextFor(Settlement.UNSETTLED, null))
    }

    @Test fun `an all-day rate keeps its whole rate half`() {
        assertEquals("€8,05/h", costTextFor(Settlement.UNSETTLED, "€8,05/h · all day"))
    }

    /**
     * A dash rather than words, because the badge on the same line already says
     * "Not known" — the pair read as a stutter on screen.
     */
    @Test fun `an unknown park quotes no money at all, and does not repeat its badge`() {
        assertEquals("—", costTextFor(Settlement.UNKNOWN, "€3,01/h · until 19:00"))
        assertTrue(costIsQuiet(Settlement.UNKNOWN))
    }

    /**
     * The coupling, held still: [costTextFor] splits on the separator
     * [tariffNowText] joins with, so the two are tested against each other
     * rather than against a remembered format.
     */
    @Test fun `the rate half survives whatever tariffNowText actually produces`() {
        val charging = tariffNowText(TariffNow.Charging("€3,01/h", endsInMin = 120), minuteOfDay = 17 * 60)
        assertEquals("€3,01/h · until 19:00", charging)
        assertEquals("€3,01/h", costTextFor(Settlement.UNSETTLED, charging))

        val allDay = tariffNowText(TariffNow.Charging("€8,05/h", endsInMin = null), minuteOfDay = 0)
        assertEquals("€8,05/h", costTextFor(Settlement.UNSETTLED, allDay))
    }

    // --- the time range ---

    @Test fun `both ends observed gives a range with the arrow`() {
        assertEquals("Mon 3 Aug · 09:12 → 17:48", whenTextFor("Mon 3 Aug", "09:12", "17:48"))
    }

    /**
     * A park the app never saw end says so. Borrowing the next park's start and
     * drawing an arrow to it would be the app claiming it watched a departure
     * it did not.
     */
    @Test fun `only a start observed says from, never an invented end`() {
        assertEquals("Mon 3 Aug · from 09:12", whenTextFor("Mon 3 Aug", "09:12", null))
    }

    // --- the place ---

    @Test fun `a named place is used as given`() {
        assertEquals("Molenwijk · Computerweg", placeTextFor("Molenwijk · Computerweg"))
    }

    @Test fun `a park with no name still happened`() {
        assertEquals("Place not recorded", placeTextFor(null))
        assertEquals("Place not recorded", placeTextFor("  "))
    }

    // --- the whole row ---

    @Test fun `a covered park assembles into the mockup's record`() {
        val row = historyRowFor(
            record = ParkRecord(
                startedAtMs = 1_000,
                endedAtMs = 2_000,
                settlement = Settlement.PERMIT,
                holder = MyCar.WASIL,
                place = "Molenwijk · Computerweg",
                rateText = "€1,72/h · until 19:00",
                paid = true,
            ),
            dayDate = "Mon 3 Aug",
            startClock = "09:12",
            endClock = "17:48",
        )
        assertEquals(
            HistoryRow(
                badge = "Permit · Wasil",
                kind = BadgeKind.PERMIT_WASIL,
                cost = "covered",
                costIsQuiet = true,
                place = "Molenwijk · Computerweg",
                whenText = "Mon 3 Aug · 09:12 → 17:48",
            ),
            row,
        )
    }

    @Test fun `an unsettled park assembles into a row that reads as a bill`() {
        val row = historyRowFor(
            record = ParkRecord(
                startedAtMs = 1_000,
                settlement = Settlement.UNSETTLED,
                place = "Nieuwmarkt · Jodenbreestraat",
                rateText = "€3,01/h · until 19:00",
                paid = true,
            ),
            dayDate = "Sat 1 Aug",
            startClock = "14:12",
            endClock = null,
        )
        assertEquals("Unsettled", row.badge)
        assertEquals(BadgeKind.OWED, row.kind)
        assertEquals("€3,01/h", row.cost)
        assertFalse(row.costIsQuiet)
        assertEquals("Sat 1 Aug · from 14:12", row.whenText)
    }

    @Test fun `an empty log explains when something would appear here`() {
        // The rule from USER-MODEL: an empty state earns space in proportion to
        // how much of the screen it owns, and this one owns a whole tab.
        assertTrue(HISTORY_EMPTY_BODY.contains("from now on"))
    }
}
