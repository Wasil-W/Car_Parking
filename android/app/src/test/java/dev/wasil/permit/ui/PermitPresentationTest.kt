package dev.wasil.permit.ui

import dev.wasil.permit.parking.Roster
import dev.wasil.permit.parking.legacyRoster
import dev.wasil.permit.parking.rosterFrom
import dev.wasil.permit.parking.zones.TariffNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffWindow
import dev.wasil.permit.parking.zones.ZoneInfo
import org.junit.Test

class PermitPresentationTest {
    private val roster = legacyRoster("RH950F", "XX123Y")
    private val wasil = roster[0]
    private val walid = roster[1]

    @Test fun `slot 0 holding lights the left arc and puts the dot left`() {
        assertEquals(MarkState(Side.LEFT, Side.LEFT), markStateFor(0))
    }

    @Test fun `slot 1 holding lights the right arc and puts the dot right`() {
        assertEquals(MarkState(Side.RIGHT, Side.RIGHT), markStateFor(1))
    }

    @Test fun `nobody holding lights neither arc`() {
        assertEquals(MarkState(null, null), markStateFor(null))
    }

    /**
     * The N ≥ 3 degrade, and the reason it needed no new drawing code: a roster
     * too large for hue reports no slot, and no slot is the wordmark the mark
     * already drew for "nobody is holding it".
     */
    @Test fun `a slot past the pair draws the wordmark rather than an invented arc`() {
        assertEquals(MarkState(null, null), markStateFor(2))
    }

    @Test fun `holding it offers to hand it to the other car`() {
        assertEquals(
            PrimaryAction("Hand to Walid", walid),
            primaryActionFor(roster, mine = wasil, holder = wasil),
        )
    }

    @Test fun `walids phone holding it offers to hand it to wasil`() {
        assertEquals(
            PrimaryAction("Hand to Wasil", wasil),
            primaryActionFor(roster, mine = walid, holder = walid),
        )
    }

    @Test fun `the other car holding it offers to take it back`() {
        assertEquals(
            PrimaryAction("Take it back", wasil),
            primaryActionFor(roster, mine = wasil, holder = walid),
        )
    }

    @Test fun `no active plate offers to claim it`() {
        assertEquals(
            PrimaryAction("Claim it", wasil),
            primaryActionFor(roster, mine = wasil, holder = null),
        )
    }

    /**
     * One button is the *correct* UI for two cars, because a permit with two
     * possible homes can only move to one place. At any other arity it is not,
     * so there is no button — a picker is the answer there, and there is no
     * third car to build one for.
     */
    @Test fun `there is no single hand-over button at any arity but two`() {
        val three = rosterFrom(listOf("AA111A", "BB222B", "CC333C"), Roster.SEED)
        assertNull(primaryActionFor(three, mine = three[0], holder = three[1]))
        val one = rosterFrom(listOf("AA111A"), Roster.SEED)
        assertNull(primaryActionFor(one, mine = one[0], holder = one[0]))
    }

    // --- obligation, separate from settlement (v0.6.1, reshaped v0.6.5) ---

    private val paidAllDay = TariffArea(
        code = "T11V",
        name = "Basistarief TC1 ma-zo 00-24",
        tariffText = "€8,05/h",
        polygons = emptyList(),
        windows = listOf(TariffWindow("€8,05/h", 0, 1440, setOf(0, 1, 2, 3, 4, 5, 6))),
    )

    /** Charges 09:00-19:00 on weekdays only — free at night and at weekends. */
    private val paidWeekdays = paidAllDay.copy(
        code = "T13B",
        windows = listOf(TariffWindow("€5,37/h", 9 * 60, 19 * 60, setOf(0, 1, 2, 3, 4))),
    )

    @Test
    fun `not parked owes nothing and says so plainly`() {
        val demand = spotDemandFor(parked = false, zone = null, dayIndex = 1, minuteOfDay = 600)
        assertEquals(SpotDemand.Unparked, demand)
    }

    @Test
    fun `parked with no position says the position is unknown, never that it is free`() {
        // The distinction v0.6.5 exists for: "we could not tell" is not "nothing
        // to pay", and guessing the second costs a fine.
        val demand = spotDemandFor(parked = true, zone = null, dayIndex = 1, minuteOfDay = 600)
        assertEquals(SpotDemand.LocationUnknown, demand)
    }

    @Test
    fun `a free zone reports why it is free, in the notifier's own words`() {
        val demand = spotDemandFor(true, ZoneInfo.Home, dayIndex = 1, minuteOfDay = 600)
        assertEquals(SpotDemand.Free("at home"), demand)
    }

    @Test
    fun `a hand-placed free zone beats geometry, as ZoneResolver already orders it`() {
        val demand = spotDemandFor(true, ZoneInfo.ManualFree("Mum's street"), 1, 600)
        assertTrue(demand is SpotDemand.Free)
    }

    @Test
    fun `a charging spot carries the live rate`() {
        val demand = spotDemandFor(true, ZoneInfo.Paid(paidAllDay), dayIndex = 1, minuteOfDay = 600)
        assertEquals(SpotDemand.Payable("€8,05/h · all day"), demand)
    }

    @Test
    fun `inside a paid area outside its hours is free for now, not payable`() {
        // The v0.6.4 contradiction: the app claimed the permit here while the
        // screen said nothing was owed. The screen now says what is true, and
        // says when it stops being true.
        val sundayNoon = spotDemandFor(true, ZoneInfo.Paid(paidWeekdays), dayIndex = 6, minuteOfDay = 720)
        assertTrue("expected FreeForNow, got $sundayNoon", sundayNoon is SpotDemand.FreeForNow)
        assertEquals("09:00", (sundayNoon as SpotDemand.FreeForNow).untilClock)
    }

    @Test
    fun `a paid area whose tariff will not parse says the rate is unknown, not free`() {
        val demand = spotDemandFor(true, ZoneInfo.Paid(null), dayIndex = 1, minuteOfDay = 600)
        assertEquals(SpotDemand.RateUnknown, demand)
    }

    // --- the three truths (v0.6.4) ---

    @Test fun `two phones and a permit is the screen that ships today`() {
        assertEquals(PermitView.Shared, permitViewFor(permitAdded = true, hasSecondPlate = true))
    }

    @Test fun `a permit with no second phone has nowhere to hand it to`() {
        assertEquals(PermitView.Sole, permitViewFor(permitAdded = true, hasSecondPlate = false))
    }

    @Test fun `no permit is a screen of its own, whether or not sharing is on`() {
        assertEquals(PermitView.NoPermit, permitViewFor(permitAdded = false, hasSecondPlate = false))
        assertEquals(PermitView.NoPermit, permitViewFor(permitAdded = false, hasSecondPlate = true))
    }

    @Test fun `the sole mark keeps the holder's colour and drops the second arc`() {
        assertEquals(MarkState(Side.LEFT, Side.LEFT, MarkArcs.SOLE), soleMarkStateFor(MyCar.WASIL))
        assertEquals(MarkState(Side.RIGHT, Side.RIGHT, MarkArcs.SOLE), soleMarkStateFor(MyCar.WALID))
    }

    @Test fun `the pair mark is still the default, so nothing else had to change`() {
        assertEquals(MarkArcs.PAIR, markStateFor(MyCar.WASIL).arcs)
        assertEquals(MarkArcs.PAIR, markStateFor(null).arcs)
    }

    // --- the rate line, split for the no-permit headline ---

    @Test fun `a live rate line splits into a rate and a deadline`() {
        assertEquals("€3,01/h" to "until 19:00", splitRateLine("€3,01/h · until 19:00"))
        assertEquals("€8,05/h" to "all day", splitRateLine("€8,05/h · all day"))
        assertEquals("Free" to "from 09:00", splitRateLine("Free · from 09:00"))
    }

    @Test fun `a line with no separator degrades to showing all of it`() {
        assertEquals("€3,01/h" to null, splitRateLine("€3,01/h"))
    }

    @Test fun `the split is pinned against what tariffNowText actually writes`() {
        val line = tariffNowText(TariffNow.Charging("€3,01/h", endsInMin = 120), minuteOfDay = 17 * 60)
        assertEquals("€3,01/h" to "until 19:00", splitRateLine(line))
    }

    // --- the no-permit headline ---

    @Test fun `a chargeable spot leads with the rate and names the place under it`() {
        val headline = spotHeadlineFor(SpotDemand.Payable("€3,01/h · until 19:00"), "Nieuwmarkt")
        assertEquals(SpotHeadline("€3,01/h", "Nieuwmarkt · until 19:00"), headline)
    }

    @Test fun `an unnamed chargeable spot still says the rate and the deadline`() {
        assertEquals(
            SpotHeadline("€3,01/h", "until 19:00"),
            spotHeadlineFor(SpotDemand.Payable("€3,01/h · until 19:00"), null),
        )
    }

    @Test fun `a free spot says nothing to pay and why`() {
        assertEquals(
            SpotHeadline("Nothing to pay", "Oostzanerwerf · at home"),
            spotHeadlineFor(SpotDemand.Free("at home"), "Oostzanerwerf"),
        )
    }

    @Test fun `not parked is a finished sentence, not an empty screen`() {
        val headline = spotHeadlineFor(SpotDemand.Unparked, null)
        assertEquals("Not parked", headline.big)
        assertTrue(headline.detail.isNotBlank())
    }

    // --- the sole-car card ---

    @Test fun `a sole card says covered rather than naming a brother`() {
        assertEquals("Covered", soleCardTitle(covered = true))
        assertEquals("RH950F · permit active", soleCardSubtitle("RH950F"))
    }

    /**
     * When the permit state could not be read there is no holder, and the card
     * must not claim one. Pinned because this state needs a live permit session
     * and so cannot be reached on an emulator to be checked by eye.
     */
    @Test fun `a sole card with no readable holder says so instead of covered`() {
        assertEquals("No plate active", soleCardTitle(covered = false))
        assertEquals(MarkState(null, null, MarkArcs.SOLE), soleMarkStateFor(null))
    }

    // --- the sole-car quiet line ---

    @Test fun `a sole park names where and since when, and says there is nothing to do`() {
        assertEquals(
            "Parked in Amsterdam-Centrum since 09:12. Nothing to do.",
            soleStatusLine(parked = true, place = "Amsterdam-Centrum", sinceClock = "09:12"),
        )
    }

    @Test fun `a sole park with no name or time still reads as a sentence`() {
        assertEquals("Parked. Nothing to do.", soleStatusLine(true, null, null))
        assertEquals("Parked since 09:12. Nothing to do.", soleStatusLine(true, null, "09:12"))
        assertEquals("Parked in Molenwijk. Nothing to do.", soleStatusLine(true, "Molenwijk", null))
    }

    @Test fun `not parked says where the permit is rather than nothing`() {
        assertEquals(
            "The permit is on your car. Nothing to do.",
            soleStatusLine(parked = false, place = "Molenwijk", sinceClock = "09:12"),
        )
    }
}
