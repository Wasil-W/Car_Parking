package dev.wasil.permit.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

class PlateNormalizerTest {
    @Test
    fun `strips dashes and spaces and uppercases`() {
        assertEquals("RH950F", normalizePlate("rh-950-f"))
        assertEquals("RH950F", normalizePlate(" RH 950 F "))
        assertEquals("XX123Y", normalizePlate("XX123Y"))
    }
}
