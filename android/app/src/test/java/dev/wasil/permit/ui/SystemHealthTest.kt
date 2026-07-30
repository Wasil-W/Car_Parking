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

    @Test fun `everything set gives five ok rows`() {
        val rows = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = true, syncConfigured = true, homeZoneSet = true,
        )
        assertEquals(5, rows.size)
        assertEquals(listOf(true, true, true, true, true), rows.map { it.ok })
        assertEquals("Car paired", rows[2].label)
        assertEquals("Sync configured", rows[3].label)
        assertEquals("Home zone set", rows[4].label)
    }

    @Test fun `no car paired is not ok — detection cannot run without it`() {
        val row = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = false, syncConfigured = true, homeZoneSet = true,
        )[2]
        assertEquals("No car paired — detection won't run", row.label)
        assertEquals(false, row.ok)
    }

    @Test fun `sync not configured reads as informational, not a fault`() {
        val row = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = true, syncConfigured = false, homeZoneSet = true,
        )[3]
        assertEquals("Sync not set up", row.label)
        assertEquals(true, row.ok)
        assertEquals(null, row.fixLabel)
    }

    @Test fun `no home zone reads as informational, not a fault`() {
        val row = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = true, syncConfigured = true, homeZoneSet = false,
        )[4]
        assertEquals("No home zone set", row.label)
        assertEquals(true, row.ok)
        assertEquals(null, row.fixLabel)
    }

    @Test fun `several missing — only the genuine faults are not ok`() {
        val rows = healthRows(
            missingPermissions = 2, batteryOptimised = true,
            carPaired = false, syncConfigured = false, homeZoneSet = false,
        )
        assertEquals(listOf(false, false, false, true, true), rows.map { it.ok })
    }
}
