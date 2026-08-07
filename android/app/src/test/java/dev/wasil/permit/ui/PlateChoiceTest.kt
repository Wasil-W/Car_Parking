package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- telling a wrong password from a site that is down (v0.6.8) ---

    /**
     * The requirement in one assertion. Wasil, 2026-08-08: *"i entered incorrect
     * credentials and it still showed my cars."* Whatever else the screen does,
     * a person has to be able to tell whether what they typed works — so the
     * refusal must not be phrased as a network problem, and the network problem
     * must not be phrased as a refusal.
     */
    @Test
    fun `a refusal blames the credentials and an outage does not`() {
        val refused = signInProblemText(SignIn.Rejected)!!
        assertTrue(refused.contains("refused"))
        assertTrue("a refusal must not send anyone hunting for a network fault",
            !refused.contains("reach"))

        val down = signInProblemText(SignIn.Unreachable)!!
        assertTrue(down.contains("Couldn't reach"))
        assertTrue("an outage must not accuse the password",
            down.contains("not been checked"))
    }

    @Test
    fun `a refusal says the stored permit was left alone`() {
        // The screen has to say it because the view model does it: a rejected
        // pair is rolled back rather than left saved. Someone who mistypes their
        // password on a working install needs to know they have not broken it.
        assertTrue(signInProblemText(SignIn.Rejected)!!.contains("Nothing has been changed"))
    }

    @Test
    fun `signing in with no cars on the account is not reported as a failed sign-in`() {
        val text = signInProblemText(SignIn.NoCars)!!
        assertTrue(text.startsWith("Signed in"))
    }

    @Test
    fun `a list of cars has nothing to apologise for`() {
        assertNull(signInProblemText(SignIn.Cars(listOf("RH950F"))))
    }
}
