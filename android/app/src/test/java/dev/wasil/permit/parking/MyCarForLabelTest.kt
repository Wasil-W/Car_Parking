package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Test

class MyCarForLabelTest {

    @Test fun `wasil label round-trips through label and myCarForLabel`() {
        assertEquals(MyCar.WASIL, myCarForLabel(MyCar.WASIL.label()))
    }

    @Test fun `walid label round-trips through label and myCarForLabel`() {
        assertEquals(MyCar.WALID, myCarForLabel(MyCar.WALID.label()))
    }

    @Test fun `myCarForLabel reads the literal strings notifiers pass`() {
        assertEquals(MyCar.WASIL, myCarForLabel("Wasil"))
        assertEquals(MyCar.WALID, myCarForLabel("Walid"))
    }
}
