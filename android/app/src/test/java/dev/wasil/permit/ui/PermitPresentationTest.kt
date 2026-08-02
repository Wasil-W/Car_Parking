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
}
