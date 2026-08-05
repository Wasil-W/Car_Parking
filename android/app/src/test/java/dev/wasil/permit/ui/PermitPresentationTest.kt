package dev.wasil.permit.ui

import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.zones.TariffNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermitPresentationTest {
    private val options = listOf(PlateOption("Wasil", "RH950F", MyCar.WASIL), PlateOption("Walid", "XX123Y", MyCar.WALID))

    @Test fun `wasil holding lights the left arc and puts the dot left`() {
        assertEquals(MarkState(Side.LEFT, Side.LEFT), markStateFor(MyCar.WASIL))
    }

    @Test fun `walid holding lights the right arc and puts the dot right`() {
        assertEquals(MarkState(Side.RIGHT, Side.RIGHT), markStateFor(MyCar.WALID))
    }

    @Test fun `nobody holding lights neither arc`() {
        assertEquals(MarkState(null, null), markStateFor(null))
    }

    @Test fun `holding it offers to hand it to the other car`() {
        assertEquals(PrimaryAction("Hand to Walid", MyCar.WALID), primaryActionFor(MyCar.WASIL, MyCar.WASIL))
    }

    @Test fun `walids phone holding it offers to hand it to wasil`() {
        assertEquals(PrimaryAction("Hand to Wasil", MyCar.WASIL), primaryActionFor(MyCar.WALID, MyCar.WALID))
    }

    @Test fun `the other car holding it offers to take it back`() {
        assertEquals(PrimaryAction("Take it back", MyCar.WASIL), primaryActionFor(MyCar.WASIL, MyCar.WALID))
    }

    @Test fun `no active plate offers to claim it`() {
        assertEquals(PrimaryAction("Claim it", MyCar.WASIL), primaryActionFor(MyCar.WASIL, null))
    }

    @Test fun `holder resolves from the active plate`() {
        assertEquals(MyCar.WASIL, holderFor("RH950F", options))
        assertEquals(MyCar.WALID, holderFor("XX123Y", options))
    }

    @Test fun `unknown or absent plate has no holder`() {
        assertNull(holderFor(null, options))
        assertNull(holderFor("ZZ999Z", options))
    }

    // --- obligation, separate from settlement (v0.6.1) ---

    @Test
    fun `not parked owes nothing and says so plainly`() {
        assertEquals(SpotDemand.Unparked, spotDemandFor(false, null, "€8,05/h · all day"))
        assertEquals("Not parked", spotDemandText(SpotDemand.Unparked))
    }

    @Test
    fun `a free zone reports why it is free, in the notifier's own words`() {
        val demand = spotDemandFor(true, "at home", null)
        assertEquals(SpotDemand.Free("at home"), demand)
        assertEquals("Nothing to pay — at home", spotDemandText(demand))
    }

    @Test
    fun `a free zone wins even when a tariff could be read for the same point`() {
        // Home and free zones are hand-placed and beat geometry, exactly as
        // ZoneResolver already orders them.
        assertEquals(SpotDemand.Free("at home"), spotDemandFor(true, "at home", "€8,05/h · all day"))
    }

    @Test
    fun `a chargeable spot carries the live rate through unchanged`() {
        val demand = spotDemandFor(true, null, "€3,01/h · until 19:00")
        assertEquals(SpotDemand.Payable("€3,01/h · until 19:00"), demand)
        assertEquals("€3,01/h · until 19:00", spotDemandText(demand))
    }

    @Test
    fun `parked with no readable tariff never claims to be free without saying why`() {
        val demand = spotDemandFor(true, null, null)
        assertEquals(SpotDemand.Free("outside a paid zone"), demand)
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
