package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlateChoiceTest {

    @Test
    fun `an empty account falls through to typing them in`() {
        assertEquals(PlateChoice.Manual, plateChoiceFor(emptyList()))
        assertEquals(PlateChoice.Manual, plateChoiceFor(listOf("", "   ")))
    }

    @Test
    fun `one plate needs no choosing`() {
        assertEquals(PlateChoice.Only("RH950F"), plateChoiceFor(listOf("rh950f")))
    }

    @Test
    fun `two or more are offered as a pick`() {
        assertEquals(PlateChoice.Pick(listOf("RH950F", "R279XH")), plateChoiceFor(listOf("RH950F", "r279xh")))
    }

    @Test
    fun `duplicates from the account are collapsed`() {
        assertEquals(PlateChoice.Only("RH950F"), plateChoiceFor(listOf("RH950F", "rh950f ")))
    }

    @Test
    fun `two plates settle the second without asking twice`() {
        assertEquals(
            "RH950F" to "R279XH",
            platePairFor(listOf("RH950F", "R279XH"), mine = "RH950F", theirs = null),
        )
    }

    @Test
    fun `three plates need both picked, and store nothing until they are`() {
        val three = listOf("AAA", "BBB", "CCC")
        assertNull(platePairFor(three, mine = "AAA", theirs = null))
        assertEquals("AAA" to "CCC", platePairFor(three, mine = "AAA", theirs = "CCC"))
    }

    @Test
    fun `a plate the account does not list is never stored`() {
        // Guards the case where the account changed under a stale screen.
        assertNull(platePairFor(listOf("AAA", "BBB"), mine = "ZZZ", theirs = null))
        assertNull(platePairFor(listOf("AAA", "BBB"), mine = "AAA", theirs = "ZZZ"))
    }

    @Test
    fun `the same plate cannot be both cars`() {
        assertNull(platePairFor(listOf("AAA", "BBB"), mine = "AAA", theirs = "AAA"))
    }

    @Test
    fun `case and padding from the account do not change the answer`() {
        assertEquals(
            "RH950F" to "R279XH",
            platePairFor(listOf(" rh950f ", "R279XH"), mine = "rh950f", theirs = null),
        )
    }
}
