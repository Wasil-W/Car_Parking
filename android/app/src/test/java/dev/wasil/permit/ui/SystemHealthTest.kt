package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemHealthTest {
    @Test fun `everything fine gives two ok rows and no fix affordance`() {
        val rows = healthRows(missingPermissions = 0, batteryOptimised = false)
        assertEquals(listOf(true, true), rows.map { it.ok })
        assertEquals(listOf(null, null), rows.map { it.fixLabel })
    }

    @Test fun `missing permissions are counted and offer a fix`() {
        val rows = healthRows(missingPermissions = 3, batteryOptimised = false)
        assertEquals("3 permissions missing", rows[0].label)
        assertEquals(false, rows[0].ok)
        assertEquals("Grant", rows[0].fixLabel)
    }

    @Test fun `a single missing permission reads in the singular`() {
        assertEquals("1 permission missing", healthRows(1, false)[0].label)
    }

    @Test fun `battery optimisation on is a warning with a fix`() {
        val row = healthRows(missingPermissions = 0, batteryOptimised = true)[1]
        assertEquals("Battery optimisation on", row.label)
        assertEquals(false, row.ok)
        assertEquals("Fix", row.fixLabel)
    }
}
