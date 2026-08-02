package dev.wasil.permit.ui

import dev.wasil.permit.parking.MyCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
